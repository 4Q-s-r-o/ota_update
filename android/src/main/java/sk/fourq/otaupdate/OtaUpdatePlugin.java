package sk.fourq.otaupdate;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.PluginRegistry;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * OtaUpdatePlugin
 */
@TargetApi(Build.VERSION_CODES.M)
public class OtaUpdatePlugin implements FlutterPlugin, ActivityAware, EventChannel.StreamHandler, PluginRegistry.RequestPermissionsResultListener {

    //CONSTANTS
    private static final String BYTES_DOWNLOADED = "BYTES_DOWNLOADED";
    private static final String BYTES_TOTAL = "BYTES_TOTAL";
    private static final String ERROR = "ERROR";
    private static final String ARG_URL = "url";
    private static final String ARG_HEADERS = "headers";
    private static final String ARG_FILENAME = "filename";
    private static final String ARG_CHECKSUM = "checksum";
    private static final String ARG_ANDROID_PROVIDER_AUTHORITY = "androidProviderAuthority";
    private static final String TAG = "FLUTTER OTA";
    private static final String DEFAULT_APK_NAME = "ota_update.apk";
    private static final long MAX_WAIT_FOR_DOWNLOAD_START = 5000; //5s

    //BASIC PLUGIN STATE
    private Context context;
    private Activity activity;
    private EventChannel.EventSink progressSink;
    private Handler handler;
    private String androidProviderAuthority;
    private BinaryMessenger messanger;

    //DOWNLOAD SPECIFIC PLUGIN STATE. PLUGIN SUPPORT ONLY ONE DOWNLOAD AT A TIME
    private String downloadUrl;
    private JSONObject headers;
    private String filename;
    private String checksum;

    /**
     * Legacy plugin initialization for embedding v1. This method provides backwards compatibility.
     *
     * @param registrar v1 embedding registration
     */
    public static void registerWith(io.flutter.plugin.common.PluginRegistry.Registrar registrar) {
        Log.d(TAG, "registerWith");
        OtaUpdatePlugin plugin = new OtaUpdatePlugin();
        plugin.initialize(registrar.context(), registrar.messenger());
        plugin.activity = registrar.activity();
        registrar.addRequestPermissionsResultListener(plugin);
    }

    //FLUTTER EMBEDDING V2 - PLUGIN BINDING
    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        Log.d(TAG, "onAttachedToEngine");
        initialize(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        Log.d(TAG, "onDetachedFromEngine");
    }

    //FLUTTER EMBEDDING V2 - ACTIVITY BINDING. PLUGIN USES ACTIVITY FOR PERMISSION REQUESTS
    @Override
    public void onAttachedToActivity(ActivityPluginBinding activityPluginBinding) {
        Log.d(TAG, "onAttachedToActivity");
        activityPluginBinding.addRequestPermissionsResultListener(this);
        activity = activityPluginBinding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges");
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
        Log.d(TAG, "onReattachedToActivityForConfigChanges");
    }

