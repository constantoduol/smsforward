package com.quest.smsforward;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    //final SmsManager sms = SmsManager.getDefault();

    private static Context cxt;

    private static final long RETRY_DELAY = 1000;

    private static String FORWARD_URL = "https://test-quest-uza.appspot.com/server";

    private static String SENDER_SERVICE = "MPESA";

    private static String TEMPLATE_REQUEST = "{\"request_header\":{\"request_svc\":\"\",\"request_msg\":\"\",\"session_id\":\"\"},\"request_object\":{}}";

    public void onReceive(Context context, Intent intent) {
        cxt = context;
        // Retrieves a map of extended data from the intent.
        final Bundle bundle = intent.getExtras();
        Database db = new Database(context);

        try {
            if (bundle != null) {
                final Object[] pdusObj = (Object[]) bundle.get("pdus");
                for (int i = 0; i < pdusObj.length; i++) {
                    SmsMessage currentMessage = SmsMessage.createFromPdu((byte[]) pdusObj[i]);
                    final String phoneNumber = currentMessage.getDisplayOriginatingAddress();
                    final String message = currentMessage.getDisplayMessageBody();
                    Log.i("SmsReceiver", "senderNum: " + phoneNumber + "; message: " + message);
                    final String id = ((Long)Math.abs(new Random().nextLong())).toString();
                    Callback callback = new Callback() {
                        @Override
                        public void doneAtBeginning() {

                        }

                        @Override
                        public Object doneInBackground() {
                            //first check that the message is from sender_service and
                            //that it is the kind of message we are looking for
                            //after that check if we have an active internet connection
                            if(!phoneNumber.equalsIgnoreCase(SENDER_SERVICE) || message.toLowerCase().indexOf("received") == -1){
                                //either wrong sender
                                //or wrong type of message
                                //fail at this point
                                Log.i("APP","Wrong sender or wrong message");
                                return null;
                            }
                            else if(hasActiveInternetConnection()) {
                                //if we have the correct message
                                //and the correct sender
                                //send to the server
                               Log.i("APP","Sending message to server");
                               toServer(message, id);

                            }
                            else {
                                //no internet connection
                                try {
                                    //save the message so that we retry sending later

                                    Log.i("APP","no internet connection, saving message");
                                    Database.put(id, message); //e.g 0722111000 : JDSSJSJ confirmed ...
                                    showAlert("No Internet", "Please enable your internet connection to process transactions");
                                    //schedule processing transaction for later
                                    //try after every 1 seconds
                                    retryExternalToServer();

                                }
                                catch(Exception e){
                                    Log.e("APP", e.toString());
                                    e.printStackTrace();
                                }

                            }
                            return null;
                        }

                        @Override
                        public void doneAtEnd(Object result) {

                        }
                    };
                    AsyncHandler handler = new AsyncHandler(callback);
                    handler.execute();

                } // end for loop
            } // bundle is null

        } catch (Exception e) {
            e.printStackTrace();
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


    private static void toServer(final String message,final String id){
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
            requestBody.put("SENDER_SERVICE","MPESA");
            //requestBody.put("SENDER_SERVICE", senderNum);
            String link = FORWARD_URL + "?json=" + URLEncoder.encode(request.toString(), "UTF-8");
            String resp = doGet(link); //might throw a runtime exception
            System.out.println(resp);
            Database.remove(id); //delete this if it exists
        }
        catch(Exception e){
            //this means there was a network error
            e.printStackTrace();
        }
    }

    private static boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) cxt.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static void setContext(Context context){
        cxt = context;
    }



    public static void retryExternalToServer(){
        Callback callback = new Callback() {
            @Override
            public void doneAtBeginning() {

            }

            @Override
            public Object doneInBackground() {
                final Timer timer = new Timer();
                TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        Log.i("APP", "retrying transaction");
                        HashMap map = Database.getAll();
                        if(map.size() == 0)  timer.cancel();
                        if(hasActiveInternetConnection()) {
                            Log.i("APP", "retrying to server transaction...");
                            Iterator iter = map.keySet().iterator();
                            if(map.size() == 0) timer.cancel();

                            while (iter.hasNext()) {
                                String id = iter.next().toString();
                                String message = map.get(id).toString();
                                toServer(message, id);
                            }
                        }
                    }
                };
                timer.scheduleAtFixedRate(task,0,RETRY_DELAY);
                return null;
            }

            @Override
            public void doneAtEnd(Object result) {

            }
        };

        AsyncHandler handler = new AsyncHandler(callback);
        handler.execute();
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









    private static String[] parseMessage(String msg){
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
