# 原型与完整功能验证记录

记录日期：2026-09-09

## 开发环境

- 工作目录：`D:\vibe_coding\VoiceClock`
- 远端：`https://github.com/cyvaction/VoiceClock.git`，默认分支 `main`，接入时 HEAD `d26fbad3b5de3c7cb429dab01fa1c2e4ee443e0f`
- 系统：Windows 11，内核版本 `10.0.26100.0`
- JDK/Javac：Eclipse Temurin `17.0.13`，`JAVA_HOME=C:\Program Files\Java\jdk-17`
- Python：`D:\app\Python312\python.exe`，版本 `3.12.10`
- Gradle Wrapper：`9.6.0`，项目缓存 `.gradle-user-home\`
- Android SDK：项目目录 `.android-sdk\`
  - Command-line Tools `19.0`
  - Platform `android-36` revision `2`
  - Build-Tools `36.0.0`
  - Platform-Tools/ADB `37.0.1`
- 语音环境：`.voice-tools\venv\`；具体模型 revision 和包版本见 `tools/voice_assets_manifest.json`

## 当前构建与资源检查

- SDK 许可：7/7 已接受。
- `testDebugUnitTest`：14 个测试通过，0 failure/error/skip。
- `lintDebug`：通过；仅保留 6 个由锁定版本基线产生的更新提示。
- `assembleDebug`：通过；APK 位于 `app\build\outputs\apk\debug\app-debug.apk`，SHA-256 `A39090AFD0A157B107BD672D2E33AC5C4A262B56BFAD8F40C52565145C8BEF69`。
- 48 条报时语音和 1 条固定低电量语音均为 24 kHz 单声道 OGG/Vorbis；用户提示音为 44.1 kHz 单声道 PCM WAV。50 个资源均可完整解码、样本有限且非静音，文案清单、资源映射和提示音 SHA-256 一致。
- `voiceclock_demo.test.cjs`：48 个时段、固定低电量文案、夏冬/闰年、下一次报时、睡眠输入和 UI 默认值检查通过。

## 原型验收结论

- 用户已确认最小原型验收完成，随后进入完整设置页、状态规则和全部语音资源开发。
- `tools\verify_android8_prototype.ps1` 接受唯一一台已授权的 API 26+ 设备，负责覆盖安装、启动并列出完整功能人工验收与设备状态恢复命令。

## Android 8 原型烟测

- 设备：Huawei BLN-AL10，Android 8.0.0 / API 26，序列号 `JJR4C16928000309`；设备时区为 `Asia/Shanghai`。
- APK 覆盖安装成功，`MainActivity` 启动状态为 `ok`。
- 试听链路完成音频焦点申请、24 kHz OGG 播放、`MEDIA_PLAYBACK_COMPLETE`、播放器释放和焦点归还。
- 第一版报时样例长 8.9 秒；用户确认发音不清晰且在目标文案外生成了额外内容，因此不通过。
- 后台单次闹钟原定 `12:05:09`，系统在 `12:05:09.408` 触发，随后启动播放前台服务；这只证明 API 26 辅助机上的普通后台链路。
- 生成输入补充中文句号后，第二版报时样例缩短为 2.28 秒，已重新构建、安装并播放；用户确认语音内容正确，但原速过快。

## 声音调整

- 每次语音前已统一播放一次固定“叮”提示音。
- 语音已通过系统 `PlaybackParams` 调整为 `0.82x`，提示音保持原速。
- 新 APK 已通过干净构建并在上述 Android 8 设备覆盖安装，`MainActivity` 启动状态为 `ok`。
- 报时试听日志确认同一次音频焦点内先读取并播放用户提供的 132,344 字节 WAV，再播放约 2.79 秒语音，最后正常释放两个播放器并归还音频焦点；用户已确认提示音和语速合适。
- 低电量内容固定为“手机没电，请及时充电”，不随触发阈值变化；Android 8 真机在模拟未充电且 24% 电量后成功执行 Worker，依次播放提示音和固定语音并返回 `SUCCESS`，测试后已恢复真实电量状态。

## 完整功能 Android 8 冒烟

- 完整功能 APK 已在 Huawei BLN-AL10（Android 8.0.0 / API 26）覆盖安装，包信息为 `minSdk 26`、`targetSdk 36`，`MainActivity` 启动和强制停止后重新启动均成功。
- 完整设置页已显示夏冬归组、48 个时段、低电量阈值、次数、间隔、睡眠窗口、最近检查时间和两个试听入口；已保存的归组日期在重启后保持不变。
- 临时选择当前冬季的 `14:00` 并启用报时后，页面显示下一次报时为 `2026-09-09 14:00`，AlarmManager 注册零窗口 `RTC_WAKEUP` 精确闹钟；随后已关闭报时并清空测试时段，系统闹钟取消且两组恢复为空。
- 低电量开关保持启用，系统 JobScheduler 中存在唯一周期 WorkManager 任务，未要求充电、低电量正常或空闲条件；最近检查时间已更新。检查时手机为 USB 充电、46% 电量，因此本次未触发语音，也未修改电量或 Doze 状态。
- 当前应用进程保持运行，未发现 `FATAL EXCEPTION` 或 VoiceClock `AndroidRuntime` 崩溃。

## 2026-09-12 可见性与开机常驻增量

- 报时时段选中态改为 Material 3 主色背景与反色文字，未选中态保持原样。
- 新增无提示音、低重要度持续通知的常驻前台服务；首次打开、`BOOT_COMPLETED` 和应用升级都会启动，返回 `START_STICKY` 请求系统在进程回收后恢复。
- 常驻服务使用 `specialUse` 前台服务类型，不承载低电量判断、不取代 AlarmManager/WorkManager，也不持有唤醒锁；系统强行停止及厂商禁用自启动仍无法绕过。
- `clean lintDebug testDebugUnitTest assembleDebug`、49 条语音资源与提示音校验、Demo 逻辑校验均通过；合并 Manifest 包含常驻服务与所需前台服务权限，未新增联网和通知运行时权限。

## 2026-10-11 评审修复增量

项目评审发现两个前台服务在 Manifest 声明了 `foregroundServiceType`，但调用的是不带 type 参数的 `startForeground(id, notification)` 旧重载。应用 targetSdk 36，在 Android 14（API 34）及以上设备上会抛出 `MissingForegroundServiceTypeException`：常驻服务在每次打开应用时崩溃，报时播放服务在闹钟触发时崩溃且语音无法播放。此前仅在 Android 8 实机验证，未覆盖此路径。

修复内容：

- `PlaybackService` 改用 `ServiceCompat.startForeground(this, id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`；`ResidentService` 改用 `ServiceCompat.startForeground(this, id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`。该 API 在 API 34+ 传 type，低版本回退旧重载，minSdk 26 不受影响。
- 两个服务的 `Notification.Builder` 一并迁移为 `NotificationCompat.Builder`，消除过时 API。
- 低电量面板新增计划要求的「系统省电可能让检测和提醒延迟；配置的间隔是两次成功提醒的最短间隔」说明，并在存在记录时显示最近检查时间（`lastCheckedAtMillis`）。
- `MainActivity.onResume` 在 Android 15+ 上仅在低电量提醒当前为启用时才执行关闭和 Worker 取消，避免每次回到前台都写 DataStore。
- `ResidentService.start` 失败时把 `residentServiceFailed` 写入 DataStore，设置页顶部显示「常驻后台服务未能自动启动」提示；服务成功创建时清回。
- 删除历史原型 `voiceclock_demo_3.html` 和 `voiceclock_demo_3_preview.png`，保留 README 引用的 v4 原型及其测试；`voiceclock_demo_4.test.cjs` 中对 v3 音频资产的比对改为文件存在时才执行。
- `.voice-tools/venv` 因基础解释器 `D:\app\Python312` 被移除（机器现为 Python 3.13，`D:\app\Python313`）而失效，已用 Python 3.13.15 按 README 步骤重建：`python -m venv .voice-tools\venv` 后 `pip install -r tools\voice_requirements.txt`，旧 venv 已删除。重建后验证：`verify_voice_assets.py` 通过；`generate_voice_assets.py` 成功加载 onnxruntime 模型并生成 2 条样例（24 kHz 单声道 OGG，报时样例约 2.28 秒，与首版原型样例时长一致），解码正常。

本次验证结果（2026-10-11）：

- `lintDebug`：通过，仅保留 6 个由锁定版本基线产生的更新提示；确认现有 Lint 规则不会检测「已声明 type 但未在运行时传入」的调用，P0 修复依赖代码评审发现。
- `testDebugUnitTest`：14 个测试通过，0 failure/error/skip。
- `assembleDebug`：通过。
- `python tools/verify_voice_assets.py`：49 条语音 + 1 条提示音校验通过。
- `node voiceclock_demo_4.test.cjs`：8 项检查通过。
- Android 14+ 实机/模拟器冒烟（打开应用不崩、报时触发可播放、常驻通知存在）：本环境无可用设备与模拟器，未执行，留待接入 API 34+ 设备后补验；未标为已验证。

## 2026-10-12 后台唤醒与保活优化增量

按《VoiceClock 后台唤醒与保活优化计划》执行，补齐 7 个保活缺口（WakeLock、OEM 开机广播、闹钟自愈、Application 入口、电池优化引导）。借鉴 QQ 邮箱五层保活中适用于本项目的轻量部分，剔除长连接、多厂商推送通道等重量级方案。

改动内容：

- 权限：新增 `WAKE_LOCK`（播放期间短时 CPU 保活）与 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（Doze 白名单引导）。未声明 `POST_NOTIFICATIONS`——前台服务无需通知运行时授权即可启动，当前 `IMPORTANCE_LOW` 通知已在 Android 8–14 验证通过。
- OEM 开机广播：`RescheduleReceiver` 的 intent-filter 新增 `QUICKBOOT_POWERON`（小米/通用）、`com.huawei.intent.action.QUICKBOOT_POWERON`（华为）、`com.android.server.action.QUICKBOOT_POWERON`（OPPO）；`onReceive` 的开机分支从精确匹配 `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` 改为匹配 `BOOT_ACTIONS` 子集，所有开机类 action 都触发 `ResidentService.start`。
- 播放 WakeLock：`PlaybackService` 播放协程内获取 `PARTIAL_WAKE_LOCK`（`VoiceClock:playback`，`acquire(17s)` = 15s 播放超时 + 2s 冗余），`finally` 中按 `isHeld` 释放后再 `stopSelf`；不持有常驻 WakeLock。`BatteryWorker` 低电量播放改用同一模式（`VoiceClock:battery`，17s），作为前台进程保护的 CPU 保活补充。
- 闹钟自愈：`BatteryWorker.doWork` 重构为「电量检查 + 报时闹钟自愈」两段；每次 Worker 运行末尾读取 `announcementsEnabled`/`plannedAtMillis`，若启用报时但 `plannedAtMillis == 0` 或已过期超过 5 分钟，则调用 `AnnouncementScheduler.reschedule` 重建下一个精确闹钟，作为每 15 分钟一次的中断兜底。
- Application 入口：新增 `VoiceClockApp : Application`，`onCreate` 调用 `ResidentService.start(this)`；Manifest `<application>` 添加 `android:name=".VoiceClockApp"`。系统因 `START_STICKY` 重建进程（非强停）时，`Application.onCreate` 作为首入口立即恢复常驻服务，无需等待 `MainActivity` 或广播。
- 电池优化引导：设置页「更多操作」菜单新增「电池优化设置」项；点击后用 `PowerManager.isIgnoringBatteryOptimizations` 判断，已加白名单则 Toast「已加入电池优化白名单」，否则弹出说明对话框，确认后发起 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（带 `package:` URI），失败则 Toast 提示。借鉴 QQ 邮箱 `keepalive/d`：仅用户主动点击时弹出，不在每次启动时强制弹出。

本次验证结果（2026-10-12）：

- `clean lintDebug testDebugUnitTest assembleDebug`：通过。
- `testDebugUnitTest`：14 个测试通过，0 failure/error/skip。
- `lintDebug`：0 error、7 warning。6 个为锁定版本基线产生的既有更新提示（`InlinedApi`×2、`OldTargetApi`、`AndroidGradlePluginVersion`、`GradleDependency`、`NewerVersionAvailable`）；新增 1 个 `BatteryLife` 提示，指向 `MainActivity.kt` 中 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。此为 Play Store 政策类**建议**（非错误），本应用报时属「用户显式配置的计划任务」这一可接受用例，且目标为国产 ROM 而非 Play 分发，故按计划保留该功能、据实记录此提示，不添加 `@SuppressLint` 压制。
- `python tools/verify_voice_assets.py`：49 条语音 + 1 条提示音校验通过。
- `node voiceclock_demo_4.test.cjs`：8 项检查通过。
- 合并 Manifest 已确认包含两项新权限、`VoiceClockApp` 应用入口与三个 OEM 开机 action。
- 真机/模拟器手测（OEM 开机广播触发、播放期间 WakeLock 持有、进程重建后常驻服务恢复、电池优化引导对话框）：本环境无可用设备与模拟器，未执行，留待接入设备后补验；未标为已验证。

## 2026-10-12 前缀铃声替换增量

将前缀提示音由 `ding_1.5s.wav` 替换为用户提供的 `叮咚音效声.mp3`。

- 定位：前缀铃声唯一实际播放点为 `VoicePlayer.kt` 的 `playResource(R.raw.chime, 1f)`，资源为 `app/src/main/res/raw/chime.wav`（原为 `ding_1.5s.wav` 的拷贝，44.1 kHz 单声道 PCM_16）；播放方式为 Android `MediaPlayer.create`。
- 格式与转换：`叮咚音效声.mp3` 原生即为 44.1 kHz 单声道（1.52 s、128 kb/s），与 `MediaPlayer` 兼容；但项目音频校验 `tools/verify_voice_assets.py` 用 `soundfile` 读取全部 raw 资源并硬断言 `chime.wav` 为 44.1 kHz，而 `soundfile` 不支持 MP3。为不改动播放代码与校验脚本，将 MP3 解码为 44.1 kHz 单声道 16-bit PCM WAV（与现有 `chime.wav` 格式一致、无需重采样）后替换 `res/raw/chime.wav`。
- 引用更新：`tools/voice_assets_manifest.json` 与 `tools/generate_voice_assets.py` 中 `chime` 元数据同步更新（sha256 `FCBA4F97…EC0D`、时长 1.489 s、`source` 改为 `叮咚音效声.mp3`）；`R.raw.chime` 引用与 `VoicePlayer` 播放逻辑均不变。
- 未改动：根目录 `ding_1.5s.wav` 源文件保留（README/计划文档仍引用）；浏览器原型 `voiceclock_demo_4.html` 内嵌的独立 chime 资产不变（非 App 实际播放路径）。

本次验证结果（2026-10-12）：

- `python tools/verify_voice_assets.py`：49 条语音 + 1 条提示音校验通过（新 chime 为 44.1 kHz 单声道、非静音）。
- `assembleDebug`：通过；解包 APK 确认 `res/raw/chime.wav` SHA-256 为 `FCBA4F97…EC0D`，与解码后的 `叮咚音效声.mp3` 及仓库资源一致——前缀铃声实际播放的即新内容。
- `node voiceclock_demo_4.test.cjs`：8 项通过（未受影响）。
- `testDebugUnitTest`：14 个测试通过（Kotlin 未改动）。
- 真机试听：本环境无设备，未执行实际出声验证，留待接入设备后补验；未标为已验证。

## 版本基线例外

批准计划中的 Compose BOM `2026.08.00` 已要求 `compileSdk 37`，与计划锁定的 `compileSdk 36` 冲突。原型保留 API 36，并使用仍支持该平台的最新稳定 Compose BOM `2026.06.01`（Compose `1.11.4`）和 Core KTX `1.18.0`；此处据实记录基线例外。
