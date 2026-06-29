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
import okio.BufferedSource;
import okio.Okio;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final String ARG_PARALLEL_DOWNLOADS = "parallelDownloads";
    public static final String TAG = "FLUTTER OTA";
    private static final String DEFAULT_APK_NAME = "ota_update.apk";
    private static final String STREAM_CHANNEL = "sk.fourq.ota_update/stream";
    private static final String METHOD_CHANNEL = "sk.fourq.ota_update/method";
    private static final int MAX_PARALLEL_DOWNLOADS = 8;

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
    private OkHttpClient rawClient;
    private InstallSessionCallback installSessionCallback;

    //DOWNLOAD SPECIFIC PLUGIN STATE. PLUGIN SUPPORT ONLY ONE DOWNLOAD AT A TIME
    private final Object callsLock = new Object();
    private final List<Call> currentCalls = new ArrayList<>();
    private Call currentCall;
    private String downloadUrl;
    private JSONObject headers;
    private String filename;
    private String checksum;
    private boolean usePackageInstaller = false;
    private int parallelDownloads = 1;
    private volatile boolean downloadRunning = false;
    private volatile boolean downloadCancelled = false;

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
            if (downloadRunning || hasActiveCalls()) {
                cancelActiveCalls();
                downloadRunning = false;
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
        headers = null;
        checksum = null;
        usePackageInstaller = false;
        parallelDownloads = 1;
        downloadUrl = argumentsMap.get(ARG_URL);
        String rawUsePackageInstaller = argumentsMap.get(ARG_USE_PACKAGE_INSTALLER);
        if (rawUsePackageInstaller != null) {
            usePackageInstaller = rawUsePackageInstaller.equals("true");
        }
        parallelDownloads = parseParallelDownloads(argumentsMap.get(ARG_PARALLEL_DOWNLOADS));
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

    private int parseParallelDownloads(String rawParallelDownloads) {
        if (rawParallelDownloads == null) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(rawParallelDownloads);
            return Math.max(1, Math.min(parsed, MAX_PARALLEL_DOWNLOADS));
        } catch (NumberFormatException ex) {
            Log.w(TAG, "Invalid parallelDownloads value: " + rawParallelDownloads, ex);
            return 1;
        }
    }

    @Override
    public void onCancel(Object o) {
        Log.d(TAG, "STREAM CLOSED");
        if (downloadRunning || hasActiveCalls()) {
            cancelActiveCalls();
            downloadRunning = false;
        }
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
            if (downloadRunning) {
                reportStatus(true, OtaStatus.ALREADY_RUNNING_ERROR, "Another download (call) is already running", null, null);
                return;
            }
            downloadRunning = true;
            downloadCancelled = false;
            contentLength = null;

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
                    clearActiveCallReferences();
                    reportStatus(true, OtaStatus.INTERNAL_ERROR, "unable to create ota_update folder in internal storage", null, null);
                    return;
                }
            }

            if (parallelDownloads > 1) {
                Log.d(TAG, "DOWNLOAD STARTING WITH " + parallelDownloads + " CONNECTIONS");
                startParallelDownload(destination, fileUri, file);
            } else {
                Log.d(TAG, "DOWNLOAD STARTING");
                startSingleDownload(destination, fileUri, file);
            }
        } catch (Exception e) {
            clearActiveCallReferences();
            reportStatus(true, OtaStatus.INTERNAL_ERROR, e.getMessage(), e, null);
        }
    }

    private void startSingleDownload(final String destination, final Uri fileUri, final File file) throws JSONException {
        if (downloadCancelled) {
            clearActiveCallReferences();
            return;
        }
        Request.Builder request = buildRequest(downloadUrl);

        currentCall = client.newCall(request.build());
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                if (downloadCancelled) {
                    clearActiveCallReferences();
                    return;
                }
                clearActiveCallReferences();
                reportStatus(true, OtaStatus.DOWNLOAD_ERROR, e.getMessage(), e, null);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                try {
                    if (downloadCancelled) {
                        return;
                    }
                    if (!response.isSuccessful()) {
                        clearActiveCallReferences();
                        reportStatus(true, OtaStatus.DOWNLOAD_ERROR, "Http request finished with status " + response.code(), null, null);
                        return;
                    }
                    try (BufferedSink sink = Okio.buffer(Okio.sink(file))) {
                        if (response.body() != null) {
                            sink.writeAll(response.body().source());
                        }
                    }
                } catch (StreamResetException ex) {
                    // Thrown when the call was canceled using 'cancel()'
                    return;
                } catch (IOException | RuntimeException ex) {
                    clearActiveCallReferences();
                    reportStatus(true, OtaStatus.DOWNLOAD_ERROR, ex.getMessage(), ex, null);
                    return;
                } finally {
                    response.close();
                }
                clearActiveCallReferences();
                onDownloadComplete(destination, fileUri);
            }
        });
    }

    private void startParallelDownload(final String destination, final Uri fileUri, final File file) throws JSONException {
        Request probeRequest = buildRequest(downloadUrl)
                .header("Range", "bytes=0-0")
                .build();
        currentCall = rawClient.newCall(probeRequest);
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                if (downloadCancelled) {
                    clearActiveCallReferences();
                    return;
                }
                Log.w(TAG, "Range probe failed. Falling back to single connection download.", e);
                currentCall = null;
                try {
                    startSingleDownload(destination, fileUri, file);
                } catch (JSONException ex) {
                    clearActiveCallReferences();
                    reportStatus(true, OtaStatus.DOWNLOAD_ERROR, ex.getMessage(), ex, null);
                }
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                try {
                    currentCall = null;
                    if (downloadCancelled) {
                        return;
                    }
                    long totalLength = parseTotalLengthFromContentRange(response.header("Content-Range"));
                    if (response.code() != 206 || totalLength < 1) {
                        Log.d(TAG, "Server does not support range requests. Falling back to single connection download.");
                        startSingleDownload(destination, fileUri, file);
                        return;
                    }
                    startRangeDownloads(destination, fileUri, file, totalLength);
                } catch (JSONException ex) {
                    clearActiveCallReferences();
                    reportStatus(true, OtaStatus.DOWNLOAD_ERROR, ex.getMessage(), ex, null);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void startRangeDownloads(final String destination, final Uri fileUri, final File file, final long totalLength) throws JSONException {
        if (downloadCancelled) {
            clearActiveCallReferences();
            return;
        }
        int connectionCount = (int) Math.min(parallelDownloads, totalLength);
        if (connectionCount <= 1) {
            startSingleDownload(destination, fileUri, file);
            return;
        }

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            randomAccessFile.setLength(totalLength);
        } catch (IOException | RuntimeException ex) {
            clearActiveCallReferences();
            reportStatus(true, OtaStatus.DOWNLOAD_ERROR, ex.getMessage(), ex, null);
            return;
        }

        contentLength = totalLength;
        reportDownloadProgress(0, totalLength);

        AtomicInteger completedParts = new AtomicInteger(0);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicLong totalDownloaded = new AtomicLong(0);

        for (int i = 0; i < connectionCount; i++) {
            final long start = (totalLength * i) / connectionCount;
            final long end = ((totalLength * (i + 1)) / connectionCount) - 1;
            final int partIndex = i;

            Request partRequest = buildRequest(downloadUrl)
                    .header("Range", "bytes=" + start + "-" + end)
                    .build();
            Call partCall = rawClient.newCall(partRequest);
            addActiveCall(partCall);
            partCall.enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    removeActiveCall(call);
                    if (downloadCancelled || finished.get()) {
                        return;
                    }
                    failParallelDownload(finished, "Part " + partIndex + " failed: " + e.getMessage(), e);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) {
                    try {
                        if (downloadCancelled || finished.get()) {
                            return;
                        }
                        if (response.code() != 206 || response.body() == null) {
                            failParallelDownload(finished, "Part " + partIndex + " finished with status " + response.code(), null);
                            return;
                        }

                        long written = writeRangeToFile(
                                response.body().source(),
                                file,
                                start,
                                totalDownloaded,
                                totalLength
                        );
                        if (written < 0 || downloadCancelled || finished.get()) {
                            return;
                        }
                        long expected = end - start + 1;
                        if (written != expected) {
                            failParallelDownload(finished, "Part " + partIndex + " downloaded " + written + " bytes, expected " + expected, null);
                            return;
                        }

                        if (completedParts.incrementAndGet() == connectionCount && finished.compareAndSet(false, true)) {
                            clearActiveCallReferences();
                            if (file.length() != totalLength) {
                                reportStatus(true, OtaStatus.DOWNLOAD_ERROR, "Downloaded file size does not match Content-Range total", null, null);
                                return;
                            }
                            onDownloadComplete(destination, fileUri);
                        }
                    } catch (StreamResetException ex) {
                        // Thrown when the call was canceled using 'cancel()'
                    } catch (IOException | RuntimeException ex) {
                        if (!downloadCancelled && !finished.get()) {
                            failParallelDownload(finished, ex.getMessage(), ex);
                        }
                    } finally {
                        response.close();
                        removeActiveCall(call);
                    }
                }
            });
        }
    }

    private Request.Builder buildRequest(String url) throws JSONException {
        Request.Builder request = new Request.Builder()
                .url(url);
        if (headers != null) {
            Iterator<String> jsonKeys = headers.keys();
            while (jsonKeys.hasNext()) {
                String headerName = jsonKeys.next();
                String headerValue = headers.getString(headerName);
                request.addHeader(headerName, headerValue);
            }
        }
        return request;
    }

    private long parseTotalLengthFromContentRange(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        int separatorIndex = contentRange.lastIndexOf('/');
        if (separatorIndex < 0 || separatorIndex == contentRange.length() - 1) {
            return -1;
        }
        String totalLength = contentRange.substring(separatorIndex + 1).trim();
        if (totalLength.equals("*")) {
            return -1;
        }
        try {
            return Long.parseLong(totalLength);
        } catch (NumberFormatException ex) {
            Log.w(TAG, "Invalid Content-Range total length: " + contentRange, ex);
            return -1;
        }
    }

    private long writeRangeToFile(
            BufferedSource source,
            File file,
            long start,
            AtomicLong totalDownloaded,
            long totalLength
    ) throws IOException {
        byte[] buffer = new byte[65536];
        long partDownloaded = 0;
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            randomAccessFile.seek(start);
            int bytesRead;
            while ((bytesRead = source.read(buffer)) != -1) {
                if (downloadCancelled) {
                    return -1;
                }
                randomAccessFile.write(buffer, 0, bytesRead);
                partDownloaded += bytesRead;
                reportDownloadProgress(totalDownloaded.addAndGet(bytesRead), totalLength);
            }
        }
        return partDownloaded;
    }

    private void reportDownloadProgress(long bytesDownloaded, long bytesTotal) {
        if (bytesTotal < 1 || progressSink == null) {
            return;
        }
        Message message = new Message();
        Bundle data = new Bundle();
        data.putLong(BYTES_DOWNLOADED, bytesDownloaded);
        data.putLong(BYTES_TOTAL, bytesTotal);
        message.setData(data);
        handler.sendMessage(message);
        contentLength = bytesTotal;
    }

    private void failParallelDownload(AtomicBoolean finished, String message, Exception e) {
        if (finished.compareAndSet(false, true)) {
            cancelActiveCalls();
            clearActiveCallReferences();
            reportStatus(true, OtaStatus.DOWNLOAD_ERROR, message, e, null);
        }
    }

    private void addActiveCall(Call call) {
        synchronized (callsLock) {
            currentCalls.add(call);
        }
    }

    private void removeActiveCall(Call call) {
        synchronized (callsLock) {
            currentCalls.remove(call);
        }
    }

    private boolean hasActiveCalls() {
        if (currentCall != null) {
            return true;
        }
        synchronized (callsLock) {
            return !currentCalls.isEmpty();
        }
    }

    private void cancelActiveCalls() {
        downloadCancelled = true;
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
        synchronized (callsLock) {
            for (Call call : currentCalls) {
                call.cancel();
            }
            currentCalls.clear();
        }
    }

    private void clearActiveCallReferences() {
        currentCall = null;
        synchronized (callsLock) {
            currentCalls.clear();
        }
        downloadRunning = false;
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
        rawClient = new OkHttpClient.Builder()
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
