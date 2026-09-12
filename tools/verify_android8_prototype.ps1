[CmdletBinding()]
param(
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.android-sdk\platform-tools\adb.exe'
if (-not $ApkPath) {
    $ApkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw "未找到项目内 ADB：$adb"
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "未找到 APK：$ApkPath。请先执行 .\gradlew.bat assembleDebug。"
}

$deviceLines = @(& $adb devices -l)
$serials = @(
    $deviceLines |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '^(\S+)\s+device(?:\s|$)' } |
        ForEach-Object { $Matches[1] }
)
if ($serials.Count -ne 1) {
    throw "需要且只能连接一台已授权设备，当前发现 $($serials.Count) 台。请检查 USB 调试授权后重试。"
}

$serial = $serials[0]
$api = (& $adb -s $serial shell getprop ro.build.version.sdk).Trim()
$release = (& $adb -s $serial shell getprop ro.build.version.release).Trim()
$model = (& $adb -s $serial shell getprop ro.product.model).Trim()
if ([int]$api -lt 26) {
    throw "设备 $model 是 Android $release / API $api；本应用最低支持 Android 8（API 26）。"
}

$install = @(& $adb -s $serial install -r $ApkPath)
if ($LASTEXITCODE -ne 0 -or ($install -join "`n") -notmatch 'Success') {
    throw "APK 安装失败：$($install -join ' ')"
}

$launch = @(& $adb -s $serial shell am start -W -n 'com.cyvaction.voiceclock/.MainActivity')
if ($LASTEXITCODE -ne 0 -or ($launch -join "`n") -match 'Error') {
    throw "Activity 启动失败：$($launch -join ' ')"
}

[pscustomobject]@{
    Serial = $serial
    Model = $model
    Android = $release
    Api = $api
    Apk = (Resolve-Path -LiteralPath $ApkPath).Path
    Install = 'Success'
    Launch = 'Success'
}

Write-Host ''
Write-Host '完整功能人工验收：'
Write-Host '1. 分别设置夏季、冬季时段并启用报时；重启应用，确认设置、当前组和下一次报时均正确。'
Write-Host '2. 试听报时与低电量语音，确认每句前只有一次“叮”声，内容、0.82x 语速与清晰度正确。'
Write-Host '3. 选择接下来两个半小时时段，退到后台并熄屏，记录原定/实际播放时刻与下一次排程。'
Write-Host '4. 强制 Doze 前先选择下一时段并启用报时，再执行：'
Write-Host "   & '$adb' -s $serial shell dumpsys deviceidle force-idle"
Write-Host '5. 打开低电量提醒，再模拟未充电且 24% 电量；分别核对阈值、次数、间隔和睡眠窗口。'
Write-Host "   & '$adb' -s $serial shell dumpsys battery unplug"
Write-Host "   & '$adb' -s $serial shell dumpsys battery set level 24"
Write-Host '6. 测试结束必须恢复设备状态：'
Write-Host "   & '$adb' -s $serial shell dumpsys battery reset"
Write-Host "   & '$adb' -s $serial shell dumpsys deviceidle unforce"
