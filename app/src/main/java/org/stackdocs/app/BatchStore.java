package org.stackdocs.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Device-local operation journal; never restored as authorization to run work. */
final class BatchStore {
    static final int LIMIT=50;
    final LibraryRepository library;
    BatchStore(LibraryRepository library){this.library=library;}
    static final class Item{final String operation,source,document,state,error;final int position;
        Item(String op,int pos,String source,String doc,String state,String error){operation=op;position=pos;this.source=source;document=doc;this.state=state;this.error=error;}}
    static final class Operation{final String id,kind,state;final List<Item> items;
        Operation(String id,String kind,String state,List<Item> items){this.id=id;this.kind=kind;this.state=state;this.items=items;}}
    String create(String kind,List<String> sources,List<String> documents) {
        if(sources.isEmpty()||sources.size()>LIMIT)throw new IllegalArgumentException("Select between 1 and 50 documents.");
        SQLiteDatabase db=library.database();String id=UUID.randomUUID().toString();db.beginTransaction();
        try{ContentValues op=new ContentValues();op.put("id",id);op.put("kind",kind);op.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,op);
            for(int i=0;i<sources.size();i++){ContentValues item=new ContentValues();item.put("operation_id",id);item.put("position",i);item.put("source",sources.get(i));item.put("document_id",documents==null?UUID.randomUUID().toString():documents.get(i));db.insertOrThrow("operation_items",null,item);}db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    void recover(){library.database().execSQL("UPDATE operations SET state='pending' WHERE state='running'");library.database().execSQL("UPDATE operation_items SET state='pending' WHERE state='running'");}
    List<Operation> operations(){List<Operation> out=new ArrayList<>();try(Cursor row=library.database().rawQuery("SELECT id,kind,state FROM operations ORDER BY created_at DESC,id LIMIT 100",null)){while(row.moveToNext())out.add(new Operation(row.getString(0),row.getString(1),row.getString(2),items(row.getString(0))));}return out;}
    Operation operation(String id){try(Cursor row=library.database().rawQuery("SELECT kind,state FROM operations WHERE id=?",new String[]{id})){if(row.moveToFirst())return new Operation(id,row.getString(0),row.getString(1),items(id));}throw new IllegalArgumentException("Operation no longer exists.");}
    List<String> pending(){List<String> ids=new ArrayList<>();try(Cursor row=library.database().rawQuery("SELECT id FROM operations WHERE state='pending' ORDER BY created_at",null)){while(row.moveToNext())ids.add(row.getString(0));}return ids;}
    List<Item> items(String id){List<Item> out=new ArrayList<>();try(Cursor rows=library.database().rawQuery("SELECT position,source,document_id,state,error FROM operation_items WHERE operation_id=? ORDER BY position",new String[]{id})){while(rows.moveToNext())out.add(new Item(id,rows.getInt(0),rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4)));}return out;}
    void state(String id,String state){ContentValues v=new ContentValues();v.put("state",state);library.database().update("operations",v,"id=?",new String[]{id});}
    void item(Item item,String state,String error){ContentValues v=new ContentValues();v.put("state",state);v.put("error",error);library.database().update("operation_items",v,"operation_id=? AND position=?",new String[]{item.operation,Integer.toString(item.position)});}
    boolean enabled(String id){return !operation(id).state.equals("canceled");}
    void retry(String id){library.database().execSQL("UPDATE operation_items SET state='pending',error='' WHERE operation_id=? AND state='failed'",new Object[]{id});state(id,"pending");}
    boolean exists(String id){try(Cursor row=library.database().rawQuery("SELECT 1 FROM documents WHERE id=?",new String[]{id})){return row.moveToFirst();}}
}
