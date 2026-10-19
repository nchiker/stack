package org.stackdocs.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.*;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;
import java.util.UUID;

/** Recoverable local draft, explicit Save/Cancel, optimistic revision check. */
public final class MetadataEditorActivity extends LibraryActivity {
    private Runnable removeBack;
    private String id,field,kind,inputToken="",evidence="{}";private boolean offered,reconsider,loading=true,saving;
    private long revision=-1;
    private EditText label,value,qualifier;private TextView error;private android.view.View save;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);id=getIntent().getStringExtra("document_id");field=getIntent().getStringExtra("field_id");
        if(id==null||field==null){finish();return;}
        kind=getIntent().getStringExtra("kind");if(kind==null)kind="text";offered=getIntent().getBooleanExtra("offered",false);reconsider=getIntent().getBooleanExtra("reconsider",false);
        if(state!=null){revision=state.getLong("revision",-1);inputToken=state.getString("input","");evidence=state.getString("evidence","{}");}
        LinearLayout root=Ui.column(this),bar=Ui.toolbar(this);
        bar.addView(Ui.iconButton(this,Icons.Symbol.CLOSE,"Cancel editing",this::cancel),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        bar.addView(Ui.text(this,field.equals("notes")?"Notes":"Edit detail",18,false),new LinearLayout.LayoutParams(0,-2,1));
        save=Ui.button(this,"Save",this::save);bar.addView(save);root.addView(bar);
        ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);body.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(Ui.text(this,field.equals("notes")?"Add a note to this document":"Review the value before saving",14,false));
        label=edit(body,"Label",false);value=edit(body,field.equals("notes")?"Notes (up to 20,000 characters)":"Value",field.equals("notes"));qualifier=edit(body,kind.equals("amount")?"Currency (USD or unknown)":"Date precision (day, month or year)",false);
        if(field.equals("notes")){hide(label);hide(qualifier);value.setMinLines(8);}
        else if(!kind.equals("date")&&!kind.equals("amount"))hide(qualifier);
        error=Ui.text(this,"",14,false);error.setTextColor(0xFFB3261E);error.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(error);
        Ui.install(this,root);removeBack=Ui.backHandler(this,this::cancel);save.setEnabled(false);
        work(() -> {
            MetadataStore.View view=app().library.metadata.view(id);DetailValue selected=null;
            for(DetailValue detail:offered?(reconsider?view.dismissed:view.offered):view.saved)if(detail.field.equals(field))selected=detail;
            long base=revision>=0?revision:field.equals("notes")?view.notesRevision:view.snapshot.revision;
            String fallback;
            if(field.equals("notes"))fallback=view.notes;
            else fallback=new JSONObject().put("label",selected==null?kind.equals("date")?"Date":kind.equals("amount")?"Amount":kind.equals("organization")?"Organization":"Detail":selected.label)
                    .put("value",selected==null?"":selected.value).put("qualifier",selected==null?kind.equals("date")?"day":kind.equals("amount")?"unknown":"":selected.qualifier).toString();
            return new Loaded(view,selected,base,app().library.metadata.draft(id,field,base,fallback));
        },loaded -> {
            revision=loaded.base;if(inputToken.isEmpty())inputToken=loaded.view.snapshot.input+":"+loaded.view.snapshot.claim;
            if(loaded.selected!=null)evidence=loaded.selected.evidence;
            try{if(field.equals("notes"))value.setText(loaded.draft);else {JSONObject json=new JSONObject(loaded.draft);label.setText(json.getString("label"));value.setText(json.getString("value"));qualifier.setText(json.getString("qualifier"));}}
            catch(Exception invalid){error.setText("Draft couldn't be read. Your saved information is kept.");}
            loading=false;save.setEnabled(true);
            TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){draft();}public void afterTextChanged(Editable e){}};
            label.addTextChangedListener(watcher);value.addTextChangedListener(watcher);qualifier.addTextChangedListener(watcher);
        });
    }
    private EditText edit(LinearLayout body,String hint,boolean multiple){TextView heading=Ui.text(this,hint,13,true);body.addView(heading);EditText edit=new EditText(this);edit.setId(android.view.View.generateViewId());heading.setLabelFor(edit.getId());edit.setTag(heading);edit.setTextColor(Ui.INK);edit.setTextSize(16);edit.setSingleLine(!multiple);edit.setContentDescription(hint);edit.setMinimumHeight(Ui.dp(this,48));if(multiple)edit.setGravity(Gravity.TOP);body.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;}
    private void hide(EditText edit){edit.setVisibility(android.view.View.GONE);((android.view.View)edit.getTag()).setVisibility(android.view.View.GONE);}
    private String payload(){if(field.equals("notes"))return value.getText().toString();try{return new JSONObject().put("label",label.getText().toString()).put("value",value.getText().toString()).put("qualifier",qualifier.getText().toString()).toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private void draft(){if(loading||saving)return;String payload=payload();app().io.execute(() -> app().library.metadata.draft(id,field,payload,revision));}
    private void cancel(){if(saving)return;work(() -> {app().library.metadata.clearDraft(id,field);return true;},ignored -> {app().io.execute(() -> app().metadata.kick());app().notifyLibraryChanged();finish();});}
    @android.annotation.SuppressLint("GestureBackNavigation") // Native back callback uses Ui.backHandler.
    @Override public void onBackPressed(){cancel();}
    private void save(){if(loading||saving)return;String text=value.getText().toString(),name=label.getText().toString(),q=qualifier.getText().toString().trim();
        try{if(!field.equals("notes"))DetailValue.validate(kind,name,text,q);else if(text.length()>MetadataStore.NOTES_LIMIT)throw new IllegalArgumentException("Use at most 20,000 characters.");}
        catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());return;}
        draft();saving=true;save.setEnabled(false);
        app().io.execute(() -> {
            try {
                if(!app().access.allowed())throw new IllegalArgumentException("Unlock Stack before saving. Your draft is kept.");
                if(field.equals("notes"))app().library.metadata.saveNotes(id,text,revision);
                else if(offered){MetadataStore.View view=app().library.metadata.view(id);if(!(view.snapshot.input+":"+view.snapshot.claim).equals(inputToken)||!(reconsider?app().library.metadata.reconsider(view,field,"edited",text,q,name):app().library.metadata.apply(view,field,"edited",text,q,name)))throw new IllegalArgumentException("The suggestion changed. Reopen it to review the latest evidence.");}
                else app().library.metadata.save(id,new DetailValue(field,kind,name,text,q,evidence,"manual","saved",0),revision);
                app().metadata.kick();app().notifyLibraryChanged();runOnUiThread(this::finish);
            }catch(Exception failure){runOnUiThread(() -> {saving=false;save.setEnabled(true);error.setText(failure instanceof IllegalArgumentException?failure.getMessage():"Couldn't save. Your input is kept; free storage and retry.");});}
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){draft();state.putLong("revision",revision);state.putString("input",inputToken);state.putString("evidence",evidence);super.onSaveInstanceState(state);}
    static void open(LibraryActivity activity,String id,String field,String kind,boolean offered){activity.startActivity(new Intent(activity,MetadataEditorActivity.class).putExtra("document_id",id).putExtra("field_id",field).putExtra("kind",kind).putExtra("offered",offered));}
    static void reconsider(LibraryActivity activity,String id,String field,String kind){activity.startActivity(new Intent(activity,MetadataEditorActivity.class).putExtra("document_id",id).putExtra("field_id",field).putExtra("kind",kind).putExtra("offered",true).putExtra("reconsider",true));}
    private static final class Loaded{final MetadataStore.View view;final DetailValue selected;final long base;final String draft;Loaded(MetadataStore.View view,DetailValue selected,long base,String draft){this.view=view;this.selected=selected;this.base=base;this.draft=draft;}}
    @Override protected void onDestroy(){if(removeBack!=null)removeBack.run();super.onDestroy();}
}
