#!/system/bin/sh

MODDIR=${0%/*}
RESET_PROP=/data/adb/ksu/bin/resetprop

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

if [ -f "$MODDIR/.guard_ok" ] && [ -x "$RESET_PROP" ]; then
  "$RESET_PROP" -n bluetooth.a2dp.source.lhdcv5_priority.config 5002
fi

CONTROLLER_APK="$MODDIR/controller/BluetoothCodecControl.apk"
CONTROLLER_RESULT="skipped"
if [ -f "$MODDIR/.guard_ok" ] && [ -f "$CONTROLLER_APK" ]; then
  INSTALLED_VERSION=$(dumpsys package com.rison.lhdccontrol 2>/dev/null |
    sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
  if [ "$INSTALLED_VERSION" != "115" ]; then
    CONTROLLER_RESULT=$(pm install -r --user 0 "$CONTROLLER_APK" 2>&1)
  else
    CONTROLLER_RESULT="already installed"
  fi
fi

DAEMON_JAR="$MODDIR/controller/LhdcControlDaemon.jar"
BRIDGE_JAR="$MODDIR/controller/LhdcControlBridge.jar"
DAEMON_LOG="$MODDIR/controller/daemon.log"
DAEMON_PID="$MODDIR/controller/daemon.pid"
DAEMON_RESULT="skipped"
if [ -f "$MODDIR/.guard_ok" ] && [ -f "$DAEMON_JAR" ] && [ -f "$BRIDGE_JAR" ]; then
  OLD_PID=$(cat "$DAEMON_PID" 2>/dev/null)
  if [ -n "$OLD_PID" ] && [ -d "/proc/$OLD_PID" ]; then
    OLD_CMD=$(tr '\000' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)
    case "$OLD_CMD" in
      *LhdcControlDaemon*) kill "$OLD_PID" 2>/dev/null ;;
    esac
  fi
  : > "$DAEMON_LOG"
  CLASSPATH="$DAEMON_JAR" nohup app_process /system/bin LhdcControlDaemon \
    "$BRIDGE_JAR" >>"$DAEMON_LOG" 2>&1 &
  echo $! > "$DAEMON_PID"
  sleep 1
  NEW_PID=$(cat "$DAEMON_PID" 2>/dev/null)
  if grep -q '^READY ' "$DAEMON_LOG"; then
    DAEMON_RESULT="ready pid=$NEW_PID"
  elif [ -n "$NEW_PID" ] && [ -d "/proc/$NEW_PID" ]; then
    DAEMON_RESULT="waiting for controller first launch pid=$NEW_PID"
  else
    DAEMON_RESULT="failed: $(tail -n 2 "$DAEMON_LOG" | tr '\n' ' ')"
  fi
fi

AUTO_SCRIPT="$MODDIR/controller/autoprofile.sh"
AUTO_LOG="$MODDIR/controller/autoprofile.log"
AUTO_PID_FILE="$MODDIR/controller/autoprofile.pid"
AUTO_RESULT="skipped"
if [ -f "$MODDIR/.guard_ok" ] && [ -f "$BRIDGE_JAR" ] && [ -f "$AUTO_SCRIPT" ]; then
  OLD_AUTO_PID=$(cat "$AUTO_PID_FILE" 2>/dev/null)
  if [ -n "$OLD_AUTO_PID" ] && [ -d "/proc/$OLD_AUTO_PID" ]; then
    OLD_AUTO_CMD=$(tr '\000' ' ' < "/proc/$OLD_AUTO_PID/cmdline" 2>/dev/null)
    case "$OLD_AUTO_CMD" in
      *autoprofile.sh*) kill "$OLD_AUTO_PID" 2>/dev/null ;;
    esac
  fi
  : > "$AUTO_LOG"
  nohup sh "$AUTO_SCRIPT" "$BRIDGE_JAR" "$MODDIR" >>"$AUTO_LOG" 2>&1 &
  echo $! > "$AUTO_PID_FILE"
  sleep 1
  NEW_AUTO_PID=$(cat "$AUTO_PID_FILE" 2>/dev/null)
  if grep -q '^AUTO_READY ' "$AUTO_LOG"; then
    AUTO_RESULT="ready pid=$NEW_AUTO_PID"
  elif [ -n "$NEW_AUTO_PID" ] && [ -d "/proc/$NEW_AUTO_PID" ]; then
    AUTO_RESULT="supervisor ready pid=$NEW_AUTO_PID"
  else
    AUTO_RESULT="failed: $(tail -n 3 "$AUTO_LOG" | tr '\n' ' ')"
  fi
fi

{
  echo "$(date '+%F %T') boot completed"
  echo "build=$(getprop ro.build.display.id)"
  echo "device=$(getprop ro.product.device)"
  echo "guard=$([ -f "$MODDIR/.guard_ok" ] && echo passed || echo failed)"
  echo "controller=$CONTROLLER_RESULT"
  echo "controller_path=$(pm path com.rison.lhdccontrol 2>/dev/null | head -n 1)"
  echo "controller_daemon=$DAEMON_RESULT"
  echo "auto_profile=$AUTO_RESULT"
  echo "zygisk_mount_log: logcat -s LhdcV5Mount"
} >> "$MODDIR/status.log"
