# 老 P7 有线 CarPlay：供 AI 制作固件匹配补丁的指南

[项目首页](../README.md) · [OP 下载](https://github.com/heavendoorx7/OP-CarPlay/releases/latest) · [应用设置](USAGE.md)

更新：2026-10-25。本文解决**原车 Android 不向应用暴露 iPhone，或暴露后不正常分派 USB 权限**的问题。目标是让车主的 AI 从该车原文件制作、临时加载和验证补丁，不要求复制本项目车主的系统文件。

OP 1.0 已包含应用侧有线 CarPlay。车机系统放行、Android USB 授权、iPhone 信任和 CarPlay 认证仍是四件事。本文不提供通用成品 `services.jar`，也不提供开启 ADB、获取 root 或永久刷写的办法。

## 先把这段交给你的 AI

```text
请依据本指南，为我有权维护的车机制作匹配当前固件的 iPhone USB 兼容包。

1. 先让我手填车机 IP 和 ADB 端口，核对实际连接、车型、API、固件、
   services.jar 哈希和挂载状态；确认已有 root 执行入口。不要扫描或猜端口。
2. 备份本车原文件，定位真实 DEX 方法。分别判断设备可见性拦截和 PTP 分流；
   没有对应拦截就不要改。不要拿别人的 JAR、偏移、UID 或哈希替换我的。
3. 按本文规则只修改必要分支，保留其他 USB 设备与用户授权流程。
   先离线生成候选和差异报告，完成 DEX/JAR 校验及匹配 API 的 ART 检查。
4. 给我完整的离线包：原件、候选、manifest、检查/临时加载/恢复脚本、
   Mac 与 Windows 入口。IP/端口手填，菜单操作，不自动安装或重启。
5. 加载采用经过核对的临时 bind mount；验证 system_server 的文件视图，
   重启 framework 后再验证真实 USB 发现、授权、重枚举和 CarPlay。
6. 失败按 manifest 撤销本次挂载，验证恢复目标和 framework。
   不改系统分区、启动项、SELinux 全局策略，不清除手机配对或其他应用默认项。

已有授权允许你完成本地分析与制作；涉及实车加载、framework 重启时，
按车主授权范围执行。若缺 root、恢复入口或固件证据，明确缺什么，不假装已打入。
```

以下内容是上述任务的实现规范。所有方法名和本车指纹都是**定位线索**；目标车辆的原始 DEX 才是最终依据。

## 1. 先判断卡在哪一层

```text
数据线 / USB Host / 内核枚举
    ↓
UsbHostManager：设备是否进入 Android USB 设备列表？       ← 补丁 A
    ↓
UsbProfileGroupSettingsManager：是否进入正常的应用分派？ ← 补丁 B（需要时）
    ↓
应用匹配、默认处理关系、当前 USB 地址的访问权
    ↓
OP：配对与认证 → 有线会话 → 音视频 / 麦克风 / 重连
```

| 现象 | 优先检查 | 不应先做什么 |
| --- | --- | --- |
| 只有充电，内核也没有 Apple 设备 | 数据线、数据口、Host 模式与内核枚举 | 修改 Java USB 权限代码 |
| 内核有 Apple 设备，Android USB 列表没有 | `UsbHostManager` 的厂商过滤 | 重装 OP、授予热点权限 |
| Android 看得到，应用接入事件／默认授权不执行 | OEM 存储/PTP 分流 | 给所有应用关闭 USB 权限确认 |
| OP 已能打开设备，但没有 CarPlay | iPhone 信任、认证配置、USB 传输、应用会话 | 继续扩大系统放行范围 |
| 首次正常，拔插或切换 USB 模式后失败 | 重枚举后的身份、地址授权、默认关联与应用恢复 | 把一次临时授权当成永久授权 |

充电正常、Linux `lsusb` 可见、Android 列表可见、OP 有权限、CarPlay 成功，分别是不同的验收结果。

## 2. 本车依据与可复用范围

已核对样本：老 P7 `E28`、骁龙 820A、Android 9 / API 28。

```text
固件：XMARTQ1ZHE28F5_V5.1.6.8807_20260521222514.1_REV01_USER_Release
原厂 services.jar SHA-256：
fb22e4fbd7c976f71155131f12a5c7a8887888c6db0b2f5d955f34459c6c12bf
```

本车已临时加载包含可见性和标准分流修改的候选；有匹配 API 的 ART 检查、重启后系统发出 USB 接入事件、应用获得设备及有线画面记录。早期还使用过限定授权辅助进程，因此这些记录不能证明“所有授权辅助均已排除后、所有手机均正常”。其他车辆应独立验证这一点。

可复用的是**定位方法、最小修改规则和验证流程**，不是二进制文件。固件名相同也必须核对文件；哈希不同应重新分析，不是把白名单中的哈希改成新值就算完成适配。

## 3. 从目标车辆取得最小输入

### 3.1 已有连接与 root

电脑 ADB 只用于传文件和执行已有权限的命令。普通 `adb shell` 的 `uid=2000(shell)` 不是 root；通常以 shell 身份运行的 Shizuku 也不能替代框架加载所需的 root。

本车的原厂命令页组件如下。已有 ADB 时，可由电脑尝试打开；若系统拒绝，不代表有通用免授权启动方法。

```bash
adb -s CAR_IP:PORT shell am start -n com.xiaopeng.devtools/.view.aftersales.CommonCmdActivity
```

在**实际执行加载脚本的环境**中运行 `id`，确认 `uid=0`。可用入口可能是车主已有授权的原厂命令页或 root SSH。打开页面本身不等于已确认执行身份；SSH 也不自动提供 root。

使用本车命令页时，电脑先把 AI 生成的核对脚本上传到本次工作目录，再由命令页执行其真实路径。例如本车接受 `ro.product.model; sh /data/local/tmp/usb_compat_demo/check-root.sh` 这类输入；目录和脚本名应由安装器生成，示例文件本身并不存在。先只运行身份核对脚本，确认结果中的 `uid=0`，再通过同一入口执行 `apply` 或 `rollback`。普通 `adb shell sh ...` 不会因为脚本名相同就提升权限；其他固件还需核对命令页是否允许这种输入。

### 3.2 读取与备份

将示例中的 `CAR_IP:PORT` 换成车主手填地址。`get-state` 必须为 `device`；连接命令正常退出不代表服务可用。

```bash
adb connect CAR_IP:PORT
adb -s CAR_IP:PORT get-state
adb -s CAR_IP:PORT shell id
adb -s CAR_IP:PORT shell getprop ro.product.model
adb -s CAR_IP:PORT shell getprop ro.build.version.sdk
adb -s CAR_IP:PORT shell getprop ro.build.display.id
adb -s CAR_IP:PORT shell sha256sum /system/framework/services.jar
adb -s CAR_IP:PORT shell cat /proc/self/mountinfo
adb -s CAR_IP:PORT shell dumpsys usb
adb -s CAR_IP:PORT pull /system/framework/services.jar services.original.jar
```

安装工具只在用户选择“核对”时读取必要状态，不后台采集。USB 状态可能含手机序列号，保留在本地；对外只分享脱敏结果。

AI 还需在 root 环境核对当前挂载、`system_server` 的挂载命名空间和实际文件视图。若当前路径已被补丁覆盖，拉到的不是原厂文件，应分别保存“当前加载版”和来源可确认的原件。不要把候选再次命名为原版备份。

按实际存在情况，同时保存同固件的 `services.odex`、`services.vdex`、`services.art`、权限和 SELinux 标签。这些是分析/恢复输入，**不是要求修改或删除它们**。本机与车端 SHA-256 必须一致。

如果 `services.jar` 没有可用 DEX，应使用匹配固件的 VDEX/ODEX 与依赖恢复分析输入；不能把任意工具输出当成可部署 JAR。无法可靠恢复时停在分析阶段。

## 4. 补丁 A：解除特定 iPhone 的可见性拦截

本车目标为：

```text
类：Lcom/android/server/usb/UsbHostManager;
方法：isBlackListedDevices(II)Z
参数：vendorId、productId
```

原厂判断的等价逻辑是 Apple VID `0x05ac`，且 PID 为 `0x12a8` 或 `0x12a0` 时拒绝。它在设备加入应用可见列表之前生效。

**修改规则：** 只解除已经确认的 Apple 手机拒绝条件。若目标方法仅包含这一条规则，返回 `false` 即可；若还含其他设备规则，只对这两个目标身份返回 `false`，其他情况执行原逻辑。

```java
// 等价意图；不是可直接覆盖任意固件的 Java 源文件。
if (vid == 0x05ac && (pid == 0x12a8 || pid == 0x12a0)) {
    return false;
}
return originalDecisionForOtherDevices(vid, pid);
```

只有确认原方法全部职责就是上述 Apple 条件时，smali 方法体才可简化为：

```smali
const/4 v0, 0x0
return v0
```

不要连带修改按总线地址过滤的 `isBlackListed(String)`，或按设备类别过滤的 `isBlackListed(int,int)`；它们与这里的 OEM 厂商条件不是同一个方法。不要放开内置设备、Hub 或所有 HID。

## 5. 补丁 B：把 Apple 手机交回正常 USB 应用分派

补丁 A 让设备可见，但不保证默认应用、授权与启动会执行。

本车 OEM 在以下入口先调用 `isUnsupportedDevice(device)`。当接口名为 `PTP`、`MTP` 或 `MIDI function` 时可能提前返回；因此手机进入了设备列表，却没走到 Android 正常应用匹配流程。

```text
类：Lcom/android/server/usb/UsbProfileGroupSettingsManager;

deviceAttachedForStorage(
    Landroid/hardware/usb/UsbDevice;Ljava/lang/String;[B)V

deviceAttachedForFixedHandlerForStorage(
    Landroid/hardware/usb/UsbDevice;Landroid/content/ComponentName;
    Ljava/lang/String;[B)V

回到的已有入口：deviceAttached(Landroid/hardware/usb/UsbDevice;)V
```

### 5.1 识别策略

只把正常 Apple 移动设备送回标准分派。下面的独立策略不包含 OP 包名、UID、签名或授权调用：

```java
package com.android.server.usb;

import android.hardware.usb.UsbDevice;

public final class IPhoneUsbDispatchPolicy {
    private IPhoneUsbDispatchPolicy() {}

    public static boolean useStandardDispatch(UsbDevice device) {
        return device != null
                && device.getVendorId() == 0x05ac
                && device.getProductId() >= 0x1290
                && device.getProductId() <= 0x12af;
    }
}
```

该 PID 范围来自 [usbmuxd 的设备定义](https://github.com/libimobiledevice/usbmuxd/blob/master/src/usb.h)，是识别依据，不是所有未来 iPhone 或恢复模式的支持保证；还可能覆盖同范围 iPad/iPod。不要扩大为所有 Apple 产品，Mac 恢复设备和键鼠等继续走原厂路径。

### 5.2 两个入口都作前置分流

在上述两个 `...ForStorage` 方法的最前面加入等价分支：

```java
if (IPhoneUsbDispatchPolicy.useStandardDispatch(device)) {
    this.deviceAttached(device);
    return;
}
// 非目标设备完整执行原方法体。
```

必须在 OEM 提前返回之前执行。固定处理程序入口对目标手机同样回到普通 `deviceAttached(device)`，让系统按用户默认处理关系选择应用；其他设备仍保留原固定处理程序。

不要把 `isUnsupportedDevice()` 全局改为 `false`，也不要直接在系统补丁里给 OP 或任意应用调用 `grantDevicePermission()`。目标是恢复系统流程，用户选择其他兼容应用时应由那款应用获得权限。标准流程还可能按原有配置先显示 MTP 通知，或使用系统应用的既有规则；不能为了强制弹选择框而删除这些分支。

### 5.3 默认关联与访问权不能混淆

| 状态 | 本车实现中的依据 | 重枚举后的含义 |
| --- | --- | --- |
| 应用声明支持设备 | APK 的 USB attach 元数据/过滤表 | 系统需要先匹配到应用 |
| 用户默认处理关系 | `DeviceFilter → 用户/包名` | 身份匹配时可再次授权并启动 |
| 本次 USB 访问权 | `USB 总线地址 → UID` | 地址变化后旧授权不自动代表新地址 |
| iPhone 信任/配对 | 手机与应用的配对记录 | 与 Android USB 默认应用不是同一授权 |

本车 `DeviceFilter` 比较 VID/PID、class/subclass/protocol、厂家名、产品名和序列号，不比较总线地址或 `getConfigurationCount()`。旧日志里的“4/6”是公开配置数量，不应称为活动配置编号。

因此需分别核对正常态、CarPlay 重枚举态的身份是否仍匹配；`hasDefaults(package)` 为真只说明存在某条关联，不能证明当前设备精确匹配。不要据此删除其他关联或声称以后不再需要确认。

## 6. AI 怎样制作目标固件的候选 JAR

推荐用 JADX 阅读逻辑，以 smali/baksmali 或 dexlib2 修改**原始 DEX**。JADX 导出的 Java 是阅读材料，不宜直接重编整个 `services.jar`，否则容易丢失厂商实现、隐藏 API 或初始化行为。

1. 枚举所有 `classes*.dex`，按类名＋完整方法签名定位；不要猜固定字节偏移。确认每个目标唯一存在，保存原始指令/规范化 smali 指纹。目标缺失或语义不同，应重新分析而非强行匹配。
2. 根据第 4、5 节确定实际需要的改动。两层都存在时，预期范围是三个 OEM 方法和一个新增策略类；仅有可见性问题时不必引入 B。
3. 修改分支时维护寄存器、参数位置、分支目标、try/catch 与 debug 地址；局部寄存器不能覆盖参数。静态/实例方法、宽参数和 `invoke-*/range` 的数量按真实签名计算，不照搬其他固件的寄存器编号。
4. 新策略类可先编译成 Java 8 字节码，再用 [D8](https://developer.android.com/tools/d8) 指定目标 `--min-api` 生成 DEX，合入包含调用方的 DEX。本车是 API 28；编译平台、DEX 格式和 hidden-API 支持也需核对。
5. 用原 JAR 的条目结构重封装，仅替换实际改动的 DEX。保留其余 DEX、资源和清单。有 JAR 签名条目时先分析真实校验链，不可静默破坏签名；不能拿自己的 APK 签名证书重签系统框架解决平台信任。
6. 校验 ZIP CRC、DEX 结构、SHA-1、Adler-32 和类/方法引用。原件若有内部校验异常，应记录并与确证原件哈希关联单独解释；不能全局关闭校验。候选必须具有有效校验。
7. 比较原件与候选：未改动的 ZIP 条目内容一致；未改动方法按指令语义、异常表等比对。DEX 重序列化可能改变索引、指令宽度和编码，不能把正常差异称为其余字节不变，也不能宽泛删掉分支或异常信息来掩盖差异。
8. 在匹配 API 的 ART 环境验证解析和目标方法；至少覆盖目标 PID 放行、非目标设备保持原规则、两个分流入口、无默认项保留原生确认/通知逻辑、不同默认应用分别获权。独立加载候选成功不等于整个 `system_server` 可启动，更不等于实车补丁已生效。

最终补丁不是把别人的 JAR 改个文件名。它应能从**本车备份＋修改脚本＋固定工具版本**重新生成，并给出修改前后每个目标方法和文件的指纹。

## 7. 离线安装包应包含什么

```text
iphone-usb-compat/
  README.md                 本车适用范围、步骤和恢复方法
  manifest.json             固件、文件/方法指纹、部署模式
  SHA256SUMS.txt
  original/                 本车来源确认的原件及所需配套文件
  candidate/services.jar    本车生成的候选
  builder/                  修改源码、工具版本及差异报告
  check                     只读核对连接、身份、哈希与挂载
  apply                     临时加载；不写系统分区
  rollback                  撤销本次修改并验证
  install-mac.command       Mac 菜单入口
  install-windows.cmd       Windows 菜单入口
  tools/                    对应平台的 ADB、依赖及许可
```

这是一份**目标车辆私用包的输出约定**，不是仓库已提供上述成品。含原厂 JAR 的生成包只在本车本地保存；公开分享修改源码/规则和脱敏报告即可。

`manifest.json` 至少记录：

```json
{
  "model": "<目标车实读>",
  "sdk": 28,
  "firmwareDisplayId": "<目标车实读>",
  "targetPath": "/system/framework/services.jar",
  "originalSha256": "<来源确认的原件64位散列>",
  "expectedLoadedBeforeSha256": "<安装前应加载的版本64位散列>",
  "candidateSha256": "<候选64位散列>",
  "rollbackToSha256": "<本次撤销后应恢复的版本64位散列>",
  "modifiedMethods": ["<实际类与完整签名、修改前后指纹>"],
  "addedClasses": ["<若有新增策略类>"],
  "deployment": "temporary-bind",
  "persistent": false
}
```

离线包应预置运行所需依赖及许可，运行时不再下载工具。

安装器应提供“核对／加载／查看结果／恢复／退出”，手填 IP 和端口。预期哈希不符、未知挂载、候选缺失、重复叠加或 root 不可用时停止。已经加载同一个候选时返回“已加载”，不要再次叠加，也不要后台不断重试。

## 8. 如何临时打入并判断真的生效

下面是供 AI 实现脚本的顺序，**不是可省略核对直接执行的通用替换命令**。脚本应从自身 manifest 读取参数，避免车主手工拼接系统路径。

1. **确认恢复入口。** root 执行与恢复方式在 framework 重启后仍需可用，或已确认完整中控 Linux 重启可清除无开机项的临时挂载。只有 `adb shell` 或一份文件备份，不代表已有系统无法启动时的救援能力。
2. **检查本次执行环境。** 读取 `id`、当前 `system_server` PID、`/proc/self/ns/mnt` 与目标进程挂载命名空间、当前 `mountinfo`。若命名空间不同，应根据该设备已有工具进入目标可见环境后重新核对，不能仅以 root 身份判断挂载可见。
3. **准备候选。** 先上传到临时工作目录，再由 root 复制到本次独立、不可由普通应用改写的目录，设为 root 持有和只读，复核 SHA-256。核对文件标签和读取条件，不能靠关闭 SELinux 全局执法来让它工作。
4. **临时挂载。** 在经核对的命名空间执行等价于 `mount -o bind 候选路径 /system/framework/services.jar` 的操作。原系统分区文件不写入。禁止 `remount,rw` 后直接覆盖原件。
5. **核对目标视图。** 读取 `/proc/<system_server_pid>/root/system/framework/services.jar` 的 SHA-256，必须等于候选；不等就撤销本次挂载并停止。只核对安装 shell 自己看到的路径不够。
6. **重新加载 framework。** 文件可见不代表已经加载的类发生变化。本车使用向已核对的 `system_server` PID 发送 `SIGTERM`，由系统重建 framework；这会中断中控应用。其他固件由 AI 先确认对应重启机制。不得使用宽泛杀进程、重置车控服务或删除全部 ART 缓存代替分析。
7. **有界等待恢复。** 重新确认 framework 启动、新 PID、USB 服务可用、进程视图哈希和目标分支行为。预优化 ODEX/VDEX/boot classpath 可能影响实际代码来源，遇到“文件变了但行为没变”应查加载路径，不自动删除系统编译产物。
8. **失败恢复。** 记录失败阶段，按下一节撤销；不循环重启、不自动持久化，不把连接暂断误报为成功。

### 挂载恢复脚本的对称规则

- 先核对当前候选哈希和本次挂载记录；未知状态停止，不能盲目循环 `umount`。
- 只卸载本次添加的这一层，再确认 manifest 的 `rollbackToSha256`。
- 重新加载 framework，使恢复后的代码生效，并确认 USB 服务与中控恢复。
- 若安装前已有一层兼容补丁，本次回退可能回到该层，并非原厂；“恢复上一状态”和“恢复原厂”必须分别说明。
- 在重启动作中，完整 Linux 重启会清除此类无开机项的运行时挂载；重开 OP、重启 ADB、单独重启 framework 均不等于清除。

本文方案不改启动分区、不添加开机任务，不提供关闭完整性校验或永久写入方法。永久化需要另行验证启动顺序、OTA、校验链和原厂恢复，不能把临时验证成功等同于永久适配完成。

## 9. 一次实车验证如何验清楚

在车辆停稳后，按下面顺序输出“通过／失败／未测”，不能只写一条“安装成功”：

1. **设备可见：** 内核与 Android `UsbManager` 均可发现目标手机，OP 能取得设备。此项验证 A。
2. **正常授权：** 无默认关联时沿用系统原生确认/通知路径及既有例外；选择默认处理应用后再次接入能正确匹配。核对 USB 接入事件由系统发出，而非安装器代点或手动拉起应用。
3. **重枚举与拔插：** 比较两态身份字段是否一致、当前地址权限是否有效；不要输出序列号。OP 在前台拔插、退出重开均检查恢复。
4. **排除辅助进程：** 若曾有 root 自动授权进程，先确认它的范围并可恢复，再在车主授权下停止对应进程作对照。进程仍在运行时，不宣称 B 已独立解决授权；不要直接杀所有 `app_process`。
5. **验证系统通用性（如有此目标）：** 选择另一款声明支持相同 USB 设备的测试应用，确认权限给该应用而不是仍给 OP；非目标设备保持原路径。未做此项就不要称“所有应用均兼容”。
6. **验证 CarPlay：** 使用 OP 1.0 或后续有线版本，数据线连接、iPhone 解锁并完成首次信任/CarPlay 允许、Android USB 授权，认证材料另行配置。检查画面、触控、音乐、Siri 和通话麦克风。
7. **验证撤销：** 恢复后核对预期哈希和 framework 状态；若测试完整中控重启，重新核对是否已恢复原挂载状态。休眠/唤醒不能代替完整重启对照。

原厂应用策略、Android USB 权限、iPhone 信任与 MFi 认证不能互相替代。USB 前置处理也不会自动授予热点或仪表投放权限。

## 10. 故障应怎样报告给 AI

| 错误 | 下一步需要的证据 |
| --- | --- |
| 连接不上 | 用户输入的 IP/端口、路由、端口与 ADB 实际状态；区分拒绝、超时、未授权和 offline |
| 找不到目标方法 | 目标 JAR/DEX 清单、实际类名和方法签名；不要把缺失直接当成已放行 |
| 文件哈希不匹配 | 确认是否 OTA、已有挂载或备份来源不同，重新分析该版本 |
| bind 成功但应用仍看不到 | `system_server` 的文件视图、framework 是否真正重载、实际类来源与 USB 事件 |
| 每次地址改变又要确认 | 两态 DeviceFilter 的脱敏比较、当前地址授权、默认关联是否精确匹配 |
| 已可见/已授权仍无画面 | OP 会话、iPhone 信任/认证及数据传输状态；不要继续扩大权限 |
| 只有原车导航没有仪表地图 | 单独检查仪表功能和通道授权，不修改 USB 系统规则 |

对外提供车型、API、固件、原件/候选哈希、修改方法列表、失败阶段与脱敏结果即可，不上传原厂 JAR、手机配对库、序列号、热点密码、私钥或原始个人日志。

## 参考入口

- [Android 9 UsbHostManager（AOSP 镜像）](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/usb/java/com/android/server/usb/UsbHostManager.java)：对照标准 Host 流程；OEM 附加限制仍以目标车为准。
- [Android 9 UsbProfileGroupSettingsManager（AOSP 镜像）](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java)：对照应用分派与默认关联。
- [usbmuxd usb.h](https://github.com/libimobiledevice/usbmuxd/blob/master/src/usb.h)：Apple 移动设备 PID 识别范围。
- [D8 官方说明](https://developer.android.com/tools/d8)：Java 字节码转换为目标 Android API 的 DEX。
