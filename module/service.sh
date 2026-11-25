#!/system/bin/sh
# Wi-Fi band preference
#
# Reads $MODDIR/mode and applies it after boot.
# Modes: auto | 5g-prefer | 5g-force | 2g-prefer | 2g-force
#
# The setting lives in the Wi-Fi framework (WifiNetworkSelectionConfig), so it
# is lost on reboot and has to be re-applied. It is not a vendor file patch.
# This script is run by magiskd, which can access the module directory even on
# setups where /data/adb is hidden from normal `su` sessions.

MODDIR=${0%/*}
LOG="$MODDIR/apply.log"

MODE=$(cat "$MODDIR/mode" 2>/dev/null | tr -d ' \t\r\n')
[ -z "$MODE" ] && MODE=auto
# auto is the framework default; nothing to restore after a reboot.
[ "$MODE" = "auto" ] && exit 0

# Wait for boot completion.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
    sleep 2
    i=$((i + 1))
done

# Wait until Wi-Fi is enabled. The connectivity manager must exist before
# setNetworkSelectionConfig() can be applied.
while true; do
    if cmd wifi status 2>/dev/null | grep -q 'Wifi is enabled'; then
        break
    fi
    sleep 10
done

# Let the client mode manager settle, then retry a few times.
i=0
while [ $i -lt 30 ]; do
    if CLASSPATH="$MODDIR/wbp.dex" app_process /system/bin WifiBandPref "$MODE" >>"$LOG" 2>&1; then
        echo "applied mode=$MODE" >>"$LOG"
        exit 0
    fi
    sleep 3
    i=$((i + 1))
done
echo "failed mode=$MODE" >>"$LOG"
