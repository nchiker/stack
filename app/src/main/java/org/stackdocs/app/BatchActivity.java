package org.stackdocs.app;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.util.*;

/** Review each durable item; completed imports open the ordinary name/filing/details workflow. */
public final class BatchActivity extends LibraryActivity {
    private LinearLayout body;private String id;
    @Override public void onCreate(Bundle state){super.onCreate(state);id=getIntent().getStringExtra("operation_id");LinearLayout root=Ui.column(this),bar=Ui.toolbar(this);bar.addView(Ui.iconButton(this,Icons.Symbol.BACK,"Back",this::finish),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));bar.addView(Ui.text(this,"Imports & batch actions",18,false),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);ScrollView scroll=new ScrollView(this);body=Ui.column(this);body.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Ui.install(this,root);}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onLibraryChanged(){refresh();}
    private void refresh(){work(() -> app().library.batches.operations(),operations -> {
        body.removeAllViews();if(operations.isEmpty())body.addView(Ui.text(this,"No batch actions yet. Select up to 50 documents or import several files.",16,false));
        for(BatchStore.Operation op:operations){if(id!=null&&!id.equals(op.id))continue;
            body.addView(Ui.text(this,op.kind+" · "+op.state+" · "+op.items.size()+" items",16,true));
            body.addView(Ui.text(this,"Selection order is the output order. Open a saved import to review its name, suggested details and stacks.",13,false));
            for(BatchStore.Item item:op.items){String label="Item "+(item.position+1)+" · "+item.state;
                body.addView(Ui.text(this,label,14,true));if(!item.error.isEmpty()){TextView error=Ui.text(this,item.error,14,false);error.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(error);}
                if(!op.kind.equals("delete")&&item.state.equals("done"))body.addView(Ui.button(this,"Review saved item "+(item.position+1),() -> startActivity(new Intent(this,DocumentActivity.class).putExtra("document_id",item.document))));
            }
            if(op.state.equals("pending")||op.state.equals("running"))body.addView(Ui.button(this,"Cancel remaining items",() -> app().batches.cancel(op.id)));
            else if(op.state.equals("failed")||op.state.equals("canceled"))body.addView(Ui.button(this,"Retry remaining items",() -> app().batches.retry(op.id)));
            List<Uri> outputs=app().batches.outputs.get(op.id);
            if(outputs!=null)body.addView(Ui.primary(this,"Share completed "+(op.kind.equals("zip")?"ZIP":"PDFs"),() -> share(op,outputs)));
            else if(op.state.equals("done")&&(op.kind.equals("pdf")||op.kind.equals("zip")))body.addView(Ui.button(this,"Prepare copies again",() -> app().batches.retry(op.id)));
            body.addView(Ui.divider(this),new LinearLayout.LayoutParams(-1,Ui.dp(this,1)));
        }
    });}
    private void share(BatchStore.Operation op,List<Uri> outputs){if(!app().access.allowed())return;
        Intent intent=new Intent(outputs.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE).setType(op.kind.equals("zip")?"application/zip":"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(outputs.size()==1)intent.putExtra(Intent.EXTRA_STREAM,outputs.get(0));else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(outputs));
        ClipData clip=ClipData.newRawUri("Selected PDF copies",outputs.get(0));for(int i=1;i<outputs.size();i++)clip.addItem(new ClipData.Item(outputs.get(i)));intent.setClipData(clip);
        try{startActivity(Intent.createChooser(intent,"Share selected copies"));}catch(ActivityNotFoundException missing){Toast.makeText(this,"No receiving app is available",Toast.LENGTH_LONG).show();}
    }
}
