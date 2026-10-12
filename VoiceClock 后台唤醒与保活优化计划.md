# VoiceClock 后台唤醒与保活优化计划

## 一、当前实现的问题分析

基于对本项目全部 Kotlin 源码的逐行审查，以及 QQ 邮箱反编译分析（参考会话 `sess_a128ad78`）提取的五层保活策略，当前实现存在以下 **7 个缺口**：

### 缺口 1：无 WakeLock，播放期间 CPU 可能重新休眠

`AnnouncementReceiver` 用 `goAsync()` 保持广播存活（~10 秒窗口），`PlaybackService` 虽是前台服务但不持有 `PARTIAL_WAKE_LOCK`。设备从 Doze 唤醒后，如果 MediaPlayer 准备或播放耗时较长，CPU 可能在播放完成前重新休眠。QQ 邮箱在推送服务中全程持有 `PARTIAL_WAKE_LOCK`。

### 缺口 2：无 `POST_NOTIFICATIONS` 权限，Android 13+ 通知可能被静默抑制

Manifest 未声明此权限。Android 13+ 用户未授予通知权限时，常驻服务通知和报时通知会消失（服务仍运行但用户看不到），影响透明度。

### 缺口 3：无 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，Doze 白名单引导缺失

国产 ROM 上电池优化策略激进，未加入白名单时 `setExactAndAllowWhileIdle` 仍可能被厂商策略覆盖。QQ 邮箱声明了此权限并主动引导用户加入白名单。

### 缺口 4：缺少国产 OEM 专属开机广播

当前只声明了标准 `BOOT_COMPLETED`。华为/小米/OPPO/vivo 等厂商使用非标准 boot intent（如 `QUICKBOOT_POWERON`），部分设备在"自启动管理"中即使允许也只发厂商私有广播。

### 缺口 5：闹钟链断裂后无自愈机制

只有"下一个"精确闹钟。如果该闹钟因系统异常丢失（设备关机期间到期、厂商杀进程后闹钟被清），链路断裂后只能等用户手动打开 App 才恢复。`BatteryWorker` 虽 15 分钟周期运行但只处理电量，不做报时闹钟自愈。

### 缺口 6：`BatteryWorker` 播放无前台服务保护

低电量语音在 Worker 协程内直接调用 `VoicePlayer.play`，不走 `PlaybackService`。如果 `ResidentService` 未运行（进程被杀但 Worker 被系统单独拉起），后台进程播放音频在 Android 12+ 可能受限。

### 缺口 7：无 `Application` 类，初始化分散

没有 `Application.onCreate()` 作为统一入口。`ResidentService` 只在 `MainActivity.onCreate` 和开机广播中启动；如果用户清除最近任务但未强行停止，系统重建进程时没有入口恢复服务。

---

## 二、优化方案

借鉴 QQ 邮箱策略中适用于本项目的部分（剔除长连接、多厂商推送通道等不适用的重量级方案），采用**"精确闹钟按需唤醒 + 前台服务播放期间 WakeLock + WorkManager 自愈兜底 + 电池优化引导 + OEM 广播兼容"**的轻量架构。

### 改动 1：添加 `WAKE_LOCK` 和 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限

**文件**：`AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

`WAKE_LOCK` 用于播放期间短时 CPU 保活（不是常驻），`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 用于引导用户加入 Doze 白名单。不声明 `POST_NOTIFICATIONS`——按官方文档，前台服务无需通知运行时授权即可启动，当前 `IMPORTANCE_LOW` 通知已在 Android 8-14 验证通过；如后续需要显示通知再按需添加。

### 改动 2：添加 OEM 专属开机广播

**文件**：`AndroidManifest.xml` → `RescheduleReceiver` 的 intent-filter

新增以下 action：
```xml
<action android:name="android.intent.action.QUICKBOOT_POWERON" />     <!-- 小米/通用 -->
<action android:name="com.huawei.intent.action.QUICKBOOT_POWERON" />  <!-- 华为 -->
<action android:name="com.android.server.action.QUICKBOOT_POWERON" /> <!-- OPPO -->
```

