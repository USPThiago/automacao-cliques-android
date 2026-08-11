---
name: testing-android-emulator
description: How to build, install and visually validate this Android app on an emulator on a Devin box (SDK setup, KVM permissions, AVD creation, visible emulator window, UI verification via adb, enabling the AccessibilityService through the Settings UI).
---

# Testing this Android app on an emulator

## Environment
- SDK: `/home/ubuntu/Android/sdk` (blueprint installs cmdline-tools, platform-tools, platforms;android-34, build-tools;34.0.0; newer branches may also need `platforms;android-36` + `build-tools;36.0.0`).
  ```bash
  export ANDROID_HOME=$HOME/Android/sdk
  export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
  ```
- Maven Central may return HTTP 429 from the VM. A Gradle init script at `~/.gradle/init.d/mirror.gradle`
  rewrites Maven Central to `https://maven-central.storage-download.googleapis.com/maven2`.
  If dependency resolution fails with 429, verify that file still exists (the blueprint recreates it).
- `local.properties` (gitignored) must contain `sdk.dir=$HOME/Android/sdk`.
- An API 34 AVD is fine for branches that bump `compileSdk`/`targetSdk` to 36, because `minSdk` is 24 —
  no need to create an API 36 AVD unless installation actually fails.

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
The `chmod` does NOT survive a box restart (the group membership does), so after any restart just
re-run `sudo chmod 666 /dev/kvm` before launching. The AVD itself persists — check `ls ~/.android/avd`
before recreating it.
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

Text colors (e.g. a green ATIVO vs red INATIVO status) are NOT in the uiautomator dump — they can only be
proven with a screenshot; use the `zoom` action on the device-screen region to make the color legible.

## Testing the AccessibilityService (`ClickAccessibilityService`)
State lives in `Settings.Secure`, so inspect/reset it with adb:
```bash
adb shell settings get secure enabled_accessibility_services   # null or empty when off
adb shell settings get secure accessibility_enabled            # 0 / 1
adb logcat -s ClickService   # Servico conectado / Evento TYPE_... pacote=... / Servico desconectado
```
Reset to a clean off precondition before recording (note: `settings put secure ... ""` prints
`Bad arguments` but the reset still takes effect — always verify with a following `get`):
```bash
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0
adb shell am force-stop com.example.automacaocliques
```
Enable it through the UI (preferred — proves the real user flow):
1. In the app, tap the button `Abrir configuracoes de acessibilidade` → system Accessibility screen.
2. Under `Downloaded apps`, tap `Automacao de Cliques`.
3. Tap the `Use Automacao de Cliques` toggle.
4. Confirm the full-control dialog with `Allow`. Disabling instead shows a smaller
   `Stop Automacao de Cliques?` dialog → tap `Stop`.
- Enabling is NOT blocked by Android 13+/14 restricted settings for an APK installed via `adb install`,
  so the full UI path is testable. If a future image does block it, the fallback is
  `adb shell settings put secure enabled_accessibility_services com.example.automacaocliques/com.example.automacaocliques.ClickAccessibilityService`
  plus `accessibility_enabled 1` — state clearly in the report which path was used.
- The app only re-reads state in `onResume`, so navigate back into the app (system Back twice from the
  service detail screen) to see the status flip; it will not update live while Settings is on top.
- To prove event logging is real, `adb logcat -c` first, then do genuine interactions (open the app
  drawer, tap an app such as Gmail, go Home) and check for several distinct event types and packages:
  `adb logcat -d -s ClickService | grep -o 'Evento TYPE_[A-Z_]*' | sort | uniq -c`
- Emulator gesture gotcha: swipe-up to open the app drawer must start very close to the bottom edge of
  the device screen (e.g. drag from y≈725 to y≈400 for a 520x1150 window); starting higher just
  scrolls the current app instead.

## Devin Secrets Needed
None.
