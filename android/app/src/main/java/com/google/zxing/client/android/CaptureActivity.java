/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.compspecific.DateProcessor;
import com.google.zxing.client.android.compspecific.FileOperations;
import com.google.zxing.client.android.compspecific.GDocsService;
import com.google.zxing.client.android.history.HistoryActivity;
import com.google.zxing.client.android.history.HistoryItem;
import com.google.zxing.client.android.history.HistoryManager;
import com.google.zxing.client.android.result.ResultButtonListener;
import com.google.zxing.client.android.result.ResultHandler;
import com.google.zxing.client.android.result.ResultHandlerFactory;
import com.google.zxing.client.android.result.supplement.SupplementalInfoRetriever;
import com.google.zxing.client.android.share.ShareActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static android.view.View.*;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public class CaptureActivity extends Activity implements SurfaceHolder.Callback {

  private static final String TAG = CaptureActivity.class.getSimpleName();

  public String fileName = "QRF1";
  public String fileName1 = "QRF2";

  private static final int SHARE_ID = Menu.FIRST;
  private static final int HISTORY_ID = Menu.FIRST + 1;
  private static final int SETTINGS_ID = Menu.FIRST + 2;
  private static final int HELP_ID = Menu.FIRST + 3;
  private static final int ABOUT_ID = Menu.FIRST + 4;

  private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
  private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

  private static final String PACKAGE_NAME = "com.google.zxing.client.android";
  private static final String PRODUCT_SEARCH_URL_PREFIX = "http://www.google";
  private static final String PRODUCT_SEARCH_URL_SUFFIX = "/m/products/scan";
  private static final String[] ZXING_URLS = { "http://zxing.appspot.com/scan", "zxing://scan/" };
  private static final String RETURN_CODE_PLACEHOLDER = "{CODE}";
  private static final String RETURN_URL_PARAM = "ret";

  public static final int HISTORY_REQUEST_CODE = 0x0000bacc;

  private static final Set<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
      EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                 ResultMetadataType.SUGGESTED_PRICE,
                 ResultMetadataType.ERROR_CORRECTION_LEVEL,
                 ResultMetadataType.POSSIBLE_COUNTRY);

  private CameraManager cameraManager;
  private CaptureActivityHandler handler;
  private Result savedResultToShow;
  private ViewfinderView viewfinderView;
  private TextView statusView;
  private View resultView;
  private View plateDataView;
  private Result lastResult;
  private boolean hasSurface;
  private boolean copyToClipboard;
  private IntentSource source;
  private String sourceUrl;
  private String returnUrlTemplate;
  private Collection<BarcodeFormat> decodeFormats;
  private String characterSet;
  private String versionName;
  private HistoryManager historyManager;
  private InactivityTimer inactivityTimer;
  private BeepManager beepManager;

  private final DialogInterface.OnClickListener aboutListener =
      new DialogInterface.OnClickListener() {
    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.zxing_url)));
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
      startActivity(intent);
    }
  };

  ViewfinderView getViewfinderView() {
    return viewfinderView;
  }

  public Handler getHandler() {
    return handler;
  }

  CameraManager getCameraManager() {
    return cameraManager;
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.capture);

    hasSurface = false;
    historyManager = new HistoryManager(this);
    historyManager.trimHistory();
    inactivityTimer = new InactivityTimer(this);
    beepManager = new BeepManager(this);

    PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

    //showHelpOnFirstLaunch();
  }

  @Override
  protected void onResume() {
    super.onResume();

    // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
    // want to open the camera driver and measure the screen size if we're going to show the help on
    // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
    // off screen.
    cameraManager = new CameraManager(getApplication());

    viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
    viewfinderView.setCameraManager(cameraManager);

    resultView = findViewById(R.id.result_view);
    statusView = (TextView) findViewById(R.id.status_view);

    plateDataView = findViewById(R.id.plateData_view);

    handler = null;
    lastResult = null;

    resetStatusView();

    SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    SurfaceHolder surfaceHolder = surfaceView.getHolder();
    if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
      initCamera(surfaceHolder);
    } else {
      // Install the callback and wait for surfaceCreated() to init the camera.
      surfaceHolder.addCallback(this);
      surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    beepManager.updatePrefs();

    inactivityTimer.onResume();

    Intent intent = getIntent();

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    copyToClipboard = prefs.getBoolean(PreferencesActivity.KEY_COPY_TO_CLIPBOARD, true)
        && (intent == null || intent.getBooleanExtra(Intents.Scan.SAVE_HISTORY, true));

    source = IntentSource.NONE;
    decodeFormats = null;
    characterSet = null;

    if (intent != null) {

      String action = intent.getAction();
      String dataString = intent.getDataString();

      if (Intents.Scan.ACTION.equals(action)) {

        // Scan the formats the intent requested, and return the result to the calling activity.
        source = IntentSource.NATIVE_APP_INTENT;
        decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);

        if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
          int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
          int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
          if (width > 0 && height > 0) {
            cameraManager.setManualFramingRect(width, height);
          }
        }
        
        String customPromptMessage = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE);
        if (customPromptMessage != null) {
          statusView.setText(customPromptMessage);
        }

      } else if (dataString != null &&
                 dataString.contains(PRODUCT_SEARCH_URL_PREFIX) &&
                 dataString.contains(PRODUCT_SEARCH_URL_SUFFIX)) {

        // Scan only products and send the result to mobile Product Search.
        source = IntentSource.PRODUCT_SEARCH_LINK;
        sourceUrl = dataString;
        decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;

      } else if (isZXingURL(dataString)) {

        // Scan formats requested in query string (all formats if none specified).
        // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
        source = IntentSource.ZXING_LINK;
        sourceUrl = dataString;
        Uri inputUri = Uri.parse(sourceUrl);
        returnUrlTemplate = inputUri.getQueryParameter(RETURN_URL_PARAM);
        decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri);

      }

      characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);

    }
  }
  
  private static boolean isZXingURL(String dataString) {
    if (dataString == null) {
      return false;
    }
    for (String url : ZXING_URLS) {
      if (dataString.startsWith(url)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void onPause() {
    if (handler != null) {
      handler.quitSynchronously();
      handler = null;
    }
    inactivityTimer.onPause();
    cameraManager.closeDriver();
    if (!hasSurface) {
      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(this);
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    inactivityTimer.shutdown();
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      if (source == IntentSource.NATIVE_APP_INTENT) {
        setResult(RESULT_CANCELED);
        finish();
        return true;
      } else if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK) && lastResult != null) {
        restartPreviewAfterDelay(0L);
        return true;
      }
    } else if (keyCode == KeyEvent.KEYCODE_FOCUS || keyCode == KeyEvent.KEYCODE_CAMERA) {
      // Handle these events so they don't launch the Camera app
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    menu.add(Menu.NONE, SHARE_ID, Menu.NONE, R.string.menu_share)
        .setIcon(android.R.drawable.ic_menu_share);
    menu.add(Menu.NONE, HISTORY_ID, Menu.NONE, R.string.menu_history)
        .setIcon(android.R.drawable.ic_menu_recent_history);
    menu.add(Menu.NONE, SETTINGS_ID, Menu.NONE, R.string.menu_settings)
        .setIcon(android.R.drawable.ic_menu_preferences);
    menu.add(Menu.NONE, HELP_ID, Menu.NONE, R.string.menu_help)
        .setIcon(android.R.drawable.ic_menu_help);
    menu.add(Menu.NONE, ABOUT_ID, Menu.NONE, R.string.menu_about)
        .setIcon(android.R.drawable.ic_menu_info_details);
    return true;
  }

  // Don't display the share menu item if the result overlay is showing.
  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);
    menu.findItem(SHARE_ID).setVisible(lastResult == null);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
    switch (item.getItemId()) {
      case SHARE_ID:
        intent.setClassName(this, ShareActivity.class.getName());
        startActivity(intent);
        break;
      case HISTORY_ID:
        intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        intent.setClassName(this, HistoryActivity.class.getName());
        startActivityForResult(intent, HISTORY_REQUEST_CODE);
        break;
      case SETTINGS_ID:
        intent.setClassName(this, PreferencesActivity.class.getName());
        startActivity(intent);
        break;
      case HELP_ID:
        intent.setClassName(this, HelpActivity.class.getName());
        startActivity(intent);
        break;
      case ABOUT_ID:
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.title_about) + versionName);
        builder.setMessage(getString(R.string.msg_about) + "\n\n" + getString(R.string.zxing_url));
        builder.setIcon(R.drawable.launcher_icon);
        builder.setPositiveButton(R.string.button_open_browser, aboutListener);
        builder.setNegativeButton(R.string.button_cancel, null);
        builder.show();
        break;
      default:
        return super.onOptionsItemSelected(item);
    }
    return true;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (resultCode == RESULT_OK) {
      if (requestCode == HISTORY_REQUEST_CODE) {
        int itemNumber = intent.getIntExtra(Intents.History.ITEM_NUMBER, -1);
        if (itemNumber >= 0) {
          HistoryItem historyItem = historyManager.buildHistoryItem(itemNumber);
          decodeOrStoreSavedBitmap(null, historyItem.getResult());
        }
      }
    }
  }

  private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
    // Bitmap isn't used yet -- will be used soon
    if (handler == null) {
      savedResultToShow = result;
    } else {
      if (result != null) {
        savedResultToShow = result;
      }
      if (savedResultToShow != null) {
        Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
        handler.sendMessage(message);
      }
      savedResultToShow = null;
    }
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    if (holder == null) {
      Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
    }
    if (!hasSurface) {
      hasSurface = true;
      initCamera(holder);
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    hasSurface = false;
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

  }

  /**
   * A valid barcode has been found, so give an indication of success and show the results.
   *
   * @param rawResult The contents of the barcode.
   * @param barcode   A greyscale bitmap of the camera data which was decoded.
   */
  public void handleDecode(Result rawResult, Bitmap barcode) {
    inactivityTimer.onActivity();
    lastResult = rawResult;
    ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);
    historyManager.addHistoryItem(rawResult, resultHandler);

    if (barcode == null) {
      // This is from history -- no saved barcode
      handleDecodeInternally(rawResult, resultHandler, null);
    } else {
      beepManager.playBeepSoundAndVibrate();
      drawResultPoints(barcode, rawResult);
      switch (source) {
        case NATIVE_APP_INTENT:
        case PRODUCT_SEARCH_LINK:
          handleDecodeExternally(rawResult, resultHandler, barcode);
          break;
        case ZXING_LINK:
          if (returnUrlTemplate == null){
            handleDecodeInternally(rawResult, resultHandler, barcode);
          } else {
            handleDecodeExternally(rawResult, resultHandler, barcode);
          }
          break;
        case NONE:
          SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
          if (prefs.getBoolean(PreferencesActivity.KEY_BULK_MODE, false)) {
            Toast.makeText(this, R.string.msg_bulk_mode_scanned, Toast.LENGTH_SHORT).show();
            // Wait a moment or else it will scan the same barcode continuously about 3 times
            restartPreviewAfterDelay(BULK_MODE_SCAN_DELAY_MS);
          } else {
            handleDecodeInternally(rawResult, resultHandler, barcode);
          }
          break;
      }
    }
  }

  /**
   * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
   *
   * @param barcode   A bitmap of the captured image.
   * @param rawResult The decoded results which contains the points to draw.
   */
  private void drawResultPoints(Bitmap barcode, Result rawResult) {
    ResultPoint[] points = rawResult.getResultPoints();
    if (points != null && points.length > 0) {
      Canvas canvas = new Canvas(barcode);
      Paint paint = new Paint();
      paint.setColor(getResources().getColor(R.color.result_image_border));
      paint.setStrokeWidth(3.0f);
      paint.setStyle(Paint.Style.STROKE);
      Rect border = new Rect(2, 2, barcode.getWidth() - 2, barcode.getHeight() - 2);
      canvas.drawRect(border, paint);

      paint.setColor(getResources().getColor(R.color.result_points));
      if (points.length == 2) {
        paint.setStrokeWidth(4.0f);
        drawLine(canvas, paint, points[0], points[1]);
      } else if (points.length == 4 &&
                 (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
                  rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
        // Hacky special case -- draw two lines, for the barcode and metadata
        drawLine(canvas, paint, points[0], points[1]);
        drawLine(canvas, paint, points[2], points[3]);
      } else {
        paint.setStrokeWidth(10.0f);
        for (ResultPoint point : points) {
          canvas.drawPoint(point.getX(), point.getY(), paint);
        }
      }
    }
  }

  private static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b) {
    canvas.drawLine(a.getX(), a.getY(), b.getX(), b.getY(), paint);
  }

  // Put up our own UI for how to handle the decoded contents.
