# automacao-cliques-android

App Android (Kotlin) para automatizar sequencia de cliques usando `AccessibilityService`.

## Objetivo

Permitir que o usuario grave/configure uma sequencia de toques na tela e a reproduza
automaticamente em outros apps, usando a API de acessibilidade do Android
(`AccessibilityService.dispatchGesture()`), sem necessidade de root.

## Requisitos

- Android Studio (Ladybug ou mais recente)
- JDK 17
- `minSdk` 24 (necessario para `dispatchGesture()`), `compileSdk`/`targetSdk` 34

## Roteiro de MVPs

- **MVP 0 (atual)**: esqueleto do projeto Android em Kotlin exibindo "Hello World",
  compilando e rodando em emulador ou dispositivo fisico.
- **MVP 1**: `AccessibilityService` declarado e habilitavel nas configuracoes do
  aparelho, com log de eventos.
- **MVP 2**: executar um unico clique programatico em coordenada fixa via
  `dispatchGesture()`.
- **MVP 3**: executar uma sequencia de cliques com intervalos configuraveis.
- **MVP 4**: UI para cadastrar/editar/salvar sequencias (coordenadas + delays) e
  iniciar/parar a execucao.
- **MVP 5**: overlay flutuante (start/stop sem sair do app alvo) e persistencia
  das sequencias.

## Como rodar (validacao do MVP 0)

1. Clone o repositorio e abra a pasta no Android Studio (`File > Open`), aguardando
   o Gradle sync.
2. Emulador: `Tools > Device Manager > Create Device` (API 24+), depois `Run 'app'`.
3. Dispositivo fisico: habilite `Opcoes do desenvolvedor > Depuracao USB`, conecte o
   cabo, autorize o computador e selecione o aparelho em `Run 'app'`.
4. Via linha de comando: `./gradlew installDebug` (com `adb devices` listando o
   dispositivo) ou `./gradlew assembleDebug` para gerar o APK em
   `app/build/outputs/apk/debug/`.

A tela inicial deve exibir o texto "Hello World".
