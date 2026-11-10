# Floating Clock（悬浮时钟）

一个使用 Java 和原生 Android View 实现的系统级悬浮时钟。项目最低支持 Android 8.0（API 26）。

## 功能

- 时间显示格式为 `HH:mm:ss.S`，十分之一秒使用强调色显示。
- 自动跟随手机系统时区，并显示 UTC 偏移与代表地区。
- 每分钟 `57.0` 至 `59.9` 秒显示三秒进度条，`00.0` 秒恢复常规底栏。
- 拖动悬浮窗主体可移动位置。
- 拖动右下角把手可等比例缩放。
- 自动保存悬浮窗的位置和大小。
- 主界面可将灰色背景透明度调整为 20% 至 100%，默认 70%，并实时生效。
- 点击左上角关闭按钮可停止悬浮窗和前台服务。

## 使用方式

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成；项目使用 JDK 17、Gradle 9.6 和 Android Gradle Plugin 9.4.1。
3. 确保 Android SDK Platform 35 已安装。
4. 运行 `app`，根据页面提示授予“显示在其他应用上层”权限。
5. 点击“开启悬浮时钟”。

Android 13 及以上系统会询问通知权限。悬浮窗仍由前台服务管理，以降低应用进入后台后被系统清理的概率。

## 主要代码

- `MainActivity.java`：授权和服务开关。
- `FloatingClockService.java`：前台服务、悬浮窗口以及位置/尺寸管理。
- `FloatingClockView.java`：时钟、时区、进度条和操作区域的自绘实现。
- `FloatingClockPreferences.java`：持久化窗口状态。

## 时区地区规则

常见 IANA 时区会显示中文代表城市。例如 `Asia/Shanghai` 显示 `UTC+8｜北京`。未配置中文映射的时区会显示 IANA 时区末级名称，不会申请定位权限。
