package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.*;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.UUID;

/** Detected details are saved inline; edits and removals remain deliberate. */
final class MetadataPanel {
    final LinearLayout root,review;private final LibraryActivity activity;private final String id;private int generation;private String shown="";
    MetadataPanel(LibraryActivity activity,String id){this.activity=activity;this.id=id;root=Ui.column(activity);review=Ui.column(activity);}
    void refresh(){int ticket=++generation;activity.work(() -> activity.app().library.metadata.view(id),view -> {if(ticket!=generation)return;show(view);});}
    private void show(MetadataStore.View view) {
        StringBuilder signature=new StringBuilder(view.snapshot.input).append(view.snapshot.claim).append(view.status).append(view.notesRevision).append(view.notes);
        for(DetailValue value:view.saved)signature.append(value.json());for(DetailValue value:view.offered)signature.append(value.json());for(DetailValue value:view.dismissed)signature.append("dismissed").append(value.json());
        if(signature.toString().equals(shown))return;shown=signature.toString();
        root.removeAllViews();review.removeAllViews();
        root.addView(heading("Details","Add manually",this::add));
        for(DetailValue value:view.saved){
            LinearLayout row=Ui.row(activity);row.setPadding(0,Ui.dp(activity,4),0,Ui.dp(activity,4));
            LinearLayout words=Ui.column(activity);TextView label=Ui.text(activity,value.label+(value.automatic()?" · Detected":""),12,false);label.setTextColor(value.automatic()?Ui.BLUE:Ui.MUTED);words.addView(label);
            TextView content=Ui.text(activity,display(value),16,false);content.setPadding(0,Ui.dp(activity,4),0,0);words.addView(content);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            ImageButton actions=Ui.iconButton(activity,Icons.Symbol.MORE,"Actions for "+value.label,() -> actions(view,value));row.addView(actions,new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)));root.addView(row);
        }
        for(DetailValue value:view.offered){
            LinearLayout row=Ui.row(activity);row.setPadding(0,Ui.dp(activity,4),0,Ui.dp(activity,4));
            LinearLayout words=Ui.column(activity);TextView label=Ui.text(activity,value.label+" · Suggested",12,false);label.setTextColor(Ui.BLUE);words.addView(label);
            TextView content=Ui.text(activity,display(value),16,false);content.setPadding(0,Ui.dp(activity,4),0,0);words.addView(content);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            Button action=Ui.button(activity,"Review",() -> proposal(view,value,false));action.setContentDescription("Review suggested "+value.label);row.addView(action);root.addView(row);
        }
        if(view.saved.isEmpty()&&view.offered.isEmpty()&&view.dismissed.isEmpty()){TextView empty=Ui.text(activity,view.status.equals("ready")?"No suggestions found":"Finding details…",14,false);empty.setTextColor(Ui.MUTED);root.addView(empty);}
        root.addView(review);
        root.addView(heading("Notes",view.notes.isEmpty()?"Add":"Edit",() -> MetadataEditorActivity.open(activity,id,"notes","text",false)));
        if(!view.notes.isEmpty()){TextView notes=Ui.text(activity,view.notes,14,false);notes.setMaxLines(3);notes.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(notes);}
        if(view.status.equals("failed"))review.addView(Ui.button(activity,"Retry detail suggestions",() -> activity.work(() -> {activity.app().library.metadata.retry(id);return true;},ignored -> changed())));
        if(!view.dismissed.isEmpty())review.addView(Ui.button(activity,"Dismissed suggestions ("+view.dismissed.size()+")",() -> {
            String[] labels=new String[view.dismissed.size()];for(int i=0;i<labels.length;i++){DetailValue value=view.dismissed.get(i);labels[i]=value.label+" · "+display(value);}
            new AlertDialog.Builder(activity).setTitle("Dismissed suggestions").setItems(labels,(d,index) -> proposal(view,view.dismissed.get(index),true)).setNegativeButton("Close",null).show();
        }));
    }
    private LinearLayout heading(String title,String action,Runnable click){LinearLayout row=Ui.row(activity);boolean large=activity.getResources().getConfiguration().fontScale>=1.5f&&action.equals("Add manually");if(large)row.setOrientation(LinearLayout.VERTICAL);row.addView(Ui.text(activity,title,14,true),large?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));row.addView(Ui.button(activity,action,click));return row;}
    private static String display(DetailValue value){return value.value+(value.kind.equals("amount")?(value.qualifier.equals("unknown")?" · Currency unknown":" · "+value.qualifier):"");}
    private void actions(MetadataStore.View view,DetailValue value){new AlertDialog.Builder(activity).setTitle(value.label).setItems(value.automatic()?new String[]{"Edit","Copy","Remove","Source"}:new String[]{"Edit","Copy","Remove"},(dialog,which) -> {
        if(which==0)MetadataEditorActivity.open(activity,id,value.field,value.kind,false);
        else if(which==1)copy(value.value);
        else if(which==3)source(value);
        else new AlertDialog.Builder(activity).setTitle("Remove this detail?").setMessage("It will stay removed when Stack reads the document again.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w) -> activity.work(() -> {activity.app().library.metadata.save(id,new DetailValue(value.field,value.kind,value.label,value.value,value.qualifier,value.evidence,"manual","removed",0),view.snapshot.revision);return true;},ignored -> changed())).show();
    }).setNegativeButton("Cancel",null).show();}
    private void source(DetailValue value){String text="Automatically added from this document.";try{JSONObject e=new JSONObject(value.evidence);text+="\n\nPage "+e.optInt("page")+"\n"+e.optString("raw");}catch(Exception ignored){}new AlertDialog.Builder(activity).setTitle(value.label).setMessage(text).setPositiveButton("Close",null).show();}
    private void proposal(MetadataStore.View view,DetailValue value,boolean reconsider){String source="";try{JSONObject e=new JSONObject(value.evidence);source="\n\nPage "+e.optInt("page")+"\n"+e.optString("raw");}catch(Exception ignored){}
        new AlertDialog.Builder(activity).setTitle(value.label).setMessage((reconsider?"Previously dismissed. Apply only if you want to use it now.\n\n":"")+display(value)+source).setPositiveButton("Apply",(d,w) -> decide(view,value,"accepted",reconsider))
                .setNeutralButton("Edit",(d,w) -> {if(reconsider)MetadataEditorActivity.reconsider(activity,id,value.field,value.kind);else MetadataEditorActivity.open(activity,id,value.field,value.kind,true);}).setNegativeButton(reconsider?"Keep dismissed":"Dismiss",(d,w) -> {if(!reconsider)decide(view,value,"dismissed",false);}).show();
    }
    private void decide(MetadataStore.View view,DetailValue value,String action,boolean reconsider){activity.work(() -> reconsider?activity.app().library.metadata.reconsider(view,value.field,action,null,null,null):activity.app().library.metadata.apply(view,value.field,action,null,null),applied -> {if(!applied)Toast.makeText(activity,"Details changed. Review the latest suggestion.",Toast.LENGTH_LONG).show();changed();});}
    private void changed(){activity.app().io.execute(() -> activity.app().metadata.kick());activity.app().notifyLibraryChanged();refresh();}
    private void add(){new AlertDialog.Builder(activity).setTitle("Add manually").setItems(new String[]{"Date","Amount","Organization","Label and value"},(d,which) -> MetadataEditorActivity.open(activity,id,"manual:"+UUID.randomUUID(),new String[]{"date","amount","organization","text"}[which],false)).setNegativeButton("Cancel",null).show();}
    private void copy(String value){if(!activity.app().access.allowed())return;ClipData clip=DetailClipboard.clip(value);activity.getSystemService(ClipboardManager.class).setPrimaryClip(clip);Toast.makeText(activity,"Detail copied",Toast.LENGTH_SHORT).show();}
}
