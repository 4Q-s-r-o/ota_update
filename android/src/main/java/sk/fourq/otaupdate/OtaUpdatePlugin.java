package sk.fourq.otaupdate;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

import java.io.File;
import java.util.Map;
import java.util.Arrays;

import android.support.v4.content.FileProvider;

/**
 * OtaUpdatePlugin
 */
@TargetApi(Build.VERSION_CODES.M)
public class OtaUpdatePlugin implements EventChannel.StreamHandler, PluginRegistry.RequestPermissionsResultListener {

    enum OtaStatus {
        DOWNLOADING, INSTALLING, ALREADY_RUNNING_ERROR, PERMISSION_NOT_GRANTED_ERROR, INTERNAL_ERROR
    }

    private final Registrar registrar;
    private EventChannel.EventSink progressSink;
    private String downloadUrl;

    private static final String TAG = "FLUTTER OTA";

    private OtaUpdatePlugin(Registrar registrar) {
        this.registrar = registrar;
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        OtaUpdatePlugin plugin = new OtaUpdatePlugin(registrar);
        final EventChannel progressChannel = new EventChannel(registrar.messenger(), "sk.fourq.ota_update");
        progressChannel.setStreamHandler(plugin);
        registrar.addRequestPermissionsResultListener(plugin);
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (progressSink != null) {
            progressSink.error("" + OtaStatus.ALREADY_RUNNING_ERROR.ordinal(), "Method call was cancelled. One method call is already running", null);
        }
        progressSink = events;
        downloadUrl = ((Map)arguments).get("url").toString();

        if (
//                PackageManager.PERMISSION_GRANTED == registrar.activity().checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) &&
//                PackageManager.PERMISSION_GRANTED == registrar.activity().checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) &&
                PackageManager.PERMISSION_GRANTED == registrar.activity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            handleCall();
        } else {
            String[] permissions = {
//                    Manifest.permission.ACCESS_WIFI_STATE,
//                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            registrar.activity().requestPermissions(permissions, 0);
        }
    }

    @Override
    public void onCancel(Object o) {
        progressSink = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        if (requestCode == 0 && grantResults.length > 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    progressSink.error("" + OtaStatus.PERMISSION_NOT_GRANTED_ERROR.ordinal(), null, null);
                    progressSink = null;
                    return false;
                }
            }
            handleCall();
            return true;
        } else {
            if (progressSink != null) {
                progressSink.error("" + OtaStatus.PERMISSION_NOT_GRANTED_ERROR.ordinal(), null, null);
                progressSink = null;
            }
            return false;
        }
    }

    private void handleCall() {
        try {
            final Context context = (registrar.activity() != null) ? registrar.activity() : registrar.context();
            //PREPARE URLS
            final String destination = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + "ordo.apk";
            final Uri fileUri = Uri.parse("file://" + destination);

            //DELETE APK FILE IF SOME ALREADY EXISTS
            File file = new File(destination);
            if (file.exists()) {
                if (!file.delete()) {
                    Log.e(TAG, "ERROR: unable to delete old apk file before starting OTA");
                }
            }

            //CREATE DOWNLOAD MANAGER REQUEST
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setDestinationUri(fileUri);

            //GET DOWNLOAD SERVICE AND ENQUEUE OUR DOWNLOAD REQUEST
            final DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            final long downloadId = manager.enqueue(request);

            //START TRACKING DOWNLOAD PROGRESS IN SEPARATE THREAD
            trackDownloadProgress(downloadId, manager);

            //REGISTER LISTENER TO KNOW WHEN DOWNLOAD IS COMPLETE
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    //DOWNLOAD IS COMPLETE, UNREGISTER RECEIVER AND CLOSE PROGRESS SINK
                    context.unregisterReceiver(this);
                    //TRIGGER APK INSTALLATION
                    Intent intent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        //AUTHORITY NEEDS TO BE THE SAME ALSO IN MANIFEST
                        Uri apkUri = FileProvider.getUriForFile(context, "sk.fourq.ota_update.provider", new File(destination));
                        intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                        intent.setData(apkUri);
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(fileUri, "application/vnd.android.package-archive");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    //SEND INSTALLING EVENT
                    progressSink.success(Arrays.asList("" + OtaStatus.INSTALLING.ordinal(), ""));
                    progressSink.endOfStream();
                    progressSink = null;
                    context.startActivity(intent);
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            progressSink.error("" + OtaStatus.INTERNAL_ERROR.ordinal(), e.getMessage(), null);
            progressSink = null;
            Log.e(TAG, "ERROR: " + e.getMessage(), e);
        }
    }

    private void trackDownloadProgress(final long downloadId, final DownloadManager manager) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                //REPORT PROGRESS WHILE DOWNLOAD STILL RUNS
                boolean downloading = true;
                while (downloading) {
                    //QUERY CURRENT PROGRESS STATUS
                    DownloadManager.Query q = new DownloadManager.Query();
                    q.setFilterById(downloadId);
                    Cursor c = manager.query(q);
                    c.moveToFirst();
                    //PUSH THE STATUS THROUGH THE SINK
                    int bytes_downloaded = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int bytes_total = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (progressSink != null) {
                        progressSink.success(Arrays.asList("" + OtaStatus.DOWNLOADING.ordinal(), "" + ((bytes_downloaded * 100) / bytes_total)));
                    }
                    //STOP CYCLE IF DOWNLOAD IS COMPLETE
                    if (c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false;
                    }
                    //CLOSE CURSOR
                    c.close();
                    //WAIT FOR 1/4 SECOND FOR ANOTHER ITERATION
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }
}