    @Override
    public void onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity");
    }

    //STREAM LISTENER
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (progressSink != null) {
            progressSink.error("" + OtaStatus.ALREADY_RUNNING_ERROR.ordinal(), "Method call was cancelled. One method call is already running!", null);
        }
        Log.d(TAG, "STREAM OPENED");
        progressSink = events;
        //READ ARGUMENTS FROM CALL
        Map argumentsMap = ((Map) arguments);
        downloadUrl = argumentsMap.get(ARG_URL).toString();
        try {
            String headersJson = argumentsMap.get(ARG_HEADERS).toString();
            if (!headersJson.isEmpty()) {
                headers = new JSONObject(headersJson);
            }
        } catch (JSONException e) {
            Log.e(TAG, "ERROR: " + e.getMessage(), e);
        }
        if (argumentsMap.containsKey(ARG_FILENAME) && argumentsMap.get(ARG_FILENAME) != null) {
            filename = argumentsMap.get(ARG_FILENAME).toString();
        } else {
            filename = DEFAULT_APK_NAME;
        }
        if (argumentsMap.containsKey(ARG_CHECKSUM) && argumentsMap.get(ARG_CHECKSUM) != null) {
            checksum = argumentsMap.get(ARG_CHECKSUM).toString();
        }
        // user-provided provider authority
        Object authority = ((Map) arguments).get(ARG_ANDROID_PROVIDER_AUTHORITY);
        if (authority != null) {
            androidProviderAuthority = authority.toString();
        } else {
            androidProviderAuthority = context.getPackageName() + "." + "ota_update_provider";
        }

        if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            executeDownload();
        } else {
            String[] permissions = {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            ActivityCompat.requestPermissions(activity, permissions, 0);
        }
    }

    @Override
    public void onCancel(Object o) {
        Log.d(TAG, "STREAM CLOSED");
        progressSink = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        Log.d(TAG, "REQUEST PERMISSIONS RESULT RECEIVED");
        if (requestCode == 0 && grantResults.length > 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    progressSink.error("" + OtaStatus.PERMISSION_NOT_GRANTED_ERROR.ordinal(), null, null);
                    progressSink = null;
                    return false;
                }
            }
            executeDownload();
            return true;
        } else {
            if (progressSink != null) {
                progressSink.error("" + OtaStatus.PERMISSION_NOT_GRANTED_ERROR.ordinal(), null, null);
                progressSink = null;
            }
            return false;
        }
    }

    /**
     * Execute download and start installation. This method is called either from onListen method
     * or from onRequestPermissionsResult if user had to grant permissions.
     */
    private void executeDownload() {
        try {
            //PREPARE URLS
            final String destination = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + filename;
            final Uri fileUri = Uri.parse("file://" + destination);

            //DELETE APK FILE IF IT ALREADY EXISTS
            File file = new File(destination);
            if (file.exists()) {
                if (!file.delete()) {
                    Log.e(TAG, "ERROR: unable to delete old apk file before starting OTA");
                }
            }

            Log.d(TAG, "DOWNLOAD STARTING");
            //CREATE DOWNLOAD MANAGER REQUEST
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            if (headers != null) {
                Iterator<String> jsonKeys = headers.keys();
                while (jsonKeys.hasNext()) {
                    String headerName = jsonKeys.next();
                    String headerValue = headers.getString(headerName);
                    request.addRequestHeader(headerName, headerValue);
                }
            }

            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            request.setDestinationUri(fileUri);

            //GET DOWNLOAD SERVICE AND ENQUEUE OUR DOWNLOAD REQUEST
            final DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            final long downloadId = manager.enqueue(request);

            Log.d(TAG, "DOWNLOAD STARTED WITH ID " + downloadId);
            //START TRACKING DOWNLOAD PROGRESS IN SEPARATE THREAD
            trackDownloadProgress(downloadId, manager);

            //REGISTER LISTENER TO KNOW WHEN DOWNLOAD IS COMPLETE
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    context.unregisterReceiver(this);
                    onDownloadComplete(destination, fileUri);
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            if (progressSink != null) {
                progressSink.error("" + OtaStatus.INTERNAL_ERROR.ordinal(), e.getMessage(), null);
                progressSink = null;
            }
            Log.e(TAG, "ERROR: " + e.getMessage(), e);
        }
    }

    /**
     * Download has been completed
     *
     * 1. Check if file exists
     * 2. If checksum was provided, compute downloaded file checksum and compare with provided value
     * 3. If checks above pass, trigger installation
     *
     * @param destination Destination path
     * @param fileUri     Uri to file
     */
    private void onDownloadComplete(String destination, Uri fileUri) {
        //DOWNLOAD IS COMPLETE, UNREGISTER RECEIVER AND CLOSE PROGRESS SINK
        File downloadedFile = new File(destination);
        if (!downloadedFile.exists()) {
            if (progressSink != null) {
                progressSink.error("" + OtaStatus.DOWNLOAD_ERROR.ordinal(), "File was not downloaded", null);
                progressSink.endOfStream();
                progressSink = null;
            }
            return;
        }

        if (checksum != null) {
            //IF user provided checksum verify file integrity
            try {
                if (!Sha256ChecksumValidator.validateChecksum(checksum, downloadedFile)) {
                    //SEND CHECKSUM ERROR EVENT
                    if (progressSink != null) {
                        progressSink.error("" + OtaStatus.CHECKSUM_ERROR.ordinal(), "Checksum verification failed", null);
                        progressSink.endOfStream();
                        progressSink = null;
                    }
                    return;
                }
            } catch (RuntimeException ex) {
                //SEND CHECKSUM ERROR EVENT
                if (progressSink != null) {
                    progressSink.error("" + OtaStatus.CHECKSUM_ERROR.ordinal(), ex.getMessage(), null);
                    progressSink.endOfStream();
                    progressSink = null;
                }
                return;
            }
        }
        //TRIGGER APK INSTALLATION
        executeInstallation(fileUri, downloadedFile);
    }

    /**
     * Execute installation
     *
     * For android API level >= 24 start intent for ACTION_INSTALL_PACKAGE (native installer)
     * For android API level < 24 start intent ACTION_VIEW (open file, android should prompt for installation)
     *
     * @param fileUri        Uri for file path
     * @param downloadedFile Downloaded file
     */
    private void executeInstallation(Uri fileUri, File downloadedFile) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //AUTHORITY NEEDS TO BE THE SAME ALSO IN MANIFEST
            Uri apkUri = FileProvider.getUriForFile(context, androidProviderAuthority, downloadedFile);
            intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        //SEND INSTALLING EVENT
        if (progressSink != null) {
            //NOTE: We have to start intent before sending event to stream
            //if application tries to programatically terminate app it may produce race condition
            //and application may end before intent is dispatched
            context.startActivity(intent);
            progressSink.success(Arrays.asList("" + OtaStatus.INSTALLING.ordinal(), ""));
            progressSink.endOfStream();
            progressSink = null;
        }
    }

    /**
     * Helper method that spawns thread, which will periodically check for status
     *
     * @param downloadId ID of the download
     * @param manager    Download manager instance
     */
    private void trackDownloadProgress(final long downloadId, final DownloadManager manager) {
        Log.d(TAG, "TRACK DOWNLOAD STARTED " + downloadId);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "TRACK DOWNLOAD THREAD STARTED " + downloadId);
                //REPORT PROGRESS WHILE DOWNLOAD STILL RUNS
                boolean downloading = true;
                boolean hasStatus = false;
                long downloadStart = System.currentTimeMillis();
                while (downloading) {
                    //QUERY CURRENT PROGRESS STATUS
                    DownloadManager.Query q = new DownloadManager.Query();
                    q.setFilterById(downloadId);
                    Cursor c = manager.query(q);
                    if (c.moveToFirst()) {
                        hasStatus = true;
                        //PUSH THE STATUS THROUGH THE SINK
                        long bytes_downloaded = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long bytes_total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (progressSink != null && bytes_total > 0) {
                            Message message = new Message();
                            Bundle data = new Bundle();
                            data.putLong(BYTES_DOWNLOADED, bytes_downloaded);
                            data.putLong(BYTES_TOTAL, bytes_total);
                            message.setData(data);
                            handler.sendMessage(message);
                        }
                        //STOP CYCLE IF DOWNLOAD IS COMPLETE
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));

                        if (status == DownloadManager.STATUS_SUCCESSFUL) { //sucess
                            Log.d(TAG, "OTA UPDATE SUCCESS");
                            downloading = false;
                        } else if (status == DownloadManager.STATUS_PENDING) { //running
                            Log.d(TAG, "OTA UPDATE PENDING");
                        } else if (status == DownloadManager.STATUS_RUNNING) { //running
                            Log.d(TAG, "OTA UPDATE TRACK DOWNLOAD RUNNING ");
                        } else if (status == DownloadManager.STATUS_FAILED) { //failed
                            downloading = false;
                            Log.d(TAG, "OTA UPDATE FAILURE: " + c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON)));
                            Message message = new Message();
                            Bundle data = new Bundle();
                            data.putString(ERROR, "RECEIVED STATUS FAILED");
                            message.setData(data);
                            handler.sendMessage(message);
                        } else if (status == DownloadManager.STATUS_PAUSED) { //failed, but may be retryied on device restart
                            Log.d(TAG, "OTA UPDATE PAUSED. REASON IS (CHECK AGAINST PAUSED_ CONSTANTS OF DownloadManager: " + c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON)));
                        }

                        //CLOSE CURSOR
                        c.close();
                        //WAIT FOR 1/4 SECOND FOR ANOTHER ITERATION
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        long duration = System.currentTimeMillis() - downloadStart;
                        if (!hasStatus && duration > MAX_WAIT_FOR_DOWNLOAD_START) {
                            //NOTE: If no status could be obtained from download manager for 5s after starting
                            // the download something is bad and we will throw error.
                            downloading = false;
                            Log.d(TAG, "OTA UPDATE FAILURE: DOWNLOAD DID NOT START AFTER 5000ms");
                            Message message = new Message();
                            Bundle data = new Bundle();
                            data.putString(ERROR, "DOWNLOAD DID NOT START AFTER 5000ms");
                            message.setData(data);
                            handler.sendMessage(message);
                        }
                    }
                }
            }
        }).start();
    }

    /**
     * Report error to the dart code
     *
     * @param otaStatus Status to report
     * @param s         Error message to report
     */
    private void reportError(OtaStatus otaStatus, String s) {
        if (progressSink != null) {
            progressSink.error("" + otaStatus.ordinal(), s, null);
            progressSink = null;
        }
    }

    /**
     * Initialization. Shared for embedding v1 and v2
     *
     * @param context   ApplicationContext
     * @param messanger BinaryMessanger for communication with dart
     */
    private void initialize(Context context, BinaryMessenger messanger) {
        this.context = context;
        handler = new Handler(context.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (progressSink != null) {
                    Bundle data = msg.getData();
                    if (data.containsKey(ERROR)) {
                        reportError(OtaStatus.DOWNLOAD_ERROR, data.getString(ERROR));
                    } else {
                        long bytesDownloaded = data.getLong(BYTES_DOWNLOADED);
                        long bytesTotal = data.getLong(BYTES_TOTAL);
                        progressSink.success(Arrays.asList("" + OtaStatus.DOWNLOADING.ordinal(), "" + ((bytesDownloaded * 100) / bytesTotal)));
                    }
                }
            }
        };
        final EventChannel progressChannel = new EventChannel(messanger, "sk.fourq.ota_update");
        progressChannel.setStreamHandler(this);
    }

    /**
     * All statuses reported by the plugin
     */
    private enum OtaStatus {
        DOWNLOADING,
        INSTALLING,
        ALREADY_RUNNING_ERROR,
        PERMISSION_NOT_GRANTED_ERROR,
        INTERNAL_ERROR,
        DOWNLOAD_ERROR,
        CHECKSUM_ERROR,
    }
}
