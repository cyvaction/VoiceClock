# VoiceClock

中文北京时间语音报时与低电量提醒 Android 应用，最低支持 Android 8（API 26）。

## 功能

- 夏季、冬季两组独立的 48 个半小时报时时段，按北京时间和配置的起始月日自动切换。
- 单个下一次精确闹钟；开机、应用升级、时间变化和授权恢复后自动重排。
- 开机后启动前台服务并显示一条低重要度持续通知；系统回收进程时由 `START_STICKY` 请求重启。
- 低电量阈值、每轮次数、最短间隔及跨午夜睡眠时段设置；后台电池事件、常驻服务检查和 WorkManager 共用同一判断入口。
- 报时优先：临近报时延后低电量语音；报时到达时可中断低电量播放，结束后重新检查。
- 界面与常驻通知同步展示本轮实际完整播放次数；失败、中断、试听和模拟不计入。
- 低电量不新增独立的允许休眠唤醒闹钟，接受深度休眠期间延后。
- 全部播放使用用户提供的 `ding_1.5s.wav` 作为前缀，随后以 `0.82x` 播放普通话语音。
- 48 条报时语音和固定低电量语音“手机没电，请及时充电”均内置离线资源。

`voiceclock_demo_4.html` 与 `voiceclock_demo_4.test.cjs` 用于演示界面和交互；Android 实际功能以应用代码及 [app需求.md](app需求.md) 为准。应用提供：蓝白卡片、三页设置、季节月日下拉、24 小时制时刻选择与睡眠时间、12 小时制普通话语音、场景测试与可停止试听。

新安装及恢复默认时，两个功能均关闭，预置夏季 12 个、冬季 10 个常用报时时刻；已有保存设置继续保留，已清空的时段不会被预置覆盖。Android 12/12L 启用报时前需要授予“闹钟和提醒”权限；Android 13+ 将准点报时作为核心闹钟功能自动获得精确闹钟权限。Android 15+ 已解除低电量功能的版本禁用；播放前仍需有可见界面或已就绪的前台服务，并成功取得音频焦点。系统拒绝播放时延期、不计数，不自动关闭用户开关。API 35/36 的系统运行验收仍待对应设备。

首次安装后仍需打开应用一次；此后设备开机解锁会自动恢复前台服务和已启用任务。常驻通知无提示音，点击可返回设置页。Android 无法保证应用进程永久存活：系统或厂商省电策略仍可能终止服务；“强行停止”后不会自动重启，须手动打开应用。需要尽量提高开机恢复成功率时，请在系统设置中允许自启动、后台活动，并按设备支持情况将本应用的电池策略设为“不受限制”。准点报时依赖现有系统闹钟；常驻服务的电量检查循环不能保证深度休眠期间持续运行，也不用于计时报时。

### 低电量后台检查与报时优先

开启低电量功能后，现有 `ResidentService` 监听电池及电源事件，并在 CPU 获得运行机会时每 60 秒提交检查；服务启动、配置变化和报时结束也会触发检查。唯一的 15 分钟 WorkManager 周期任务继续兜底。所有入口经 `BatteryReminder` 串行判断，常驻服务合并连续请求，避免重复播放。

低电量在所有开关组合下均不新增 `setExactAndAllowWhileIdle` 或 `setAndAllowWhileIdle` 闹钟；应用级休眠唤醒限制由 Android 统一管理，不按报时、电量两套额度独立计数。深度休眠时允许低电量延后，不能承诺熄屏后每分钟检查。配置的 5/15/30/60 分钟是普通时段两次成功提醒的最短间隔，不是准点保证；后台未获得执行机会时，电量或短暂充电变化仍可能无法及时观察。

报时前 30 秒、已到期但仍在 5 分钟有效期内的计划，以及报时待播放/播放期间，低电量语音让行。有效报时到达时可取消正在播放的低电量语音，释放播放锁后报时；报时结束再交给常驻服务复查。被中断的低电量不计数，不立即循环重试。电量检查循环不持有唤醒锁，仅播放尝试使用有超时上限的短时锁。

界面和通知读取同一持久化状态。完整播放“叮咚 + 低电量语音”后，核对配置版本、轮次和预期次数再提交一次成功记录；静音、零闹钟音量、焦点失败、超时或取消不加次数。检测到充电或电量达到阈值后归零；若降低上限，已成功次数保持真实值。通知折叠时优先显示报时信息，展开后显示电量次数，不因每分钟检查而反复刷新或响铃。

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
2. 在“低电量提醒”中设置阈值（5%–99%）、每轮次数（1–10 次）和间隔（5/15/30/60 分钟），在“睡眠免扰”配置睡眠窗口，再打开低电量开关。仅在未充电且电量严格低于阈值时提醒；界面和展开通知可查看“本轮低电量已提醒 N/M 次”。
3. “睡眠免打扰”开启时，每个睡眠窗口最多成功提醒一次；这次提醒计入本轮总次数，醒来后仍有次数时继续按最短间隔判断。
4. 试听只验证当前声音，不改变低电量提醒次数。
5. 设置卡片底部的“更多操作”可进入场景测试或恢复默认。场景测试沿用当前开关、阈值和睡眠规则，支持模拟日期、电量、充电状态及推进时间；模拟计数与实际提醒隔离。

