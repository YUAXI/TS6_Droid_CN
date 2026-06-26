# TS6 Droid 简中版

基于原作者 [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid) 的开源项目进行汉化与功能增强的 Android 客户端。

这是一个自由、轻量级的 TeamSpeak 3/6 安卓客户端，使用 Jetpack Compose 构建，底层由 Rust 编写的 `tslib` 驱动。

---

## 项目演示

<div align="center">
<table>
  <tr>
    <td align="center"><img src="img/screenshow1.jpg" width="240"/></td>
    <td align="center"><img src="img/screenshow2.jpg" width="240"/></td>
  </tr>
  <tr>
    <td align="center"><img src="img/screenshow3.jpg" width="240"/></td>
    <td align="center"><img src="img/screenshow4.jpg" width="240"/></td>
  </tr>
</table>
</div>

---

## 更新日志

### v2.0.1-Han（2026-06-27）

**Compose 性能优化**
- 全项目 54 处 Flow 采集从 `collectAsState` 迁移至 `collectAsStateWithLifecycle`，应用切后台时自动暂停 UI 采集，降低 CPU 占用和电量消耗
- 背景图片淡入动画从 `Modifier.alpha()` 迁移至 `Modifier.graphicsLayer {}`，动画帧跳过 Composition 阶段重组，减少掉帧
- 缓存壁纸网格添加稳定 `key`，避免增删壁纸时滚动位置跳回

**Bug 修复**
- 修复查看壁纸缓存无反应，点击后弹出缩略图网格弹窗
- 清空壁纸缓存添加二次确认弹窗
- 设置页音量增益滑块可正常调节
- 设置页开关切换页面时不再跳动闪烁
- 文件管理器点击图片文件支持应用内全屏预览

### v2.0.0-Han（2026-06-26）

**Material3 UI 全面重构**
- 采用 Google Material Design 3 规范，完全重构配色、排版与组件样式
- Dynamic Color 动态取色（Android 12+），主题色从壁纸图片自动提取并生成完整配色方案
- 15 级排版体系，Shape 圆角 token 对齐 M3 标准
- 所有组件（按钮、输入框、卡片、弹窗、底部栏）统一 M3 风格

**启动页与主题自适应**
- 新增 SplashScreen 启动界面，加载期间显示品牌标识
- 壁纸图片下载后自动提取主色调，主题配色实时适配
- 3 秒超时保护：网络异常时从缓存随机抽取壁纸作为背景

**底部导航栏 + 设置页**
- 首页新增底部导航栏（主页 + 设置），支持页面切换
- 语言切换、自动重连、音量增益、悬浮窗、动漫背景、麦克风降噪、关于软件全部整合到设置页
- 服务端不再显示设置弹窗，界面更简洁

**壁纸缓存系统**
- 壁纸图片自动缓存到本地，启动时优先使用缓存
- 可设置缓存最大容量（10MB - 500MB 滑块调节）
- 查看缓存壁纸缩略图网格，支持单张删除
- 清空缓存带二次确认弹窗
- 以上设置仅在「我是二刺螈」开启时可用

**动画背景优化**
- 壁纸切换不再闪烁：缓存机制 + 600ms 淡入动画
- 切页不再触发重新获取，全局共享同一张壁纸
- 首页空列表居中显示「暂无连接」

**文件管理器图片预览**
- 点击图片文件直接在应用内全屏预览，不再弹出外部打开方式

**Bug 修复**
- 修复 Config#HARDWARE bitmap 无法 getPixel 导致闪退
- 修复设置页开关在页面切换时跳动闪烁
- 修复 SettingsDialog 残留代码导致编译错误
- 修复窗口背景色导致的灰色底色问题
- 统一所有组件使用 M3 颜色 token

---

## 汉化及增强特性

1. **简体中文本地化**：100% 补齐全文本简体中文翻译（`zh-rCN`）。
2. **语言切换**：支持中文、English、Français 一键切换，无需更改手机系统语言。
3. **内置核心语音驱动**：直接内置全架构核心二进制库（jniLibs），开箱即用。
4. **CI/CD 深度优化**：适配 AndroidX/Jetifier 兼容环境，优化 Gradle JVM 内存上限。

---

## 如何进行云编译 (GitHub Actions)

1. **Fork 本仓库** 到你自己的 GitHub 账号下。
2. 进入仓库页面，点击顶部的 **Actions** 标签，点击绿色按钮激活 Actions。
3. 每次代码推送或手动触发工作流，GitHub 自动打包。
4. 编译完成后，在 **Assets** 区域下载 `app-debug.apk`。

---

## 技术架构与配置

关于底层 Rust 架构、本地编译环境搭建等技术细节，请参考原作者仓库：

[flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid)

## 开源许可

本项目遵循 GNU GPLv3 开源许可证。详见 [LICENSE](LICENSE) 文件。

---

## 贡献者

感谢所有为本项目做出贡献的开发者！

<a href="https://github.com/YUAXI/TS6_Droid_CN/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=YUAXI/TS6_Droid_CN" />
</a>
