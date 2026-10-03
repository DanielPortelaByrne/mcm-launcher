#!/system/bin/sh
# Temporary, device-side Home redirect. Launch from an authorized ADB shell.
# Stops automatically; does not change Home, accessibility, or boot settings.
seconds=${1:-120}
case "$seconds" in ''|*[!0-9]*) exit 2 ;; esac
[ "$seconds" -ge 15 ] && [ "$seconds" -le 300 ] || exit 2
echo "MCM Home trial started for $seconds seconds"
timeout "$seconds" logcat -T 1 -v brief 'ActivityManager:I' '*:S' |
while IFS= read -r line; do
    case "$line" in
        *'START u0'*'category.HOME'*'cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext'*)
            echo "Home detected; opening MCM"
            am start -n com.example.tvlauncher/.MainActivity
            ;;
    esac
done
echo 'MCM Home trial finished'
