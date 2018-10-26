# ota_update

Flutter plugin implementing OTA update.\
On iOS it opens safari with specified ipa url. 
On Android it downloads the file (with progress reporting) and triggers app installation intent

## Usage

To use this plugin, add `ota_update` as a [dependency in your `pubspec.yaml` file](https://flutter.io/platform-plugins/).

## Example

``` dart
// IMPORT PACKAGE
import 'package:ota_update/ota_update.dart';

// RUN OTA UPDATE 
try {
  // INITIALIZE
  OtaUpdate otaUpdate = OtaUpdate();
  
  // START LISTENING FOR DOWNLOAD PROGRESS REPORTING EVENTS
  otaUpdate.progressChannelEvents.listen((String progress) {
    print('DOWNLOAD PROGRESS: $progress');
  });
  
  // EXECUTE OTA UPDATE WITH SPECIFIC URL
  bool result = await otaUpdate.execute('https://file.io/lHrabF');
} catch (e) {
  print('Failed to make OTA update. Details: $e');
}

```

## TODO
* restrict download to specific connection type (mobile, wifi)

## Contribution and Support

* Contributions are welcome!
* If you want to contribute code please create a PR
* If you find a bug or want a feature, please fill an issue