import 'dart:async';

import 'package:flutter/services.dart';

class OtaUpdate {
  static const EventChannel _progressChannel =
      const EventChannel('sk.fourq.ota_update');
  Stream<OtaEvent> _progressStream;

  Stream<OtaEvent> execute(String url) {
    if (_progressStream == null) {
      _progressStream = _progressChannel.receiveBroadcastStream(
        {"url": url},
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
  INTERNAL_ERROR
}
