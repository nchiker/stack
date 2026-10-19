package org.stackdocs.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Explicit, foreground file exchange and raw causal-alternative review. */
public final class SyncActivity extends LibraryActivity {
    private static final int EXPORT=81,IMPORT=82;
    private TextView status;private String exportPeer;private SyncExchange.Staged staged;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=Ui.column(this);LinearLayout toolbar=Ui.toolbar(this);toolbar.addView(Ui.iconButton(this,Icons.Symbol.BACK,"Back",this::finish));toolbar.addView(Ui.text(this,"Sync by file",18,false));root.addView(toolbar);
        ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);int space=Ui.dp(this,24);body.setPadding(space,space,space,space);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView intro=Ui.text(this,"Exchange a file with a reviewed computer. Choose the complete peer list before starting or adopting a group. Exchange files contain private originals and are not encrypted.",15,false);body.addView(intro);
        body.addView(Ui.button(this,"Start a new group",this::start));body.addView(Ui.button(this,"Export exchange file",this::choosePeer));body.addView(Ui.button(this,"Import exchange file",this::open));body.addView(Ui.button(this,"Review sync conflicts",this::review));
        status=Ui.text(this,"Loading sync status…",14,false);body.addView(status);Ui.install(this,root);refresh();
    }
    @Override protected void onDestroy(){if(staged!=null)staged.close();super.onDestroy();}
    private void refresh(){work(() -> new SyncStore(this,app().library).status(),this::render);}
    private void render(JSONObject state){try {
        StringBuilder text=new StringBuilder("This device: ").append(state.getString("replica"));
        if(state.optBoolean("adopted")){text.append("\nGroup: ").append(state.getString("library")).append("\nMembers: ").append(state.getJSONArray("members")).append("\nDurable frontier: ").append(state.getJSONObject("frontier"));text.append("\nPending gaps: ").append(state.getInt("pending_gaps")).append("\nReview conflicts: ").append(state.getJSONObject("conflicts").length());}
        else text.append("\nNo group adopted yet.");status.setText(text.toString());
    }catch(Exception error){status.setText("Sync status could not be displayed.");}}
    private EditText entry(String preset){EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setSingleLine(false);input.setText(preset);input.setSelectAllOnFocus(true);return input;}
    private void start(){work(() -> new SyncStore(this,app().library).status(),state -> {
        if(state.optBoolean("adopted")){new AlertDialog.Builder(this).setMessage("This library already belongs to a group.").setPositiveButton("OK",null).show();return;}
        EditText peers=entry("");new AlertDialog.Builder(this).setTitle("Review complete peer list").setMessage("Enter every computer or phone replica UUID, separated by commas. This device is added automatically. Keep the group ID for those peers.").setView(peers)
                .setNegativeButton("Cancel",null).setPositiveButton("Start",(dialog,which)->work(() -> new SyncStore(this,app().library).start(parse(peers.getText().toString())),result -> {render(result);new AlertDialog.Builder(this).setTitle("Group created").setMessage("Group: "+result.optString("library")+"\nThis device: "+result.optString("replica")+"\nMembers: "+result.optJSONArray("members")).setPositiveButton("OK",null).show();})).show();
    });}
    private void choosePeer(){work(() -> new SyncStore(this,app().library).status(),state -> {try {
        if(!state.optBoolean("adopted"))throw new IllegalStateException("Start a group or adopt an exchange first.");JSONArray list=state.getJSONArray("members");List<String> peers=new ArrayList<>();for(int i=0;i<list.length();i++)if(!list.getString(i).equals(state.getString("replica")))peers.add(list.getString(i));if(peers.isEmpty())throw new IllegalStateException("Add a reviewed peer to this group before exporting.");
        new AlertDialog.Builder(this).setTitle("Choose receiving peer").setItems(peers.toArray(new String[0]),(dialog,which)->{exportPeer=peers.get(which);pick(true);}).show();
    }catch(Exception error){new AlertDialog.Builder(this).setMessage(error.getMessage()).setPositiveButton("OK",null).show();}});}
    private void open(){pick(false);}
    private void pick(boolean saving){Intent intent=new Intent(saving?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(saving?"application/octet-stream":"*/*");
        if(saving)intent.putExtra(Intent.EXTRA_TITLE,"Stack-"+new SimpleDateFormat("yyyy-MM-dd-HHmmss",Locale.ROOT).format(new Date())+".stackexchange");
        try{startActivityForResult(intent,saving?EXPORT:IMPORT);}catch(ActivityNotFoundException error){new AlertDialog.Builder(this).setMessage("A local document picker is required.").setPositiveButton("OK",null).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==EXPORT){String peer=exportPeer;work(() -> {try(OutputStream output=getContentResolver().openOutputStream(data.getData(),"w")){if(output==null)throw new java.io.IOException("Destination unavailable");new SyncExchange(this,app().library).export(peer,output);}return peer;},p -> new AlertDialog.Builder(this).setMessage("Exchange file written for "+p+". Peer acknowledgment is still required.").setPositiveButton("OK",null).show());}
        if(request==IMPORT){work(() -> {try(InputStream input=getContentResolver().openInputStream(data.getData())){if(input==null)throw new java.io.IOException("File unavailable");return new SyncExchange(this,app().library).stage(input);}},this::reviewImport);}
    }
    private void reviewImport(SyncExchange.Staged file){if(staged!=null)staged.close();staged=file;work(() -> new SyncStore(this,app().library).status(),state -> {
        String group=file.header.optString("library"),sender=file.header.optString("sender");boolean adopting=!state.optBoolean("adopted");
        String message="Group: "+group+"\nSender: "+sender+"\nOperations: "+file.operations.size()+"\nOriginal blobs: "+file.header.optJSONArray("blobs").length();
        if(!adopting){new AlertDialog.Builder(this).setTitle("Review exchange").setMessage(message).setNegativeButton("Cancel",(d,w)->closeStage()).setPositiveButton("Import",(d,w)->apply(file,null,null)).show();return;}
        EditText members=entry(sender+","+state.optString("replica"));new AlertDialog.Builder(this).setTitle("Adopt reviewed group").setMessage(message+"\n\nThis library must have no saved documents. Enter the complete member UUID list, including both devices and any other reviewed peers.").setView(members).setNegativeButton("Cancel",(d,w)->closeStage()).setPositiveButton("Adopt & import",(d,w)->apply(file,group,parse(members.getText().toString()))).show();
    });}
    private void apply(SyncExchange.Staged file,String group,List<String> members){work(() -> new SyncExchange(this,app().library).importFile(file,group,members),result -> {closeStage();refresh();new AlertDialog.Builder(this).setTitle("Exchange imported").setMessage("Applied operations: "+result.optInt("applied")+"\nPending gaps: "+result.optInt("pending_gaps")+"\nConflicts to review: "+result.optInt("conflicts")).setPositiveButton("OK",null).show();},this::closeStage);}
    private void closeStage(){if(staged!=null){staged.close();staged=null;}}
    private static List<String> parse(String text){List<String> result=new ArrayList<>();for(String token:text.split("[,\\s]+"))if(!token.isEmpty())result.add(token.trim());return result;}
    private void review(){work(() -> new SyncStore(this,app().library).status(),state -> {JSONObject conflicts=state.optJSONObject("conflicts");if(conflicts==null||conflicts.length()==0){new AlertDialog.Builder(this).setMessage("No sync conflicts need review.").setPositiveButton("OK",null).show();return;}
        List<String> keys=SyncProtocol.keys(conflicts);new AlertDialog.Builder(this).setTitle("Choose a conflict").setItems(keys.toArray(new String[0]),(dialog,which)->reviewOne(keys.get(which),conflicts.optJSONArray(keys.get(which)))).show();});}
    private void reviewOne(String key,JSONArray alternatives){if(alternatives==null)return;String[] labels=new String[alternatives.length()];for(int i=0;i<labels.length;i++){JSONObject value=alternatives.optJSONObject(i);labels[i]=value==null?"Unknown alternative":value.optJSONObject("id")+" — "+String.valueOf(value.opt("value"));}
        new AlertDialog.Builder(this).setTitle(key).setItems(labels,(dialog,which)->new AlertDialog.Builder(this).setTitle("Use this alternative?").setMessage(labels[which]+"\n\nThe complete reviewed alternative set will be superseded.").setNegativeButton("Cancel",null).setPositiveButton("Use",(d,w)->{
            JSONArray ids=new JSONArray();for(int i=0;i<alternatives.length();i++)ids.put(alternatives.optJSONObject(i).optJSONObject("id"));Object selected=alternatives.optJSONObject(which).opt("value");work(() -> new SyncStore(this,app().library).resolve(key,ids,selected),state -> {render(state);review();});
        }).show()).setNegativeButton("Cancel",null).show();}
}
