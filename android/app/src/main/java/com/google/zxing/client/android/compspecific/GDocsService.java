package com.google.zxing.client.android.compspecific;



import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;


/**
 * Created by SLAIK on 10/23/2014.
 */
public class GDocsService extends Service{
    public static final String TAG = "GDocsService";
    public String fileName = "QRF1";
    public String fileName1 = "QRF2";
    public String QRWetPlateDB = "1r87QIsrbb2uHNST4jv8KOIWK5M6twwYDuHirALwg4eQ";
    public String QRWetPlateDB_form = "1d1oyIa6EXaoQDd4Zmqe9auui9Yq0cK1cWxs-eRda-To";
    public String QRWetPlateDB_pMONID = "1564493013";
    public String QRWetPlateDB_gap = "293395442";
    public String QRWetPlateDB_date_1 = "510787124";
    public String QRWetPlateDB_SolID_1 = "609180926";
    public String QRWetPlateDB_SampleNo_1 = "910440535";
    public String QRWetPlateDB_SolID_2 = "1974241920";
    public String QRWetPlateDB_SampleNo_2 = "338554167";
    public String QRWetPlateDB_SolID_3 = "790217406";
    public String QRWetPlateDB_SampleNo_3 = "1040655519";
    public String QRWetPlateDB_SolID_4 = "2001205040";
    public String QRWetPlateDB_SampleNo_4 = "2051635195";
    public String QRWetPlateDB_Loc = "1662187074";
    public String QRWetPlateDB_Remarks = "1496753051";

    public String gid_worksheetid="1453423528";

    public void init(final String pMONID, final String gap, final String date_1, final String SolID_1, final String SampleNo_1, final String SolID_2, final String SampleNo_2, final String SolID_3, final String SampleNo_3, final String SolID_4, final String SampleNo_4,final String Location, final String Remarks){
        //call the gps service
        Log.e(TAG, "init-post");

        //run a seperate thread and post the data to Google Docs
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                postData(pMONID,gap,date_1,SolID_1,SampleNo_1,SolID_2,SampleNo_2,SolID_3,SampleNo_3,SolID_4,SampleNo_4,Location,Remarks);

            }
        });
        t.start();

    }

    public String getData(final String pmon){

        Log.e(TAG, "getData");
        //query GDocs Database for the same
        final String GDQuery="https://docs.google.com/spreadsheets/d/"+QRWetPlateDB+"/gviz/tq?tqx=out:JSON&headers=0&gid="+gid_worksheetid+"&tq=select%20*%20where%20B=%20"+pmon+"%20";
        // -- new change 1: for some reason after changing the pmon size from 16 to 5-6 the select query is not working with the %27"+pmon+"%27"; style. so replaced them with %20.
        //run a seperate thread and post the data to Google Docs
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {

                jsonHandler(GDQuery,pmon);

            }
        });
        t.start();
        try {
                t.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //get the data and pass it back to the callee program
        FileOperations fop = new FileOperations();
        String feed = fop.read(fileName);
        return feed.replace("\n","");

        }

    public void jsonHandler(String GDQuery, String pmon) {
        Log.e(TAG, "jsonHandler");
        String htmlPage = null;

        try {


            //fetch HTML body content
            URL url = new URL(GDQuery);
            URLConnection yc = url.openConnection();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(
                            yc.getInputStream()));
            String inputLine;
            StringBuilder builder = new StringBuilder();
            while ((inputLine = in.readLine()) != null)
                builder.append(inputLine.trim());
            in.close();
            htmlPage = builder.toString();
            //String versionNumber = htmlPage.replaceAll("\\<.*?>","");


        } catch (MalformedURLException e) {

        } catch (IOException e) {

        }


        Log.e(TAG,"\n\n\n\n\n");
        Log.e(TAG,GDQuery);
        Log.e(TAG,"\n\n\n\n\n");
        Log.e(TAG, String.valueOf(htmlPage));
        Log.e(TAG,"\n\n\n\n\n");
        //update tracking file
        final FileOperations fop = new FileOperations();
        fop.write(fileName,"");



        //check if any data was returned
        try{
            if(htmlPage.contains("\"rows\":[]")){
                Log.e(TAG,"No Data found for "+pmon);

            }else{
                //fish out the data and write into a file
                //htmlPage = htmlPage.replaceAll("google.visualization.Query.setResponse.*?\\\"v\\\":\\\"",""); -- new change 1: for some reason the quote after : is killing the regex
                htmlPage = htmlPage.replaceAll("google.visualization.Query.setResponse.*?\\\"v\\\":","");
                String htmlPage_str = htmlPage.replaceAll("\\\"\\}\\]\\}\\]\\}\\}\\)\\;","");
                Log.d(TAG,pmon+":"+htmlPage_str);

                //filter out the values
                String[] html_split = htmlPage_str.split("\\\"\\}\\,\\{\\\"");
                //-- new change 1: due to the change we are getting 1 additional value @ [0] now, to fix that the below operation is starting from 1 instead of 0.

                //format the values & re-build the string to save in file
//                htmlPage_str=html_split[0];
//                for (int i=1;i<html_split.length;i++){
//                    html_split[i] = html_split[i].split("\":\"")[1];
//                    htmlPage_str=htmlPage_str+"|"+html_split[i];
//                }


                //-- new change 1:
                htmlPage_str=html_split[1].split("\":\"")[1];
                for (int i=2;i<html_split.length;i++){
                    html_split[i] = html_split[i].split("\":\"")[1];
                    htmlPage_str=htmlPage_str+"|"+html_split[i];
                }





                //update tracking file
                //final FileOperations fop = new FileOperations();
                fop.write(fileName, htmlPage_str);


            }

        }catch(Exception e){
            Log.e(TAG,"HTML parsing failed.");
        }
    }





    public void postData(String pMONID,String gap,String date_1,String SolID_1,String SampleNo_1,String SolID_2,String SampleNo_2,String SolID_3,String SampleNo_3,String SolID_4,String SampleNo_4,String Location,String Remarks) {

        Log.e(TAG, "postData");
        String fullUrl = "https://docs.google.com/forms/d/"+ QRWetPlateDB_form +"/formResponse";
        HttpRequest mReq = new HttpRequest();

        String data = null;
        try {
            data = "entry."+QRWetPlateDB_pMONID+"=" + URLEncoder.encode(pMONID, "UTF-8") + "&" +
                    "entry."+QRWetPlateDB_gap+"=" + URLEncoder.encode(gap, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_date_1+"=" + URLEncoder.encode(date_1, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SolID_1+"=" + URLEncoder.encode(SolID_1, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SolID_2+"=" + URLEncoder.encode(SolID_2, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SolID_3+"=" + URLEncoder.encode(SolID_3, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SolID_4+"=" + URLEncoder.encode(SolID_4, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SampleNo_1+"=" + URLEncoder.encode(SampleNo_1, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SampleNo_2+"=" + URLEncoder.encode(SampleNo_2, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SampleNo_3+"=" + URLEncoder.encode(SampleNo_3, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_SampleNo_4+"=" + URLEncoder.encode(SampleNo_4, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_Loc+"=" + URLEncoder.encode(Location, "UTF-8")+ "&" +
                    "entry."+QRWetPlateDB_Remarks+"=" + URLEncoder.encode(Remarks, "UTF-8");

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        String response = mReq.sendPost(fullUrl, data);
        //Log.i(TAG, response);
        Log.i(TAG, "Data Posted");
    }


    //Encryption-Decryption for outgoing & incoming data
    //Same logic must be implemented on the server side





    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

