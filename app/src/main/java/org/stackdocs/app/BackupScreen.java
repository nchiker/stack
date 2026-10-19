package org.stackdocs.app;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared Stack typography, circular original icons and ordinary library action rows. */
final class BackupScreen {
    final LinearLayout root;
    private final View backup,restore;
    private final LinearLayout operation;
    private final TextView heading,status,summary,backupDate;
    private final ProgressBar progress;
    private final ScrollView scroll;
    private final Button commit,cancel;
    private String lastMessage="",lastState="";

    BackupScreen(Activity activity,Runnable back,Runnable save,Runnable open,Runnable add,Runnable stop,Runnable help) {
        root=Ui.column(activity);
        LinearLayout toolbar=Ui.toolbar(activity);
        toolbar.addView(Ui.iconButton(activity,Icons.Symbol.BACK,"Back",back),new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)));
        toolbar.addView(Ui.text(activity,"Backup & restore",18,false),new LinearLayout.LayoutParams(0,-2,1));
        root.addView(toolbar);
        scroll=new ScrollView(activity);scroll.setFillViewport(true);
        LinearLayout body=Ui.column(activity);body.setPadding(Ui.dp(activity,24),Ui.dp(activity,24),Ui.dp(activity,24),Ui.dp(activity,24));scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        TextView title=Ui.text(activity,"A copy of your library",22,false);body.addView(title);
        TextView intro=muted(activity,"Save your documents to a location you choose, or bring back a previous backup.",15);
        intro.setPadding(0,Ui.dp(activity,12),0,Ui.dp(activity,24));body.addView(intro);

        backup=Ui.actionRow(activity,Icons.Symbol.FOLDER,"Backup library","Choose where to save a copy",0xFFDCECF9,save);
        restore=Ui.actionRow(activity,Icons.Symbol.DOCUMENTS,"Restore library","Add documents from a backup",0xFFDCEADB,open);
        body.addView(backup);
        backupDate=muted(activity,BackupHistory.label(activity),14);
        backupDate.setPadding(0,0,0,Ui.dp(activity,12));body.addView(backupDate);
        body.addView(Ui.divider(activity),new LinearLayout.LayoutParams(-1,Ui.dp(activity,1)));body.addView(restore);

        operation=Ui.column(activity);operation.setPadding(Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,12));
        operation.setBackground(Ui.outlined(activity,Color.WHITE,Ui.LINE,12,1));
        LinearLayout.LayoutParams panel=new LinearLayout.LayoutParams(-1,-2);panel.topMargin=Ui.dp(activity,24);body.addView(operation,panel);
        heading=Ui.text(activity,"",17,true);operation.addView(heading);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BLUE));
        LinearLayout.LayoutParams progressSize=new LinearLayout.LayoutParams(-1,Ui.dp(activity,4));progressSize.topMargin=Ui.dp(activity,16);operation.addView(progress,progressSize);
        status=muted(activity,"",14);status.setPadding(0,Ui.dp(activity,12),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);operation.addView(status);
        summary=Ui.text(activity,"",15,false);summary.setLineSpacing(Ui.dp(activity,3),1);summary.setPadding(0,Ui.dp(activity,12),0,Ui.dp(activity,12));operation.addView(summary);
        commit=Ui.primary(activity,"Add restored documents",add);operation.addView(commit,new LinearLayout.LayoutParams(-1,-2));
        cancel=Ui.button(activity,"Cancel operation",stop);operation.addView(cancel,new LinearLayout.LayoutParams(-1,-2));

        TextView scope=muted(activity,"Saved documents, page edits, stacks and text are included. Unfinished scans stay on this device.",14);
        scope.setPadding(0,Ui.dp(activity,28),0,Ui.dp(activity,8));body.addView(scope);
        TextView protection=muted(activity,"Backup files are not encrypted.",13);body.addView(protection);
        Button details=Ui.button(activity,"What's included",help);
        LinearLayout.LayoutParams helpSize=new LinearLayout.LayoutParams(-2,-2);helpSize.gravity=Gravity.START;helpSize.topMargin=Ui.dp(activity,8);helpSize.leftMargin=-Ui.dp(activity,16);body.addView(details,helpSize);
    }

    private static TextView muted(Activity activity,String value,int size) {
        TextView text=Ui.text(activity,value,size,false);text.setTextColor(Ui.MUTED);text.setLineSpacing(Ui.dp(activity,3),1);return text;
    }
    void history(String label){if(!backupDate.getText().toString().equals(label))backupDate.setText(label);}
    void render(String state,String label,String message,String reviewSummary,boolean canceled) {
        boolean busy=state.equals("exporting")||state.equals("validating")||state.equals("restoring"),review=state.equals("review");
        backup.setEnabled(!busy&&!review);restore.setEnabled(!busy&&!review);
        backup.setAlpha(busy||review?.45f:1);restore.setAlpha(busy||review?.45f:1);
        operation.setVisibility(busy||review||!message.isEmpty()?View.VISIBLE:View.GONE);
        heading.setText(review?"Review backup":state.equals("exporting")?"Saving backup":state.equals("validating")?"Checking backup":state.equals("restoring")?"Restoring documents":state.equals("failed")?"Couldn't finish":state.equals("canceled")?"Canceled":"Finished");
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        String current=busy?label:message;if(!lastMessage.equals(current)){status.setText(current);lastMessage=current;}
        status.setVisibility(current.isEmpty()?View.GONE:View.VISIBLE);
        summary.setVisibility(review?View.VISIBLE:View.GONE);
        if(!lastState.equals(state)){summary.setText(review?reviewSummary:"");if(review)summary.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);if(busy||review)scroll.post(()->scroll.smoothScrollTo(0,operation.getTop()));lastState=state;}
        commit.setVisibility(review?View.VISIBLE:View.GONE);cancel.setVisibility(busy||review?View.VISIBLE:View.GONE);
        cancel.setText(canceled&&busy?"Canceling…":"Cancel operation");cancel.setEnabled(!canceled);
    }
}
