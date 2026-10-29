# 来源与许可

OP Ultra 基于 OP CarPlay 与 xcertplay，加入老 P7 的车况读取、宽幅仪表合成、图层恢复、统一设置及性能优化。

- 上游 xcertplay：[shilapi/xcertplay](https://github.com/shilapi/xcertplay)，研究基线为提交 `17c92439413638dfd1d7f91d7e1c2e7358398762`。保留原版权与 GPL-3.0 许可，派生代码沿用相应许可。
- 独立仪表显示协议参考：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)，已有来源记录的参考提交为 `6d3b8a8ce927cb4b115b8c3881f17619433e93b1`。P7 车况、宽幅显示和恢复路径属于本项目的车辆适配实现，不能将其他车辆的方案直接描述为 P7 的通用接口。
- 动态视区字段参考：[harman-f/mhi2_altscreen_carplay](https://github.com/harman-f/mhi2_altscreen_carplay)。参考协议不代表本项目已实现全场景无缝尺寸切换。
- 上游还致谢 [LIVI](https://github.com/f-io/LIVI) 与 [showcase](https://github.com/amineross/showcase)。
- Shizuku API／Provider：保留其 MIT 许可与原版权。
- LZ4、tinf 及其他第三方组件：分别保留源码中的许可证、版权和适用条件。

公开源码与对应发行物应提供完整许可证。第三方商标、地图内容、封面、字体及原厂图片的权利属于各自权利人；GPL 代码许可不自动授予它们的再分发权。

个人研究部署中的认证材料、私钥、原车固件和原始采集数据不属于拟公开发布内容。

## 本分支修改

2026-10-29：`zgaiss/OP-Ultra` 分支在 Android 合成层加入小封面、歌词优先布局与有界时间戳呈现模型；保留上游版权及 GPL-3.0 许可。未加入真实歌词获取服务，测试和离线预览使用原创示例，不代表第三方音乐内容的再分发授权。

同日字体修订：歌词改用 28 px medium、邻句 22 px，歌名 26 px，未唱歌词提高到 70% ink 透明度；生产与预览读取同一组样式参数。没有加入独立红绿灯或倒计时功能。

## 仪表提示字形

提示用到的 Noto Sans CJK 字形数据按 SIL Open Font License1.1 分发，不随项目代码改为 GPL 授权。版权通知与完整许可证位于 `third_party/noto-cjk/`，并随 APK 放在 `assets/licenses/`。
