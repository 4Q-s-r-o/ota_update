import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ota_update/ota_update.dart';

void main() => runApp(MyApp());

/// example widget for ota_update plugin
class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  OtaEvent? currentEvent;

  @override
  void initState() {
    super.initState();
    tryOtaUpdate();
  }

  Future<void> tryOtaUpdate() async {
    try {
      print('ABI Platform: ${await OtaUpdate().getAbi()}');
      //LINK CONTAINS APK OF FLUTTER HELLO WORLD FROM FLUTTER SDK EXAMPLES
      OtaUpdate()
          .execute(
            'https://main.4q.sk/flutter_hello_world.apk',
            destinationFilename: 'flutter_hello_world.apk',
            //FOR NOW ANDROID ONLY - ABILITY TO VALIDATE CHECKSUM OF FILE:
            sha256checksum: '28cff8632531859634c4142ec704e86c5345244bddd6433b6160edaabc9b646a',
          )
          .listen((OtaEvent event) {
            setState(() => currentEvent = event);
          });
      // ignore: avoid_catches_without_on_clauses
    } catch (e) {
      print('Failed to make OTA update. Details: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (currentEvent == null) {
      return Container();
    }
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Plugin example app')),
        body: Center(
          child: Text('OTA status: ${currentEvent?.status} : ${currentEvent?.value} \n'),
        ),
      ),
    );
  }
}
