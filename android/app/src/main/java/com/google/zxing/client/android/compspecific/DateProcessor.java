package com.google.zxing.client.android.compspecific;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

/**
 * Created by SLAIK on 11/18/2014.
 */
public class DateProcessor extends Service {

    public static final String TAG = "DateProcessor";

    public String[] DateProcessor(Date date,String gap){

        DateFormat dateFormat= new SimpleDateFormat("dd/MM/yyyy");

        String[] gap_actual = gap.split(",");
        String[] dates = new String[4];
        Calendar c = Calendar.getInstance();
        c.setTime(date);

        //first date
        dates[0] = dateFormat.format(c.getTime()).toString();//date.toString();

        //second date
        c.add(Calendar.DATE, Integer.parseInt(gap_actual[0]));
        dates[1] = dateFormat.format(c.getTime()).toString();

        //third date
        c.add(Calendar.DATE, Integer.parseInt(gap_actual[1]));
        dates[2] = dateFormat.format(c.getTime()).toString();

        //forth date
        c.add(Calendar.DATE, Integer.parseInt(gap_actual[2]));
        dates[3] = dateFormat.format(c.getTime()).toString();

        Log.e(TAG, "Dates returned");
        return dates;
    }

    public String[] DateProcessor(String date,String gap,String format){

        DateFormat dateFormat= new SimpleDateFormat(format);

        String[] gap_actual = gap.split(",");
        String[] dates = new String[4];
        Calendar c = Calendar.getInstance();
        Date td = null;
        try {
            td = dateFormat.parse(date);

            c.setTime(td);

            //first date
            dates[0] = dateFormat.format(c.getTime()).toString();//date.toString();

            //second date
            c.add(Calendar.DATE, Integer.parseInt(gap_actual[0]));
            dates[1] = dateFormat.format(c.getTime()).toString();

            //third date
            c.add(Calendar.DATE, Integer.parseInt(gap_actual[1]));
            dates[2] = dateFormat.format(c.getTime()).toString();

            //forth date
            c.add(Calendar.DATE, Integer.parseInt(gap_actual[2]));
            dates[3] = dateFormat.format(c.getTime()).toString();

        } catch (ParseException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "Dates returned");
        return dates;
    }

    public String date_format_shifter(String date,String format1,String format2){
        String date_str = "";
        Date td;

        DateFormat dateFormat1= new SimpleDateFormat(format1);
        DateFormat dateFormat2= new SimpleDateFormat(format2);

        try {
            td = dateFormat1.parse(date);
            Calendar c = Calendar.getInstance();
            c.setTime(td);


            //first date
            date_str = dateFormat2.format(c.getTime()).toString();

            return date_str;

        } catch (ParseException e) {
            e.printStackTrace();
        }

        return date_str;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
