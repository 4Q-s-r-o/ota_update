import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

/// Flutter plugin implementing OTA update
/// On Android it downloads the file (with progress reporting) and triggers app installation intent.
/// On iOS it opens safari with specified ipa url. (not yet functioning)
class OtaUpdate {
  static const EventChannel _progressChannel = const EventChannel('sk.fourq.ota_update');
  Stream<OtaEvent> _progressStream;

  /// Execute download and instalation of the plugin.
  /// Download progress and all success or error states are publish in stream as OtaEvent
  Stream<OtaEvent> execute(
    String url, {
    Map<String, String> headers = const {},
    String androidProviderAuthority,
    String destinationFilename,
    String sha256checksum,
  }) {
    if (destinationFilename != null && destinationFilename.contains("/")) {
      throw OtaUpdateException('Invalid filename $destinationFilename');
    }
    final StreamController<OtaEvent> controller = StreamController<OtaEvent>.broadcast();
    if (_progressStream == null) {
      _progressChannel.receiveBroadcastStream(
        {
          "url": url,
          "androidProviderAuthority": androidProviderAuthority,
          "filename": destinationFilename,
          "checksum": sha256checksum,
          "headers": jsonEncode(headers)
        },
      ).listen((dynamic event) {
        final OtaEvent otaEvent = _toOtaEvent(event.cast<String>());
        controller.add(otaEvent);
        if (otaEvent.status != OtaStatus.DOWNLOADING) {
          controller.close();
        }
      }).onError((Object error) {
        if (error is PlatformException) {
          controller.add(_toOtaEvent(<String>[error.code, error.message]));
        }
      });
      _progressStream = controller.stream;
    }
    return _progressStream;
  }

  OtaEvent _toOtaEvent(List<String> event) {
    return OtaEvent()
      ..status = OtaStatus.values[int.parse(event[0])]
      ..value = event[1];
  }
}

///Event describing current status
class OtaEvent {
  /// Current status as enum value
  OtaStatus status;

  /// Additional status info e.g. percents downloaded or error message (can be null)
  String value;
}

/// Enum values describing states
enum OtaStatus {
  /// FILE IS BEING DOWNLOADED
  DOWNLOADING,

  /// INSTALLATION HAS BEEN TRIGGERED
  INSTALLING,

  /// DOWNLOAD IS ALREADY RUNNING
  ALREADY_RUNNING_ERROR,

  /// COULD NOT CONTINUE BECAUSE OF MISSING PERMISSIONS
  PERMISSION_NOT_GRANTED_ERROR,

  /// UNKWNON ERROR. SEE VALUE FOR MORE INFROMATION
  INTERNAL_ERROR,

  /// FILE COULD NOT BE DOWNLOADED. SEE VALUE FOR MORE INFORMATION
  DOWNLOAD_ERROR,

  /// CHECKSUM VERIFICATION FAILED. MOSTLY THIS IS DUE INCORRECT OR CORRUPTED FILE
  /// THIS IS ALSO RETURNED IF PLUGIN WAS UNABLE TO CALCULATE SHA 256 HASH OF DOWNLOADED FILE
  /// SEE VALUE FOR MORE INFORMATION
  CHECKSUM_ERROR
}

/// EXCEPTION FOR QUICK IDENTIFICATION OF ERRORS THROWN FROM THIS PLUGIN.
/// THIS IS USED CURRENTLY ONLY FOR INPUT PARAMETER VERIFICATION ERRORS. IF THERE IS PROBLEM WITH
/// DOWNLOADING THE FILE OR RUNNING UPDATE OtaEvent WITH ONE OF THE _ERROR STATUSES IS USED
class OtaUpdateException implements Exception {
  /// ERROR MESSAGE
  final message;

  /// DEFAULT CONSTRUCTOR
  OtaUpdateException(this.message);

  @override
  String toString() {
    if (message == null) return "Exception";
    return "Exception: $message";
  }
}
