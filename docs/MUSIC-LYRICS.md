# 歌词优先音乐卡

本分支首先调整 Android 合成层的音乐卡，不修改仪表程序、传输协议、车型检查、签名校验或固件校验。

## 布局

- 卡片仍是 480×512，保留现有玻璃背景、封面色调光晕、日夜配色和左右交换。
- 封面从 300×300 缩为顶部 88×88，歌名和歌手移到右侧，各自单行省略；歌名 26 px，歌手 19 px。
- 当前歌词为 28 px、最多两行；上下邻句为 22 px、各一行省略。歌词始终使用现有 `sans-serif-medium` / `Typeface.NORMAL`，不是粗体；未唱正文使用 70% ink 透明度。Android `StaticLayout` 处理换行，正文区域裁切，长句不会挤占标题或进度条。
- 去掉多余的“歌词”小标签，保留正文间距、原有换句位置与动效。`UltraMusicCardStyle` 是生产绘制和测试预览共用的唯一几何/字体参数来源，预览不得另设一套字号。
- 底部保留原有 3 px 进度条和时间。没有封面时绘制小唱片占位，不改变正文空间。

没有加入独立红绿灯/倒计时 UI 或对应 SDK。手机高德在地图视频中自带的信息原样保留，不识别、遮挡或重画它。

## 真实数据与尚未接通的部分

**当前 CarPlay 桥接只提供歌名、歌手、封面和播放进度，没有可用歌词源。生产界面因此显示“暂无歌词 / 当前来源未提供歌词”，不会出现测试歌词或伪造逐字时序。** 没有媒体元数据时显示“等待音乐”。本次没有接入小鹏音乐、网络歌词服务、音频识别或任何新的读取权限。

`UltraLyricsState` 与 `UltraMusicCardRenderer` 已支持未来可信来源提供的时间戳数据：

1. 在请求开始时保存 `dashboard.lyricsGeneration()`。
2. 授权来源返回数据后，调用 `dashboard.lyrics(generation, lines)`。返回 `false` 表示代次过期或数据无效；原有效数据不会被部分覆盖。
3. `Line(startMs, endMs, text)` 只有行时间：只整句强调，不按时长平均推算逐字。
4. `Line(startMs, endMs, text, words)` 带 `Word(startMs, endMs, startChar, endChar)`：只按明确给出的词/字时间渐进高亮。字符范围使用原文 UTF-16 偏移，不能切开代理对。
5. 前奏、间奏、歌词结束都有明确状态；空数据或 `null` 清空当前歌词。

当前媒体身份只能根据歌名和歌手建立本地代次；这不是持久的歌曲 ID。同名同歌手新曲目或会话切换，未来适配器必须先调用 `dashboard.resetLyrics()`，再获取新代次开始请求，或提供更强的歌曲/会话身份。不能仅靠标题匹配第三方响应。现有 CarPlay 空媒体或 Ultra 引擎停止会清空已知代次；超过 750 ms 的进度跳转会直接定位，暂停时的进度变化也会直接定位，不回放跨过的歌词动画。播放中的小幅进度修正按时钟漂移处理；未来能识别精确 seek 事件的数据源应单独通知模型，不能据此声称任意小幅 seek 都已精准区分。

源数据有界：最多 512 行、每行 512 个 UTF-16 字符、总计 65,536 个字符、每行 256 个词/字片段、时间范围 24 小时。重叠、逆序、无效字符范围或过期响应整批拒绝。模型不保存到磁盘，不把歌词写入诊断导出。

## 动效与百兆预算

- 正常歌词画面以 200 ms 采样（5 Hz）；换句上滚使用 520 ms 的平滑插值，仅短窗口按 100 ms 采样（最多 10 Hz）。实际显示时间受现有输出采样和 ACK 背压影响，不是 60 fps 动画或恒定帧率承诺。
- 仅具有明确字/词时间戳的当前句进行逐字高亮。相邻句只是位置与强调程度过渡，不跑横向字幕。
- 词句时钟随暂停冻结；暂停不停止地图、车况或投放链路。seek、换曲、迟到歌词、隐藏后返回直接定位，不累积过期动效。
- 歌词可视变化加入 `captureRevision()`，短暂上滚纳入 `captureAnimating()`，防止现有帧缓存吞掉变化；没有将整张仪表永久标记为动画。
- 卡片隐藏或 View 不在窗口中时，不新建歌词 UI 定时任务。离屏投放仍由既有帧请求取得当前歌词状态。
- `UltraPerformanceBudget`、100 ms 最小帧间隔、单帧预备和 ACK 背压保持不变。没有提高到 15 fps，没有改传输格式。

