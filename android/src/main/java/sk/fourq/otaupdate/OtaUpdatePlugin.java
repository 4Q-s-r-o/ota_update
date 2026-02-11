package sk.fourq.otaupdate;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import androidx.core.content.FileProvider;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import okio.BufferedSink;
import okio.Okio;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OtaUpdatePlugin
 */
public class OtaUpdatePlugin implements
        FlutterPlugin,
        ActivityAware,
        EventChannel.StreamHandler,
        MethodCallHandler,
        PluginRegistry.RequestPermissionsResultListener,
        ProgressListener {

    //CONSTANTS
    private static final String BYTES_DOWNLOADED = "BYTES_DOWNLOADED";
    private static final String BYTES_TOTAL = "BYTES_TOTAL";
    private static final String ERROR = "ERROR";
    private static final String ARG_URL = "url";
    private static final String ARG_USE_PACKAGE_INSTALLER = "usePackageInstaller";
    private static final String ARG_HEADERS = "headers";
    private static final String ARG_FILENAME = "filename";
    private static final String ARG_CHECKSUM = "checksum";
    private static final String ARG_ANDROID_PROVIDER_AUTHORITY = "androidProviderAuthority";
    public static final String TAG = "FLUTTER OTA";
    private static final String DEFAULT_APK_NAME = "ota_update.apk";
    private static final String STREAM_CHANNEL = "sk.fourq.ota_update/stream";
    private static final String METHOD_CHANNEL = "sk.fourq.ota_update/method";

    // CONTENT LENGTH FOR PROGRESS REPORTING
    private Long contentLength;

    //BASIC PLUGIN STATE
    private Context context;
    private Activity activity;
    private EventChannel.EventSink progressSink;
    private Handler handler;
    private String androidProviderAuthority;
    private BinaryMessenger messanger;
    private OkHttpClient client;
    private InstallSessionCallback installSessionCallback;

    //DOWNLOAD SPECIFIC PLUGIN STATE. PLUGIN SUPPORT ONLY ONE DOWNLOAD AT A TIME
    private Call currentCall;
    private String downloadUrl;
    private JSONObject headers;
    private String filename;
    private String checksum;
    private boolean usePackageInstaller = false;

    //FLUTTER EMBEDDING V2 - PLUGIN BINDING
    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        Log.d(TAG, "onAttachedToEngine");
        initialize(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        Log.d(TAG, "onDetachedFromEngine");
        context = null;
        messanger = null;
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
        Log.d(TAG, "onMethodCall " + call.method);
        if (call.method.equals("getAbi")) {
            result.success(Build.SUPPORTED_ABIS[0]);
        } else if (call.method.equals("cancel")) {
            if (currentCall != null) {
                currentCall.cancel();
                currentCall = null;
                reportStatus(true, OtaStatus.CANCELED, "Call was canceled using cancel()", null, null);
            }
            result.success(null);
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
        Map<String, String> argumentsMap;
        try {
            argumentsMap = parseArgumentsMap(arguments);
        } catch (RuntimeException ex) {
            reportStatus(true, OtaStatus.INTERNAL_ERROR, "Invalid arguments passed to onListen()", ex, null);
            return;
        }
        downloadUrl = argumentsMap.get(ARG_URL);
        String rawUsePackageInstaller = argumentsMap.get(ARG_USE_PACKAGE_INSTALLER);
        if (rawUsePackageInstaller != null) {
            usePackageInstaller = rawUsePackageInstaller.equals("true");
        }
        try {
            String headersJson = argumentsMap.get(ARG_HEADERS);
            if (headersJson != null && !headersJson.isEmpty()) {
                headers = new JSONObject(headersJson);
            }
        } catch (JSONException e) {
            Log.e(TAG, "ERROR: " + e.getMessage(), e);
        }
        if (argumentsMap.containsKey(ARG_FILENAME) && argumentsMap.get(ARG_FILENAME) != null) {
            filename = argumentsMap.get(ARG_FILENAME);
        } else {
            filename = DEFAULT_APK_NAME;
        }
        if (argumentsMap.containsKey(ARG_CHECKSUM) && argumentsMap.get(ARG_CHECKSUM) != null) {
            checksum = argumentsMap.get(ARG_CHECKSUM);
        }
        // user-provided provider authority
        String authority = argumentsMap.get(ARG_ANDROID_PROVIDER_AUTHORITY);
        androidProviderAuthority = Objects.requireNonNullElseGet(authority, () -> context.getPackageName() + "." + "ota_update_provider");
        executeDownload();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseArgumentsMap(Object arguments) {
        if (arguments instanceof Map) {
            return ((Map<String, String>) arguments);
        }
        throw new IllegalArgumentException();
    }

    @Override
    public void onCancel(Object o) {
        Log.d(TAG, "STREAM CLOSED");
        closeSink();
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        Log.d(TAG, "REQUEST PERMISSIONS RESULT RECEIVED");
        if (requestCode == 0 && grantResults.length > 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    reportStatus(true, OtaStatus.PERMISSION_NOT_GRANTED_ERROR, "Permission not granted", null, null);
                    return false;
                }
            }
            executeDownload();
            return true;
        } else {
            reportStatus(true, OtaStatus.PERMISSION_NOT_GRANTED_ERROR, "Permission not granted", null, null);
            return false;
        }
    }

    /**
     * Execute download and start installation. This method is called either from onListen method
     * or from onRequestPermissionsResult if user had to grant permissions.
     */
    private void executeDownload() {
        try {
            if (currentCall != null) {
                reportStatus(true, OtaStatus.ALREADY_RUNNING_ERROR, "Another download (call) is already running", null, null);
                return;
            }

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
            } else if (file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    reportStatus(true, OtaStatus.INTERNAL_ERROR, "unable to create ota_update folder in internal storage", null, null);
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

            currentCall = client.newCall(request.build());
            currentCall.enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    reportStatus(true, OtaStatus.DOWNLOAD_ERROR, e.getMessage(), e, null);
                    currentCall = null;
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) {
                    if (!response.isSuccessful()) {
                        reportStatus(true, OtaStatus.DOWNLOAD_ERROR, "Http request finished with status " + response.code(), null, null);
                    }
                    try {
                        BufferedSink sink = Okio.buffer(Okio.sink(file));
                        if (response.body() != null) {
                            sink.writeAll(response.body().source());
                        }
                        sink.close();
                    } catch (StreamResetException ex) {
                        // Thrown when the call was canceled using 'cancel()'
                        currentCall = null;
                        return;
                    } catch (IOException | RuntimeException ex) {
                        reportStatus(true, OtaStatus.DOWNLOAD_ERROR, ex.getMessage(), ex, null);
                        currentCall = null;
                        return;
                    }
                    onDownloadComplete(destination, fileUri);
                    currentCall = null;
                }
            });
        } catch (Exception e) {
            reportStatus(true, OtaStatus.INTERNAL_ERROR, e.getMessage(), e, null);
            currentCall = null;
        }
    }

    /**
     * Download has been completed
     * <p>
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
            reportStatus(true, OtaStatus.DOWNLOAD_ERROR, "File was not downloaded", null, null);
            return;
        }
        if (checksum != null) {
            //IF the user provided checksum verify file integrity
            try {
                if (!Sha256ChecksumValidator.validateChecksum(checksum, downloadedFile)) {
                    //SEND CHECKSUM ERROR EVENT
                    reportStatus(true, OtaStatus.CHECKSUM_ERROR, "Checksum verification failed", null, null);
                    return;
                }
            } catch (RuntimeException ex) {
                //SEND CHECKSUM ERROR EVENT
                reportStatus(true, OtaStatus.CHECKSUM_ERROR, ex.getMessage(), ex, null);
                return;
            }
        }
        //TRIGGER APK INSTALLATION
        handler.post(() -> executeInstallation(fileUri, downloadedFile)
        );
    }

    /**
     * Check if app has INSTALL_PACKAGES permission (system app privilege)
     */
    private boolean hasInstallPackagesPermission() {
        try {
            boolean hasInstallPackages = context.checkCallingOrSelfPermission("android.permission.INSTALL_PACKAGES")
                    == PackageManager.PERMISSION_GRANTED;
            Log.d(TAG, "INSTALL_PACKAGES permission: " + hasInstallPackages);
            return hasInstallPackages;
        } catch (Exception e) {
            Log.w(TAG, "Error checking INSTALL_PACKAGES permission", e);
            return false;
        }
    }

    /**
     * Execute installation
     * <p>
     * If app has INSTALL_PACKAGES permission, use package installer (will be silent if possible)
     * For android API level >= 24 use package installer (will be silent if possible)
     * For android API level < 24 start intent ACTION_VIEW (open file, android should prompt for installation)
     *
     * @param fileUri        Uri for file path
     * @param downloadedFile Downloaded file
     */
    private void executeInstallation(Uri fileUri, File downloadedFile) {
        // Try silent installation for system apps first
        if (hasInstallPackagesPermission()) {
            Log.d(TAG, "App has INSTALL_PACKAGES, using package installer");
            installUsingPackageInstaller(downloadedFile);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (usePackageInstaller) {
                installUsingPackageInstaller(downloadedFile);
            } else {
                installUsingActionInstallPackage(downloadedFile);
            }
        } else {
            installUsingVndPackageArchive(fileUri);
        }
    }

    /**
     * Perform installation using PackageInstaller (for system apps)
     */
    private void installUsingPackageInstaller(File downloadedFile) {
        try {
            Log.d(TAG, "Using PackageInstaller installation method");
            // NOTIFY DART PART OF THE PLUGIN, THAT INSTALLATION STARTED
            reportStatus(false, OtaStatus.INSTALLING, "Installation started", null, null);
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            // Configure session parameters.
            // MODE_FULL_INSTALL means we’re doing a full APK installation (not a staged/delta update).
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
            );
            // Create a new installation session and get its unique ID
            // Open the session so we can write the APK bytes into it
            int sessionId = packageInstaller.createSession(params);
            packageInstaller.registerSessionCallback(installSessionCallback);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);
            long totalWritten = 0;
            try (OutputStream out = session.openWrite("package", 0, -1);
                 InputStream in = new FileInputStream(downloadedFile)
            ) {
                // Buffer for copying data from the APK file into the session
                byte[] buffer = new byte[65536];
                int c;
                while ((c = in.read(buffer)) != -1) {
                    out.write(buffer, 0, c);
                    totalWritten += c;
                    if (contentLength != null) {
                        session.setStagingProgress(totalWritten / ((float) contentLength));
                    }
                }
                session.fsync(out);
            }

            // Create intent for the installation result
            Intent intent = new Intent(context, InstallResultReceiver.class);
            intent.setAction(context.getPackageName() + "." + InstallResultReceiver.ACTION_INSTALL_COMPLETE);
            // Wrap the result Intent in a PendingIntent, which gives us an IntentSender for commit().
            // On Android 12 (S) and above, PendingIntent must be declared mutable/immutable explicitly.
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);

            // Set callback now so this instance (the one with progressSink set) receives the result.
            // If we only set it in initialize(), a recreated plugin instance would overwrite it and
            // that instance has progressSink == null.
            InstallResultReceiver.setCallback(new InstallResultReceiver.InstallResultCallback() {
                @Override
                public void onInstallSuccess(String message) {
                    reportStatus(true, OtaStatus.INSTALLATION_DONE, message, null, null);
                }
                @Override
                public void onInstallFailure(String message) {
                    reportStatus(true, OtaStatus.INSTALLATION_ERROR, message, null, null);
                }
            });

            // Commit the session. This hands control over to the system to actually perform the install.
            // The provided IntentSender will be invoked with the result of the installation.
            session.commit(pendingIntent.getIntentSender());
            session.close();
            Log.d(TAG, "Installation session committed");
        } catch (Exception e) {
            Log.e(TAG, "PackageInstaller installation method failed", e);
            reportStatus(true, OtaStatus.INSTALLATION_ERROR, "Installation failed: " + e.getMessage(), e, null);
        }
    }

    @SuppressWarnings("deprecation")
    private void installUsingActionInstallPackage(File downloadedFile) {
        Intent intent;
        Uri apkUri = FileProvider.getUriForFile(context, androidProviderAuthority, downloadedFile);
        intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        reportStatus(true, OtaStatus.INSTALLING, "Installation started", null, null);
    }

    @SuppressWarnings("deprecation")
    private void installUsingVndPackageArchive(Uri fileUri) {
        Intent intent;
        intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        reportStatus(true, OtaStatus.INSTALLING, "Installation started", null, null);
    }


    /**
     * Report error to the dart code
     *
     * @param closeSink Indicates whether to close the progress sink after reporting status
     * @param otaStatus Status to report
     * @param s         Error message to report
     * @param e         Exception to report
     */
    private void reportStatus(final boolean closeSink, final OtaStatus otaStatus, final String s, final Exception e, Object arg) {
        if (Looper.getMainLooper().isCurrentThread()) {
            if (otaStatus.isError()) {
                Log.e(TAG, "ERROR: " + s, e);
            }
              if (progressSink != null) {
                // Always send status as a success payload [ordinal, message] so the Dart stream
                // receives it in onData. Using progressSink.error() would deliver to onError and
                // the listener would never get an OtaEvent (e.g. INSTALLATION_ERROR / downgrade).
                List<String> responseArgs = new ArrayList<>(2);
                responseArgs.add("" + otaStatus.ordinal());
                if (arg != null) {
                    responseArgs.add(arg.toString());
                } else if (s != null) {
                    responseArgs.add(s);
                } else {
                    responseArgs.add("");
                }
                progressSink.success(responseArgs);
                // Defer close so the engine can deliver the success event to Dart before the
                // stream is closed. Calling endOfStream() immediately can drop the last event.
                if (closeSink) {
                    closeSink();
                }
            }
        } else {
            //REPORT ERROR ON UI THREAD
            handler.post(new Runnable() {
                @Override
                public void run() {
                    reportStatus(closeSink, otaStatus, s, e, null);
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
                        reportStatus(true, OtaStatus.DOWNLOAD_ERROR, data.getString(ERROR), null, null);
                    } else {
                        long bytesDownloaded = data.getLong(BYTES_DOWNLOADED);
                        long bytesTotal = data.getLong(BYTES_TOTAL);
                        reportStatus(false, OtaStatus.DOWNLOADING, "", null, "" + ((bytesDownloaded * 100) / bytesTotal));
                    }
                }
            }
        };

        // Set callback for install session callback
        installSessionCallback = new InstallSessionCallback(progress -> {
            reportStatus(false, OtaStatus.INSTALLING, "", null, (int) Math.floor(progress * 100));
        });

        // Install result callback is set in installUsingPackageInstaller() right before commit(),
        // so the instance that started the install (and has progressSink set) receives the result.
        // Do not set it here: if the plugin is recreated (e.g. engine reattach), a new instance
        // would overwrite the callback and that instance has progressSink == null.

        final EventChannel progressChannel = new EventChannel(messanger, STREAM_CHANNEL);
        progressChannel.setStreamHandler(this);

        final MethodChannel methodChannel = new MethodChannel(messanger, METHOD_CHANNEL);
        methodChannel.setMethodCallHandler(this);

        client = new OkHttpClient.Builder()
                .addNetworkInterceptor(chain -> {
                    Response originalResponse = chain.proceed(chain.request());
                    return originalResponse.newBuilder()
                            .body(new ProgressResponseBody(originalResponse.body(), OtaUpdatePlugin.this))
                            .build();
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
                    this.contentLength = contentLength;
                }
            }
        }
    }
    /**
     * Single place that disposes the stream: endOfStream() and null the sink.
     * Only call when the stream is truly done (result sent, or cancel/error path).
     */
    private void closeSink() {
        if (progressSink != null) {
            progressSink.endOfStream();
        }
        progressSink = null;
        contentLength = null;
        try {
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            packageInstaller.unregisterSessionCallback(installSessionCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error unregistering session callback", e);
        }
    }

    /**
     * All statuses reported by the plugin
     */
    private enum OtaStatus {
        DOWNLOADING(false),
        INSTALLING(false),
        INSTALLATION_DONE(false),
        INSTALLATION_ERROR(true),
        ALREADY_RUNNING_ERROR(true),
        PERMISSION_NOT_GRANTED_ERROR(true),
        INTERNAL_ERROR(true),
        DOWNLOAD_ERROR(true),
        CHECKSUM_ERROR(true),
        CANCELED(true);

        /**
         * Indicates whether status represents an error
         */
        private final boolean error;

        /**
         * Constructor
         *
         * @param error Indicates whether status represents an error
         */
        OtaStatus(boolean error) {
            this.error = error;
        }

        /**
         * @return true if status represents an error, false otherwise
         */
        public boolean isError() {
            return error;
        }
    }
}
