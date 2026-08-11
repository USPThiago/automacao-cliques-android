# Ambiente de desenvolvimento

## Ferramentas necessarias

| Ferramenta | Versao | Observacao |
| --- | --- | --- |
| JDK | 17 | Exigido pelo AGP 8.11; o Android Studio ja embute um JBR 17 |
| Android Studio | Ladybug (2024.2) ou mais recente | Opcional se voce so usar a linha de comando |
| Android SDK Platform | API 36 (`platforms;android-36`) | `compileSdk` / `targetSdk` do projeto |
| Android SDK Build-Tools | 36.0.0 | |
| Platform-Tools (`adb`) | mais recente | Necessario para instalar no celular |
| Gradle | 8.13 | Ja vem via wrapper (`./gradlew`), nao precisa instalar |
| Emulador (opcional) | `emulator` + `system-images;android-34;google_apis;x86_64` | Para testar sem o aparelho fisico |

O `minSdk` e 24 porque `AccessibilityService.dispatchGesture()` (base dos MVPs 2+) exige API 24.

## Setup pelo Android Studio

1. `File > Open` e selecione a raiz do repositorio; aguarde o Gradle sync.
2. `Tools > SDK Manager > SDK Platforms`: marque **Android 16 (API 36)**.
3. `SDK Tools`: marque **Android SDK Build-Tools 36**, **Platform-Tools** e, se for usar emulador, **Android Emulator**.
4. O Android Studio cria o `local.properties` com o `sdk.dir` automaticamente.

## Setup por linha de comando (Linux)

```bash
export ANDROID_HOME="$HOME/Android/sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -fsSL -o /tmp/cmdtools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools
mv /tmp/cmdtools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"

export PATH="$ANDROID_HOME/platform-tools:$PATH"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## Comandos do dia a dia

```bash
./gradlew assembleDebug   # gera app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug    # compila e instala no dispositivo/emulador conectado
./gradlew lint            # analise estatica do Android Lint
./gradlew test            # testes unitarios (JVM)
./gradlew clean
```

## Emulador (opcional)

```bash
sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n mvp0 -k "system-images;android-34;google_apis;x86_64" -d pixel_5
emulator -avd mvp0 -no-audio -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
```

Em Linux o emulador precisa de acesso ao KVM. Se aparecer
"This user doesn't have permissions to use KVM":

```bash
sudo usermod -aG kvm "$USER"   # requer novo login
sudo chmod 666 /dev/kvm        # alternativa imediata
```

## Instalar no aparelho fisico (ex.: Samsung Galaxy A73)

O APK gerado nao contem codigo nativo, portanto o mesmo arquivo serve para
qualquer arquitetura (incluindo o arm64 do A73).

**Via cabo USB (recomendado):**

1. No celular: `Configuracoes > Sobre o telefone > Informacoes de software` e toque
   7x em **Numero de compilacao** para liberar as **Opcoes do desenvolvedor**.
2. `Configuracoes > Opcoes do desenvolvedor` e ative **Depuracao USB**.
3. Conecte o cabo, escolha o modo **Transferencia de arquivos (MTP)** e autorize a
   impressao digital RSA do computador quando o aparelho perguntar.
4. `adb devices` deve listar o aparelho como `device`; depois rode
   `./gradlew installDebug` ou `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

**Sem cabo:** copie o `app-debug.apk` para o celular (Drive, e-mail, cabo em modo MTP),
abra o arquivo e permita **Instalar apps desconhecidos** para o app usado na
transferencia.

Observacao: o APK de debug e assinado com a chave de debug e serve para testes.
Para distribuir de forma estavel, gere uma keystore propria e rode
`./gradlew assembleRelease` com a `signingConfig` configurada.

## Problemas conhecidos

- **HTTP 429 do Maven Central**: em redes com IP compartilhado o `repo.maven.apache.org`
  pode responder `429 Too Many Requests`. Solucao: apontar o Maven Central para o
  mirror do Google criando `~/.gradle/init.d/mirror.gradle` que reescreve
  `repo.maven.apache.org` / `repo1.maven.org` para
  `https://maven-central.storage-download.googleapis.com/maven2`.
