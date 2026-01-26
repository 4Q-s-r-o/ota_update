package sk.fourq.otaupdate;

import android.content.pm.PackageInstaller;
import android.util.Log;

public class InstallSessionCallback extends PackageInstaller.SessionCallback {
    private final ProgressCallback onInstallProgress;

    public interface ProgressCallback {
        void onProgress(float progress);
    }

    public InstallSessionCallback(ProgressCallback onInstallProgress) {
        this.onInstallProgress = onInstallProgress;
    }

    @Override
    public void onActiveChanged(int sessionId, boolean active) {

    }

    @Override
    public void onBadgingChanged(int sessionId) {

    }

    @Override
    public void onCreated(int sessionId) {

    }

    @Override
    public void onFinished(int sessionId, boolean success) {
        Log.d("InstallSessionCallback", "Install session finished " + success);
        // DOES NOT PROPAGATE TO PLUGIN AS IT SHOULD BE HANDLED BY InstallResultReceiver
    }

    @Override
    public void onProgressChanged(int sessionId, float progress) {
        Log.d("InstallSessionCallback", "Install session callback progress " + progress);
        if (onInstallProgress != null) {
            onInstallProgress.onProgress(progress);
        } else {
            Log.w("InstallSessionCallback", "Cannot report progress - onInstallProgress callback is null");
        }
    }
}
