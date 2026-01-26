package sk.fourq.otaupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

public class InstallResultReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_COMPLETE = "ACTION_INSTALL_COMPLETE";
    
    private static InstallResultCallback callback;

    public interface InstallResultCallback {
        void onInstallSuccess(String message);
        void onInstallFailure(String message);
    }

    public static void setCallback(InstallResultCallback cb) {
        InstallResultReceiver.callback = cb;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(OtaUpdatePlugin.TAG, "InstallResultReceiver received ping");
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );

        Log.d(OtaUpdatePlugin.TAG, "Status: "+status);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmationIntent = null;
            confirmationIntent = getConfirmationIntent(intent);
            if (confirmationIntent != null) {
                context.startActivity(confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            callback.onInstallSuccess(msg);
        } else {
            callback.onInstallFailure(msg);
        }
    }

    @SuppressWarnings("deprecation")
    private Intent getConfirmationIntent(Intent intent) {
        Intent confirmationIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            confirmationIntent = intent.getExtras().getParcelable(Intent.EXTRA_INTENT, Intent.class);
        } else {
            confirmationIntent = intent.getExtras().getParcelable(Intent.EXTRA_INTENT);
        }
        return confirmationIntent;
    }
}
