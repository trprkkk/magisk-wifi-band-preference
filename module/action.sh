#!/system/bin/sh
# Magisk / KernelSU action button:
#   auto -> 5g-force -> 2g-force -> auto
#
# 5g-prefer / 2g-prefer are available from wifi-band-pref (CLI).
# The action is executed by magiskd, so the module directory is accessible.

MODDIR=${0%/*}

CUR=$(cat "$MODDIR/mode" 2>/dev/null | tr -d ' \t\r\n')
case "$CUR" in
    5g-force) NEXT=2g-force ;;
    2g-force) NEXT=auto ;;
    *) NEXT=5g-force ;;
esac

echo "$NEXT" > "$MODDIR/mode"
echo "Wi-Fi band preference: $NEXT"

if ! CLASSPATH="$MODDIR/wbp.dex" app_process /system/bin WifiBandPref "$NEXT"; then
    echo "適用に失敗しました。dumpsys wifi の ScoringParams と apply.log を確認してください。"
    exit 1
fi

echo "現在の接続をすぐ切り替えるには Wi-Fi をオフ/オンしてください。"
