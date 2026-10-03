param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Adb = 'adb'
)

& $Adb -s $Device shell pm enable --user 0 com.amazon.tv.launcher
if ($LASTEXITCODE -ne 0) { throw 'Could not re-enable Amazon Home.' }
& $Adb -s $Device shell pm enable --user 0 com.amazon.tv.launcher/.ui.HomeActivity_vNext
& $Adb -s $Device shell cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext
& $Adb -s $Device shell input keyevent 3
