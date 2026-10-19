package org.stackdocs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.sqlite.SQLiteConstraintException;
import android.widget.EditText;
import java.util.concurrent.ExecutorService;

/** Keep the entered name visible until validation and the durable save succeed. */
final class NameEditor {
    interface Save { void run(String name) throws Exception; }
    static AlertDialog show(Activity activity,String heading,String initial,String action,ExecutorService io,Save save,Runnable saved) {
        return show(activity,heading,initial,action,io,save,saved,java.util.Collections.emptyList());
    }
    static AlertDialog show(Activity activity,String heading,String initial,String action,ExecutorService io,Save save,Runnable saved,java.util.List<String> names) {
        EditText input=new EditText(activity);
        input.setSingleLine(true); input.setText(initial); input.selectAll();
        input.setContentDescription(heading);
        android.widget.LinearLayout body=Ui.column(activity);
        body.setPadding(Ui.dp(activity,24),Ui.dp(activity,8),Ui.dp(activity,24),Ui.dp(activity,8));
        input.setTextSize(16);input.setMinimumHeight(Ui.dp(activity,56));body.addView(input,new android.widget.LinearLayout.LayoutParams(-1,-2));
        if(!names.isEmpty()){
            android.widget.Button suggestions=Ui.button(activity,"Suggested names",() -> new AlertDialog.Builder(activity).setTitle("Choose a name")
                    .setItems(names.toArray(new String[0]),(d,index) -> {input.setText(names.get(index));input.setSelection(input.length());}).setNegativeButton("Cancel",null).show());
            suggestions.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);body.addView(suggestions);
        }
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(heading).setView(scroll)
                .setPositiveButton(action,null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name;
            try { name=LibraryRepository.cleanName(input.getText().toString()); }
            catch(IllegalArgumentException invalid) { input.setError(invalid.getMessage()); return; }
            input.setError(null); input.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.setCancelable(false);
            io.execute(() -> {
                Exception failure=null;
                try { save.run(name); } catch(Exception error) { failure=error; }
                Exception outcome=failure;
                activity.runOnUiThread(() -> {
                    if(activity.isFinishing() || activity.isDestroyed()) return;
                    if(outcome==null) { dialog.dismiss(); saved.run(); return; }
                    input.setEnabled(true); dialog.setCancelable(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    String message=outcome instanceof SQLiteConstraintException ? "That stack name is already in use. Choose another name."
                            : outcome instanceof IllegalArgumentException ? outcome.getMessage() : "Couldn't save the name. Try again.";
                    input.setError(message); input.requestFocus();
                });
            });
        }));
        dialog.show();
        return dialog;
    }
}
