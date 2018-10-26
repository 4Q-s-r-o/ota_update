import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ota_update/ota_update.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  bool updateStatus;

  @override
  void initState() {
    super.initState();
    tryOtaUpdate();
  }

  Future<void> tryOtaUpdate() async {
    try {
      OtaUpdate otaUpdate = OtaUpdate();
      otaUpdate.progressChannelEvents.listen((String progress) {
        print('PROGRESS: $progress');
      });
      //LINK CONTAINES APK OF FLUTTER HELLO WORLD FROM FLUTTER SDK EXAMPLES
      updateStatus = await otaUpdate.execute('https://test1.4q.sk/turbo.apk');
    } catch (e) {
      print('Failed to make OTA update. Details: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Center(
          child: Text('OTA Update success: $updateStatus\n'),
        ),
      ),
    );
  }
}
