#!/system/bin/sh

RESET_PROP=/data/adb/ksu/bin/resetprop
if [ -x "$RESET_PROP" ]; then
  "$RESET_PROP" --delete bluetooth.a2dp.source.lhdcv5_priority.config
fi

rm -f "${0%/*}/.guard_ok"

rm -f /data/dalvik-cache/arm64/apex@com.android.bt@app@Bluetooth@BP4A.251205.006@Bluetooth.apk@classes.dex
rm -f /data/dalvik-cache/arm64/apex@com.android.bt@app@Bluetooth@BP4A.251205.006@Bluetooth.apk@classes.vdex

# The controller is installed by this module and uses its bundled helper.
pm uninstall --user 0 com.rison.lhdccontrol >/dev/null 2>&1

DAEMON_PID_FILE="${0%/*}/controller/daemon.pid"
DAEMON_PID=$(cat "$DAEMON_PID_FILE" 2>/dev/null)
if [ -n "$DAEMON_PID" ] && [ -d "/proc/$DAEMON_PID" ]; then
  DAEMON_CMD=$(tr '\000' ' ' < "/proc/$DAEMON_PID/cmdline" 2>/dev/null)
  case "$DAEMON_CMD" in
    *LhdcControlDaemon*) kill "$DAEMON_PID" 2>/dev/null ;;
  esac
fi

AUTO_PID_FILE="${0%/*}/controller/autoprofile.pid"
AUTO_PID=$(cat "$AUTO_PID_FILE" 2>/dev/null)
if [ -n "$AUTO_PID" ] && [ -d "/proc/$AUTO_PID" ]; then
  AUTO_CMD=$(tr '\000' ' ' < "/proc/$AUTO_PID/cmdline" 2>/dev/null)
  case "$AUTO_CMD" in
    *autoprofile.sh*) kill "$AUTO_PID" 2>/dev/null ;;
  esac
fi