## 验证

```bash
# 无 Android 依赖的状态模型回归；使用本机已有 Java 编译器模块。
bash scripts/test-lyrics-standalone.sh

# 完整工具链准备好后，包含相同状态模型回归的标准测试。
./gradlew -Pp7Profile=true -Pp7ViewAreas=true \
  :common:testDebugUnitTest :shared:testDebugUnitTest :mobile:lintRelease :mobile:assembleDebug
python3 scripts/check-public-tree.py
git diff --check
```

### 直接执行生产 Canvas 的字体夹具

`UltraMusicCardRenderTest` 使用测试依赖 Robolectric 4.17 / Android API 28 / `GraphicsMode.NATIVE`，直接执行生产 `UltraMusicCardRenderer.draw()` 和 `UltraDashboardView.drawDashboard()`，不是另写一套 UI 模拟图。它检查 28/22 px `StaticLayout`、medium 字体 weight 500 / NORMAL / 非粗体、中文字符可用、两行省略、真实未唱像素 alpha 179（70%）、普通行/逐字时间戳差异、空数据裁切及日夜左右交换。

```bash
./gradlew -Pp7Profile=true -Pp7ViewAreas=true :common:testDebugUnitTest \
  --tests 'com.heavendoor.ultralab.UltraMusicCardRenderTest'
```

PNG 写入 `common/build/reports/lyrics-renderer/`，不随源码提交，也不进入 APK。全景与近景均直接取自真实 Canvas 输出；若添加交付说明，只能放在原像素之外。预览应记录对应 commit/tree 与 Style、Renderer、Dashboard 的源码 SHA256，避免把旧图当成新代码的证明。

`scripts/preview-lyrics-layout.py` 仍可在没有 Android 测试运行时的环境做**离线几何近似检查**。它读取同一个 `UltraMusicCardStyle` 并验证生产代码确实使用这些字段；`scripts/test-preview-lyrics-layout.py` 防止字号、透明度、字体或标签在预览与实现间分叉。该近似绘制不能冒称原生 Canvas 图。

测试中的原创歌词、地图、封面和车况只在测试源码/离线预览中使用，不接到应用运行路径。Robolectric 原生 Canvas 图是 JVM 中的生产绘制测试，不是运行 APK 的设备截图、真实歌词服务或实车验收；车辆系统字体可能仍与测试运行时不同。编译与单元测试也不能替代真实媒体源时序、车内百兆链路、长时温升和恢复验收。

### 本分支云端检查（2026-10-29）

- JDK 25 / Java 11 API 与字节码：172 项歌词模型断言通过，标准 JUnit suite 复用同一组检查。
- `:common:testDebugUnitTest`、`:shared:testDebugUnitTest`：544 项测试中 543 通过、1 项私用材料检查按条件跳过，零失败/错误；其中 6 项直接运行原生 Canvas 绘制与像素/字体检查，3 项保护样式层级与边界。
- 单一参数源预览脚本 11 项回归、25 项离线字界检查通过。交付主图使用原生 Canvas 输出，不以近似脚本图替代。
- `:mobile:lintRelease`：零错误、7 个既存警告；其中依赖中的宽松 TLS TrustManager 2 项、资源目录 1 项、未用资源 4 项。本次没有修改这些依赖，也不是安全审计通过声明。
- `:mobile:assembleDebug` 成功，包信息与 v2 APK 签名验证通过。自行 Debug 签名不匹配上游受限服务 pin，不能据此称可直接替换原包或已经适配车辆。
- 公开树筛查和 `git diff --check` 通过。未改变 native、传输、车型、签名或固件校验。
