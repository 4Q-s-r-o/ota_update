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
  }

  Future<void> tryOtaUpdate(bool packageInstaller) async {
    try {
      // print('ABI Platform: ${await OtaUpdate().getAbi()}');
      //LINK CONTAINS APK OF FLUTTER HELLO WORLD FROM FLUTTER SDK EXAMPLES
      OtaUpdate()
          .execute(
            'https://main.4q.sk/flutter_hello_world.apk',
            destinationFilename: 'flutter_hello_world.apk',
            sha256checksum: '28cff8632531859634c4142ec704e86c5345244bddd6433b6160edaabc9b646a',
          )
          .listen((OtaEvent event) {
            setState(() => currentEvent = event);
          });
      // windows and linux example. Link contains tar.gz of flutter hello world example from flutter sdk examples
      // OtaUpdate()
      //     .execute(
      //       'https://main.4q.sk/flutter_hello_world.tar.gz',
      //       destinationFilename: 'flutter_hello_world.tar.gz',
      //       sha256checksum: '28cff8632531859634c4142ec704e86c5345244bddd6433b6160edaabc9b646a',
      //     )
      //     .listen((OtaEvent event) {
      //       setState(() => currentEvent = event);
      //     });
      // ignore: avoid_catches_without_on_clauses
    } catch (e) {
      print('Failed to make OTA update. Details: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('OTA update example app')),
        body: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Center(
            child: Column(
              children: <Widget>[
                const Text(
                  'ota_update has two basic modes in which it can download apk. '
                  'The newer and prefered method is using PackageInstaller. '
                  'This allows us to report INSTALL result and progress, '
                  'hovewer presenting progress to user is left to integrating app. '
                  'This is actually good for app that wish to present progress in their own UI. '
                  'Additionally, in some cases this method may result in silent update (system apps).',
                ),
                Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: ElevatedButton(
                    onPressed: () => tryOtaUpdate(true),
                    child: const Text('Try using packageInstaller'),
                  ),
                ),
                const Text(
                  'Legacy mode uses Intent.ACTION_INSTALL_PACKAGE to trigger system installation UI. '
                  'As simple it is, the operating system will never provide result of the installation ',
                ),
                Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: ElevatedButton(
                    onPressed: () => tryOtaUpdate(false),
                    child: const Text('Try using legacy method'),
                  ),
                ),
                Text('OTA status: ${currentEvent?.status} : ${currentEvent?.value} \n'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
