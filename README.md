# ota_update

[![pub package](https://img.shields.io/pub/v/signature.svg)](https://pub.dartlang.org/packages/ota_update)

Flutter plugin implementing OTA update.\
On Android it downloads the file (with progress reporting) and triggers app installation intent.\
On iOS it opens safari with specified ipa url. (only briefly tested)

## Usage

To use this plugin, add `ota_update` as a [dependency in your `pubspec.yaml` file](https://flutter.io/platform-plugins/).

## Example

``` dart
// IMPORT PACKAGE
import 'package:ota_update/ota_update.dart';

// RUN OTA UPDATE 
try {
  // START LISTENING FOR DOWNLOAD PROGRESS REPORTING EVENTS
  try {
    //LINK CONTAINS APK OF FLUTTER HELLO WORLD FROM FLUTTER SDK EXAMPLES
    OtaUpdate().execute('https://test1.4q.sk/flutter_hello_world.apk').listen(
      (OtaEvent event) {
        print('EVENT: ${event.status} : ${event.value}');
      },
    );
  } catch (e) {
    print('Failed to make OTA update. Details: $e');
  }
```
### Android
Add following provider referrence to AndroidManifest.xml
```xml
<provider
    android:name="sk.fourq.otaupdate.OtaUpdateFileProvider"
    android:authorities="sk.fourq.ota_update.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/filepaths" />
</provider>
```

## Statuses
* DOWNLOADING: 
    * status for events during downloading phase
    * event value is download progress percentage
* INSTALLING: 
    * event status that is sent just before triggering installation intent
    * event value is null
* ALREADY_RUNNING_ERROR: 
    * sent when 'execute' method is called before previous run finished
    * event value is null
* PERMISSION_NOT_GRANTED_ERROR: 
    * sent when user refused to grant required permissions
    * event value is null.
* INTERNAL_ERROR: 
    * sent in all other error cases
    * event value is underlying error message

## TODO
* restrict download to specific connection type (mobile, wifi)

## Contribution and Support
* Contributions are welcome!
* If you want to contribute code please create a PR
* If you find a bug or want a feature, please fill an issue