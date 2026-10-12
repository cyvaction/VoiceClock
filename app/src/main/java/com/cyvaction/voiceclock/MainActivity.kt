package com.cyvaction.voiceclock

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ResidentService.start(this)
        setContent {
            MaterialTheme(colorScheme = VoiceClockColors) {
                VoiceClockScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            AnnouncementScheduler.reschedule(this@MainActivity)
            if (Build.VERSION.SDK_INT >= 35 && SettingsStore.get(this@MainActivity).batteryEnabled) {
                SettingsStore.setBatteryEnabled(this@MainActivity, false)
                BatteryWorker.cancel(this@MainActivity)
            }
        }
    }
}

@Composable
private fun VoiceClockScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember(context) { SettingsStore.flow(context) }.collectAsState(initial = AppSettings())
    var now by remember { mutableStateOf(Instant.now()) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabFocus = remember { List(3) { FocusRequester() } }
    var editingSummer by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var morning by rememberSaveable { mutableStateOf(true) }
    var previewSlot by rememberSaveable { mutableIntStateOf(35) }
    var showSeasonDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showSimulationDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
    var simulation by remember { mutableStateOf(SimulationState()) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var playingText by remember { mutableStateOf<String?>(null) }
    var playingRawId by remember { mutableStateOf<Int?>(null) }
    var saveStatus by remember { mutableStateOf("已自动保存") }
    var summerDraft by remember(settings.summerStart) { mutableStateOf(settings.summerStart) }
    var winterDraft by remember(settings.winterStart) { mutableStateOf(settings.winterStart) }
    var dateError by remember { mutableStateOf("") }
    var sleepStartDraft by remember(settings.sleepStartMinutes) { mutableIntStateOf(settings.sleepStartMinutes) }
    var sleepEndDraft by remember(settings.sleepEndMinutes) { mutableIntStateOf(settings.sleepEndMinutes) }
    var sleepError by remember { mutableStateOf("") }
    var thresholdDraft by remember(settings.threshold) { mutableFloatStateOf(settings.threshold.toFloat()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    val summerStart = ClockLogic.parseStart(settings.summerStart) ?: MonthDay.of(6, 1)
    val winterStart = ClockLogic.parseStart(settings.winterStart) ?: MonthDay.of(12, 1)
    val currentIsSummer = ClockLogic.isSummer(
        now.atZone(ClockLogic.beijingZone).toLocalDate(),
        summerStart,
        winterStart,
    )
    val upcoming = remember(
        now.epochSecond / 60,
        settings.announcementsEnabled,
        settings.summerStart,
        settings.winterStart,
        settings.summerMask,
        settings.winterMask,
    ) {
        if (settings.announcementsEnabled) {
            upcomingAnnouncements(now, summerStart, winterStart, settings.summerMask, settings.winterMask)
        } else {
            emptyList()
        }
    }

    fun save(action: suspend () -> Unit) {
        scope.launch {
            saveStatus = "正在保存…"
            try {
                action()
                saveStatus = "已自动保存"
            } catch (_: IOException) {
                saveStatus = "保存失败，请重试"
                Toast.makeText(context, saveStatus, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        playingText = null
        playingRawId = null
    }

    fun playPreview(rawId: Int, phrase: String) {
        val previous = previewJob
        previous?.cancel()
        previewJob = scope.launch {
            previous?.join()
            playingText = phrase
            playingRawId = rawId
            try {
                val played = withTimeoutOrNull(15_000) { VoicePlayer.play(context, rawId) } == true
                if (!played) Toast.makeText(context, "试听未播放，请检查音量后重试", Toast.LENGTH_SHORT).show()
            } finally {
                playingText = null
                playingRawId = null
            }
        }
    }
    val playAnnouncement: (Int) -> Unit = { playPreview(VoiceAssets.announcements[it], ClockLogic.announcementText(it)) }
    val playBattery: () -> Unit = { playPreview(R.raw.battery_low, ClockLogic.batteryText()) }
    val reschedule: suspend () -> Unit = { AnnouncementScheduler.reschedule(context) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 860.dp
            val contentWidth = minOf(maxWidth - 32.dp, 1136.dp)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(contentWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 18.dp, bottom = if (playingText == null) 18.dp else 90.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    maxItemsInEachRow = if (wide) 2 else 1,
                ) {
                    Column(Modifier.width(if (wide) 330.dp else contentWidth)) {
                        ClockPreview(
                            now = now,
                            compact = !wide,
                            currentIsSummer = currentIsSummer,
                            settings = settings,
                            upcoming = upcoming,
                            onListenNext = { upcoming.firstOrNull()?.let { playAnnouncement(ClockLogic.announcementSlot(it)) } },
                        )
                        if (settings.residentServiceFailed) {
                            Notice("常驻后台服务未能自动启动，重新打开应用或调整系统自启动设置后可恢复。")
                        }
                        if (wide) {
                            UpcomingOverview(now, settings, upcoming, onTab = { selectedTab = it; tabFocus[it].requestFocus() })
                        }
                    }

                    Card(
                        modifier = Modifier.width(if (wide) contentWidth - 358.dp else contentWidth),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        val tabs = listOf("报时计划", "电量提醒", "睡眠免扰")
                        SecondaryTabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEachIndexed { index, label ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    modifier = Modifier.focusRequester(tabFocus[index]).onPreviewKeyEvent { event ->
                                        val target = when (event.key) {
                                            Key.DirectionRight -> (index + 1) % tabs.size
                                            Key.DirectionLeft -> (index + tabs.size - 1) % tabs.size
                                            Key.MoveHome -> 0
                                            Key.MoveEnd -> tabs.lastIndex
                                            else -> null
                                        }
                                        if (event.type == KeyEventType.KeyDown && target != null) {
                                            selectedTab = target
                                            tabFocus[target].requestFocus()
                                            true
                                        } else false
                                    },
                                    text = { Text(label, maxLines = 1, fontSize = 12.sp) },
                                )
                            }
                        }
                        when (selectedTab) {
                            0 -> SchedulePanel(
                                settings = settings,
                                currentIsSummer = currentIsSummer,
                                editingSummer = editingSummer ?: currentIsSummer,
                                morning = morning,
                                previewSlot = previewSlot,
                                onAnnouncementsEnabled = { enabled ->
                                    save {
                                        SettingsStore.setAnnouncementsEnabled(context, enabled)
                                        if (enabled && !AnnouncementScheduler.canSchedule(context)) {
                                            context.startActivity(
                                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                    data = ("package:" + context.packageName).toUri()
                                                },
                                            )
                                        }
                                        reschedule()
                                    }
                                },
                                onEditingSummer = { editingSummer = it },
                                onAdjustSeason = {
                                    summerDraft = settings.summerStart
                                    winterDraft = settings.winterStart
                                    dateError = ""
                                    showSeasonDialog = true
                                },
                                onMorning = { morning = it },
                                onSetMask = { mask ->
                                    val summer = editingSummer ?: currentIsSummer
                                    save {
                                        SettingsStore.setScheduleMask(context, summer, mask)
                                        reschedule()
                                    }
                                },
                                onToggleSlot = { slot ->
                                    previewSlot = slot
                                    val summer = editingSummer ?: currentIsSummer
                                    save {
                                        SettingsStore.toggleScheduleSlot(context, summer, slot)
                                        reschedule()
                                    }
                                },
                                onListen = { playAnnouncement(previewSlot) },
                            )

                            1 -> BatteryPanel(
                                settings = settings,
                                thresholdDraft = thresholdDraft,
                                onEnabled = { enabled ->
                                    save {
                                        SettingsStore.setBatteryEnabled(context, enabled)
                                        if (enabled) BatteryWorker.schedule(context) else BatteryWorker.cancel(context)
                                    }
                                },
                                onThresholdChange = { thresholdDraft = it },
                                onThresholdSave = {
                                    val threshold = thresholdDraft.roundToInt()
                                    save { SettingsStore.setThreshold(context, threshold) }
                                },
                                onMaxReminders = { save { SettingsStore.setMaxReminders(context, it) } },
                                onInterval = { save { SettingsStore.setIntervalMinutes(context, it) } },
                                onListen = playBattery,
                            )

                            else -> SleepPanel(
                                settings = settings,
                                startDraft = sleepStartDraft,
                                endDraft = sleepEndDraft,
                                error = sleepError,
                                onEnabled = { save { SettingsStore.setSleepEnabled(context, it) } },
                                onStart = { start ->
                                    sleepStartDraft = start
                                    if (ClockLogic.validSleepTimes(start, sleepEndDraft)) {
                                        sleepError = ""
                                        val end = sleepEndDraft
                                        save { SettingsStore.setSleepTimes(context, start, end) }
                                    } else {
                                        sleepError = "开始与结束时间不能相同，尚未保存。"
                                    }
                                },
                                onEnd = { end ->
                                    sleepEndDraft = end
                                    if (ClockLogic.validSleepTimes(sleepStartDraft, end)) {
                                        sleepError = ""
                                        val start = sleepStartDraft
                                        save { SettingsStore.setSleepTimes(context, start, end) }
                                    } else {
                                        sleepError = "开始与结束时间不能相同，尚未保存。"
                                    }
                                },
                            )
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (saveStatus == "已自动保存") "✓ $saveStatus" else saveStatus, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoreActions(
                                onBatteryOptimization = {
                                    val pm = context.getSystemService(PowerManager::class.java)
                                    if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                        Toast.makeText(context, "已加入电池优化白名单", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showBatteryOptimizationDialog = true
                                    }
                                },
                                onSimulation = { showSimulationDialog = true },
                                onReset = { showResetDialog = true },
                            )
                        }
                    }
                }
                Text(
                    "先轻轻「叮」一声，再用普通话报时。",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            playingText?.let { phrase ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).width(contentWidth),
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("试听中 · $phrase", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp)
                        TextButton(onClick = ::stopPreview) { Text("停止试听") }
                    }
                }
            }
        }
    }

    if (showSeasonDialog) {
        AlertDialog(
            onDismissRequest = { showSeasonDialog = false },
            title = { Text("季节切换日期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("每年从所选日期开始，自动使用对应作息。")
                    SeasonDateDropdowns("夏季开始", summerDraft, dateError) { summerDraft = it }
                    SeasonDateDropdowns("冬季开始", winterDraft, dateError) { winterDraft = it }
                    if (dateError.isNotEmpty()) Text(dateError, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (!ClockLogic.startsAreValid(summerDraft, winterDraft)) {
                        dateError = "夏季和冬季的开始日期不能相同。"
                    } else {
                        save {
                            SettingsStore.setSeasonStarts(context, summerDraft, winterDraft)
                            reschedule()
                            showSeasonDialog = false
                        }
                    }
                }) { Text("保存日期") }
            },
            dismissButton = { TextButton(onClick = { showSeasonDialog = false }) { Text("取消") } },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置？") },
            text = { Text("将关闭语音报时和低电量提醒，恢复夏冬常用作息、电量提醒和睡眠时段。") },
            confirmButton = {
                Button(onClick = {
                    stopPreview()
                    save {
                        SettingsStore.reset(context)
                        BatteryWorker.cancel(context)
                        reschedule()
                        editingSummer = null
                        morning = true
                        previewSlot = 35
                        sleepStartDraft = 22 * 60
                        sleepEndDraft = 7 * 60
                        sleepError = ""
                        simulation = SimulationState()
                        showResetDialog = false
                    }
                }) { Text("恢复默认") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } },
        )
    }

    if (showBatteryOptimizationDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            title = { Text("电池优化设置") },
            text = { Text("系统电池优化（Doze）可能在部分设备上暂停后台闹钟，导致报时延迟或无法准时播放。加入电池优化白名单可让报时按计划运行。") },
            confirmButton = {
                Button(onClick = {
                    showBatteryOptimizationDialog = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = ("package:" + context.packageName).toUri()
                            },
                        )
                    }.onFailure {
                        Toast.makeText(context, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { showBatteryOptimizationDialog = false }) { Text("取消") } },
        )
    }

    SimulationDialog(
        visible = showSimulationDialog,
        settings = settings,
        state = simulation,
        onState = { simulation = it },
        playing = playingRawId == R.raw.battery_low,
        onListen = { if (playingRawId == R.raw.battery_low) stopPreview() else playBattery() },
        onDismiss = { showSimulationDialog = false },
    )
}

