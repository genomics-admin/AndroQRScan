package com.google.zxing.client.android.compspecific;

/**
 * Created by SLAIK on 10/21/2014.
 */
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
public class FileOperations {
    public static final String TAG = "FileOperations";

    public FileOperations() {
    }

    public Boolean write(String fname, String fcontent){
        try {
            String fpath = Environment.getExternalStorageDirectory().getPath()+"/"+fname+".txt";
            File file = new File(fpath);
            // If file does not exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            FileWriter fw = new FileWriter(file.getAbsoluteFile());
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(fcontent);
            bw.close();
            Log.e("Suceess","Sucess");
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
    public String read(String fname){
        BufferedReader br = null;
        String response = null;
        try {
            StringBuffer output = new StringBuffer();
            String fpath = Environment.getExternalStorageDirectory().getPath()+"/"+fname+".txt";
            File file = new File(fpath);
            if (file.exists()) {
                br = new BufferedReader(new FileReader(fpath));
                String line = "";
                while ((line = br.readLine()) != null) {
                    output.append(line +"\n");
                }
                response = output.toString();
                Log.e("Suceess","File read");
            }else{
                Log.e("Failure","File not found");
                //file.createNewFile();
            }

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return response;
    }
    //--find and overwrite
    public Boolean overwrite(String fname, String fcontent){
        try {
            String fpath = Environment.getExternalStorageDirectory().getPath()+"/"+fname+".txt";
            File file = new File(fpath);
            // If file does not exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            //read the file content first
            String txt = read(fname);

            if(txt.contains(fcontent)){
                txt = txt.replace(fcontent,"");
                txt = txt.replace("\n\n","\n");
                //sed for any blanks related autocorrection
                txt = autoCorrection(txt);
                //need to write a if loop to handle empty \n if needed - here
                FileWriter fw = new FileWriter(file.getAbsoluteFile());
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(txt);
                bw.close();
                Log.e("Suceess","Removed");
                return false;
            }else {
                FileWriter fw = new FileWriter(file.getAbsoluteFile());
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(fcontent+"\n"+txt);
                bw.close();
                Log.e("Suceess","Added");
                return true;
            }
        } catch (IOException e) {
            Log.e("Fail","Error");
            e.printStackTrace();
            return false;
        }
    }

    //--append
    public Boolean append(String fname, String fcontent){
        try {
            String fpath = Environment.getExternalStorageDirectory().getPath()+"/"+fname+".txt";
            File file = new File(fpath);
            // If file does not exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            //read the file content first
            String txt = read(fname);


            FileWriter fw = new FileWriter(file.getAbsoluteFile());
            BufferedWriter bw = new BufferedWriter(fw);
            txt = autoCorrection(fcontent+"\n"+txt);
            bw.write(txt);
            bw.close();
            Log.e("Suceess","Appended");
            return true;

        } catch (IOException e) {
            Log.e("Fail","Error");
            e.printStackTrace();
            return false;
        }
    }

    //autocorrection for the blanks
    public String autoCorrection(String text){
        //convert text to array
        ArrayList txtArray = new ArrayList<String>(Arrays.asList(text.split("\n")));

        //loop through array to check for blanks
        //cleaning up the list incase some blank entries has entered.
        for (int i = 0; i < txtArray.size(); i++) {
            if(txtArray.get(i).equals("")){
                Log.e(TAG,"txtArray: Blank detected: @ "+i);
                txtArray.remove(i);
                i--;
            }
        }

        //rewrite the file with clean buddylist
        text =  TextUtils.join("\n", txtArray);

        Log.e(TAG,text);

        return text;
    }

    public boolean removeFile(String fname){
        try {
            String fpath = Environment.getExternalStorageDirectory().getPath() + "/" + fname + ".txt";
            File file = new File(fpath);
            if (file.exists()) {
                file.delete();
                if (!file.exists()){
                    Log.d("Pass","File erased.");
                    return true;
                }else {
                    Log.e("Fail","File not removed");
                    return false;
                }
            }else {
                Log.e("Fail","File not found");
                return false;
            }
        }catch (Exception e) {
            Log.e("Fail","Error");
            e.printStackTrace();
            return false;
        }
    }
}

