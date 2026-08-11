---
name: testing-android-emulator
description: How to build, install and visually validate this Android app on an emulator on a Devin box (SDK setup, KVM permissions, AVD creation, visible emulator window, UI verification via adb).
---

# Testing this Android app on an emulator

## Environment
- SDK: `/home/ubuntu/Android/sdk` (blueprint installs cmdline-tools, platform-tools, platforms;android-34, build-tools;34.0.0).
  ```bash
  export ANDROID_HOME=$HOME/Android/sdk
  export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
  ```
- Maven Central may return HTTP 429 from the VM. A Gradle init script at `~/.gradle/init.d/mirror.gradle`
  rewrites Maven Central to `https://maven-central.storage-download.googleapis.com/maven2`.
  If dependency resolution fails with 429, verify that file still exists (the blueprint recreates it).
- `local.properties` (gitignored) must contain `sdk.dir=$HOME/Android/sdk`.

## Emulator packages / AVD (NOT covered by the blueprint — install per session)
```bash
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install \
  "emulator" "system-images;android-34;google_apis;x86_64"     # ~5 min
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n mvp0 -k "system-images;android-34;google_apis;x86_64" -d pixel_5 --force
```

### KVM permission gotcha
x86_64 emulation requires hardware acceleration. `/dev/kvm` exists but is `root:kvm 0660`, and
the `ubuntu` user is not in the `kvm` group, so the emulator aborts with
"This user doesn't have permissions to use KVM (/dev/kvm)". Fix (passwordless sudo works):
```bash
sudo usermod -aG kvm ubuntu && sudo chmod 666 /dev/kvm
```
Then launch (window must be visible on `DISPLAY=:0` for the recording):
```bash
DISPLAY=:0 nohup emulator -avd mvp0 -no-audio -no-snapshot -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
```
Boot takes ~35s under nested virtualization. Dismiss the "Emulator Running in Nested Virtualization"
info dialog before recording, and minimize Chrome so only the emulator is on screen.

Enlarge/position the emulator window (do NOT use xdotool super+Up):
```bash
DISPLAY=:0 wmctrl -r "Android Emulator - mvp0:5554" -e 0,300,10,520,1150
DISPLAY=:0 wmctrl -a "Android Emulator - mvp0:5554"
```

## Build / install / launch
```bash
./gradlew clean assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
./gradlew installDebug
adb shell pm list packages com.example.automacaocliques   # expect: package:com.example.automacaocliques
```
Launch from the UI (preferred for recordings): swipe up on the emulator screen
(`left_click_drag` from near the bottom of the device screen upward) to open the app drawer,
then tap the icon labeled with the app name.
- Gotcha: typing into the launcher search box with `type` can leave the launcher stuck in a
  non-repainting search state. Recover with `adb shell input keyevent KEYCODE_HOME`, then swipe up
  again and tap the icon directly instead of searching.

## Verifying UI + stability via adb (corroboration; screenshots are the real proof)
```bash
adb shell dumpsys activity activities | grep topResumedActivity   # expect .../.MainActivity
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '>' '>\n' | grep <viewId>
adb exec-out screencap -p > /tmp/dev.png
adb shell pidof <applicationId>
adb logcat -d | grep -Ei "FATAL EXCEPTION|ANR in <applicationId>|Process <applicationId>.*has died"
```
Centering can be proven numerically by comparing the TextView `bounds` midpoint from the
uiautomator dump against the content-area midpoint.
Benign log noise to ignore: `ziparchive: Unable to open ... base.dm`, `InputManager-JNI: Input channel
... Splash Screen ... disposed without first being removed`.

## Devin Secrets Needed
None.