在 `RescheduleReceiver.ACTIONS` 集合中同步添加这些 action 字符串。`onReceive` 中的 `BOOT_COMPLETED` 分支逻辑改为：所有 boot 类 action 都触发 `ResidentService.start(context)`。

### 改动 3：`PlaybackService` 播放期间持有短时 WakeLock

**文件**：`PlaybackService.kt`

- 在 `onStartCommand` 的协程中，播放开始前创建 `PARTIAL_WAKE_LOCK`，在 `finally` 块中释放。
- WakeLock 生命周期严格等于播放协程生命周期：获取→播放（chime + 语音）→释放，超时也释放。不持有常驻 WakeLock。
- 加超时保护：WakeLock 自身设 `ACQUIRE_CAUSES_WAKEUP` 不需要（`PARTIAL_WAKE_LOCK` 只保 CPU），但加一个比播放超时（15 秒）多 2 秒的安全冗余，防止极端情况下泄漏。

```kotlin
// 伪代码
val wakeLock = powerManager.newWakeLock(PARTIAL_WAKE_LOCK, "VoiceClock:playback")
wakeLock.acquire(17_000L) // 15s 播放超时 + 2s 安全冗余
try {
    withTimeoutOrNull(15_000) { VoicePlayer.play(...) }
} finally {
    if (wakeLock.isHeld) wakeLock.release()
    stopSelf(startId)
}
```

### 改动 4：`BatteryWorker` 播放改为走 `PlaybackService`

**文件**：`BatteryWorker.kt`

当前 Worker 直接调用 `VoicePlayer.play(context, R.raw.battery_low)`。改为：在 `decision.shouldPlay` 时，启动 `PlaybackService`（传入 `EXTRA_RAW_ID = R.raw.battery_low`），由前台服务统一处理播放和 WakeLock。

- `PlaybackService.onStartCommand` 已有 `EXTRA_PLANNED_AT` 的校验逻辑，需要为低电量路径放宽：当 `rawId == R.raw.battery_low` 时跳过 `plannedAt` / `shouldPlay` / `isAnnouncementEnabled` 校验，直接播放。
- Worker 等待播放完成的方式：在 `PlaybackService` 播放结束后发送一个 LocalBroadcast 或用 `liveData`/`Flow` 通知 Worker，Worker 在 15 秒超时内等待结果。或者更简单：Worker 仍然调用 `VoicePlayer.play` 但在播放期间持有自己的 WakeLock（通过 `setForeground` 使 Worker 成为前台服务也是一个选项，但计划文档已明确"Worker 不调用 setForeground"）。

**最终方案（简化）**：Worker 内部在 `VoicePlayer.play` 期间持有短时 `PARTIAL_WAKE_LOCK`（与改动 3 相同的模式），不改为走 `PlaybackService`。这样改动最小且符合实施计划约束。Worker 已有的 `ResidentService` 前台进程保护在正常情况下足够；WakeLock 作为 CPU 保活的补充。

### 改动 5：`BatteryWorker` 增加报时闹钟自愈检查

**文件**：`BatteryWorker.kt` → `doWork()`

在现有电量检查逻辑之后，增加一段"闹钟自愈"检查：
- 读取 `settings.announcementsEnabled` 和 `settings.plannedAtMillis`
- 如果启用报时但 `plannedAtMillis == 0` 或 `plannedAtMillis` 对应的时间已过去超过 5 分钟（说明闹钟已过期但未触发），调用 `AnnouncementScheduler.reschedule(context)`
- 这不替代精确闹钟，只是每 15 分钟的兜底检查：如果闹钟链因任何原因断裂，最多 15 分钟后自愈

### 改动 6：创建 `Application` 类统一初始化

**新建文件**：`VoiceClockApp.kt`

```kotlin
class VoiceClockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ResidentService.start(this)
    }
}
```

在 Manifest 的 `<application>` 标签添加 `android:name=".VoiceClockApp"`。