//  private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
//    statusView.setVisibility(View.GONE);
//    viewfinderView.setVisibility(View.GONE);
//    resultView.setVisibility(View.VISIBLE);
//
//    ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
//    if (barcode == null) {
//      barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
//          R.drawable.launcher_icon));
//    } else {
//      barcodeImageView.setImageBitmap(barcode);
//    }
//
//    TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
//    formatTextView.setText(rawResult.getBarcodeFormat().toString());
//
//    TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
//    typeTextView.setText(resultHandler.getType().toString());
//
//    DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
//    String formattedTime = formatter.format(new Date(rawResult.getTimestamp()));
//    TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
//    timeTextView.setText(formattedTime);
//
//
//    TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
//    View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
//    metaTextView.setVisibility(View.GONE);
//    metaTextViewLabel.setVisibility(View.GONE);
//    Map<ResultMetadataType,Object> metadata = rawResult.getResultMetadata();
//    if (metadata != null) {
//      StringBuilder metadataText = new StringBuilder(20);
//      for (Map.Entry<ResultMetadataType,Object> entry : metadata.entrySet()) {
//        if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
//          metadataText.append(entry.getValue()).append('\n');
//        }
//      }
//      if (metadataText.length() > 0) {
//        metadataText.setLength(metadataText.length() - 1);
//        metaTextView.setText(metadataText);
//        metaTextView.setVisibility(View.VISIBLE);
//        metaTextViewLabel.setVisibility(View.VISIBLE);
//      }
//    }
//
//    TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
//    CharSequence displayContents = resultHandler.getDisplayContents();
//    contentsTextView.setText(displayContents);
//    // Crudely scale betweeen 22 and 32 -- bigger font for shorter text
//    int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
//    contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);
//
//    TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
//    supplementTextView.setText("");
//    supplementTextView.setOnClickListener(null);
//    if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
//        PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
//      SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
//                                                     resultHandler.getResult(),
//                                                     handler,
//                                                     historyManager,
//                                                     this);
//    }
//
//    int buttonCount = resultHandler.getButtonCount();
//    ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
//    buttonView.requestFocus();
//    for (int x = 0; x < ResultHandler.MAX_BUTTON_COUNT; x++) {
//      TextView button = (TextView) buttonView.getChildAt(x);
//      if (x < buttonCount) {
//        button.setVisibility(View.VISIBLE);
//        button.setText(resultHandler.getButtonText(x));
//        button.setOnClickListener(new ResultButtonListener(resultHandler, x));
//      } else {
//        button.setVisibility(View.GONE);
//      }
//    }
//
//    if (copyToClipboard && !resultHandler.areContentsSecure()) {
//      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
//      clipboard.setText(displayContents);
//    }
//  }

    //Customized - Sandeep
    private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
        CharSequence displayContents = null;
        if(rawResult.getBarcodeFormat().toString() == "QR_CODE" && resultHandler.getType().toString()=="TEXT") {
            displayContents = resultHandler.getDisplayContents();
        }
        if(displayContents!=null && displayContents.toString().toLowerCase().contains("pmon")){
            statusView.setText(displayContents);
            //color changing
//            String colorContents = displayContents.toString().split("Color : ")[1];
//            colorContents = "#"+colorContents;
//            Log.e(TAG,"::1-1::"+colorContents);
//            statusView.setTextColor(Color.parseColor(colorContents));

            //filtering pmon id
            String pmonContents = displayContents.toString().toLowerCase().split("pmon")[1];
            //pmonContents = pmonContents.substring(0,16);
            pmonContents = pmonContents.substring(0,pmonContents.length());
            Log.e(TAG,"::1-1::"+pmonContents);
            statusView.setText("PMON : "+pmonContents);

            //query DB and check if any data exists for this PMON
            final GDocsService gDocsService = new GDocsService();
            String feed = gDocsService.getData(pmonContents);

            statusView.setText("feed : "+feed);
            Log.e(TAG,"::1-2::"+feed);

            //set exiting data in UI
            //instanciating status
            final TextView status = (TextView) findViewById(R.id.Lv5_textView);

            //set up pmon id
            TextView pmonid = (TextView) findViewById(R.id.pMONID_textView);
            pmonid.setText("pMON ID :  "+pmonContents);


            //moving the data to array from the string and showing it in UI

            if(feed != ""){
                try{
                    String[] feed_box = feed.toString().split("\\|");
                    String[] gap_list = feed_box[13].split(",");
                //gap values
                //gap1
                    EditText gap1 = (EditText) findViewById(R.id.gap1_editText);
                    gap1.setText(gap_list[0]);
                //gap2
                    EditText gap2 = (EditText) findViewById(R.id.gap2_editText);
                    gap2.setText(gap_list[1]);
                //gap3
                    EditText gap3 = (EditText) findViewById(R.id.gap3_editText);
                    gap3.setText(gap_list[2]);
                //date values
                //date1
                    //change the date format
                    DateProcessor dateProcessor = new DateProcessor();
                    feed_box[1]=dateProcessor.date_format_shifter(feed_box[1],"MM/dd/yyyy","dd/MM/yyyy");
                    EditText date1 = (EditText) findViewById(R.id.date1_editText);
                    date1.setText(feed_box[1]);
                //date2
                    feed_box[4]=dateProcessor.date_format_shifter(feed_box[4],"MM/dd/yyyy","dd/MM/yyyy");
                    TextView date2 = (TextView) findViewById(R.id.date2_textView);
                    date2.setText(feed_box[4]);
                //date3
                    feed_box[7]=dateProcessor.date_format_shifter(feed_box[7],"MM/dd/yyyy","dd/MM/yyyy");
                    TextView date3 = (TextView) findViewById(R.id.date3_textView);
                    date3.setText(feed_box[7]);
                //date4
                    feed_box[10]=dateProcessor.date_format_shifter(feed_box[10],"MM/dd/yyyy","dd/MM/yyyy");
                    TextView date4 = (TextView) findViewById(R.id.date4_textView);
                    date4.setText(feed_box[10]);
                //solution values
                //solution 1
                    EditText sol1 = (EditText) findViewById(R.id.sol1_editText);
                    sol1.setText(feed_box[2]);
                //solution 2
                    EditText sol2 = (EditText) findViewById(R.id.sol2_editText);
                    sol2.setText(feed_box[5]);
                //solution 3
                    EditText sol3 = (EditText) findViewById(R.id.sol3_editText);
                    sol3.setText(feed_box[8]);
                //solution 4
                    EditText sol4 = (EditText) findViewById(R.id.sol4_editText);
                    sol4.setText(feed_box[11]);
                //sample values
                //sample 1
                    EditText scount1 = (EditText) findViewById(R.id.scount1_editText);
                    scount1.setText(feed_box[3]);
                //sample 2
                    EditText scount2 = (EditText) findViewById(R.id.scount2_editText);
                    scount2.setText(feed_box[6]);
                //sample 3
                    EditText scount3 = (EditText) findViewById(R.id.scount3_editText);
                    scount3.setText(feed_box[9]);
                //sample 4
                    EditText scount4 = (EditText) findViewById(R.id.scount4_editText);
                    scount4.setText(feed_box[12]);
                //location
                    EditText location = (EditText) findViewById(R.id.loc_editText);
                    location.setText(feed_box[14]);
                //remarks - handling multiline
                    EditText remarks = (EditText) findViewById(R.id.remarks_editText);
                    remarks.setText(feed_box[15].replace("\\n", "\n"));


                status.setText("Status:\nData fetched from DB.\nUpdate and Click Save.");
                }catch (Exception e){
                    Log.e(TAG,"feed parsing failed.");
                    status.setText("Status:\nData fetched from DB.\nBut parsing failed.\nCheck data in DB.");
                }
            }else{
                try{
                    //gap values
                    //gap1
                        EditText gap1 = (EditText) findViewById(R.id.gap1_editText);
                        gap1.setText("7");
                    //gap2
                        EditText gap2 = (EditText) findViewById(R.id.gap2_editText);
                        gap2.setText("7");
                    //gap3
                        EditText gap3 = (EditText) findViewById(R.id.gap3_editText);
                        gap3.setText("7");
                    //date values
                    Date d = new Date();
                    DateProcessor dateProcessor = new DateProcessor();
                    String[] dates = dateProcessor.DateProcessor(d,"7,7,7");
                    //date1
                    EditText date1 = (EditText) findViewById(R.id.date1_editText);
                    date1.setText(dates[0]);
                    //date2
                    TextView date2 = (TextView) findViewById(R.id.date2_textView);
                    date2.setText(dates[1]);
                    //date3
                    TextView date3 = (TextView) findViewById(R.id.date3_textView);
                    date3.setText(dates[2]);
                    //date4
                    TextView date4 = (TextView) findViewById(R.id.date4_textView);
                    date4.setText(dates[3]);
                    //solution values
                    //solution 1
                    EditText sol1 = (EditText) findViewById(R.id.sol1_editText);
                    sol1.setText("1234");
                    //solution 2
                    EditText sol2 = (EditText) findViewById(R.id.sol2_editText);
                    sol2.setText("2345");
                    //solution 3
                    EditText sol3 = (EditText) findViewById(R.id.sol3_editText);
                    sol3.setText("3456");
                    //solution 4
                    EditText sol4 = (EditText) findViewById(R.id.sol4_editText);
                    sol4.setText("4567");
                    //sample values
                    //sample 1
                    EditText scount1 = (EditText) findViewById(R.id.scount1_editText);
                    scount1.setText("100");
                    //sample 2
                    EditText scount2 = (EditText) findViewById(R.id.scount2_editText);
                    scount2.setText("80");
                    //sample 3
                    EditText scount3 = (EditText) findViewById(R.id.scount3_editText);
                    scount3.setText("50");
                    //sample 4
                    EditText scount4 = (EditText) findViewById(R.id.scount4_editText);
                    scount4.setText("30");
                    //location
                    EditText location = (EditText) findViewById(R.id.loc_editText);
                    location.setText("");
                    //remarks
                    EditText remarks = (EditText) findViewById(R.id.remarks_editText);
                    remarks.setText("");


                    status.setText("Status:\nNew pMON ID.\nNo Data found in DB.\nStandard data populated.");
                }catch (Exception e){
                    Log.e(TAG,"feed parsing failed.-default");
                    status.setText("Status:\nNo Data for this pMON was found in DB.\nStandard data population failed for unknown reason.\nCheck log.");
                }
            }
            //launching the data entry screen
            statusView.setVisibility(GONE);
            viewfinderView.setVisibility(GONE);
            plateDataView.setVisibility(VISIBLE);

            //if any of the gap value or first date gets updated then modify the other values
            //gap1-on text change
            EditText gap1 = (EditText) findViewById(R.id.gap1_editText);
            gap1.addTextChangedListener(new TextWatcher() {

                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    try{
                        boolean bool = dateRefresh();
                        if(bool==true){
                            status.setText("Status:\nSynchronised dates successfully.");
                        }else{
                            status.setText("Status:\nSynchronising dates Failed.");
                        }
                    }catch (Exception e){
                        status.setText("Status:\nSynchronising dates Failed.");
                    }
                }
            });

            //gap2-on text change
            EditText gap2 = (EditText) findViewById(R.id.gap2_editText);
            gap2.addTextChangedListener(new TextWatcher() {

                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    try{
                        boolean bool = dateRefresh();
                        if(bool==true){
                            status.setText("Status:\nSynchronised dates successfully.");
                        }else{
                            status.setText("Status:\nSynchronising dates Failed.");
                        }
                    }catch (Exception e){
                        status.setText("Status:\nSynchronising dates Failed.");
                    }
                }
            });

            //gap3-on text change
            EditText gap3 = (EditText) findViewById(R.id.gap3_editText);
            gap3.addTextChangedListener(new TextWatcher() {

                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    try{
                        boolean bool = dateRefresh();
                        if(bool==true){
                            status.setText("Status:\nSynchronised dates successfully.");
                        }else{
                            status.setText("Status:\nSynchronising dates Failed.");
                        }
                    }catch (Exception e){
                        status.setText("Status:\nSynchronising dates Failed.");
                    }
                }
            });

            //date1-on text change
            EditText date1 = (EditText) findViewById(R.id.date1_editText);
            date1.addTextChangedListener(new TextWatcher() {

                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                    status.setText("Status:\nSynchronising dates.");
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    try{
                        boolean bool = dateRefresh();
                        if(bool==true){
                            status.setText("Status:\nSynchronised dates successfully.");
                        }else{
                            status.setText("Status:\nSynchronising dates Failed.");
                        }
                    }catch (Exception e){
                        status.setText("Status:\nSynchronising dates Failed.");
                    }
                }
            });

            //on submit click validate and post data
            Button submit = (Button) findViewById(R.id.submit_button);
            final String finalPmonContents = pmonContents;
            submit.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View view) {
                    try {
                        String Emsg = "";
                        //get all the data from the UI
                        EditText gap1 = (EditText) findViewById(R.id.gap1_editText);
                        String gap = gap1.getText().toString();
                        if(gap1.getText().toString().equals("")){
                            Emsg = Emsg+"Day Gap:Field 1:Value missing.\n";
                        }
                        //gap2
                        EditText gap2 = (EditText) findViewById(R.id.gap2_editText);
                        gap = gap + "," + gap2.getText().toString();
                        if(gap2.getText().toString().equals("")){
                            Emsg = Emsg+"Day Gap:Field 2:Value missing.\n";
                        }
                        //gap3
                        EditText gap3 = (EditText) findViewById(R.id.gap3_editText);
                        gap = gap + "," + gap3.getText().toString();
                        if(gap3.getText().toString().equals("")){
                            Emsg = Emsg+"Day Gap:Field 3:Value missing.\n";
                        }
                        //date values
                        //date1
                        EditText date1 = (EditText) findViewById(R.id.date1_editText);
                        String date1_1 = date1.getText().toString();
                        if(date1.getText().toString().equals("")){
                            Emsg = Emsg+"Date 1:Value missing.\n";
                        }
                        //solution values
                        //solution 1
                        EditText sol1 = (EditText) findViewById(R.id.sol1_editText);
                        String sol1_1 = sol1.getText().toString();
                        if(sol1.getText().toString().equals("")){
                            Emsg = Emsg+"Solution ID:Field 1:Value missing.\n";
                        }
                        //solution 2
                        EditText sol2 = (EditText) findViewById(R.id.sol2_editText);
                        String sol2_1 = sol2.getText().toString();
                        if(sol2.getText().toString().equals("")){
                            Emsg = Emsg+"Solution ID:Field 2:Value missing.\n";
                        }
                        //solution 3
                        EditText sol3 = (EditText) findViewById(R.id.sol3_editText);
                        String sol3_1 = sol3.getText().toString();
                        if(sol3.getText().toString().equals("")){
                            Emsg = Emsg+"Solution ID:Field 3:Value missing.\n";
                        }
                        //solution 4
                        EditText sol4 = (EditText) findViewById(R.id.sol4_editText);
                        String sol4_1 = sol4.getText().toString();
                        if(sol4.getText().toString().equals("")){
                            Emsg = Emsg+"Solution ID:Field 4:Value missing.\n";
                        }
                        //sample values
                        //sample 1
                        EditText scount1 = (EditText) findViewById(R.id.scount1_editText);
                        int scount1_1 = Integer.parseInt(scount1.getText().toString());
                        String scount1_2 = scount1.getText().toString();
                        if(scount1.getText().toString().equals("")){
                            Emsg = Emsg+"Sample Count:Field 1:Value missing.\n";
                        }
                        //sample 2
                        EditText scount2 = (EditText) findViewById(R.id.scount2_editText);
                        int scount2_1 = Integer.parseInt(scount2.getText().toString());
                        String scount2_2 = scount2.getText().toString();
                        if(scount2.getText().toString().equals("")){
                            Emsg = Emsg+"Sample Count:Field 2:Value missing.\n";
                        }
                        //sample 3
                        EditText scount3 = (EditText) findViewById(R.id.scount3_editText);
                        int scount3_1 = Integer.parseInt(scount3.getText().toString());
                        String scount3_2 = scount3.getText().toString();
                        if(scount3.getText().toString().equals("")){
                            Emsg = Emsg+"Sample Count:Field 3:Value missing.\n";
                        }
                        //sample 4
                        EditText scount4 = (EditText) findViewById(R.id.scount4_editText);
                        int scount4_1 = Integer.parseInt(scount4.getText().toString());
                        String scount4_2 = scount4.getText().toString();
                        if(scount4.getText().toString().equals("")){
                            Emsg = Emsg+"Sample Count:Field 4:Value missing.\n";
                        }

                        //location
                        EditText location = (EditText) findViewById(R.id.loc_editText);
                        String location1_1 = location.getText().toString();
                        if(location.getText().toString().equals("")){
                            Emsg = Emsg+"Location field left blank.\n";
                        }

                        //Remarks
                        EditText remarks = (EditText) findViewById(R.id.remarks_editText);
                        String remarks1_1 = remarks.getText().toString();
                        if(remarks.getText().toString().equals("")){
                            remarks1_1 = "No Remarks";
                        }


                        if(Emsg=="") {
                            if (scount1_1 >= scount2_1 && scount2_1 >= scount3_1 && scount3_1 >= scount4_1) {
                                status.setText("Status:\nTrying to update your data in DB.");
                                //need to deactivate the save button to stop users from clicking multiple times
                                //change the date format
                                DateProcessor dateProcessor = new DateProcessor();
                                date1_1=dateProcessor.date_format_shifter(date1_1,"dd/MM/yyyy","MM/dd/yyyy");
                                gDocsService.init(finalPmonContents,gap,date1_1,sol1_1,scount1_2,sol2_1,scount2_2,sol3_1,scount3_2,sol4_1,scount4_2,location1_1, remarks1_1);

                            } else {
                                status.setText("Status:\nSample count can not increase.\nCheck your data.");
                            }
                        }else{
                            status.setText("Status:\n"+Emsg);
                        }

                    }catch (Exception e){
                        status.setText("Status:\nCheck your data.\nProbably 1 or more field left blank.");
                    }
                }
            });




        }else{
            statusView.setVisibility(GONE);
            viewfinderView.setVisibility(GONE);
            resultView.setVisibility(VISIBLE);

            ImageView barcodeImageView = (ImageView) findViewById(R.id.barcode_image_view);
            if (barcode == null) {
                barcodeImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
                        R.drawable.launcher_icon));
            } else {
                barcodeImageView.setImageBitmap(barcode);
            }

            TextView formatTextView = (TextView) findViewById(R.id.format_text_view);
            formatTextView.setText(rawResult.getBarcodeFormat().toString());

            Log.e(TAG,"::1::"+rawResult.getBarcodeFormat().toString());

            TextView typeTextView = (TextView) findViewById(R.id.type_text_view);
            typeTextView.setText(resultHandler.getType().toString());

            Log.e(TAG, "::2::" + resultHandler.getType().toString());

            DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            String formattedTime = formatter.format(new Date(rawResult.getTimestamp()));
            TextView timeTextView = (TextView) findViewById(R.id.time_text_view);
            timeTextView.setText(formattedTime);

            Log.e(TAG,"::3::"+formattedTime);

            TextView metaTextView = (TextView) findViewById(R.id.meta_text_view);
            View metaTextViewLabel = findViewById(R.id.meta_text_view_label);
            metaTextView.setVisibility(GONE);
            metaTextViewLabel.setVisibility(GONE);
            Map<ResultMetadataType,Object> metadata = rawResult.getResultMetadata();
            if (metadata != null) {
                StringBuilder metadataText = new StringBuilder(20);
                for (Map.Entry<ResultMetadataType,Object> entry : metadata.entrySet()) {
                    if (DISPLAYABLE_METADATA_TYPES.contains(entry.getKey())) {
                        metadataText.append(entry.getValue()).append('\n');
                    }
                }
                if (metadataText.length() > 0) {
                    metadataText.setLength(metadataText.length() - 1);
                    metaTextView.setText(metadataText);
                    metaTextView.setVisibility(VISIBLE);
                    metaTextViewLabel.setVisibility(VISIBLE);
                    Log.e(TAG,"::4::"+metadataText);
                }
            }

            TextView contentsTextView = (TextView) findViewById(R.id.contents_text_view);
            //CharSequence displayContents = resultHandler.getDisplayContents();
            displayContents = resultHandler.getDisplayContents();
            contentsTextView.setText(displayContents);
            Log.e(TAG, "::5::" + displayContents);
            // Crudely scale betweeen 22 and 32 -- bigger font for shorter text
            int scaledSize = Math.max(22, 32 - displayContents.length() / 4);
            contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);

            TextView supplementTextView = (TextView) findViewById(R.id.contents_supplement_text_view);
            supplementTextView.setText("");
            supplementTextView.setOnClickListener(null);
            if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                    PreferencesActivity.KEY_SUPPLEMENTAL, true)) {
                SupplementalInfoRetriever.maybeInvokeRetrieval(supplementTextView,
                        resultHandler.getResult(),
                        handler,
                        historyManager,
                        this);
            }

                int buttonCount = resultHandler.getButtonCount();
                ViewGroup buttonView = (ViewGroup) findViewById(R.id.result_button_view);
                buttonView.requestFocus();
                for (int x = 0; x < ResultHandler.MAX_BUTTON_COUNT; x++) {
                    TextView button = (TextView) buttonView.getChildAt(x);
                    if (x < buttonCount) {
                        button.setVisibility(VISIBLE);
                        button.setText(resultHandler.getButtonText(x));
                        button.setOnClickListener(new ResultButtonListener(resultHandler, x));
                    } else {
                        button.setVisibility(GONE);
                    }
                }

            if (copyToClipboard && !resultHandler.areContentsSecure()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setText(displayContents);
            }
        }

    }

  // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
  private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {
    viewfinderView.drawResultBitmap(barcode);

    // Since this message will only be shown for a second, just tell the user what kind of
    // barcode was found (e.g. contact info) rather than the full contents, which they won't
    // have time to read.
    statusView.setText(getString(resultHandler.getDisplayTitle()));

    if (copyToClipboard && !resultHandler.areContentsSecure()) {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      clipboard.setText(resultHandler.getDisplayContents());
    }

    if (source == IntentSource.NATIVE_APP_INTENT) {
      
      // Hand back whatever action they requested - this can be changed to Intents.Scan.ACTION when
      // the deprecated intent is retired.
      Intent intent = new Intent(getIntent().getAction());
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
      intent.putExtra(Intents.Scan.RESULT, rawResult.toString());
      intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult.getBarcodeFormat().toString());
      byte[] rawBytes = rawResult.getRawBytes();
      if (rawBytes != null && rawBytes.length > 0) {
        intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes);
      }
      Map<ResultMetadataType,?> metadata = rawResult.getResultMetadata();
      if (metadata != null) {
        Integer orientation = (Integer) metadata.get(ResultMetadataType.ORIENTATION);
        if (orientation != null) {
          intent.putExtra(Intents.Scan.RESULT_ORIENTATION, orientation.intValue());
        }
        String ecLevel = (String) metadata.get(ResultMetadataType.ERROR_CORRECTION_LEVEL);
        if (ecLevel != null) {
          intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL, ecLevel);
        }
        Iterable<byte[]> byteSegments = (Iterable<byte[]>) metadata.get(ResultMetadataType.BYTE_SEGMENTS);
        if (byteSegments != null) {
          int i = 0;
          for (byte[] byteSegment : byteSegments) {
            intent.putExtra(Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i, byteSegment);
            i++;
          }
        }
      }
      sendReplyMessage(R.id.return_scan_result, intent);
      
    } else if (source == IntentSource.PRODUCT_SEARCH_LINK) {
      
      // Reformulate the URL which triggered us into a query, so that the request goes to the same
      // TLD as the scan URL.
      int end = sourceUrl.lastIndexOf("/scan");
      String replyURL = sourceUrl.substring(0, end) + "?q=" + resultHandler.getDisplayContents() + "&source=zxing";      
      sendReplyMessage(R.id.launch_product_query, replyURL);
      
    } else if (source == IntentSource.ZXING_LINK) {
      
      // Replace each occurrence of RETURN_CODE_PLACEHOLDER in the returnUrlTemplate
      // with the scanned code. This allows both queries and REST-style URLs to work.
      if (returnUrlTemplate != null) {
        String codeReplacement = String.valueOf(resultHandler.getDisplayContents());
        try {
          codeReplacement = URLEncoder.encode(codeReplacement, "UTF-8");
        } catch (UnsupportedEncodingException e) {
          // can't happen; UTF-8 is always supported. Continue, I guess, without encoding
        }
        String replyURL = returnUrlTemplate.replace(RETURN_CODE_PLACEHOLDER, codeReplacement);
        sendReplyMessage(R.id.launch_product_query, replyURL);
      }
      
    }
  }
  
  private void sendReplyMessage(int id, Object arg) {
    Message message = Message.obtain(handler, id, arg);
    long resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                                                     DEFAULT_INTENT_RESULT_DURATION_MS);
    if (resultDurationMS > 0L) {
      handler.sendMessageDelayed(message, resultDurationMS);
    } else {
      handler.sendMessage(message);
    }
  }

  /**
   * We want the help screen to be shown automatically the first time a new version of the app is
   * run. The easiest way to do this is to check android:versionCode from the manifest, and compare
   * it to a value stored as a preference.
   */
  private boolean showHelpOnFirstLaunch() {
    try {
      PackageInfo info = getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
      int currentVersion = info.versionCode;
      // Since we're paying to talk to the PackageManager anyway, it makes sense to cache the app
      // version name here for display in the about box later.
      this.versionName = info.versionName;
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
      int lastVersion = prefs.getInt(PreferencesActivity.KEY_HELP_VERSION_SHOWN, 0);
      if (currentVersion > lastVersion) {
        prefs.edit().putInt(PreferencesActivity.KEY_HELP_VERSION_SHOWN, currentVersion).commit();
        Intent intent = new Intent(this, HelpActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        // Show the default page on a clean install, and the what's new page on an upgrade.
        String page = lastVersion == 0 ? HelpActivity.DEFAULT_PAGE : HelpActivity.WHATS_NEW_PAGE;
        intent.putExtra(HelpActivity.REQUESTED_PAGE_KEY, page);
        startActivity(intent);
        return true;
      }
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, e);
    }
    return false;
  }

  private void initCamera(SurfaceHolder surfaceHolder) {
    try {
      cameraManager.openDriver(surfaceHolder);
      // Creating the handler starts the preview, which can also throw a RuntimeException.
      if (handler == null) {
        handler = new CaptureActivityHandler(this, decodeFormats, characterSet, cameraManager);
      }
      decodeOrStoreSavedBitmap(null, null);
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
      displayFrameworkBugMessageAndExit();
    } catch (RuntimeException e) {
      // Barcode Scanner has seen crashes in the wild of this variety:
      // java.?lang.?RuntimeException: Fail to connect to camera service
      Log.w(TAG, "Unexpected error initializing camera", e);
      displayFrameworkBugMessageAndExit();
    }
  }

  private void displayFrameworkBugMessageAndExit() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(getString(R.string.app_name));
    builder.setMessage(getString(R.string.msg_camera_framework_bug));
    builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
    builder.setOnCancelListener(new FinishListener(this));
    builder.show();
  }

  public void restartPreviewAfterDelay(long delayMS) {
    if (handler != null) {
      handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
    }
    resetStatusView();
  }

  private boolean dateRefresh(){
      String Emsg = "";
      //get all the data from the UI
      EditText gap1 = (EditText) findViewById(R.id.gap1_editText);
      String gap = gap1.getText().toString();
      if(gap1.getText().toString().equals("")){
          Emsg = Emsg+"Day Gap:Field 1:Value missing.\n";
      }
      //gap2
      EditText gap2 = (EditText) findViewById(R.id.gap2_editText);
      gap = gap + "," + gap2.getText().toString();
      if(gap2.getText().toString().equals("")){
          Emsg = Emsg+"Day Gap:Field 2:Value missing.\n";
      }
      //gap3
      EditText gap3 = (EditText) findViewById(R.id.gap3_editText);
      gap = gap + "," + gap3.getText().toString();
      if(gap3.getText().toString().equals("")){
          Emsg = Emsg+"Day Gap:Field 3:Value missing.\n";
      }
      //date values
      //date1
      EditText date1 = (EditText) findViewById(R.id.date1_editText);
      String date1_1 = date1.getText().toString();
      if(date1.getText().toString().equals("")){
          Emsg = Emsg+"Date 1:Value missing.\n";
      }

      //get the updated dates
      DateProcessor dateProcessor = new DateProcessor();
      String[] dates = dateProcessor.DateProcessor(date1_1,gap,"dd/MM/yyyy");

      //update values
      //date2
      TextView date2 = (TextView) findViewById(R.id.date2_textView);
      date2.setText(dates[1]);
      //date3
      TextView date3 = (TextView) findViewById(R.id.date3_textView);
      date3.setText(dates[2]);
      //date4
      TextView date4 = (TextView) findViewById(R.id.date4_textView);
      date4.setText(dates[3]);

      if(Emsg.equals("")){
          return true;
      }
      return false;
  }


  private void resetSubmitUIElements(){
      //reset all the UI values for submit view
      TextView pmonid = (TextView) findViewById(R.id.pMONID_textView);
      pmonid.setText("pMON ID :  ");
      //gap values
      //gap1
      EditText gap1 = (EditText) findViewById(R.id.gap1_editText);
      gap1.setText("");
      //gap2
      EditText gap2 = (EditText) findViewById(R.id.gap2_editText);
      gap2.setText("");
      //gap3
      EditText gap3 = (EditText) findViewById(R.id.gap3_editText);
      gap3.setText("");
      //date values
      //date1
      EditText date1 = (EditText) findViewById(R.id.date1_editText);
      date1.setText("");
      //date2
      TextView date2 = (TextView) findViewById(R.id.date2_textView);
      date2.setText("");
      //date3
      TextView date3 = (TextView) findViewById(R.id.date3_textView);
      date3.setText("");
      //date4
      TextView date4 = (TextView) findViewById(R.id.date4_textView);
      date4.setText("");
      //solution values
      //solution 1
      EditText sol1 = (EditText) findViewById(R.id.sol1_editText);
      sol1.setText("");
      //solution 2
      EditText sol2 = (EditText) findViewById(R.id.sol2_editText);
      sol2.setText("");
      //solution 3
      EditText sol3 = (EditText) findViewById(R.id.sol3_editText);
      sol3.setText("");
      //solution 4
      EditText sol4 = (EditText) findViewById(R.id.sol4_editText);
      sol4.setText("");
      //sample values
      //sample 1
      EditText scount1 = (EditText) findViewById(R.id.scount1_editText);
      scount1.setText("");
      //sample 2
      EditText scount2 = (EditText) findViewById(R.id.scount2_editText);
      scount2.setText("");
      //sample 3
      EditText scount3 = (EditText) findViewById(R.id.scount3_editText);
      scount3.setText("");
      //sample 4
      EditText scount4 = (EditText) findViewById(R.id.scount4_editText);
      scount4.setText("");
      //location
      EditText location = (EditText) findViewById(R.id.loc_editText);
      location.setText("");
      //remarks
      EditText remarks = (EditText) findViewById(R.id.remarks_editText);
      remarks.setText("");

      //Status
      TextView status = (TextView) findViewById(R.id.Lv5_textView);
      status.setText("Status:");
  }

  private void resetStatusView() {
    resultView.setVisibility(GONE);
    plateDataView.setVisibility(GONE);
    resetSubmitUIElements();
    statusView.setText(R.string.msg_default_status);
    statusView.setTextColor(Color.parseColor("#ffffff"));
    statusView.setVisibility(VISIBLE);
    viewfinderView.setVisibility(VISIBLE);
    lastResult = null;
  }

  public void drawViewfinder() {
    viewfinderView.drawViewfinder();
  }
}
