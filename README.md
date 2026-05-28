# TS6 Droid 简体中文增强版

基于原作者 [flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid) 的开源项目进行汉化与功能增强的 Android 客户端。

这是一个自由、轻量级的 TeamSpeak 3/6 安卓客户端，使用 Jetpack Compose 构建，底层由 Rust 编写的 `tslib` 驱动。

---

## 🌟 汉化及增强特性

相较于原版，本项目进行了以下关键优化：

1. **简体中文本地化**：100% 补齐了全文本的简体中文翻译（`zh-rCN`）。
2. **新增手动语言切换**：在应用**右上角独家新增了语言切换按钮**，支持在中文与英文之间一键自由切换，无需更改手机系统语言。
3. **内置核心语音驱动**：移除了原版繁琐的本地 Rust 交叉编译限制，在源码中**直接内置了全架构核心二进制库（jniLibs）**，实现开箱即用。
4. **云编译（CI/CD）深度优化**：
   - 适配了 AndroidX 及 Jetifier 兼容环境，铲平了 `checkDebugAarMetadata` 编译崩溃大坑。
   - 优化了 Gradle JVM 内存上限（`-Xmx4096m`），彻底解决了云端虚拟机打包 `.so` 库时极易触发的 `OutOfMemoryError` 内存溢出问题。

---

## 🛠️ 如何进行云编译 (GitHub Actions)

得益于本项目对云端环境的优化，你不需要在本地配置复杂的 Android Studio、Rust 以及 NDK 环境，直接利用 GitHub 即可一键编译属于你自己的 APK：

1. **Fork 本仓库** 到你自己的 GitHub 账号下。
2. 进入你 Fork 后的仓库页面，点击顶部的 **Actions** 标签。
3. 如果提示需要开启权限，点击绿色按钮激活 Actions。
4. 之后的每一次代码推送（Push），或者你在 Actions 页面手动触发工作流，GitHub 都会自动开始打包。
5. 编译完成后，点击对应的构建历史，拉到最底部即可在 **Assets** 区域下载最新生成的 `app-debug.apk`。

---

## 📚 详细技术架构与配置

关于本项目的底层 Rust 架构设计、本地编译环境搭建（Android NDK/Rust/Cargo）以及更多技术细节，请直接参考**原作者仓库说明**：

🔗 **原作者官方仓库**：[flamme-demon/TS6_Droid](https://github.com/flamme-demon/TS6_Droid)

## 📄 开源许可

本项目遵循原作者的自由软件开源协议。详细内容请参阅 [LICENSE](LICENSE) 文件。
