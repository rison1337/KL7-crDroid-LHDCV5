#!/system/bin/sh

ui_print "- LHDC V5 + Codec Control for TECNO KL7 / crDroid 12.9 (20260419)"
ui_print "- Checking exact ROM payload..."

APK_TARGET=/apex/com.android.bt/app/Bluetooth@BP4A.251205.006/Bluetooth.apk
JNI_TARGET=/apex/com.android.bt/lib64/libbluetooth_jni.so
APK_EXPECTED=f49f44f8b783dac622b21073303eabf88066d4ff9fe16d18cfe3c29209927c49
JNI_EXPECTED=2680b21467c44cc81f6467ba8171eec3e5915990321f7e5401db5b67268b8013

DEVICE=$(getprop ro.product.device)
SDK=$(getprop ro.build.version.sdk)
INCREMENTAL=$(getprop ro.build.version.incremental)
APK_ACTUAL=$(sha256sum "$APK_TARGET" 2>/dev/null | cut -d ' ' -f 1)
JNI_ACTUAL=$(sha256sum "$JNI_TARGET" 2>/dev/null | cut -d ' ' -f 1)

if [ "$DEVICE" != "KL7" ] || [ "$SDK" != "36" ] || \
   [ "$INCREMENTAL" != "1776559493" ] || \
   [ "$APK_ACTUAL" != "$APK_EXPECTED" ] || \
   [ "$JNI_ACTUAL" != "$JNI_EXPECTED" ]; then
  ui_print "! Unsupported device/ROM; installation aborted safely."
  ui_print "! device=$DEVICE sdk=$SDK incremental=$INCREMENTAL"
  abort "! Stock Bluetooth payload hash does not match."
fi

ui_print "- Compatibility check passed"
ui_print "- Bluetooth Codec Control will be installed after reboot"
ui_print "- Auto profile: LHDC V5 / 500 Kbit/s / Low Latency"
ui_print "- Reboot after installation"
