package com.cyvaction.voiceclock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ResidentService.start(this)
        setContent { MaterialTheme { VoiceClockScreen() } }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            AnnouncementScheduler.reschedule(this@MainActivity)
            if (Build.VERSION.SDK_INT >= 35) {
                SettingsStore.setBatteryEnabled(this@MainActivity, false)
                BatteryWorker.cancel(this@MainActivity)
            }
        }
    }
}

@Composable
private fun VoiceClockScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(context).collectAsState(initial = AppSettings())
    var now by remember { mutableStateOf(Instant.now()) }
    var editingSummer by rememberSaveable { mutableStateOf(true) }
    var previewSlot by rememberSaveable { mutableIntStateOf(14) }
    var summerDraft by remember(settings.summerStart) { mutableStateOf(settings.summerStart) }
    var winterDraft by remember(settings.winterStart) { mutableStateOf(settings.winterStart) }
    var dateError by remember { mutableStateOf("") }
    var sleepStartDraft by remember(settings.sleepStartMinutes) { mutableStateOf(formatMinutes(settings.sleepStartMinutes)) }
    var sleepEndDraft by remember(settings.sleepEndMinutes) { mutableStateOf(formatMinutes(settings.sleepEndMinutes)) }
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
    val currentIsSummer = ClockLogic.isSummer(now.atZone(ClockLogic.beijingZone).toLocalDate(), summerStart, winterStart)
    val currentMask = if (editingSummer) settings.summerMask else settings.winterMask
    val anySchedule = settings.summerMask != 0L || settings.winterMask != 0L

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("语音时钟", style = MaterialTheme.typography.headlineMedium)
            Text(BEIJING_FORMATTER.format(now) + " · 北京时间")
            Text("当前作息组：" + if (currentIsSummer) "夏季" else "冬季")
            Text(
                if (settings.plannedAtMillis > 0) "下一次报时：" + DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(settings.plannedAtMillis))
                else "下一次报时：未安排",
            )
            Text(
                if (AnnouncementScheduler.canSchedule(context)) "精确闹钟：可用"
                else "精确闹钟：需要系统授权",
                color = if (AnnouncementScheduler.canSchedule(context)) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            )

            SettingsCard("语音报时") {
                LabeledSwitch(
                    title = "启用语音报时",
                    subtitle = if (anySchedule) "按北京时间播放已选时段" else "请先选择至少一个时段",
                    checked = settings.announcementsEnabled,
                    enabled = anySchedule,
                ) { enabled ->
                    scope.launch {
                        SettingsStore.setAnnouncementsEnabled(context, enabled)
                        if (enabled && !AnnouncementScheduler.canSchedule(context)) {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = ("package:" + context.packageName).toUri()
                                },
                            )
                        }
                        AnnouncementScheduler.reschedule(context)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = editingSummer,
                        onClick = { editingSummer = true },
                        label = { Text("夏季" + if (currentIsSummer) " · 当前" else "") },
                    )
                    FilterChip(
                        selected = !editingSummer,
                        onClick = { editingSummer = false },
                        label = { Text("冬季" + if (!currentIsSummer) " · 当前" else "") },
                    )
                }
                Text(
                    (if (editingSummer) "夏季：" else "冬季：") +
                        seasonRangeText(
                            if (editingSummer) settings.summerStart else settings.winterStart,
                            if (editingSummer) settings.winterStart else settings.summerStart,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("全年归组起始日", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = summerDraft,
                        onValueChange = { summerDraft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("夏季 MM-DD") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = winterDraft,
                        onValueChange = { winterDraft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("冬季 MM-DD") },
                        singleLine = true,
                    )
                }
                if (dateError.isNotEmpty()) Text(dateError, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (!ClockLogic.startsAreValid(summerDraft, winterDraft)) {
                        dateError = "请输入两个不同的有效月日，不允许 02-29"
                    } else {
                        dateError = ""
                        scope.launch {
                            SettingsStore.setSeasonStarts(context, summerDraft, winterDraft)
                            AnnouncementScheduler.reschedule(context)
                        }
                    }
                }) { Text("保存归组日期") }

                Text("选择报时时段 · 已选 " + java.lang.Long.bitCount(currentMask) + "/48")
                ScheduleGrid(currentMask) { slot ->
                    previewSlot = slot
                    scope.launch {
                        SettingsStore.toggleScheduleSlot(context, editingSummer, slot)
                        AnnouncementScheduler.reschedule(context)
                    }
                }
                Text(
                    if ((if (currentIsSummer) settings.summerMask else settings.winterMask) == 0L) "当前作息组未设置报时"
                    else "当前作息组已有报时时段",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            val played = withTimeoutOrNull(15_000) {
                                VoicePlayer.play(context, VoiceAssets.announcements[previewSlot])
                            } == true
                            Toast.makeText(context, if (played) "试听完成" else "试听未播放", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("试听：" + ClockLogic.announcementText(previewSlot)) }
            }

            SettingsCard("低电量提醒") {
                LabeledSwitch(
                    title = "启用低电量提醒",
                    subtitle = if (Build.VERSION.SDK_INT >= 35) "Android 15+ 首版不支持后台低电量语音"
                    else "约每 15 分钟检查，系统省电可能延迟",
                    checked = settings.batteryEnabled,
                    enabled = Build.VERSION.SDK_INT < 35,
                ) { enabled ->
                    scope.launch {
                        SettingsStore.setBatteryEnabled(context, enabled)
                        if (enabled) BatteryWorker.schedule(context) else BatteryWorker.cancel(context)
                    }
                }

                Text("提醒阈值：" + thresholdDraft.toInt() + "%")
                Slider(
                    value = thresholdDraft,
                    onValueChange = { thresholdDraft = it },
                    onValueChangeFinished = { scope.launch { SettingsStore.setThreshold(context, thresholdDraft.toInt()) } },
                    valueRange = 5f..99f,
                    steps = 93,
                )
                Text("电量严格低于阈值时提醒，等于阈值不提醒", style = MaterialTheme.typography.bodySmall)

                NumberStepper("每轮最多提醒", settings.maxReminders, 1..10) {
                    scope.launch { SettingsStore.setMaxReminders(context, it) }
                }
                Text("非睡眠时段最短间隔")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 15, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = settings.intervalMinutes == minutes,
                            onClick = { scope.launch { SettingsStore.setIntervalMinutes(context, minutes) } },
                            label = { Text(minutes.toString() + " 分") },
                        )
                    }
                }

                LabeledSwitch(
                    title = "睡眠免打扰",
                    subtitle = "每个睡眠窗口最多成功提醒一次",
                    checked = settings.sleepEnabled,
                ) { scope.launch { SettingsStore.setSleepEnabled(context, it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sleepStartDraft,
                        onValueChange = { sleepStartDraft = it },
                        modifier = Modifier.weight(1f),
                        enabled = settings.sleepEnabled,
                        label = { Text("开始 HH:mm") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = sleepEndDraft,
                        onValueChange = { sleepEndDraft = it },
                        modifier = Modifier.weight(1f),
                        enabled = settings.sleepEnabled,
                        label = { Text("结束 HH:mm") },
                        singleLine = true,
                    )
                }
                if (sleepError.isNotEmpty()) Text(sleepError, color = MaterialTheme.colorScheme.error)
                Button(
                    enabled = settings.sleepEnabled,
                    onClick = {
                        val start = parseTime(sleepStartDraft)
                        val end = parseTime(sleepEndDraft)
                        if (start == null || end == null || !ClockLogic.validSleepTimes(start, end)) {
                            sleepError = "只能选择整点或半点，起止时间不能相同"
                        } else {
                            sleepError = ""
                            scope.launch { SettingsStore.setSleepTimes(context, start, end) }
                        }
                    },
                ) { Text("保存睡眠时段") }

                Text("本轮已成功提醒：" + settings.spokenCount + "/" + settings.maxReminders)
                Text(
                    if (settings.lastCheckedAtMillis == 0L) "最近检查：尚未检查"
                    else "最近检查：" + DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(settings.lastCheckedAtMillis)),
                )
                Text(
                    "两次检查之间短暂充电可能无法被观察到；系统省电可能使检测和提醒继续延迟。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            val played = withTimeoutOrNull(15_000) { VoicePlayer.play(context, R.raw.battery_low) } == true
                            Toast.makeText(context, if (played) "试听完成" else "试听未播放", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("试听：" + ClockLogic.batteryText()) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun LabeledSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Button(enabled = value > range.first, onClick = { onChange(value - 1) }) { Text("−") }
        Text(value.toString(), Modifier.padding(horizontal = 12.dp))
        Button(enabled = value < range.last, onClick = { onChange(value + 1) }) { Text("+") }
    }
}

@Composable
private fun ScheduleGrid(mask: Long, onToggle: (Int) -> Unit) {
    (0 until 48).chunked(4).forEach { rowSlots ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rowSlots.forEach { slot ->
                FilterChip(
                    selected = mask and (1L shl slot) != 0L,
                    onClick = { onToggle(slot) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    label = { Text(formatMinutes(slot * 30)) },
                )
            }
        }
    }
}

private fun parseTime(value: String): Int? {
    val match = Regex("^([01]\\d|2[0-3]):(00|30)$").matchEntire(value) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun seasonRangeText(start: String, otherStart: String): String {
    val startMonthDay = ClockLogic.parseStart(start) ?: return "日期无效"
    val otherMonthDay = ClockLogic.parseStart(otherStart) ?: return "日期无效"
    val end = LocalDate.of(2001, otherMonthDay.month, otherMonthDay.dayOfMonth).minusDays(1)
    val prefix = if (otherMonthDay <= startMonthDay) "次年 " else ""
    return start + " 至 " + prefix + "%02d-%02d".format(end.monthValue, end.dayOfMonth)
}

private val BEIJING_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.of("Asia/Shanghai"))
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Shanghai"))
