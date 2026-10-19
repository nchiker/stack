package org.stackdocs.app;

import android.content.ContentValues;
import android.database.Cursor;
import java.util.UUID;

/** Durable source decisions, including a reserved document identity before import commit. Repository IO only. */
final class PhotoLedger {
    private final LibraryRepository library;
    PhotoLedger(LibraryRepository library){this.library=library;}
    boolean decided(String source) {
        try(Cursor row=library.database().rawQuery("SELECT state,document_id FROM photo_history WHERE source=?",new String[]{source})) {
            if(!row.moveToFirst())return false;
            if(!row.getString(0).equals("pending"))return true;
            // Death after durable document commit but before source acknowledgement cannot reimport it.
            if(library.batches.exists(row.getString(1))){complete(source);return true;}
            return false;
        }
    }
    boolean duplicate(String source,String digest){try(Cursor row=library.database().rawQuery("SELECT 1 FROM photo_history WHERE digest=? AND source<>?",new String[]{digest,source})){return row.moveToFirst();}}
    String reserve(String source,String digest) {
        try(Cursor row=library.database().rawQuery("SELECT digest,state,document_id FROM photo_history WHERE source=?",new String[]{source})) {
            if(row.moveToFirst()) {
                if(!row.getString(1).equals("pending"))return null;
                if(!row.getString(0).equals(digest)){state(source,"changed");return null;}
                return row.getString(2);
            }
        }
        String id=UUID.randomUUID().toString();record(source,digest,"pending",id);return id;
    }
    void record(String source,String digest,String state,String document){ContentValues row=new ContentValues();row.put("source",source);row.put("digest",digest);row.put("state",state);row.put("document_id",document);library.database().insertWithOnConflict("photo_history",null,row,android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);}
    void complete(String source){state(source,"saved");}
    void failed(String source){state(source,"failed");}
    private void state(String source,String state){ContentValues update=new ContentValues();update.put("state",state);library.database().update("photo_history",update,"source=?",new String[]{source});}
}
