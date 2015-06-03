package com.quest.smsforward;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.webkit.WebView;

import java.util.HashMap;

public class Database extends SQLiteOpenHelper{

    private static String DATABASE_NAME="QUEST_DB";

    private static SQLiteDatabase dbRead;
    private static SQLiteDatabase dbWrite;



    public Database(Context cxt){
        super(cxt,DATABASE_NAME,null,1);
        dbRead = this.getReadableDatabase();
        dbWrite = this.getWritableDatabase();
    }



    @Override
    public void onCreate(SQLiteDatabase db) {
        //create the tables we will need currently
        db.execSQL("create table LOCAL_DATA (KEY_STORAGE TEXT, VALUE_STORAGE TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int arg1, int arg2) {
        // TODO Auto-generated method stub

    }

    public static boolean ifValueExists(String value, String column, String table){
        Cursor cs = dbRead.rawQuery("SELECT " + column + " FROM " + table + " WHERE " + column + "='" + value + "'", null);
        String exists=null;
        try{
            while(cs.moveToNext()){
                exists=cs.getString(cs.getColumnIndex(column));
            }
            cs.close();
            if(exists!=null && exists.length()>0 ){
                return true;
            }
            return false;
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    public static HashMap getAll(){
        Cursor cs = dbRead.rawQuery("SELECT * FROM LOCAL_DATA", null);
        try{
            HashMap map = new HashMap();
            while(cs.moveToNext()) {
                String key = cs.getString(0);
                String value = cs.getString(1);
                map.put(key,value);
            }
            cs.close();
            return map;
        }
        catch(Exception e){
            e.printStackTrace();
            return null;
        }
    }

    public static void put(final String key,final String value){
        Callback cb = new Callback() {
            @Override
            public Object doneInBackground() {
                boolean exists = ifValueExists(key,"KEY_STORAGE","LOCAL_DATA");
                if(exists) {
                    dbWrite.execSQL("UPDATE LOCAL_DATA SET VALUE_STORAGE = '" + value + "' WHERE KEY_STORAGE = '" + key + "'");
                }
                else{
                  //insert
                    ContentValues values = new ContentValues();
                    values.put("KEY_STORAGE", key);
                    values.put("VALUE_STORAGE",value);
                    dbWrite.insert("LOCAL_DATA", null, values);
                }
                return null;
            }

            @Override
            public void doneAtEnd(Object result) {

            }

            @Override
            public void doneAtBeginning() {


            }
        };

        AsyncHandler handler = new AsyncHandler(cb);
        handler.execute();

    }


    public static String get(final String key){
        Cursor cs = dbRead.rawQuery("SELECT * FROM LOCAL_DATA WHERE KEY_STORAGE='"+key+"'", null);
        try{
            String value = "";
            while(cs.moveToNext()) {
                value = cs.getString(1);
            }
            cs.close();
            return value;
        }
        catch(Exception e){
            return null;
        }
    }

    public static void remove(final String key){
        dbWrite.delete("LOCAL_DATA","KEY_STORAGE='"+key+"'",null);
    }

}

