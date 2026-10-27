# 构建 OP Ultra

## 工具链

- JDK 25，项目使用 Gradle Wrapper。
- Android SDK Platform 37、Build Tools 36.0.0。
- Android NDK 28.2.13676358。
- Python 3 用于重建仪表原生辅助程序和公开树检查。

Android 目标车辆仍是 E28／API28。构建 SDK 版本不是车辆系统版本。

设置 `JAVA_HOME`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。SDK、NDK、Gradle 依赖必须先自行准备，缓存完整后可使用 `--offline`；源码不包含维护者机器上的工具目录。

## 仪表 native

公开源码包含 ARM 接收程序、行程订阅辅助及配套源文件，APK 的对应 assets 也随源码提供。修改 native 后必须重建，不能只修改 Android 类：

```bash
python3 scripts/build-instrument-native.py --ndk /path/to/android-ndk
```

该步骤在 macOS／Linux NDK 上运行，编译 32 位 ARM 仪表程序，并自动更新接收端和行程辅助的资产摘要。不是中控 Android JNI 库，也不连接车辆。中控 JNI 由 Gradle 的正常 NDK 流程编译。

Windows 可使用 WSL／Linux 完成此步骤，或在不修改 native 的情况下使用源码包中已配套的 assets 编译 Android 层。

## Android Debug

公开构建不注入个人认证材料：

```bash
unset OP_MFI_ASSETS_DIR
./gradlew -Pp7Profile=true -Pp7ViewAreas=true :mobile:assembleDebug
```

Windows：

```powershell
Remove-Item Env:OP_MFI_ASSETS_DIR -ErrorAction SilentlyContinue
.\gradlew.bat -Pp7Profile=true -Pp7ViewAreas=true :mobile:assembleDebug
```

当前包名为 `com.heavendoor.opultra.carplay`，版本名 `0.6.4-ultra-perf`、升级代码 20。自行签名不能覆盖维护者签名的包。

## 辅助服务的签名校验

源码中的 `OwnRootService`、`UltraDataService` 使用维护者公开签名指纹保护高权限入口。开发者使用自己的签名时，必须把这些 pin 一致更新成自己证书的 SHA256 指纹，保留包名、UID、接口和车型检查。签名证书的公开指纹不是私钥；不要索取或公开维护者私钥。

没有更新 pin 的自签构建可以编译，但高权限车况／投放服务会拒绝启动。不能通过删除检查来假装完成适配。

## Release

在本机配置自己的签名信息，勿写入源码：

```bash
export ANDROID_KEYSTORE_PATH=/path/to/your-release.jks
export ANDROID_KEYSTORE_PASSWORD='your-store-password'
export ANDROID_KEY_ALIAS='your-alias'
export ANDROID_KEY_PASSWORD='your-key-password'
unset OP_MFI_ASSETS_DIR
./gradlew -Pp7Profile=true -Pp7ViewAreas=true :mobile:assembleRelease
```

产物为 `mobile/build/outputs/apk/release/mobile-release.apk`。默认没有认证证书／私钥，需要用户配置自己的认证方式。公开图形与字体的实现见 [ASSETS](../ASSETS.md)。

## 检查

```bash
./gradlew -Pp7Profile=true -Pp7ViewAreas=true :common:testDebugUnitTest :shared:testDebugUnitTest :mobile:lintRelease
python3 scripts/check-public-tree.py
git diff --check
```

未配置私用认证资产时，相应私有材料测试按条件跳过。测试不代替原车接口、显示恢复、全部告警与长期使用的实车验收。该项目不提供构建、安装或故障排查支持。
