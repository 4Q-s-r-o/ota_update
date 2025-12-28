# ota_update

[![pub package](https://img.shields.io/pub/v/ota_update.svg)](https://pub.dartlang.org/packages/ota_update)

实现 OTA 更新的 Flutter 插件。

- 在 Android 上，它会下载文件（带进度条）并触发应用安装意图
- 在 iOS 上，它会用指定的 ipa 链接打开 Safari（未实现）

[English](./README.md) | 简体中文

## 更新日志

### 更新至 7.0.0+

由于我们计划在所有插件中使用更现代的 Java 特性，因此我们选择了对 Java 特性进行脱糖处理。因此，你需要启用脱糖功能。越来越多的包已经要求这样做，所以很有可能你已经启用了它。

如果没有启用，以下是 [操作方法](https://stackoverflow.com/questions/79158012/dependency-flutter-local-notifications-requires-core-library-desugaring-to-be).
`android/app/build.gradle`文件：

```gradle
android {
    defaultConfig {
        // 当 minSdkVersion 设置为 20 或更低时需要
        multiDexEnabled true
    }

    compileOptions {
        // 启用对新语言 API 支持的标志
        coreLibraryDesugaringEnabled true
        // 将 Java 兼容性设置为 Java 8
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    // 对于 AGP 7.4+
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.3'
    // 对于 AGP 7.3
    // coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:1.2.3'
    // 对于 AGP 4.0 到 7.2
    // coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:1.1.9'
}
```

### 更新至 5.0.0+

此更新移除了对 Flutter Android 嵌入 v1 的遗留支持。现在应该不会有人受到影响。如果在极少数情况下你仍在使用旧的嵌入方式，请考虑升级到 v2

### 更新至 4.0.0+

此更新解决了许多因使用 Android 下载管理器并保存到外部下载文件夹而产生的问题。

重要变更：

- 不再使用下载管理器下载文件
  - 由于我们不使用下载管理器，因此没有关于进度的默认系统通知。但是，由于更新事件会发布到 Flutter 代码中，因此如果需要，你可以自己实现通知。
- 文件保存在内部目录中，这消除了使用 SAF 的需要，并防止使用此包的多个应用程序可能覆盖 apk

升级版本号后，你需要将文件 `android/src/main/res/xml/filepaths.xml` 的内容替换为以下内容。

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path name="internal_apk_storage" path="ota_update/"/>
</paths>
```

## 使用

要使用此插件，请将 `ota_update` 作为 [`pubspec.yaml`文件中的依赖项](https://flutter.io/platform-plugins/)加入

## 示例

``` dart
// 导入包
import 'package:ota_update/ota_update.dart';

// 运行 OTA 更新
// 开始监听下载进度报告事件
try {
    // 链接包含来自 Flutter SDK 示例的 Flutter Hello World 的 APK
    OtaUpdate()
        .execute(
      'https://internal1.4q.sk/flutter_hello_world.apk',
      // 可选
      destinationFilename: 'flutter_hello_world.apk',
      // 可选，仅 Android - 能够验证文件的校验和：
      sha256checksum: "d6da28451a1e15cf7a75f2c3f151befad3b80ad0bb232ab15c20897e54f21478",
    ).listen(
      (OtaEvent event) {
        setState(() => currentEvent = event);
      },
    );
} catch (e) {
    print('OTA 更新失败。详情：$e');
}
```

### Android

向 `AndroidManifest.xml` 添加权限

```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
```

#### PackageInstaller 使用方法

从 `7.1.0` 版本开始，该插件支持使用 `PackageInstaller` 方法安装 APK。此方法是**系统应用**的默认方法，因为它允许静默安装（下一节有更多介绍）。

对于**普通应用**，这包括通过插件报告安装进度以及安装结果后的通知（但是，安装成功后，操作系统可能会重启应用）。但请注意，操作系统不再提供带有安装进度的良好 UI，因此它更适合那些想要自己处理进度通知的应用。

因此， `PackageInstaller` 方法不是默认启用的，你需要通过向 `execute` 方法传递 `usePackageInstaller: true` 选择启用

请注意，如果你想确保进度报告按预期工作，你需要在`AndroidManifest.xml`的`<application>`节点内添加以下接收器引用

```xml
<receiver android:name="sk.fourq.otaupdate.InstallResultReceiver"  android:exported="false">
    <intent-filter>
        <action android:name="${applicationId}.ACTION_INSTALL_COMPLETE"/>
    </intent-filter>
</receiver>
```

#### 静默安装（仅系统应用）

此插件自动支持系统应用的静默安装，无需用户交互。该插件包含 `INSTALL_PACKAGES` 权限，可启用此功能。

**工作原理：**

- [x] **普通应用** （应用商店、侧载）：向用户显示标准安装提示
- [x] **系统应用** （预安装在 `/system/` 中或使用平台证书签名）：无需用户交互即可静默安装

**无需额外配置！** 该插件会自动检测你的应用是否为系统应用，并使用相应的安装方法。对于普通应用，Android 会无害地忽略 INSTALL_PACKAGES 权限，并使用正常的安装流程。

适用于：

- IoT 设备
- Kiosk 软件
- 企业 / MDM 部署
- 自定义 Android ROM 发行版

在 `AndroidManifest.xml` 的 `<application>` 节点内添加以下提供程序引用

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

在 `AndroidManifest.xml` 的 `<application>` 节点内添加以下接收器引用

```xml
<receiver android:name="sk.fourq.otaupdate.InstallResultReceiver"  android:exported="false">
    <intent-filter>
        <action android:name="${applicationId}.ACTION_INSTALL_COMPLETE"/>
    </intent-filter>
</receiver>
```

使用静默安装或 `PackageInstaller` 方法时，这允许插件获取安装结果。
参见示例中的 `AndroidManifest.xml`
此外，创建文件 `android/src/main/res/xml/filepaths.xml` 并包含以下内容。这将允许插件访问下载文件夹以启动更新。

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
<files-path name="internal_apk_storage" path="ota_update/"/>
</paths>
```

参见示例中的 [filepaths.xml](example/android/app/src/main/res/xml/filepaths.xml)

### 非 https 流量

出于安全原因，Android 下载管理器默认禁用明文流量。要[允许它](https://stackoverflow.com/questions/51770323/how-to-solve-android-p-downloadmanager-stopping-with-cleartext-http-traffic-to)，你需要创建文件 `res/xml/network_security_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

并在应用程序标签中的 `AndroidManifest.xml` 中引用它

```xml
android:networkSecurityConfig="@xml/network_security_config"
```

#### 基本工作流程

由于此插件仅处理下载和安装，因此仍有一些步骤超出我们的范围需要完成。这主要是为了允许不同的实现场景

1. 更新托管。你需要有一个提供安装文件的服务器。
2. 检查更新。你需要有一种方法来检查是否有可用的更新。
3. 身份验证。如果你的更新服务器需要登录，你可能需要在下载 APK 之前获取授权令牌（或任何其他用于身份验证的东西）。
4. APK 的下载和安装。这是插件为你提供的部分。

#### 使用 sha256checksum

此包支持文件完整性的 sha256 校验和验证。这使我们能够检测文件在传输过程中是否已损坏
要使用此功能，你的更新服务器应向你提供 APK 的 sha256 校验和，并且你需要在检查更新时获取此值。当你使用此参数运行 `execute` 方法时，插件将从下载的文件计算 sha256 值，并与提供的值进行比较。只有当两个值匹配时，更新才会继续，否则会抛出错误。

#### 使用拆分 apks（实验性）

插件现在允许你获取 android ABI 平台。如果你正在为每个 abi 构建多个 apks（使用 flutter 参数 --split-per-abi），你现在可以利用更小的 APK。你可以获取系统架构并选择下载更小的 APK。

#### 注意事项

- 在某些情况下，Google Play Protect 可能会导致安装问题。
- 对于系统应用，该插件支持无需用户交互的静默安装。普通应用将继续显示标准安装提示

## 状态

- DOWNLOADING:
  - 下载阶段事件的状态
  - 事件值是下载进度百分比
- INSTALLING:
  - 触发安装意图之前发送的事件状态
  - 第一次调用时事件值始终为 null
  - 使用 `PackageInstaller` 方法时，事件值是安装进度百分比。
- INSTALLATION_DONE:
  - 仅在使用 `PackageInstaller` 方法时发送
  - 表示更新已成功安装
- ALREADY_RUNNING_ERROR:
  - 当在先前运行完成之前调用 `execute` 方法时发送
  - 事件值为 null
- INSTALLATION_ERROR:
  - 仅在使用 `PackageInstaller` 方法时发送
  - 表示安装导致错误
  - 某些设备可能在用户点击取消时触发此事件，但并非总是如此
- PERMISSION_NOT_GRANTED_ERROR:
  - 当用户拒绝授予所需权限时发送
  - 事件值为 null
- DOWNLOAD_ERROR
  - 下载崩溃时发送
- CHECKSUM_ERROR (仅Android)
  - 如果计算的 SHA-256 校验和与提供的（可选）值不匹配，则发送
  - 如果应该验证校验和值，但校验和计算失败，则发送
- INTERNAL_ERROR:
  - 在所有其他错误情况下发送
  - 事件值是潜在的错误消息
- CANCELED:
  - 当使用 `OtaUpdate ().cancel ()` 取消下载时发送

## TODO

- 限制下载到特定的连接类型（移动网络、wifi）

## 贡献&支持

- 欢迎贡献！
- 如果你发现错误或想要某个功能，请填写issue
- 如果你想贡献代码，请创建 PR

### PR 指南

- 请不要更改 pubspec.yaml 中的版本，也不要更新 CHANGELOG.md - 这将在新版本发布前完成，因为发布可能包含多个修复/功能。这将防止一些潜在的（但简单的）合并冲突。
