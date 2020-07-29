## 2.4.1
* Fixed null pointer when passing null as checksum or filename (chronm)
## 2.4.0
* Fixed a problem with not receiving error events from stream
* Added ability to validate SHA-256 checksum of downloaded file (android only)
* Added more documentation comments
## 2.3.0
* Added support of sending headers (by vasilich6107)
## 2.2.1
* Intent to install downloaded APK is now dispatched before dispatching ```INSTALLING``` event to flutter. This change should solve race condition if application programatically exits on ```INSTALLING``` event (APP could exit before intent was dispatched and installation would not start in that case). 
## 2.2.0
* Reporting of download errors
## 2.1.1
* Added flag FLAG_ACTIVITY_NEW_TASK to intent ACTION_INSTALL_PACKAGE in Android implementation
## 2.1.0
* Added support for custom filename
## 2.0.3
* Updated example to AndroidX
* Updated link to APK used in example
* Improved logging in Android implementation
* Fixed negative progress values in case of big APK
## 2.0.2
* Fixed division by zero error
## 2.0.1
* fixed crash in case of missing `progressSink`
## 2.0.0
* fixed crash on flutter 1.7 (by kmtong)
* added ability to override provider authority (by kmtong)
* changed default authority to be dynamic to support multiple apps with ``ota_update`` out of the box. Please note that this is **breaking change**. To migrate plase update your provider authority in ``AndroidManifest`` to ``${applicationId}.ota_update_provider``  

## 1.0.3
* fixed compatibility for older Android APIs (by zileyuan)

## 1.0.2
* ios syntax error fix (by tchunwei)

## 1.0.1
* migration of embeded example to AndroidX 
* Upgraded Gradle to latest version

## 1.0.0
* migration to AndroidX and boost compileVersion to 28 (by tchunwei)

## 0.1.3
* extended Android FileProvider to prevent possible conflicts with other plugins that also use FileProvider.

## 0.1.2

* fixing NullPointerException when onRequestPermissionsResult was triggered by different plugin than this one

## 0.1.1

* refactor to use streams only as communication channel
* documentation changes

## 0.1.0

* Initial release
