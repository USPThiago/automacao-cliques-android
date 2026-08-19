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

## Provando gestos despachados (`dispatchGesture`, MVP 2+)
- Os logs esperados por ciclo de conexão são: `Servico conectado` → (delay) →
  `Despachando clique em x=.. y=..` → `Gesto concluido em x=.. y=..`. Verifique também que
  `Gesto cancelado` e `dispatchGesture retornou false` têm contagem 0:
  ```bash
  adb logcat -d -s ClickService | grep -E "Servico conectado|Despachando|Gesto|retornou"
  adb logcat -d -s ClickService | grep -cE "Gesto cancelado|retornou false"   # esperado: 0
  ```
- `Resources.getSystem().displayMetrics` NÃO devolve o tamanho físico da tela: num Pixel 5 AVD
  (1080x2340) devolve 1080x2138 (sem status/nav bar), então o "centro" cai em (540,1069),
  ~101 px acima do centro físico (540,1170). Calcule o valor esperado assim antes de julgar o log.
- Provar que o toque foi realmente entregue (não só "concluido"):
  - `settings put system show_touches 1` dá só um flash de 50 ms — fraco como evidência.
    `pointer_location 1` NÃO registra gestos injetados por acessibilidade (fica em 0.0).
  - Melhor: use um alvo determinístico com efeito persistente. O teclado do
    **Relógio > Timer** (`adb shell am start -a android.intent.action.SET_TIMER -e android.intent.extra.alarm.SKIP_UI false`)
    tem o botão `5` em bounds `[416,927][664,1175]`, que contém (540,1069): o display muda de
    `00h 00m 00s` para `00h 00m 05s`. Confirme também `Evento TYPE_VIEW_CLICKED pacote=com.google.android.deskclock`.
  - Na própria tela de detalhe do serviço em Configurações, o clique central acerta o Switch
    "Use ..." e abre o diálogo `Stop ...?` (com `TYPE_VIEW_CLICKED classe=android.widget.Switch`) —
    também é evidência válida, mas cancele o diálogo para não desligar o serviço.
- Rearmar o clique = reconectar o serviço (desligar/ligar o toggle na UI). Cuidado: rodar
  `uiautomator dump` faz o sistema religar os serviços de acessibilidade (`Servico desconectado` +
  `Servico conectado`), o que dispara um clique extra ~3 s depois; evite dumps durante a janela de
  medição e prefira screenshots.
- Para a gravação ficar legível, mostre o logcat ao vivo numa janela ao lado do emulador
  (`sudo apt-get install -y xterm`; não há terminal instalado por padrão):
  ```bash
  DISPLAY=:0 xterm -fa Monospace -fs 10 -bg black -fg green -geometry 110x24+330+470 \
    -e "bash -c 'adb logcat -s ClickService'" &
  DISPLAY=:0 wmctrl -r "Android Emulator - mvp0:5554" -e 0,10,10,480,1060
  DISPLAY=:0 xdotool windowminimize $(DISPLAY=:0 wmctrl -l | grep Chrome | cut -d" " -f1)
  ```
- Gestos de mouse (`left_click_drag`/mouse_down+move) muitas vezes NÃO abrem a gaveta de apps nem
  acionam os botões do painel lateral do emulador. Fallback: `adb shell input keyevent KEYCODE_APP_SWITCH`
  (Recentes) e então tocar no card do app com o mouse.

## Leitura de tela + sequências de cliques (MVP 3+)
- Centro físico: a partir do MVP 3 o serviço usa `WindowManager.currentWindowMetrics` (fallback
  `getRealMetrics()` < API 30), então no Pixel 5 AVD o clique automático loga **(540.0, 1170.0)**
  (não mais 1069.0). Use esse valor como critério de aprovação.
- `Ler tela agora` deve logar `Tela de <pacote>: N nos visiveis` + uma linha por nó
  (classe, text, desc, id, bounds, centro, clicavel) e mostrar um toast. Filtre com
  `adb logcat -d -s ClickService | grep -E "nos visiveis|id=.*:id/"`.
- **Gotcha importante: digitação por teclado do host (`type`/xdotool) NÃO chega ao emulador.**
  Use o teclado virtual do Android: `?123` (canto inferior esquerdo) para dígitos e volte para `ABC`
  para a vírgula (o layout numérico só tem `;`). Alternativa não-UI: `adb shell input text`.
- **Gotcha: o layout do app muda quando o IME esconde/aparece**, então o botão pode não estar
  onde a screenshot anterior mostrava. Padrão confiável: tocar primeiro no campo de texto
  (IME aberto) e só então tocar no botão, confirmando pelo toast na screenshot retornada.
- **Gotcha: cliques do mouse no emulador podem não registrar enquanto um comando do host roda em
  background** (exec backgrounded). Sempre confirme que a ação chegou (toast/log `Iniciando
  sequencia com N passo(s)`) antes de contar tempo.
- Trazer a tela-alvo para frente dentro da janela de 3 s é a parte difícil: chamadas de ferramenta
  do host levam 2-4 s. Duas técnicas que funcionam:
  1. Agendar a troca **dentro do device** (o exec retorna na hora, o sleep roda no Android):
     ```bash
     adb shell "nohup sh -c 'sleep 5; am start -a android.intent.action.SET_TIMER' >/dev/null 2>&1 &"
     ```
     e só então tocar em "Executar sequência" na UI.
  2. Prefixar a sequência com termos-isca (ex.: `zz,zz,zz,7,8,9`): os passos de isca gastam tempo
     (1 s cada) e ainda provam o caminho `nenhum no encontrado para termo~...`, e os dígitos caem
     já com o alvo em primeiro plano.
- Alvo determinístico para múltiplos cliques resolvidos por leitura de tela: **Relógio > Timer**.
  Os botões têm ids `com.google.android.deskclock:id/timer_setup_digit_<n>`; a sequência `7,8,9`
  deixa o display em `00h 07m 89s`. Zere com ⌫ ou reabra o Timer antes de cada rodada.
- Cuidado com auto-match: enquanto o próprio app está em primeiro plano, qualquer termo contido no
  texto do `EditText` de termos casa o próprio campo (`Clicando no no EditText text="7,8,9" ...`).
  Isso não é bug, mas invalida a evidência — confirme no log o `pacote`/`id` do nó clicado.
- `KEYCODE_APP_SWITCH` duas vezes troca de app, mas via host pode levar >3 s; verifique com
  `adb shell dumpsys activity activities | grep -m1 ResumedActivity`.

## Devin Secrets Needed
None.
