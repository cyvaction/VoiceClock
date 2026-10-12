## 计划：采纳两条增量优化

### 改动 1：精确闹钟权限被拒时降级为 `setAndAllowWhileIdle`

**文件**：`app/src/main/java/com/cyvaction/voiceclock/AnnouncementScheduler.kt`

**现状**：`reschedule()` 第 30–33 行，当 `canSchedule(context)` 返回 false（Android 12+ 用户未授权精确闹钟）时，直接 `setPlannedAt(0)` + 返回 null，不注册任何闹钟。报时完全失效。

**改法**：将 `if (next == null || !canSchedule(context))` 拆为两个分支：

- `next == null` → 保持现状（无下次报时，清零 plannedAt，返回 null）
- `next != null && !canSchedule(context)` → **仍然持久化 `plannedAt`**，然后注册 `setAndAllowWhileIdle`（非精确闹钟）而非 `setExactAndAllowWhileIdle`，返回 next

关键点：

- 必须调用 `SettingsStore.setPlannedAt(context, next.toEpochMilli())`，因为 `AnnouncementReceiver` 会校验 `plannedAtMillis == plannedAt`——如果 plannedAt 为 0，即使非精确闹钟触发了也会被接收器拒绝
- `setAndAllowWhileIdle` 不需要精确闹钟权限，不会抛 `SecurityException`，但保留现有 try/catch 无害
- Doze 下非精确闹钟可能延迟超过 5 分钟，此时 `ClockLogic.shouldPlay` 的 5 分钟有效期会跳过本次报时——这是安全行为（不播错内容），只是可能跳过某些报时点，比完全无闹钟好
- 用户后续授权精确闹钟时，`RescheduleReceiver` 收到 `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` 广播，调用 `reschedule()`，先 `cancel()` 取消非精确闹钟再注册精确闹钟——此流程已正确工作，无需额外改动

### 改动 2：播放通道从 `USAGE_NOTIFICATION` 改为 `USAGE_ALARM`

**文件**：`app/src/main/java/com/cyvaction/voiceclock/VoicePlayer.kt`

**改法**：

1. 第 40 行：`setUsage(AudioAttributes.USAGE_NOTIFICATION)` → `setUsage(AudioAttributes.USAGE_ALARM)`
2. 第 27 行音量守卫：`getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0` → `getStreamVolume(AudioManager.STREAM_ALARM) == 0`

理由：

- 改为 `USAGE_ALARM` 后音频走闹钟音量通道，但第 27 行仍检查通知音量——两者不一致会导致：通知音量为 0 时报时被错误阻止，或闹钟音量为 0 时报时通过守卫但不可闻。必须同步改为 `STREAM_ALARM`
- `RINGER_MODE_SILENT` 检查（第 26 行）保持不变——保守做法，尊重用户的静音意图
- `AudioAttributes` 对象在 `AudioFocusRequest` 和 `MediaPlayer.create` 中共用，改一处即覆盖焦点协商和播放路由
- `PlaybackService` 委托 `VoicePlayer.play`，无需额外改动

### 验证

1. `GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew clean lintDebug testDebugUnitTest assembleDebug`
2. `./.voice-tools/venv/Scripts/python.exe tools/verify_voice_assets.py`
3. `node voiceclock_demo_4.test.cjs`
4. 更新 `PROTOTYPE_VALIDATION.md` 新增日期章节
5. 提交并推送
