package com.quest.smsforward;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by Connie on 18-May-15.
 */
public class IncomingSMS extends BroadcastReceiver {

    // Get the object of SmsManager
    final SmsManager sms = SmsManager.getDefault();

    private static Context cxt;

    private final long RETRY_DELAY = 5000;

    private static String FORWARD_URL = "https://test-quest-uza.appspot.com/server";

    private static String TEMPLATE_REQUEST = "{\"request_header\":{\"request_svc\":\"\",\"request_msg\":\"\",\"session_id\":\"\"},\"request_object\":{}}";

    public void onReceive(Context context, Intent intent) {

        // Retrieves a map of extended data from the intent.
        final Bundle bundle = intent.getExtras();
        cxt = context;
        try {
            if (bundle != null) {
                final Object[] pdusObj = (Object[]) bundle.get("pdus");
                for (int i = 0; i < pdusObj.length; i++) {
                    SmsMessage currentMessage = SmsMessage.createFromPdu((byte[]) pdusObj[i]);
                    String phoneNumber = currentMessage.getDisplayOriginatingAddress();
                    String senderNum = phoneNumber;
                    String message = currentMessage.getDisplayMessageBody();
                    Log.i("SmsReceiver", "senderNum: " + senderNum + "; message: " + message);
                    String id = ((Long)Math.abs(new Random().nextLong())).toString();
                    if(hasActiveInternetConnection()) {
                        //if we have a connection
                        toServer(message,id);
                    }
                    else {
                        //no internet connection
                        try {
                            //save the message so that we retry sending later
                            Log.i("APP","id : "+id);
                            Database.put(id, message); //e.g 0722111000 : JDSSJSJ confirmed ...
                            showAlert("No Internet", "Please enable your internet connection to process transactions");
                            //schedule processing transaction for later
                            //try after every 5 seconds
                            Handler handler = new Handler();
                            Runnable runnable = new Runnable(){
                                public void run() {
                                    Looper.prepare();
                                    retryToServer();
                                }
                            };
                            handler.postAtTime(runnable, System.currentTimeMillis()+RETRY_DELAY);
                            handler.postDelayed(runnable, RETRY_DELAY);
                            Log.e("APP", "Yeah got to the end");
                        }
                        catch(Exception e){
                            Log.e("APP", e.toString());
                            e.printStackTrace();
                        }
                    }
                } // end for loop
            } // bundle is null

        } catch (Exception e) {
            Log.e("SmsReceiver", "Exception smsReceiver" +e);
        }
    }

    public static boolean hasActiveInternetConnection() {
        if (isNetworkAvailable()) {
            try {
                HttpURLConnection urlc = (HttpURLConnection) (new URL("http://www.google.com").openConnection());
                urlc.setRequestProperty("User-Agent", "Test");
                urlc.setRequestProperty("Connection", "close");
                urlc.setConnectTimeout(1500);
                urlc.connect();
                return (urlc.getResponseCode() == 200);
            } catch (IOException e) {
                Log.e("APP", "Error checking internet connection", e);
            }
        } else {
            Log.d("APP", "No network available!");
        }
        return false;
    }


    private void toServer(final String message,final String id){
        Callback cb = new Callback() {
            @Override
            public Object doneInBackground() {
                String [] data = parseMessage(message);
                try {
                    //name, number from, amount, transaction id
                    JSONTokener tokener = new JSONTokener(TEMPLATE_REQUEST);
                    JSONObject request = (JSONObject) tokener.nextValue();
                    JSONObject requestHeader = request.optJSONObject("request_header");
                    JSONObject requestBody = request.optJSONObject("request_object");
                    requestHeader.put("request_svc", "open_data_service");
                    requestHeader.put("request_msg", "pay_bill_mpesa");
                    requestBody.put("TRANS_ID", data[0]);
                    requestBody.put("SENDER_NAME", data[1]);
                    requestBody.put("AMOUNT", data[2]);
                    requestBody.put("SENDER_PHONE_NO", data[3]);
                    //requestBody.put("SENDER_SERVICE", senderNum);
                    String link = FORWARD_URL + "?json=" + URLEncoder.encode(request.toString(), "UTF-8");
                    String resp = doGet(link); //might throw a runtime exception
                    System.out.println(resp);

                    JSONTokener tk = new JSONTokener(resp);
                    JSONObject response = (JSONObject) tk.nextValue();
                    Database.remove(id); //delete this if it exists
                }
                catch(Exception e){
                   //this means there was a network error
                    e.printStackTrace();
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

    private static boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) cxt.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public void retryToServer(){
        Log.i("APP","retrying transaction...");
        HashMap map = Database.getAll();
        Iterator iter = map.keySet().iterator();
        while(iter.hasNext()){
           String id = iter.next().toString();
           String message = map.get(id).toString();
           toServer(message,id);
        }
    }

    public void showAlert(String title,String message){
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(cxt)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(message);
        NotificationManager mNotificationManager =
                (NotificationManager) cxt.getSystemService(Context.NOTIFICATION_SERVICE);
// mId allows you to update the notification later on.
        mNotificationManager.notify(7, mBuilder.build());
    }









    private String[] parseMessage(String msg){
        String transId = msg.substring(0,msg.indexOf(" ")).trim();
        String name = msg.substring(msg.indexOf("from") + 4, msg.indexOf("07")).trim();
        String amount = msg.substring(msg.indexOf("Ksh")+3,msg.indexOf("from")).trim();
        String phoneNo = msg.substring(msg.indexOf("07"),msg.indexOf("07") + 10).trim();
        return new String[]{transId,name,amount,phoneNo};
    }

    public static String doGet(String urlToRead) {
        URL url;
        HttpURLConnection conn;
        BufferedReader rd;
        String line;
        String result = "";
        try {
            url = new URL(urlToRead);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            while ((line = rd.readLine()) != null) {
                result += line;
            }
            rd.close();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }


}