@Composable
private fun MoreActions(onBatteryOptimization: () -> Unit, onSimulation: () -> Unit, onReset: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "更多操作" }) { Text("•••") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("电池优化设置") }, onClick = { expanded = false; onBatteryOptimization() })
            DropdownMenuItem(text = { Text("场景测试") }, onClick = { expanded = false; onSimulation() })
            DropdownMenuItem(text = { Text("恢复默认") }, onClick = { expanded = false; onReset() })
        }
    }
}

@Composable
private fun ClockPreview(
    now: Instant,
    compact: Boolean,
    currentIsSummer: Boolean,
    settings: AppSettings,
    upcoming: List<Instant>,
    onListenNext: () -> Unit,
) {
    val currentMask = if (currentIsSummer) settings.summerMask else settings.winterMask
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = if (compact) 20.dp else 24.dp, vertical = if (compact) 14.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◷  北京时间", modifier = Modifier.weight(1f), color = Color.White, fontSize = 12.sp)
                Surface(
                    color = Color.Transparent, shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                ) {
                    Text(
                        if (currentIsSummer) "夏季作息" else "冬季作息",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.White, fontSize = 11.sp,
                    )
                }
            }
            Column(Modifier.padding(vertical = if (compact) 6.dp else 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(CLOCK_FORMATTER.format(now), color = Color.White, fontSize = if (compact) 52.sp else 58.sp, fontWeight = FontWeight.Light)
                    Text(
                        if (now.atZone(ClockLogic.beijingZone).hour < 12) "上午" else "下午",
                        modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
                        color = Color(0xFFE8EBFF), fontSize = 12.sp,
                    )
                }
                Text(DATE_FORMATTER.format(now), color = Color(0xFFE2E7FF), fontSize = 11.sp)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (settings.announcementsEnabled) "●  报时已开启" else "○  报时已关闭",
                    modifier = Modifier.weight(1f), color = Color(0xFFE8EBFF), fontSize = 11.sp,
                )
                Text(
                    if (settings.announcementsEnabled) "每日 ${java.lang.Long.bitCount(currentMask)} 次" else "计划已暂停",
                    color = Color(0xFFE8EBFF), fontSize = 11.sp,
                )
            }
        }
        val next = upcoming.firstOrNull()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = if (compact) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (next == null) "下一次报时" else "下一次报时 · ${dayLabel(now, next)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                    if (next != null) Text(countdownText(now, next), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                }
                Text(
                    next?.let(::announcementTimeLabel) ?: if (settings.announcementsEnabled) "等待安排" else "已暂停",
                    fontWeight = FontWeight.SemiBold, fontSize = 23.sp,
                )
            }
            PlayButton("试听下一次报时", enabled = next != null, onClick = onListenNext)
        }
    }
}

