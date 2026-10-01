#!/bin/bash
# Installs the debug APK on the emulator, goes through the first start, the
# permission page and every tab, and collects crashes from logcat into out/.
PKG=com.vsp.internetspeedmeter
OUT=out
mkdir -p "$OUT"
step=0

dump() {
  for i in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 &&
      adb shell cat /sdcard/ui.xml > /tmp/ui.xml && return 0
    sleep 1
  done
  return 1
}

shot() {
  step=$((step + 1))
  local name
  name=$(printf '%02d-%s' "$step" "$1")
  adb exec-out screencap -p > "$OUT/$name.png"
  dump && cp /tmp/ui.xml "$OUT/$name.xml"
  echo "== $name | $(adb shell dumpsys window | grep -m1 mCurrentFocus) | pid=$(adb shell pidof $PKG)"
}

tap() {
  dump || { echo "!! no ui dump"; return 1; }
  local b x1 y1 x2 y2
  b=$(grep -o "resource-id=\"$1\"[^>]*bounds=\"[^\"]*\"" /tmp/ui.xml | head -1 |
    grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  if [ -z "$b" ]; then echo "!! $1 not on screen"; return 1; fi
  read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr -c '0-9' ' ')"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
  echo ">> tapped $1"
}

tabs() {
  for t in nav_history nav_apps nav_settings nav_home; do
    tap "$PKG:id/$t"; sleep 4; shot "$1-$t"
  done
}

adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
if [ "$APP_LOCALE" != "" ]; then
  adb shell cmd locale set-app-locales "$PKG" --locales "$APP_LOCALE"
fi
adb logcat -c
adb logcat -b crash -c

adb shell am start -W -n "$PKG/.MainActivity"
sleep 6; shot first-start
tap "$PKG:id/btn_onb_notif"; sleep 3; shot notif-dialog
tap com.android.permissioncontroller:id/permission_allow_button; sleep 3; shot notif-allowed
tap "$PKG:id/btn_onb_done"; sleep 12; shot after-onboarding
tabs no-usage-access

adb shell appops set "$PKG" GET_USAGE_STATS allow
adb shell dumpsys deviceidle whitelist +"$PKG"
tabs usage-access

adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -W -n "$PKG/.MainActivity"; sleep 8; shot second-start
adb shell am force-stop "$PKG"; sleep 1
adb shell am start -W -n "$PKG/.MainActivity"; sleep 20; shot cold-start

adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
adb shell dumpsys activity services "$PKG" > "$OUT/services.txt"
echo "===== crash buffer ====="
cat "$OUT/crash.txt"
echo "===== app lines ====="
grep -E "FATAL|AndroidRuntime|InternetService|did not then call|ANR in|has died|Force finishing|Process: $PKG|ActivityTaskManager.*$PKG" "$OUT/logcat.txt" | head -150
exit 0
