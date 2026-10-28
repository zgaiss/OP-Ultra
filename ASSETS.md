# 公开构建的图形与字体

公开版本不包含从原厂仪表固件提取的字形 PNG、字体、车辆图和功率图。

- 主界面字体使用设备自身的 Android `sans-serif`／`sans-serif-medium`，没有随 APK 分发原车字体文件。
- 速度数字、车辆示意和功率条由 `UltraPublicArtwork.java` 在初始化时通过 Canvas 绘制，绘制代码随本项目按 GPL-3.0 提供。
- 替代图形只影响外观，不改变车况来源、地图接收、卡片字段、图层恢复或投放协议。车辆示意中的门线是装饰，不代表已经实现实时开门动画。
- OP 应用图标为本项目制作，来源记录见 `asset/op-app-icon-source.txt`。公开包的默认 CarPlay 标志也使用 OP 图标；用户仍可自行选择自定义图标。

公开包与作者个人研究部署的字体／图形可能略有不同。手机地图、歌曲封面均为运行时接收的内容，不作为示例内容打进本 APK。

- 仪表自绘提示使用从 Noto Sans CJK SC Regular 生成的缓存字形，随包附 SIL OFL1.1 许可与版权通知。生成器与字体来源见 `scripts/generate-*-atlas.py` 和 `third_party/noto-cjk/`；不使用原车提取字形。