@Composable
private fun UpcomingOverview(now: Instant, settings: AppSettings, upcoming: List<Instant>, onTab: (Int) -> Unit) {
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("接下来", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("普通话 · 12 小时制", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        if (upcoming.isEmpty()) Text("暂无报时计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
        upcoming.forEachIndexed { index, instant ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (index == 0) "●" else "○", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shortSlot(ClockLogic.announcementSlot(instant)), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${dayLabel(now, instant)} ${if (instant.atZone(ClockLogic.beijingZone).hour < 12) "上午" else "下午"}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (index == 0) "下一次" else "已安排", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryBox("电量提醒", if (settings.batteryEnabled) "低于 ${settings.threshold}%" else "已关闭", Modifier.weight(1f)) { onTab(1) }
            SummaryBox(
                "睡眠时段",
                if (settings.sleepEnabled) "${formatMinutes(settings.sleepStartMinutes)} — ${formatMinutes(settings.sleepEndMinutes)}" else "已关闭",
                Modifier.weight(1f),
            ) { onTab(2) }
        }
    }
}

@Composable
private fun SummaryBox(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier, color = Color.White, shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SchedulePanel(
    settings: AppSettings,
    currentIsSummer: Boolean,
    editingSummer: Boolean,
    morning: Boolean,
    previewSlot: Int,
    onAnnouncementsEnabled: (Boolean) -> Unit,
    onEditingSummer: (Boolean) -> Unit,
    onAdjustSeason: () -> Unit,
    onMorning: (Boolean) -> Unit,
    onSetMask: (Long) -> Unit,
    onToggleSlot: (Int) -> Unit,
    onListen: () -> Unit,
) {
    val mask = if (editingSummer) settings.summerMask else settings.winterMask
    val anySchedule = settings.summerMask != 0L || settings.winterMask != 0L
    PanelColumn {
        SectionHeading("语音报时", settings.announcementsEnabled, anySchedule, onAnnouncementsEnabled)
        val currentMask = if (currentIsSummer) settings.summerMask else settings.winterMask
        if (!anySchedule) Notice("请先选择时刻，再开启报时。")
        else if (currentMask == 0L) Notice("当前${if (currentIsSummer) "夏季" else "冬季"}作息为空，暂无近期报时。")
        if (!AnnouncementScheduler.canSchedule(androidx.compose.ui.platform.LocalContext.current)) {
            Notice("需要系统“闹钟和提醒”授权，报时才能准时触发。")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeasonCard(
                title = "夏季作息",
                icon = "☀",
                range = seasonRangeText(settings.summerStart, settings.winterStart),
                selected = editingSummer,
                active = currentIsSummer,
                modifier = Modifier.weight(1f),
            ) { onEditingSummer(true) }
            SeasonCard(
                title = "冬季作息",
                icon = "❄",
                range = seasonRangeText(settings.winterStart, settings.summerStart),
                selected = !editingSummer,
                active = !currentIsSummer,
                modifier = Modifier.weight(1f),
            ) { onEditingSummer(false) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "↻ 按日期自动切换",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdjustSeason) { Text("调整日期 ›", fontSize = 11.sp) }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("报时时刻", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Surface(color = SoftBlue, shape = RoundedCornerShape(7.dp)) {
                Text(
                    "已选 ${java.lang.Long.bitCount(mask)} 个",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        val commonMask = ClockLogic.defaultScheduleMask(editingSummer)
        val presets = listOf(
            "常用时段" to commonMask,
            "全部整点" to ALL_HOURS_MASK,
            "日间半小时" to DAYTIME_MASK,
            "清空" to 0L,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { (label, preset) ->
                OutlinedButton(
                    onClick = { onSetMask(preset) }, shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(label, fontSize = 11.sp) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFF2F3F8), RoundedCornerShape(9.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PeriodTab("上午", java.lang.Long.bitCount(mask and MORNING_MASK), morning, Modifier.weight(1f)) { onMorning(true) }
            PeriodTab("下午", java.lang.Long.bitCount(mask and AFTERNOON_MASK), !morning, Modifier.weight(1f)) { onMorning(false) }
        }
        ScheduleGrid(mask = mask, startSlot = if (morning) 0 else 24, onToggle = onToggleSlot)
        AudioStrip("报时试听", ClockLogic.announcementText(previewSlot), onListen)
    }
}

@Composable
private fun BatteryPanel(
    settings: AppSettings,
    thresholdDraft: Float,
    onEnabled: (Boolean) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onThresholdSave: () -> Unit,
    onMaxReminders: (Int) -> Unit,
    onInterval: (Int) -> Unit,
    onListen: () -> Unit,
) {
    PanelColumn {
        SectionHeading("低电量提醒", settings.batteryEnabled, Build.VERSION.SDK_INT < 35, onEnabled)
        if (Build.VERSION.SDK_INT >= 35) Notice("Android 15+ 暂不支持后台低电量语音，前台试听仍可使用。")
        Surface(color = SoftBlue, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("电量低于", modifier = Modifier.weight(1f))
                    Text("${thresholdDraft.roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 46.sp, fontWeight = FontWeight.Normal)
                }
                Slider(
                    value = thresholdDraft,
                    onValueChange = onThresholdChange,
                    onValueChangeFinished = onThresholdSave,
                    valueRange = 5f..99f,
                    steps = 93,
                    modifier = Modifier.semantics { contentDescription = "低电量阈值，${thresholdDraft.roundToInt()}%" },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("5%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("99%", style = MaterialTheme.typography.bodySmall)
                }
                Text("低于 ${thresholdDraft.roundToInt()}% 时提醒充电", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingRow("提醒次数", "充电或电量恢复后重新计数") {
            NumberDropdown(
                value = settings.maxReminders,
                options = 1..10,
                enabled = true,
                modifier = Modifier.width(116.dp),
                display = { "$it 次" },
                label = "每轮提醒次数",
                onSelect = onMaxReminders,
            )
        }
        SettingRow("提醒间隔", "") {
            NumberDropdown(
                value = settings.intervalMinutes,
                options = listOf(5, 15, 30, 60),
                enabled = true,
                modifier = Modifier.width(116.dp),
                display = { "$it 分钟" },
                label = "提醒间隔",
                onSelect = onInterval,
            )
        }
        AudioStrip("提示音 + 语音", ClockLogic.batteryText(), onListen)
        Text(
            buildString {
                append("系统省电可能让检测和提醒延迟；配置的间隔是两次成功提醒的最短间隔。")
                if (settings.lastCheckedAtMillis > 0) {
                    append(" 最近检查：")
                    append(LAST_CHECK_FORMATTER.format(Instant.ofEpochMilli(settings.lastCheckedAtMillis)))
                }
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (settings.sleepEnabled) {
                "☾ 睡眠时段 ${formatMinutes(settings.sleepStartMinutes)}–${formatMinutes(settings.sleepEndMinutes)}，最多提醒一次。"
            } else {
                "睡眠免扰已关闭。"
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepPanel(
    settings: AppSettings,
    startDraft: Int,
    endDraft: Int,
    error: String,
    onEnabled: (Boolean) -> Unit,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit,
) {
    PanelColumn {
        SectionHeading("睡眠免扰", settings.sleepEnabled, onCheckedChange = onEnabled)
        Surface(color = NightBlue, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("睡眠时段", color = Color(0xFFD4D8F3), style = MaterialTheme.typography.labelMedium)
                Text("${formatMinutes(settings.sleepStartMinutes)} — ${formatMinutes(settings.sleepEndMinutes)}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                Text(
                    if (!settings.sleepEnabled) "睡眠免扰已关闭"
                    else if (settings.sleepStartMinutes > settings.sleepEndMinutes) "次日结束"
                    else "当天结束",
                    color = Color(0xFFCDD3EF),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeDropdown("开始时间", startDraft, Modifier.weight(1f), settings.sleepEnabled, error, onStart)
            TimeDropdown("结束时间", endDraft, Modifier.weight(1f), settings.sleepEnabled, error, onEnd)
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(
            if (settings.sleepEnabled) "睡眠时段忽略重复间隔，醒来后恢复剩余提醒。" else "全天按设定次数与间隔提醒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("睡眠期间的低电量提醒", Modifier.weight(1f), fontSize = 13.sp)
            Text(if (settings.sleepEnabled) "最多 1 次" else "按次数设置", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        HorizontalDivider()
        Text("仅影响电量提醒，语音报时仍按计划执行。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PanelColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SectionHeading(title: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(if (checked) "已开启" else "已关闭", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { contentDescription = "启用$title" })
    }
}

@Composable
private fun SeasonCard(
    title: String,
    icon: String,
    range: String,
    selected: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) SoftBlue else Color.White),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 22.sp)
                Spacer(Modifier.width(7.dp))
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(range, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(if (active) "生效中" else " ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PeriodTab(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected; role = Role.Tab },
        color = if (selected) Color.White else Color.Transparent,
        shape = RoundedCornerShape(7.dp),
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Text(
            "$label  $count",
            modifier = Modifier.padding(vertical = 9.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleGrid(mask: Long, startSlot: Int, onToggle: (Int) -> Unit) {
    BoxWithConstraints {
        val columns = if (maxWidth >= 440.dp) 6 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (startSlot until startSlot + 24).chunked(columns).forEach { rowSlots ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowSlots.forEach { slot ->
                        val chosen = mask and (1L shl slot) != 0L
                        Surface(
                            selected = chosen,
                            onClick = { onToggle(slot) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                contentDescription = formatMinutes(slot * 30)
                                role = Role.Button
                            },
                            shape = RoundedCornerShape(9.dp),
                            color = if (chosen) MaterialTheme.colorScheme.primary else Color.White,
                            contentColor = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(formatMinutes(slot * 30), fontSize = 13.sp, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioStrip(label: String, phrase: String, onListen: () -> Unit) {
    Surface(color = Color(0xFFF7F8FC), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("♪  $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(phrase, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
            PlayButton("试听$phrase", onClick = onListen)
        }
    }
}

@Composable
private fun PlayButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(50),
    ) { Text("▶") }
}

@Composable
private fun Notice(text: String) {
    Surface(color = Color(0xFFFBF5E8), shape = RoundedCornerShape(9.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFF87672B), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

@Composable
private fun TimeDropdown(
    label: String,
    minutes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    validationError: String,
    onChange: (Int) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberDropdown(
            value = minutes / 30, options = 0..47, enabled = enabled,
            modifier = Modifier.semantics { if (validationError.isNotEmpty()) error(validationError) },
            display = { formatMinutes(it * 30) }, label = "睡眠$label",
        ) { onChange(it * 30) }
    }
}

@Composable
private fun SeasonDateDropdowns(label: String, value: String, validationError: String, onChange: (String) -> Unit) {
    val date = requireNotNull(ClockLogic.parseStart(value))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberDropdown(
                date.monthValue, 1..12, true,
                Modifier.weight(1f).semantics { if (validationError.isNotEmpty()) error(validationError) },
                display = { "${it}月" }, label = "${label}月份",
            ) { onChange(ClockLogic.seasonStartForMonth(value, it)) }
            NumberDropdown(
                date.dayOfMonth, 1..date.month.length(false), true,
                Modifier.weight(1f).semantics { if (validationError.isNotEmpty()) error(validationError) },
                display = { "${it}日" }, label = "${label}日期",
            ) { onChange(MonthDay.of(date.month, it).toString().removePrefix("--")) }
        }
    }
}

@Composable
private fun NumberDropdown(
    value: Int,
    options: Iterable<Int>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    display: (Int) -> String = { "%02d".format(it) },
    label: String,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label，${display(value)}" },
            shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(display(value), modifier = Modifier.weight(1f), maxLines = 1, fontSize = 13.sp)
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    modifier = Modifier.semantics { selected = value == option },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SimulationDialog(
    visible: Boolean,
    settings: AppSettings,
    state: SimulationState,
    onState: (SimulationState) -> Unit,
    playing: Boolean,
    onListen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var time by remember { mutableStateOf(Instant.now().atZone(ClockLogic.beijingZone).truncatedTo(ChronoUnit.MINUTES)) }
    var battery by remember { mutableFloatStateOf((settings.threshold - 1).toFloat()) }
    var charging by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible && !initialized) {
            time = Instant.now().atZone(ClockLogic.beijingZone).truncatedTo(ChronoUnit.MINUTES)
            battery = (settings.threshold - 1).toFloat()
            initialized = true
        }
    }

    LaunchedEffect(state) {
        if (state == SimulationState()) {
            logs = emptyList()
            validationError = ""
        }
    }

    fun check(at: java.time.ZonedDateTime) {
        time = at
        if (state.lastAt != null && at.toInstant() < state.lastAt) {
            validationError = "时间早于上次提醒，请推进时间或重新测试。"
            return
        }
        validationError = ""
        val next = state.check(at.toInstant(), battery.roundToInt(), charging, settings)
        onState(next)
        val stamp = at.format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT))
        logs = (listOf("$stamp · ${battery.roundToInt()}%${if (charging) " · 充电中" else ""} · ${next.message}") + logs).take(8)
    }

    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("场景测试") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("使用模拟时间与电量，不触发实际提醒。", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("日间低电量", "睡眠低电量", "正在充电").forEachIndexed { index, label ->
                        TextButton(onClick = {
                            charging = index == 2
                            if (!charging) {
                                val minute = if (index == 1) settings.sleepStartMinutes else 12 * 60
                                time = time.toLocalDate().atTime(LocalTime.of(minute / 60, minute % 60)).atZone(ClockLogic.beijingZone)
                                battery = (settings.threshold - 1).toFloat()
                            }
                            validationError = ""
                        }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label, fontSize = 11.sp) }
                    }
                }
                Column {
                    Text("模拟日期 · 北京时间", fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            DatePickerDialog(context, { _, year, month, day ->
                                time = LocalDate.of(year, month + 1, day).atTime(time.toLocalTime()).atZone(ClockLogic.beijingZone)
                            }, time.year, time.monthValue - 1, time.dayOfMonth).show()
                        }, modifier = Modifier.semantics { contentDescription = "模拟日期" }) {
                            Text(time.toLocalDate().toString(), fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, hour, minute ->
                                time = time.withHour(hour).withMinute(minute)
                            }, time.hour, time.minute, true).show()
                        }, modifier = Modifier.semantics { contentDescription = "模拟北京时间" }) {
                            Text(formatMinutes(time.hour * 60 + time.minute))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("模拟电量")
                    Text("${battery.roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = battery, onValueChange = { battery = it }, valueRange = 0f..100f, steps = 99,
                    modifier = Modifier.semantics { contentDescription = "模拟电量，${battery.roundToInt()}%" },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(charging, onCheckedChange = { charging = it }, modifier = Modifier.semantics { contentDescription = "正在充电" })
                    Text("正在充电")
                }
                if (validationError.isNotEmpty()) Text(
                    validationError, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { check(time) }) { Text("检查提醒", fontSize = 12.sp) }
                    OutlinedButton(onClick = { check(time.plusMinutes(settings.intervalMinutes.toLong())) }) {
                        Text("推进 ${settings.intervalMinutes} 分钟", fontSize = 12.sp)
                    }
                }
                Surface(color = SoftBlue, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val summer = ClockLogic.isSummer(time.toLocalDate(), MonthDay.parse("--${settings.summerStart}"), MonthDay.parse("--${settings.winterStart}"))
                        val sleep = settings.sleepEnabled && ClockLogic.currentSleepWindowStart(time.toInstant(), settings.sleepStartMinutes, settings.sleepEndMinutes) != null
                        Text("${if (summer) "夏季" else "冬季"}作息 · ${if (sleep) "睡眠时段" else "非睡眠时段"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (logs.isEmpty()) "准备就绪" else if (state.reminded) "将提醒：${ClockLogic.batteryText()}" else "本次不提醒充电",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(state.message, fontSize = 12.sp)
                        Text("本轮已模拟提醒 ${state.count} / ${settings.maxReminders} 次。", fontSize = 12.sp)
                        val scheduled = settings.announcementsEnabled && time.minute % 30 == 0 && ClockLogic.isAnnouncementEnabled(
                            time.toInstant(), MonthDay.parse("--${settings.summerStart}"), MonthDay.parse("--${settings.winterStart}"), settings.summerMask, settings.winterMask,
                        )
                        Text(
                            if (scheduled) "此刻也有报时计划：${ClockLogic.announcementText(ClockLogic.announcementSlot(time.toInstant()))}" else "此刻没有需要执行的报时计划。",
                            fontSize = 12.sp,
                        )
                    }
                }
                logs.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onState(SimulationState()); logs = emptyList(); validationError = "" }) { Text("重新测试") }
                    OutlinedButton(onClick = onListen) { Text(if (playing) "停止试听" else "试听低电量语音") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun upcomingAnnouncements(
    now: Instant,
    summerStart: MonthDay,
    winterStart: MonthDay,
    summerMask: Long,
    winterMask: Long,
): List<Instant> = buildList {
    var cursor = now
    repeat(3) {
        val next = ClockLogic.nextAnnouncement(cursor, summerStart, winterStart, summerMask, winterMask)
            ?: return@buildList
        add(next)
        cursor = next
    }
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun shortSlot(slot: Int): String {
    val hour = (slot / 2 % 12).takeUnless { it == 0 } ?: 12
    return "%02d:%02d".format(hour, if (slot % 2 == 0) 0 else 30)
}

private fun announcementTimeLabel(instant: Instant): String {
    val time = instant.atZone(ClockLogic.beijingZone)
    return (if (time.hour < 12) "上午 " else "下午 ") + shortSlot(time.hour * 2 + time.minute / 30)
}

private fun countdownText(now: Instant, next: Instant): String {
    val minutes = (Duration.between(now, next).toMillis().coerceAtLeast(0) + 59_999) / 60_000
    return when {
        minutes < 60 -> "${minutes} 分钟后"
        minutes < 24 * 60 -> "${minutes / 60}小时${if (minutes % 60 == 0L) "" else "${minutes % 60}分"}后"
        else -> "${minutes / (24 * 60)} 天后"
    }
}

private fun dayLabel(now: Instant, next: Instant): String {
    val today = now.atZone(ClockLogic.beijingZone).toLocalDate()
    val date = next.atZone(ClockLogic.beijingZone).toLocalDate()
    return when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "今天"
        1L -> "明天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

private fun seasonRangeText(start: String, otherStart: String): String {
    val startMonthDay = ClockLogic.parseStart(start) ?: return "日期无效"
    val otherMonthDay = ClockLogic.parseStart(otherStart) ?: return "日期无效"
    val end = LocalDate.of(2001, otherMonthDay.month, otherMonthDay.dayOfMonth).minusDays(1)
    val endLabel = if (otherStart == "03-01") "02.末" else "%02d.%02d".format(end.monthValue, end.dayOfMonth)
    return startMonthDay.toString().removePrefix("--").replace('-', '.') + " — " + endLabel
}

private val ALL_HOURS_MASK = (0 until 48 step 2).fold(0L) { mask, slot -> mask or (1L shl slot) }
private val DAYTIME_MASK = (14..42).fold(0L) { mask, slot -> mask or (1L shl slot) }
private val MORNING_MASK = (1L shl 24) - 1
private val AFTERNOON_MASK = MORNING_MASK shl 24

private val VoiceClockColors = lightColorScheme(
    primary = Color(0xFF3E55CE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0F2FF),
    onPrimaryContainer = Color(0xFF2E40A3),
    background = Color(0xFFF7F6F2),
    onBackground = Color(0xFF222738),
    surface = Color.White,
    onSurface = Color(0xFF222738),
    surfaceVariant = Color(0xFFF7F8FC),
    onSurfaceVariant = Color(0xFF6E7280),
    outline = Color(0xFFBFC5DA),
    outlineVariant = Color(0xFFE8E8EE),
)

private val SoftBlue = Color(0xFFF0F2FF)
private val NightBlue = Color(0xFF252D51)

private val LAST_CHECK_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ClockLogic.beijingZone)
private val CLOCK_FORMATTER = DateTimeFormatter.ofPattern("hh:mm").withZone(ZoneId.of("Asia/Shanghai"))
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE).withZone(ClockLogic.beijingZone)
