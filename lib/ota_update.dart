import 'dart:io';
import 'android_ota_update.dart' as android;
import 'const.dart';
import 'desktop_ota_update.dart' as desktop;
export 'const.dart';

/// Main class for OTA update. It will automatically use the correct implementation based on the platform.
class OtaUpdate {
  /// Execute download and installation of the plugin.
  Stream<OtaEvent> execute(
    String url, {
    Map<String, String> headers = const <String, String>{},
    String? destinationFilename,
    String? sha256checksum,
  }) {
    if (Platform.isWindows || Platform.isLinux || Platform.isMacOS) {
      return desktop.DesktopOtaUpdate().execute(
        url,
        headers: headers,
        destinationFilename: destinationFilename,
        sha256checksum: sha256checksum,
      );
    } else if (Platform.isAndroid) {
      return android.AndroidOtaUpdate().execute(
        url,
        headers: headers,
        destinationFilename: destinationFilename,
        sha256checksum: sha256checksum,
      );
    }
    throw OtaUpdateException('not supported on this platform');
  }

  /// ANDROID ONLY: split-apk
  Future<String?> getAbi() async {
    if (Platform.isAndroid) {
      return android.AndroidOtaUpdate().getAbi();
    }
    throw OtaUpdateException('not supported on this platform');
  }

  /// ANDROID ONLY: Cancel the currently active ota download if there is one
  Future<void> cancel() async {
    if (Platform.isAndroid) {
      return android.AndroidOtaUpdate().cancel();
    }
    throw OtaUpdateException('not supported on this platform');
  }
}
