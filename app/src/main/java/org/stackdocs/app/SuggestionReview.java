package org.stackdocs.app;

import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Review within saved-document Details; each field has its own explicit choice. */
final class SuggestionReview {
    private final android.app.Activity activity;
    final LinearLayout root;
    private final String id;
    private final Runnable changed;
    private int generation;
    private final LibraryRepository library;
    private final java.util.concurrent.ExecutorService io;
    private final Runnable kick;
    SuggestionReview(LibraryActivity activity,String id,Runnable changed) {
        this(activity,activity.app().library,activity.app().io,() -> {
            activity.app().suggestions.kick(); activity.app().notifyLibraryChanged();
        },id,changed);
    }
    SuggestionReview(android.app.Activity activity,LibraryRepository library,java.util.concurrent.ExecutorService io,Runnable kick,String id,Runnable changed) {
        this.activity=activity; this.library=library; this.io=io; this.kick=kick; this.id=id; this.changed=changed;
        root=Ui.column(activity); root.setPadding(0,Ui.dp(activity,16),0,0);
    }
    void refresh() {
        int ticket=++generation;
        work(() -> library.suggestions.view(id),view -> {
            if(ticket!=generation) return;
            root.removeAllViews(); root.addView(Ui.text(activity,"Suggestions",14,true));
            if(view==null) return;
            if(view.status.equals("waiting")) { message("Suggestions will be ready after text reading finishes."); return; }
            if(view.status.equals("pending") || view.status.equals("running")) { message("Preparing suggestions…"); return; }
            if(view.status.equals("failed")) {
                message("Suggestions couldn't be prepared. You can still rename and choose stacks.");
                root.addView(Ui.button(activity,"Retry suggestions",() -> work(() -> {
                    library.suggestions.retry(id); kick.run(); return true;
                },ignored -> refresh()))); return;
            }
            if(view.status.equals("empty")) { message("No clear name or stacks found. You can rename and choose stacks."); return; }
            if(view.result.partial) message("Some pages couldn't be read. Review these suggestions carefully.");
            if(view.result.truncated) message("Suggestions use a limited portion of this long document.");
            boolean offered=false;
            if(!view.result.title.isEmpty() && view.titleState.equals("offered")) {
                offered=true; message("Suggested name: "+view.result.title);
                controls("suggested name",() -> decide(view,"title","accepted",null,null),() -> editTitle(view),() -> decide(view,"title","dismissed",null,null));
            }
            if(!view.result.stacks.isEmpty() && view.filingState.equals("offered")) {
                offered=true;
                work(() -> library.stacks(),stacks -> {
                    if(ticket!=generation) return;
                    List<String> names=new ArrayList<>();
                    for(StackCategory stack:stacks) if(view.result.stacks.containsKey(stack.id)) names.add(stack.name);
                    message("Suggested stacks: "+android.text.TextUtils.join(", ",names));
                    for(String explanation:view.result.stacks.values()) message(explanation);
                    TextView note=Ui.text(activity,"Accept adds these stacks to your current filing.",12,false); root.addView(note);
                    controls("suggested stacks",() -> decide(view,"filing","accepted",null,null),() -> editStacks(view),() -> decide(view,"filing","dismissed",null,null));
                });
            }
            if(!offered) message("Suggestions reviewed. Your choices have been kept.");
        });
    }
    private <T> void work(java.util.concurrent.Callable<T> operation,java.util.function.Consumer<T> success) {
        io.execute(() -> {
            try {
                T result=operation.call(); activity.runOnUiThread(() -> {
                    if(!activity.isFinishing() && !activity.isDestroyed()) success.accept(result);
                });
            } catch(Exception failure) {
                activity.runOnUiThread(() -> {
                    if(!activity.isFinishing() && !activity.isDestroyed()) new AlertDialog.Builder(activity)
                            .setTitle("Couldn't finish").setMessage("Please reopen Details and try again.").setPositiveButton("OK",null).show();
                });
            }
        });
    }
    private void message(String text) { TextView label=Ui.text(activity,text,13,false); label.setPadding(0,Ui.dp(activity,8),0,0); root.addView(label); }
    private void controls(String subject,Runnable accept,Runnable edit,Runnable dismiss) {
        LinearLayout buttons=new Actions(activity);
        String[] labels={"Accept","Edit","Dismiss"}; Runnable[] actions={accept,edit,dismiss};
        for(int i=0;i<labels.length;i++) {
            android.widget.Button button=Ui.button(activity,labels[i],actions[i]);
            button.setContentDescription(labels[i]+" "+subject); buttons.addView(button);
        }
        root.addView(buttons);
    }
    private static final class Actions extends LinearLayout {
        Actions(android.app.Activity activity) { super(activity); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int needed=getPaddingLeft()+getPaddingRight();
            for(int i=0;i<getChildCount();i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),heightSpec);
                needed+=getChildAt(i).getMeasuredWidth();
            }
            setOrientation(MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED && needed>MeasureSpec.getSize(widthSpec) ? VERTICAL : HORIZONTAL);
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private void editTitle(SuggestionStore.View view) {
        NameEditor.show(activity,"Edit suggested name",view.result.title,"Save",io,value -> {
            boolean applied=library.suggestions.decide(view,"title","edited",value,null);
            kick.run();
            if(!applied) throw new IllegalArgumentException("This suggestion changed. Close this editor and review it again.");
        },() -> { changed.run(); refresh(); });
    }
    private void editStacks(SuggestionStore.View view) {
        work(() -> new Filing(library.document(id),library.stacks()),filing -> {
            LinkedHashSet<String> initial=new LinkedHashSet<>(filing.document.stackIds); initial.addAll(view.result.stacks.keySet());
            StackPicker.show(activity,filing.stacks,initial,"Edit all document stacks",selected -> decide(view,"filing","edited",null,selected));
        });
    }
    private void decide(SuggestionStore.View view,String field,String action,String title,List<String> stacks) {
        work(() -> {
            boolean applied=library.suggestions.decide(view,field,action,title,stacks);
            kick.run(); return applied;
        },applied -> {
            if(!applied) Toast.makeText(activity,"This suggestion changed. Review it again.",Toast.LENGTH_LONG).show();
            changed.run(); refresh();
        });
    }
    private static final class Filing {
        final Document document; final List<StackCategory> stacks;
        Filing(Document document,List<StackCategory> stacks) { this.document=document; this.stacks=stacks; }
    }
}
