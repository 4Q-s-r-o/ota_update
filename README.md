# ota_update

[![pub package](https://img.shields.io/pub/v/ota_update.svg)](https://pub.dartlang.org/packages/ota_update)

Flutter plugin implementing OTA update.\
On Android it downloads the file (with progress reporting) and triggers app installation intent.\
On iOS it opens safari with specified ipa url. (not yet functioning)

## Usage

To use this plugin, add `ota_update` as a [dependency in your `pubspec.yaml` file](https://flutter.io/platform-plugins/).

## Example

``` dart
// IMPORT PACKAGE
import 'package:ota_update/ota_update.dart';

  // RUN OTA UPDATE 
  // START LISTENING FOR DOWNLOAD PROGRESS REPORTING EVENTS
  try {
      //LINK CONTAINS APK OF FLUTTER HELLO WORLD FROM FLUTTER SDK EXAMPLES
      OtaUpdate()
          .execute(
        'https://internal1.4q.sk/flutter_hello_world.apk',
        // OPTIONAL
        destinationFilename: 'flutter_hello_world.apk',
        //OPTIONAL, ANDROID ONLY - ABILITY TO VALIDATE CHECKSUM OF FILE:
        sha256checksum: "d6da28451a1e15cf7a75f2c3f151befad3b80ad0bb232ab15c20897e54f21478",
      ).listen(
        (OtaEvent event) {
          setState(() => currentEvent = event);
        },
      );
  } catch (e) {
      print('Failed to make OTA update. Details: $e');
  }
```
### Android
Add following provider referrence to AndroidManifest.xml inside ```<application>``` node.
```xml
<provider
    android:name="sk.fourq.otaupdate.OtaUpdateFileProvider"
    android:authorities="${applicationId}.ota_update_provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/filepaths" />
</provider>
```
See [AndroidManifest.xml](example/android/app/src/main/AndroidManifest.xml) in example

Also, create the file ```android/src/main/res/xml/filepaths.xml``` with following contents. 
This will allow plugin to access the downloads folder to start the update.

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="external_download" path="Download"/>
</paths>
```

See [filepaths.xml](example/android/app/src/main/res/xml/filepaths.xml) in example

#### Note
Google Play Protect may in some cases cause problems with installation. 

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
* DOWNLOAD_ERROR
    * sent when download crashed.
* CHECKSUM_ERROR (android only)
    * sent if calculated SHA-256 checksum does not match provided (optional) value 
    * sent if checksum value should be verified, but checksum calculation failed  
* INTERNAL_ERROR: 
    * sent in all other error cases
    * event value is underlying error message

## TODO
* restrict download to specific connection type (mobile, wifi)

## Contribution and Support
* Contributions are welcome!
* If you find a bug or want a feature, please fill an issue
* If you want to contribute code please create a PR

### PR guidelines

* Please do not change version in pubspec.yaml nor update CHANGELOG.md - this will be done before release of new version as release may contain multiple fixes and/or features. This will prevent some potential (yet simple) merge  conflicts.
