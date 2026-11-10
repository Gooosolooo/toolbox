# 实用工具（Toolbox）

一个 Android 工具箱，提供悬浮时钟和搜索直达。工具首页和搜索页使用 Kotlin 与 Jetpack Compose，悬浮窗及桌面组件使用原生 Android API。最低支持 Android 8.0（API 26）。

## 悬浮时钟

从工具首页进入「悬浮时钟」，按提示开启「显示在其他应用上层」，再点击「开启悬浮时钟」。Android 13 及以上也可能提示授予通知权限。

- 显示 `HH:mm:ss.S`，自动跟随手机时区并显示 UTC 偏移及代表地区，无需定位权限。
- 每分钟最后三秒显示进度条。
- 拖动窗体移动位置，拖动右下角把手等比例缩放，位置和大小会自动保存。
- 可设置背景颜色和透明度，透明度范围为 20%–100%，默认 70%。
- 点击悬浮窗左上角关闭按钮即可停止悬浮时钟。

## 搜索直达

从工具首页进入「搜索直达」，或在 Android 桌面添加「搜索直达」小组件。组件默认占 2×2 格，卡片宽高比为 4∶5。点击组件会打开搜索页并弹出键盘。

输入关键词后，点击目标 App。入口以每行 5 列的图标网格显示，两个分类可独立折叠并记住上次状态。也可以在页面空白处下滑收起键盘，已输入的关键词会保留。

| 分类 | 支持的 App |
| --- | --- |
| 购物 | 淘宝、京东、拼多多、抖音商城 |
| 社交与网页 | 小红书、抖音、bilibili、Chrome Beta |

Chrome Beta 直接打开 Google 搜索。其余入口使用「辅助搜索」尝试在目标 App 中填写关键词，并通过键盘提交。首次使用时可按提示前往系统无障碍设置开启服务，也可以选择手动搜索。自动搜索取决于 Android 版本及目标 App 的界面支持。识别或提交失败时，可留在目标 App 内手动继续。

辅助搜索仅在用户选定目标后处理本次搜索，不保存搜索历史。Debug 版可在 Logcat 中按 `ToolboxSearchPermission` 过滤，区分系统开关状态和服务连接状态。权限日志不包含搜索词。

## 本地运行

1. 用 Android Studio 打开项目根目录 `Toolbox`。
2. 安装 Android SDK Platform 35，按仓库中的 Gradle Wrapper 和 Gradle JVM 配置完成同步。
3. 选择 `app` 运行配置，在真机或模拟器上运行。

项目使用 Gradle 9.6、Android Gradle Plugin 9.4.1，Gradle 守护进程使用 JDK 25，Java 源码兼容级别为 17。验证搜索直达时，测试设备需安装对应的目标 App。

## 主要代码

- [MainActivity.kt](app/src/main/java/com/example/floatingclock/MainActivity.kt) — 工具首页和主题。
- [ClockActivity.java](app/src/main/java/com/example/floatingclock/ClockActivity.java) — 悬浮时钟设置和权限引导。
- [FloatingClockService.java](app/src/main/java/com/example/floatingclock/FloatingClockService.java) — 悬浮窗、前台服务和位置、尺寸管理。
- [SearchActivity.kt](app/src/main/java/com/example/floatingclock/SearchActivity.kt) — 搜索页、目标选择和权限引导。
- [SearchAccessibilityService.java](app/src/main/java/com/example/floatingclock/SearchAccessibilityService.java) — 目标 App 内的关键词填写、键盘提交和连接日志。
- [SearchWidgetProvider.java](app/src/main/java/com/example/floatingclock/SearchWidgetProvider.java) — 桌面搜索组件。
