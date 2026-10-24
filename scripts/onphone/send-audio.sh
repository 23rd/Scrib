#!/bin/sh
set -eu
FILE=${1:?usage: send-audio.sh FILE [PACKAGE]}
PKG=${2:-org.scrib.transcriber.debug}
NAME=$(basename "$FILE")

adb push "$FILE" /data/local/tmp/scrib-send-audio >/dev/null
adb shell run-as "$PKG" mkdir -p files
adb shell run-as "$PKG" cp /data/local/tmp/scrib-send-audio "files/$NAME"
adb shell rm -f /data/local/tmp/scrib-send-audio

adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

adb shell am start -a android.intent.action.SEND -t audio/x-wav -p "$PKG" \
    --eu android.intent.extra.STREAM "file:///data/data/$PKG/files/$NAME" >/dev/null
echo "sent $NAME to $PKG; the transcript lands in files/history/ (or watch: adb logcat -s scrib.state)"
