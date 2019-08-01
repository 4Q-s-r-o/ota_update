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
