# QuietNotify

QuietNotify（通知静默窗口）是一个基于 LSPosed Modern API 102 的 Android 模块，用于减少同一应用在短时间内重复产生的通知打扰。

为应用设置固定时间窗口后，模块可以分别限制重复的顶部通知，以及通知声音和振动。通知仍会正常保留在通知中心。

## 功能

- 为每个应用单独设置 `1 秒` 至 `24 小时`的固定窗口
- 亮屏且设备已解锁时，同一应用在窗口内只显示一次顶部通知
- 窗口内移除同一应用后续通知的系统声音和振动
- 顶部通知限制与声音、振动限制可独立启用
- 按 Android 用户和应用分别计时，互不影响
- 支持搜索已安装应用，并可选择显示系统应用
- 通过 LSPosed Remote Preferences 实时同步配置
- 设置页面显示作用域及系统进程加载状态

## 工作方式

对每个已配置应用，两项功能分别维护一个固定窗口：

1. 第一条符合条件的通知正常提醒，并开始计时。
2. 窗口内的后续通知按已启用的功能限制顶部弹出、声音和振动。
3. 窗口内的新通知不会延长计时。
4. 窗口结束后的下一条符合条件通知会正常提醒，并开始新窗口。

顶部通知限制仅在设备亮屏、处于交互状态且已经解锁时生效。熄屏、锁屏和 AOD 状态不会消耗顶部通知窗口。

模块只控制普通顶部通知以及系统通知产生的声音和振动，不会取消通知，也不会从通知中心删除通知。全屏 Intent、气泡，以及应用自行播放的媒体声音或自行触发的振动不在处理范围内。

## 系统要求

- Android 14、15 或 16
- 支持 Modern API 102 的 LSPosed 框架

模块使用以下静态作用域：

```text
system
com.android.systemui
```

其中 `system` 是 Modern API 表示 `system_server` 的虚拟系统包。不要使用 `android` 代替，也不需要将被管理的普通应用加入模块作用域。

## 安装与使用

1. 安装 Release APK 并打开“通知静默窗口”。
2. 在 LSPosed 中启用模块。
3. 确认模块作用域包含 `system` 和 `com.android.systemui`。
4. 完整重启设备。
5. 再次打开模块，确认状态显示“模块已连接”。
6. 按需启用“限制顶部通知”和“限制声音和振动”。
7. 在应用列表中选择需要管理的应用并设置窗口时长。

修改开关、应用或时长后，配置会实时同步，通常不需要再次重启。更新模块版本后，建议重启设备以确保 `system_server` 和 SystemUI 都加载了新代码。

## 注意事项

- 勿扰模式、通知渠道设置、应用前台状态和系统通知策略仍可能阻止通知弹出或响铃。
- 本来没有声音和振动，或已经被系统策略静音的通知，不会开启声音、振动窗口。
- `system_server` 重启后，声音和振动窗口会清空。
- SystemUI 重启后，顶部通知窗口会清空。
- 不同厂商可能修改系统通知实现；无法匹配兼容入口时，模块会记录错误并默认放行通知。
- Debug APK 使用 Android 调试证书，不能直接覆盖使用正式密钥签名的 Release APK。

## 构建

### 环境

- JDK 17
- Android SDK 37
- Gradle Wrapper 9.5.1

项目使用 Kotlin、Jetpack Compose、Material 3 和 libxposed API 102。

### 命令

在项目根目录执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat assembleRelease
```

Linux 或 macOS：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease
```

构建产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

Release 构建从以下本地文件读取签名配置：

```text
signing/quietnotify-release.jks
signing/keystore.properties
```

`signing/` 已被 Git 忽略。没有本地签名材料时，请配置自己的签名文件，或只构建 Debug APK。不要将密钥和密码提交到仓库。

## 项目结构

```text
app/src/main/java/io/github/lsp1/quietnotify/
├── data/       # 应用列表与配置访问
├── ui/         # Jetpack Compose 设置界面
└── xposed/     # Modern API 模块入口及通知窗口逻辑

app/src/main/resources/META-INF/xposed/
├── java_init.list
├── module.prop
└── scope.list

app/src/test/   # 固定窗口单元测试
```

## 问题反馈

提交兼容性问题时，请提供：

- Android 版本和完整系统构建号
- ROM 名称及版本
- QuietNotify 版本
- LSPosed 日志中包含 `QuietNotify` 的相关行

请勿提交通知正文、联系人信息或其他隐私内容。
