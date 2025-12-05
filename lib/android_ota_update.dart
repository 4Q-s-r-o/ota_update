import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'const.dart';

/// For Android
class AndroidOtaUpdate {
  static const EventChannel _progressChannel = EventChannel('sk.fourq.ota_update/stream');
  static const MethodChannel _methodChannel = MethodChannel('sk.fourq.ota_update/method');
  Stream<OtaEvent>? _progressStream;

  /// Get most preffered abi platform. Use if you are using
  /// split-apk
  Future<String?> getAbi() async {
    return _methodChannel.invokeMethod<String>('getAbi');
  }

  /// Cancel the currently active ota download if there is one
  Future<void> cancel() async {
    return _methodChannel.invokeMethod<void>('cancel');
  }

  /// Execute download and installation of the plugin.
  /// Download progress and all success or error states are publish in stream as OtaEvent
  Stream<OtaEvent> execute(
    String url, {
    Map<String, String> headers = const <String, String>{},
    String? androidProviderAuthority,
    String? destinationFilename,
    String? sha256checksum,
  }) {
    if (destinationFilename != null && destinationFilename.contains('/')) {
      throw OtaUpdateException('Invalid filename $destinationFilename');
    }
    final StreamController<OtaEvent> controller = StreamController<OtaEvent>.broadcast();
    if (_progressStream == null) {
      _progressChannel
          .receiveBroadcastStream(<dynamic, dynamic>{
            'url': url,
            'androidProviderAuthority': androidProviderAuthority,
            'filename': destinationFilename,
            'checksum': sha256checksum,
            'headers': jsonEncode(headers),
          })
          .listen((dynamic event) {
            final OtaEvent otaEvent = _toOtaEvent(event.cast<String>());
            controller.add(otaEvent);
            if (otaEvent.status != OtaStatus.DOWNLOADING) {
              controller.close();
            }
          })
          .onError((Object error) {
            if (error is PlatformException) {
              controller.add(_toOtaEvent(<String?>[error.code, error.message]));
            }
          });
      _progressStream = controller.stream;
    }
    return _progressStream!;
  }

  OtaEvent _toOtaEvent(List<String?> event) {
    return OtaEvent(OtaStatus.values[int.parse(event[0]!)], event[1]);
  }
}
