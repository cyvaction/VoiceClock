# VoiceClock

中文北京时间语音报时与低电量提醒 Android 应用，最低支持 Android 8（API 26）。

## 功能

- 夏季、冬季两组独立的 48 个半小时报时时段，按北京时间和配置的起始月日自动切换。
- 单个下一次精确闹钟；开机、应用升级、时间变化和授权恢复后自动重排。
- 开机后启动前台服务并显示一条低重要度持续通知；系统回收进程时由 `START_STICKY` 请求重启。
- 低电量阈值、每轮次数、最短间隔及跨午夜睡眠时段设置。
- 全部播放使用用户提供的 `ding_1.5s.wav` 作为前缀，随后以 `0.82x` 播放普通话语音。
- 48 条报时语音和固定低电量语音“手机没电，请及时充电”均内置离线资源。

界面和交互以 `voiceclock_demo_4.html`、`voiceclock_demo_4.test.cjs` 为准：蓝白卡片、三页设置、季节月日下拉、24 小时制时刻选择与睡眠时间、12 小时制普通话语音、场景测试与可停止试听。

新安装及恢复默认时，两个功能均关闭，预置夏季 12 个、冬季 10 个常用报时时刻；已有保存设置继续保留，已清空的时段不会被预置覆盖。Android 12/12L 启用报时前需要授予“闹钟和提醒”权限；Android 13+ 将准点报时作为核心闹钟功能自动获得精确闹钟权限。Android 15+ 因后台音频焦点限制，本版不启用后台低电量语音，报时和前台试听不受此开关限制。

首次安装后仍需打开应用一次；此后设备开机解锁会自动恢复前台服务和已启用任务。常驻通知无提示音，点击可返回设置页。Android 无法保证应用进程永久存活：系统或厂商省电策略仍可能终止服务；“强行停止”后不会自动重启，须手动打开应用。需要尽量提高开机恢复成功率时，请在系统设置中允许自启动、后台活动，并按设备支持情况将本应用的电池策略设为“不受限制”。准点报时依赖精确闹钟，电量检查依赖 WorkManager，而非前台服务持续计时。

低电量检测由 WorkManager 约每 15 分钟请求一次，系统省电可能继续延迟。配置的 5/15/30/60 分钟是两次成功提醒的最短间隔，不是准点保证；两次检查之间短暂充电可能无法重置当前轮次。

## 构建与安装

项目使用 JDK 17、Gradle Wrapper 9.6.0、AGP 9.4.0、API 36。本地 SDK 和缓存固定在项目目录且不会提交：

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
$env:ANDROID_SDK_ROOT = "$PWD\.android-sdk"
.\gradlew.bat clean lintDebug testDebugUnitTest assembleDebug
python .\tools\verify_voice_assets.py
node .\voiceclock_demo_4.test.cjs
```

Debug APK：`app\build\outputs\apk\debug\app-debug.apk`。

连接并授权一台 Android 8+ 手机后可覆盖安装并启动：

```powershell
.\tools\verify_android8_prototype.ps1
```

## 使用说明

1. 在“语音报时”中设置夏季、冬季起始日，分别选择时段，再打开总开关。
2. 在“低电量提醒”中设置阈值、次数、间隔和睡眠窗口，再打开总开关。
3. “睡眠免打扰”开启时，每个睡眠窗口最多成功提醒一次；这次提醒计入本轮总次数，醒来后仍有次数时继续按最短间隔判断。
4. 试听只验证当前声音，不改变低电量提醒次数。
5. 设置卡片底部的“更多操作”可进入场景测试或恢复默认。场景测试沿用当前开关、阈值和睡眠规则，支持模拟日期、电量、充电状态及推进时间；模拟计数与实际提醒隔离。

### 华为 / 荣耀手机重启后没有恢复

在手机管家或系统设置的“启动管理”中找到“语音时钟”，关闭“自动管理”，并在“手动管理”中开启“允许自启动、允许关联启动、允许后台活动”。如果应用已被手机管家停止，再打开应用一次，然后重启验证常驻通知和报时排程是否恢复；应用界面不会自动弹出。电池优化白名单与厂商启动管理是两个独立设置，不能用前者替代后者。Android 8 / EMUI 8 的实际定位和验证结果见 [2026-10-13 真机记录](ANDROID8_VALIDATION_2026-10-13.md)。

## 语音资源复现

语音使用 `HojoAI/Hojo-TTS-Light-40M` 的 `hojo_zh_f_01`。模型 revision、工具版本、文案及提示音校验值记录在 `tools/voice_assets_manifest.json`。

```powershell
python -m venv .voice-tools\venv
.\.voice-tools\venv\Scripts\python.exe -m pip install -r tools\voice_requirements.txt
git -c http.sslBackend=openssl clone https://github.com/HojoAI/Hojo-TTS-Light.git .voice-tools\Hojo-TTS-Light
git -C .voice-tools\Hojo-TTS-Light checkout 52ed7ef017363c6c49c71c3a8a649899142243f3
.\.voice-tools\venv\Scripts\python.exe .\tools\generate_voice_assets.py --all
```

Android 8 原型实机记录见 `PROTOTYPE_VALIDATION.md`。其他 Android 版本仅完成同一 APK 的代码、Lint 和构建兼容检查，未标为对应版本实机验证。
