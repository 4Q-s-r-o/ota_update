import 'dart:async';

import 'package:flutter/services.dart';

class OtaUpdate {
  static const EventChannel _progressChannel = const EventChannel('sk.fourq.ota_update');
  Stream<OtaEvent> _progressStream;

  Stream<OtaEvent> execute(String url,
      {String androidProviderAuthority, String destinationFilename}) {
    if (destinationFilename != null && destinationFilename.contains("/")) {
      throw OtaUpdateException('Invalid filename $destinationFilename');
    }
    if (_progressStream == null) {
      _progressStream = _progressChannel.receiveBroadcastStream(
        {
          "url": url,
          "androidProviderAuthority": androidProviderAuthority,
          "filename": destinationFilename,
        },
      ).map(
        (dynamic event) => _toOtaEvent(event.cast<String>()),
      );
    }
    return _progressStream;
  }

  OtaEvent _toOtaEvent(List<String> event) {
    return OtaEvent()
      ..status = OtaStatus.values[int.parse(event[0])]
      ..value = event[1];
  }
}

class OtaEvent {
  OtaStatus status;
  String value;
}

enum OtaStatus {
  DOWNLOADING,
  INSTALLING,
  ALREADY_RUNNING_ERROR,
  PERMISSION_NOT_GRANTED_ERROR,
  INTERNAL_ERROR,
  DOWNLOAD_ERROR
}

class OtaUpdateException implements Exception {
  final message;

  OtaUpdateException(this.message);

  String toString() {
    if (message == null) return "Exception";
    return "Exception: $message";
  }
}
