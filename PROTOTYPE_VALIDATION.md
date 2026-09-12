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
- `testDebugUnitTest`：9 个测试通过，0 failure/error/skip。
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

## 版本基线例外

批准计划中的 Compose BOM `2026.08.00` 已要求 `compileSdk 37`，与计划锁定的 `compileSdk 36` 冲突。原型保留 API 36，并使用仍支持该平台的最新稳定 Compose BOM `2026.06.01`（Compose `1.11.4`）和 Core KTX `1.18.0`；此处据实记录基线例外。