**作用**：当系统因 `START_STICKY` 重建进程时（非 force-stop），`Application.onCreate` 是第一个执行的入口，确保 `ResidentService` 在进程重建时立即重启，不必等 `MainActivity` 或广播。QQ 邮箱的推送服务也采用类似的进程级初始化策略。

### 改动 7：设置页增加电池优化引导

**文件**：`MainActivity.kt`

在设置页底部"更多操作"菜单中新增"电池优化设置"项，点击后：
- 检查 `PowerManager.isIgnoringBatteryOptimizations(packageName)`
- 如果未加入白名单，弹出对话框说明原因，确认后调用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` Intent
- 如果已加入，显示"已加入电池优化白名单"

借鉴 QQ 邮箱的 `keepalive/d` 类的做法：只在用户主动点击时弹出，不在每次启动时强制弹出。

### 改动 8：`RescheduleReceiver` 启动 `ResidentService` 的时机扩展

**文件**：`RescheduleReceiver.kt`

当前只在 `BOOT_COMPLETED` 和 `MY_PACKAGE_REPLACED` 时调用 `ResidentService.start`。扩展为：所有 boot 类 action（含 OEM 专属）都触发服务启动。

---

## 三、实施步骤

### 阶段 A：权限与 Manifest（改动 1、2、8）

1. `AndroidManifest.xml`：添加 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限
2. `AndroidManifest.xml`：在 `RescheduleReceiver` intent-filter 添加 3 个 OEM boot action
3. `RescheduleReceiver.kt`：`ACTIONS` 集合添加对应字符串；boot 类判断从精确匹配 `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` 改为匹配一个 `BOOT_ACTIONS` 子集
4. 验证：`assembleDebug` + `lintDebug`，检查合并 Manifest

### 阶段 B：WakeLock 与播放保活（改动 3、4）

5. `PlaybackService.kt`：播放协程中获取 `PARTIAL_WAKE_LOCK`，超时 17 秒，finally 释放
6. `BatteryWorker.kt`：`VoicePlayer.play` 调用处包裹 WakeLock（相同模式）
7. 验证：`testDebugUnitTest` + `assembleDebug`

### 阶段 C：自愈机制（改动 5、6）

8. 新建 `VoiceClockApp.kt`，`onCreate` 调用 `ResidentService.start`
9. `AndroidManifest.xml`：`<application>` 添加 `android:name=".VoiceClockApp"`
10. `BatteryWorker.kt`：`doWork` 末尾增加报时闹钟自愈检查
11. 验证：`testDebugUnitTest` + `assembleDebug` + `lintDebug`

### 阶段 D：UI 电池优化引导（改动 7）

12. `MainActivity.kt`：`MoreActions` 下拉菜单增加"电池优化设置"项
13. 实现检查逻辑和引导对话框
14. 验证：`assembleDebug`，Android 8 真机/模拟器手测

### 阶段 E：全量验证与提交

15. `clean lintDebug testDebugUnitTest assembleDebug`
16. `python tools/verify_voice_assets.py`
17. `node voiceclock_demo_4.test.cjs`
18. 更新 `PROTOTYPE_VALIDATION.md`
19. 提交

---

## 四、不采纳的 QQ 邮箱策略（及原因）

| 策略 | 不采纳原因 |
|------|-----------|
| TCP 长连接 + 心跳保活 | VoiceClock 无服务端，不需要实时通信 |
| 多厂商推送通道（小米/华为/OPPO/vivo 推送 SDK） | 无服务端推送需求，引入 6 个 SDK 体积过大 |
| SyncAdapter 账户同步保活 | 需要创建系统账户，架构复杂度不匹配 |
| `setComponentEnabledSetting` 动态启停 boot 接收器 | VoiceClock 的 boot 接收器始终需要，无动态启停需求 |
| `setAlarmClock` 替代 `setExactAndAllowWhileIdle` | 会显示系统闹钟图标，用户体验不符；当前 API 在有精确闹钟权限时已足够 |
| 常驻 WakeLock | 耗电，且本项目无长连接需求，只在播放期间短时持有 |