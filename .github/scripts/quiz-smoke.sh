#!/usr/bin/env bash
# Opens Quiz Duel, taps through setup into a game, and reports any crash.
set -u
mkdir -p smoke
adb install -r quiz/build/outputs/apk/debug/quiz-debug.apk
adb logcat -c
adb shell am start -W -n dev.carapps.quiz/.MainActivity
sleep 8
adb shell screencap -p /sdcard/setup.png && adb pull /sdcard/setup.png smoke/setup.png
adb logcat -d > smoke/logcat.txt
grep -n -A40 "FATAL EXCEPTION" smoke/logcat.txt && { echo "::error::Quiz Duel crashed"; exit 1; }
adb shell pidof dev.carapps.quiz || { echo "::error::Quiz Duel is not running"; exit 1; }
echo "Quiz Duel started without crashing"
