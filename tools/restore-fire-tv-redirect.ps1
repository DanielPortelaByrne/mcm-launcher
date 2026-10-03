param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Adb = 'adb'
)

$mcmService = 'com.example.tvlauncher/com.example.tvlauncher.system.FireHomeService'
$existing = (& $Adb -s $Device shell settings get secure enabled_accessibility_services).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not read accessibility services.' }
$remaining = @($existing -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $mcmService })
if ($remaining.Count -gt 0) {
    $serviceList = $remaining -join ':'
    if ($serviceList -notmatch '^[A-Za-z0-9_./:]+$') { throw 'Unexpected service identifier; no settings changed.' }
    & $Adb -s $Device shell settings put secure enabled_accessibility_services $serviceList
} else {
    & $Adb -s $Device shell settings delete secure enabled_accessibility_services
    & $Adb -s $Device shell settings put secure accessibility_enabled 0
}
& $Adb -s $Device shell cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext
& $Adb -s $Device shell input keyevent 3
