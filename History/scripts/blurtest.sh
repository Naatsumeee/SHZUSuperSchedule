#!/bin/bash
# usage: blurtest.sh "<diag content or empty>" <tag> [tab] [swipes]
ADB="C:/Users/xutia/WorkBuddy/android-toolchain/android-sdk/platform-tools/adb.exe"
PKG=com.shzu.superschedule
D=/c/Users/xutia/WorkBuddy/SHZUClassList
DIAG=/sdcard/Android/data/$PKG/files/bar_diag.txt
CONTENT="$1"; TAG="$2"; TAB="${3:-1}"; SWIPES="${4:-8}"

if [ -z "$CONTENT" ]; then
  "$ADB" shell rm -f "$DIAG"
else
  "$ADB" shell "printf '%s' '$CONTENT' > $DIAG; chmod 666 $DIAG"
fi

"$ADB" shell am force-stop $PKG
"$ADB" shell logcat -c
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 5

case "$TAB" in
  0) X=240;; 1) X=720;; 2) X=1200;; *) X=720;;
esac
"$ADB" shell input tap $X 3040
sleep 2

# 滚到底，让内容真的跑到（或跑到不了）底栏后面
for i in $(seq 1 "$SWIPES"); do
  "$ADB" shell input swipe 720 2200 720 1000 150
done
sleep 3

"$ADB" exec-out screencap -p > "$D/$TAG.png"
"$ADB" shell logcat -d -s ShzuBar:V > "$D/${TAG}_log.txt"
echo "saved $TAG.png ($(stat -c %s "$D/$TAG.png") bytes)"
