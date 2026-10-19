package org.stackdocs.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Category tiles with durable per-action saves. Native drags are local to this editor only. */
public final class StackEditorActivity extends LibraryActivity {
    private LinearLayout grid;
    private ScrollView scroll;
    private TextView status;
    private Button add;
    private List<StackCategory> catalog = new ArrayList<>();
    private final List<View> tiles = new ArrayList<>();
    private boolean busy, loading;
    private int generation, restoredScroll;
    private Drag drag;
    private View highlighted;
    private float dragX, dragY;
    private int scrollDirection;
    private static final class Drag {
        final StackEditorActivity owner;
        final List<StackCategory> expected;
        final String id;
        Drag(StackEditorActivity owner, List<StackCategory> expected, String id) {
            this.owner = owner; this.expected = new ArrayList<>(expected); this.id = id;
        }
    }
    private final Runnable edgeScroll = new Runnable() {
        @Override public void run() {
            if (drag == null || scrollDirection == 0) return;
            scroll.scrollBy(0, Ui.dp(StackEditorActivity.this, 12) * scrollDirection);
            highlight(targetAt(dragX, dragY));
            scroll.postDelayed(this, 32);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        restoredScroll = state == null ? 0 : state.getInt("scroll");
        LinearLayout root = Ui.column(this), bar = Ui.toolbar(this);
        bar.addView(Ui.iconButton(this, Icons.Symbol.BACK, "Back", this::finish), new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        bar.addView(Ui.text(this,"Edit stacks",18,false),new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(Ui.button(this,"Done",this::finish)); root.addView(bar);
        LinearLayout actions = Ui.row(this); actions.setPadding(Ui.dp(this,16),0,Ui.dp(this,8),0);
        TextView hint = Ui.text(this,"Hold a tile to reorder",13,false); hint.setTextColor(Ui.MUTED);
        actions.addView(hint,new LinearLayout.LayoutParams(0,-2,1));
        add = Ui.button(this,"New stack",this::create); actions.addView(add); root.addView(actions);
        status = Ui.text(this,"",13,false); status.setTextColor(Ui.MUTED);
        status.setPadding(Ui.dp(this,24),0,Ui.dp(this,24),Ui.dp(this,8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setVisibility(View.GONE); root.addView(status);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        grid = Ui.column(this); grid.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),Ui.dp(this,24));
        scroll.addView(grid); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnDragListener(this::onDrag);
        Ui.install(this,root);
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onLibraryChanged() { if (drag == null && !busy) refresh(); }
    @Override protected void onPause() { clearDrag(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("scroll",scroll.getScrollY()); super.onSaveInstanceState(state); }
    private void refresh() {
        if (grid == null || loading || busy || drag != null) return;
        loading = true; final int ticket = ++generation;
        work(() -> app().library.stacks(), result -> {
            loading = false; if (ticket != generation || drag != null) return;
            boolean changed = catalog.size() != result.size() || tiles.isEmpty();
            if (!changed) for (int i=0;i<result.size();i++) {
                if (!LibraryRepository.sameStack(catalog.get(i),result.get(i)) || catalog.get(i).count!=result.get(i).count) { changed=true; break; }
            }
            catalog = result;
            // OCR/metadata progress also notifies this screen. Rebuilding identical
            // tiles would cancel a finger held down while that background work runs.
            if (changed) render();
            if (restoredScroll > 0) { int y = restoredScroll; restoredScroll = 0; scroll.post(() -> scroll.scrollTo(0,y)); }
        }, () -> { loading = false; status.setText(R.string.stack_load_error); status.setVisibility(View.VISIBLE); });
    }
    private void render() {
        int y = scroll.getScrollY(); grid.removeAllViews(); tiles.clear();
        float font = getResources().getConfiguration().fontScale;
        int width = getResources().getConfiguration().screenWidthDp;
        int columns = font >= 1.5f ? 1 : Math.max(2, Math.min(4, (width - 24) / 140));
        LinearLayout row = null;
        for (int i = 0; i < catalog.size(); i++) {
            if (i % columns == 0) { row = Ui.row(this); row.setGravity(Gravity.TOP); grid.addView(row,new LinearLayout.LayoutParams(-1,-2)); }
            View tile = tile(catalog.get(i), i); tiles.add(tile);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1); lp.setMargins(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4)); row.addView(tile,lp);
        }
        if (row != null) for (int i = catalog.size() % columns; i > 0 && i < columns; i++) {
            LinearLayout.LayoutParams spacer=new LinearLayout.LayoutParams(0,0,1);
            spacer.setMargins(Ui.dp(this,4),0,Ui.dp(this,4),0);row.addView(new View(this),spacer);
        }
        add.setEnabled(!busy); setControls(grid,!busy); scroll.post(() -> scroll.scrollTo(0,y));
    }
    private View tile(StackCategory stack, int position) {
        LinearLayout tile = Ui.column(this); tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,8),0);
        tile.setTag(stack.id); tile.setBackground(Ui.outlined(this,Color.WHITE,Ui.LINE,12,1));
        View badge = Ui.stackBadge(this,stack,64);
        tile.addView(badge,new LinearLayout.LayoutParams(Ui.dp(this,64),Ui.dp(this,64)));
        TextView name = Ui.text(this,stack.name,15,true); name.setGravity(Gravity.CENTER);
        name.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,4)); tile.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView count = Ui.text(this,stack.count+(stack.count==1?" document":" documents"),12,false); count.setTextColor(Ui.MUTED); count.setGravity(Gravity.CENTER); tile.addView(count);
        LinearLayout controls = Ui.row(this);
        CheckBox home = new CheckBox(this); home.setText(R.string.stack_home); home.setTextSize(13); home.setTextColor(Ui.INK); home.setChecked(stack.visible);
        home.setMinHeight(Ui.dp(this,48)); home.setContentDescription("Show "+stack.name+" on Home");
        home.setOnClickListener(view -> {
            home.setChecked(stack.visible); // Show only the committed value; failed writes leave no false success.
            mutate(() -> app().library.editStack(stack,stack.name,!stack.visible),"Visibility saved");
        }); controls.addView(home,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(Ui.iconButton(this,Icons.Symbol.MORE,"Options for "+stack.name,() -> options(stack)),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        tile.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        tile.setFocusable(true); tile.setContentDescription(stack.name+", position "+(position+1)+" of "+catalog.size()+". Hold to reorder. Options include Move earlier and Move later.");
        View.OnLongClickListener hold = view -> beginDrag(tile,stack);
        tile.setOnLongClickListener(hold); badge.setOnLongClickListener(hold); name.setOnLongClickListener(hold);
        return tile;
    }
    private void options(StackCategory stack) {
        List<StackCategory> expected = new ArrayList<>(catalog);
        int position = LibraryRepository.stackIds(expected).indexOf(stack.id);
        List<String> labels = new ArrayList<>(); labels.add("Rename");
        if (position > 0) labels.add("Move earlier");
        if (position < catalog.size()-1) labels.add("Move later");
        labels.add("Delete stack");
        new AlertDialog.Builder(this).setTitle(stack.name).setItems(labels.toArray(new String[0]),(dialog,index) -> {
            switch(labels.get(index)) {
                case "Rename": NameEditor.show(this,"Rename stack",stack.name,"Save",app().io,
                        name -> { if(!app().access.allowed())throw new IllegalArgumentException("Unlock Stack to save."); app().library.editStack(stack,name,stack.visible); },this::refresh); break;
                case "Move earlier": move(stack.id,position-1,expected); break;
                case "Move later": move(stack.id,position+1,expected); break;
                default: new AlertDialog.Builder(this).setTitle("Delete this stack?").setMessage("Its documents will stay in All documents.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w) -> mutate(() -> app().library.deleteStack(stack),"Stack deleted")).show();
            }
        }).setNegativeButton("Cancel",null).show();
    }
    private void create() {
        NameEditor.show(this,"Create a stack","","Create",app().io,name -> {
            if(!app().access.allowed())throw new IllegalArgumentException("Unlock Stack to save."); app().library.createStack(name);
        },this::refresh);
    }
    private interface Change { void run(); }
    private void mutate(Change change, String message) {
        if (busy) return; busy = true; ++generation; loading = false;
        add.setEnabled(false); setControls(grid,false); status.setText(R.string.saving_scan); status.setVisibility(View.VISIBLE);
        work(() -> { change.run(); return true; }, ignored -> { busy=false; status.setText(message); refresh(); },
                () -> { busy=false; status.setText(R.string.stack_save_error); refresh(); });
    }
    private static void setControls(View view, boolean enabled) {
        view.setEnabled(enabled); if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)setControls(group.getChildAt(i),enabled);
        }
    }
    private boolean beginDrag(View tile, StackCategory stack) {
        if (busy || drag != null || !app().access.allowed()) return false;
        Drag candidate = new Drag(this,catalog,stack.id); drag = candidate;
        // No ClipData, URI grants or GLOBAL flag: no category names/content cross app boundaries.
        if(!tile.startDragAndDrop(null,new View.DragShadowBuilder(tile),candidate,0)) { clearDrag(); return false; }
        tile.setAlpha(.45f); return true;
    }
    private boolean onDrag(View view, DragEvent event) {
        if (!(event.getLocalState() instanceof Drag)) return false;
        Drag session = (Drag)event.getLocalState();
        if(session.owner != this || session != drag || !app().access.allowed())return false;
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return !busy;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                dragX=event.getX(); dragY=event.getY(); highlight(targetAt(dragX,dragY));
                scrollDirection = dragY<Ui.dp(this,48)?-1:dragY>scroll.getHeight()-Ui.dp(this,48)?1:0;
                scroll.removeCallbacks(edgeScroll); if(scrollDirection!=0)scroll.post(edgeScroll); return true;
            case DragEvent.ACTION_DRAG_EXITED: scrollDirection=0; scroll.removeCallbacks(edgeScroll); highlight(-1); return true;
            case DragEvent.ACTION_DROP:
                int target=targetAt(event.getX(),event.getY());
                clearDrag(); if(target<0)return false; move(session.id,target,session.expected); return true;
            case DragEvent.ACTION_DRAG_ENDED: clearDrag(); refresh(); return true;
            default: return true;
        }
    }
    private int targetAt(float x, float y) {
        float best=Float.MAX_VALUE; int target=-1;
        float contentY=y+scroll.getScrollY();
        for(int i=0;i<tiles.size();i++) {
            View tile=tiles.get(i),row=(View)tile.getParent();
            float dx=x-(row.getLeft()+grid.getLeft()+tile.getLeft()+tile.getWidth()/2f);
            float dy=contentY-(grid.getTop()+row.getTop()+tile.getTop()+tile.getHeight()/2f);
            float distance=dx*dx+dy*dy;
            if(distance<best){best=distance;target=i;}
        }
        return target;
    }
    private void highlight(int position) {
        View next=position<0?null:tiles.get(position);
        if(highlighted==next)return;
        if(highlighted!=null)highlighted.setBackground(Ui.outlined(this,Color.WHITE,Ui.LINE,12,1));
        highlighted=next;
        if(next!=null)next.setBackground(Ui.outlined(this,0xFFE8F0FE,Ui.BLUE,12,2));
    }
    private void clearDrag() {
        drag=null; scrollDirection=0; if(scroll!=null)scroll.removeCallbacks(edgeScroll);
        highlight(-1); for(View tile:tiles)tile.setAlpha(1);
    }
    private void move(String id, int target, List<StackCategory> expected) {
        List<String> order=LibraryRepository.stackIds(expected); int from=order.indexOf(id);
        if(from<0 || target<0 || target>=order.size() || from==target)return;
        order.remove(from); order.add(target,id);
        mutate(() -> app().library.reorderStacks(expected,order),"Order saved");
    }
}
