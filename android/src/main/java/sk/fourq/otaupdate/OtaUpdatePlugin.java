package sk.fourq.otaupdate;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * OtaUpdatePlugin
 */
@TargetApi(Build.VERSION_CODES.M)
public class OtaUpdatePlugin implements FlutterPlugin, ActivityAware, EventChannel.StreamHandler, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, ProgressListener {

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
    private static final String STREAM_CHANNEL = "sk.fourq.ota_update/stream";
    private static final String METHOD_CHANNEL = "sk.fourq.ota_update/method";

    //BASIC PLUGIN STATE
    private Context context;
    private Activity activity;
    private EventChannel.EventSink progressSink;
    private Handler handler;
    private String androidProviderAuthority;
    private BinaryMessenger messanger;
    private OkHttpClient client;

    //DOWNLOAD SPECIFIC PLUGIN STATE. PLUGIN SUPPORT ONLY ONE DOWNLOAD AT A TIME
    private String downloadUrl;
    private JSONObject headers;
    private String filename;
    private String checksum;

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

    //METHOD LISTENER
    @Override
    public void onMethodCall(MethodCall call, Result result) {
        Log.d(TAG, "onMethodCall "+call.method);
        if (call.method.equals("getAbi")) {
            result.success(Build.SUPPORTED_ABIS[0]);
        } else {
            result.notImplemented();
        }
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

        int SKIP_WRITE_EXTERNAL_STORAGE_SDK_INT = 33;

        // WRITE_EXTERNAL_STORAGE permission always returns false on sdk 33
        boolean skipWriteExternalStorage = android.os.Build.VERSION.SDK_INT >= SKIP_WRITE_EXTERNAL_STORAGE_SDK_INT;

        if (skipWriteExternalStorage || PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
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
                    reportError(OtaStatus.PERMISSION_NOT_GRANTED_ERROR, "Permission not granted", null);
                    return false;
                }
            }
            executeDownload();
            return true;
        } else {
            reportError(OtaStatus.PERMISSION_NOT_GRANTED_ERROR, "Permission not granted", null);
            return false;
        }
    }

    /**
     * Execute download and start installation. This method is called either from onListen method
     * or from onRequestPermissionsResult if user had to grant permissions.
     */
    private void executeDownload() {
        try {
            String dataDir = context.getApplicationInfo().dataDir + "/files/ota_update";
            //PREPARE URLS
            final String destination = dataDir + "/" + filename;
            final Uri fileUri = Uri.parse("file://" + destination);

            //DELETE APK FILE IF IT ALREADY EXISTS
            final File file = new File(destination);
            if (file.exists()) {
                if (!file.delete()) {
                    Log.e(TAG, "WARNING: unable to delete old apk file before starting OTA");
                }
            } else if (!file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    reportError(OtaStatus.INTERNAL_ERROR, "unable to create ota_update folder in internal storage", null);
                }
            }

            Log.d(TAG, "DOWNLOAD STARTING");
            Request.Builder request = new Request.Builder()
                    .url(downloadUrl);
            if (headers != null) {
                Iterator<String> jsonKeys = headers.keys();
                while (jsonKeys.hasNext()) {
                    String headerName = jsonKeys.next();
                    String headerValue = headers.getString(headerName);
                    request.addHeader(headerName, headerValue);
                }
            }

            client.newCall(request.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    reportError(OtaStatus.DOWNLOAD_ERROR, e.getMessage(), e);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        reportError(OtaStatus.DOWNLOAD_ERROR, "Http request finished with status " + response.code(), null);
                    }
                    try {
                        BufferedSink sink = Okio.buffer(Okio.sink(file));
                        sink.writeAll(response.body().source());
                        sink.close();
                    } catch (RuntimeException ex) {
                        reportError(OtaStatus.DOWNLOAD_ERROR, ex.getMessage(), ex);
                        return;
                    }
                    onDownloadComplete(destination, fileUri);
                }
            });
        } catch (Exception e) {
            reportError(OtaStatus.INTERNAL_ERROR, e.getMessage(), e);
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
    private void onDownloadComplete(final String destination, final Uri fileUri) {
        //DOWNLOAD IS COMPLETE, UNREGISTER RECEIVER AND CLOSE PROGRESS SINK
        final File downloadedFile = new File(destination);
        if (!downloadedFile.exists()) {
            reportError(OtaStatus.DOWNLOAD_ERROR, "File was not downloaded", null);
            return;
        }

        if (checksum != null) {
            //IF user provided checksum verify file integrity
            try {
                if (!Sha256ChecksumValidator.validateChecksum(checksum, downloadedFile)) {
                    //SEND CHECKSUM ERROR EVENT
                    reportError(OtaStatus.CHECKSUM_ERROR, "Checksum verification failed", null);
                    return;
                }
            } catch (RuntimeException ex) {
                //SEND CHECKSUM ERROR EVENT
                reportError(OtaStatus.CHECKSUM_ERROR, ex.getMessage(), ex);
                return;
            }
        }
        //TRIGGER APK INSTALLATION
        handler.post(new Runnable() {
                         @Override
                         public void run() {
                             executeInstallation(fileUri, downloadedFile);
                         }
                     }

        );
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
     * Report error to the dart code
     *
     * @param otaStatus Status to report
     * @param s         Error message to report
     */
    private void reportError(final OtaStatus otaStatus, final String s, final Exception e) {
        if (Looper.getMainLooper().isCurrentThread()) {
            Log.e(TAG, "ERROR: " + s, e);
            if (progressSink != null) {
                progressSink.error("" + otaStatus.ordinal(), s, null);
                progressSink = null;
            }
        } else {
            //REPORT ERROR ON UI THREAD
            handler.post(new Runnable() {
                @Override
                public void run() {
                    reportError(otaStatus, s, e);
                }
            });
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
                        reportError(OtaStatus.DOWNLOAD_ERROR, data.getString(ERROR), null);
                    } else {
                        long bytesDownloaded = data.getLong(BYTES_DOWNLOADED);
                        long bytesTotal = data.getLong(BYTES_TOTAL);
                        progressSink.success(Arrays.asList("" + OtaStatus.DOWNLOADING.ordinal(), "" + ((bytesDownloaded * 100) / bytesTotal)));
                    }
                }
            }
        };
        final EventChannel progressChannel = new EventChannel(messanger, STREAM_CHANNEL);
        progressChannel.setStreamHandler(this);

        final MethodChannel methodChannel = new MethodChannel(messanger, METHOD_CHANNEL);
        methodChannel.setMethodCallHandler(this);

        client = new OkHttpClient.Builder()
                .addNetworkInterceptor(new Interceptor() {
                    @NotNull
                    @Override
                    public Response intercept(@NotNull Chain chain) throws IOException {
                        Response originalResponse = chain.proceed(chain.request());
                        return originalResponse.newBuilder()
                                .body(new ProgressResponseBody(originalResponse.body(), OtaUpdatePlugin.this))
                                .build();
                    }
                })
                .build();
    }

    @Override
    public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {

        if (done) {
            Log.d(TAG, "Download is complete");
        } else {
            if (contentLength < 1) {
                Log.d(TAG, "Content-length header is missing. Cannot compute progress.");
            } else {
                if (progressSink != null) {
                    Message message = new Message();
                    Bundle data = new Bundle();
                    data.putLong(BYTES_DOWNLOADED, bytesRead);
                    data.putLong(BYTES_TOTAL, contentLength);
                    message.setData(data);
                    handler.sendMessage(message);
                }
            }
        }
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