### 华为 / 荣耀后台与重启恢复

在手机管家或系统设置的“启动管理”中找到“语音时钟”，关闭“自动管理”，并在“手动管理”中开启“允许自启动、允许关联启动、允许后台活动”。如果应用已被手机管家停止，再打开应用一次，然后重启验证常驻通知和报时排程是否恢复；应用界面不会自动弹出。电池优化白名单与厂商启动管理是两个独立设置，不能用前者替代后者。建议同时锁定最近任务卡片，正常使用后返回桌面，避免显式移除应用卡片。手动启动许可及电池优化白名单不能阻止所有厂商强停，锁定也不能保证显式移除后恢复。对照结果见 [华为后台对照](HUAWEI_BACKGROUND_COMPARISON_2026-10-24.md)；本版的后续停止记录与验证边界见 [最新验证记录](LOW_BATTERY_OPTIMIZATION_VALIDATION_2026-10-24.md)。

## 语音资源复现

48 条报时语音来自 `speaker/HH_mm.mp3`，按时刻对应内置的 `announcement_HHmm.mp3`；低电量提醒来自 `speaker/battery_low.mp3`。声音由 ai-speaker.net 生成，设置为新闻女声、语速 1x、音调 1.2x、音量 100%；本次以用户确认的当前 `speaker` 文件为准。所有语音均为单声道、24 kHz MP3，直接复制，无需重新编码，叮咚提示音保持原资源。报时使用“现在时间”及凌晨、上午、中午、下午、晚上分段，00:00 按用户要求保留“现在时间，晚上12点整”。网站生成记录见 `tools/speaker_generation_2026-10-14.json`，当前内置文件校验值见 `tools/voice_assets_manifest.json`。

```powershell
Get-ChildItem .\speaker -Filter '??_??.mp3' | ForEach-Object {
    $assetName = 'announcement_' + $_.BaseName.Replace('_', '') + '.mp3'
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path .\app\src\main\res\raw $assetName)
}
Copy-Item .\speaker\battery_low.mp3 .\app\src\main\res\raw\battery_low.mp3
.\.voice-tools\venv\Scripts\python.exe .\tools\verify_voice_assets.py
```

## 实现与验证

| 组件 | 职责 |
| --- | --- |
| `AnnouncementScheduler` / `AnnouncementReceiver` | 保留单个下一次报时闹钟、计划校验与重排，登记当前报时优先状态 |
| `PlaybackService` / `VoicePlayer` / `PlaybackPriority` | 报时播放、共享音频锁、低优先级取消与播后电量检查交接 |
| `ResidentService` / `BatteryWorker` / `BatteryReminder` | 复用常驻前台服务、事件及定时检查、唯一周期任务与公共电量处理 |
| `SettingsStore` / `MainActivity` | 持久化设置和轮次计数、拒绝过期提交、界面状态展示 |

截至 2026-10-24，`testDebugUnitTest`、`lintDebug`、`assembleDebug` 通过，26 项单元测试通过，Lint 为 0 错误、9 警告。Android 8 / Honor BLN-AL10 已验证后台低电量、自然重复及上限、充电复位/中断、零音量不计数、进程与重启恢复；23:30 真实强制 Doze 报时后接续低电量，以及人工注入报时请求抢占电量音频，均获用户听感确认。人工抢占测试与真实闹钟调度分开记录。

Android 15/API 35、Android 16/API 36 已纳入实现范围，但没有对应环境的运行验收；Android 12/14 系统回归、自然整夜待机和部分失败路径仍待补验。一次强制 Doze 测试不代表长期后台保证，构建成功也不代表所有 Android 版本通过。

- [当前功能需求](app需求.md)
- [低电量优化实施方案](LOW_BATTERY_OPTIMIZATION_PLAN_2026-10-24.md)
- [本轮实施与验证记录](LOW_BATTERY_OPTIMIZATION_VALIDATION_2026-10-24.md)
- [回退版本报时验证](ROLLBACK_VALIDATION_2026-10-24.md)
- 历史原型记录：`PROTOTYPE_VALIDATION.md`
