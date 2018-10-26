import 'dart:async';

import 'package:flutter/services.dart';

class OtaUpdate {
  static const MethodChannel _channel = const MethodChannel('sk.fourq.ota_update');
  static const EventChannel _progressChannel = const EventChannel('sk.fourq.ota_update_progress');
  Stream<String> _progressChannelEvents;

  Future<bool> execute(String url) async {
    return await _channel.invokeMethod('execute', {"url": url});
  }

  Stream<String> get progressChannelEvents {
    if (_progressChannelEvents == null) {
      _progressChannelEvents = _progressChannel
          .receiveBroadcastStream()
          .map((dynamic event) => event.toString());
    }
    return _progressChannelEvents;
  }
}
