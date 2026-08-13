#!/system/bin/sh

MODDIR=${0%/*}
RESET_PROP=/data/adb/ksu/bin/resetprop
APK_TARGET=/apex/com.android.bt/app/Bluetooth@BP4A.251205.006/Bluetooth.apk
JNI_TARGET=/apex/com.android.bt/lib64/libbluetooth_jni.so
APK_EXPECTED=f49f44f8b783dac622b21073303eabf88066d4ff9fe16d18cfe3c29209927c49
JNI_EXPECTED=2680b21467c44cc81f6467ba8171eec3e5915990321f7e5401db5b67268b8013

rm -f "$MODDIR/.guard_ok"

DEVICE=$(getprop ro.product.device)
SDK=$(getprop ro.build.version.sdk)
INCREMENTAL=$(getprop ro.build.version.incremental)
APK_ACTUAL=$(sha256sum "$APK_TARGET" 2>/dev/null | cut -d ' ' -f 1)
JNI_ACTUAL=$(sha256sum "$JNI_TARGET" 2>/dev/null | cut -d ' ' -f 1)

if [ "$DEVICE" != "KL7" ] || [ "$SDK" != "36" ] || \
   [ "$INCREMENTAL" != "1776559493" ] || \
   [ "$APK_ACTUAL" != "$APK_EXPECTED" ] || \
   [ "$JNI_ACTUAL" != "$JNI_EXPECTED" ]; then
  if [ -x "$RESET_PROP" ]; then
    "$RESET_PROP" --delete bluetooth.a2dp.source.lhdcv5_priority.config
  fi
  {
    echo "$(date '+%F %T') COMPATIBILITY CHECK FAILED; module disabled"
    echo "device=$DEVICE sdk=$SDK incremental=$INCREMENTAL"
    echo "Bluetooth.apk=$APK_ACTUAL"
    echo "libbluetooth_jni.so=$JNI_ACTUAL"
  } > "$MODDIR/status.log"
  touch "$MODDIR/disable"
  exit 0
fi

touch "$MODDIR/.guard_ok"

# This property is consumed while the A2DP source codec table is initialized.
if [ -x "$RESET_PROP" ]; then
  "$RESET_PROP" -n bluetooth.a2dp.source.lhdcv5_priority.config 5002
fi

# ART output for the stock Bluetooth APK must not be reused for the first test.
# These are only caches; Android recreates them automatically.
if [ ! -f "$MODDIR/.art_cache_cleared" ]; then
  rm -f /data/dalvik-cache/arm64/apex@com.android.bt@app@Bluetooth@BP4A.251205.006@Bluetooth.apk@classes.dex
  rm -f /data/dalvik-cache/arm64/apex@com.android.bt@app@Bluetooth@BP4A.251205.006@Bluetooth.apk@classes.vdex
  touch "$MODDIR/.art_cache_cleared"
fi

{
  echo "$(date '+%F %T') compatibility check passed"
  echo "device=$DEVICE sdk=$SDK incremental=$INCREMENTAL"
  echo "stock_apk_sha256=$APK_ACTUAL"
  echo "stock_jni_sha256=$JNI_ACTUAL"
} > "$MODDIR/status.log"
