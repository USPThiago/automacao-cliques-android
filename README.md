# automacao-cliques-android

App Android (Kotlin) para automatizar sequencia de cliques usando `AccessibilityService`.

## Objetivo

Permitir que o usuario grave/configure uma sequencia de toques na tela e a reproduza
automaticamente em outros apps, usando a API de acessibilidade do Android
(`AccessibilityService.dispatchGesture()`), sem necessidade de root.

## Requisitos

- Android Studio (Ladybug ou mais recente)
- JDK 17
- `minSdk` 24 (necessario para `dispatchGesture()`), `compileSdk`/`targetSdk` 36

Passo a passo de instalacao das ferramentas, comandos do Gradle e como instalar o
APK no celular: veja [DEVELOPMENT.md](DEVELOPMENT.md).

## Roteiro de MVPs

- **MVP 0**: esqueleto do projeto Android em Kotlin exibindo "Hello World",
  compilando e rodando em emulador ou dispositivo fisico.
- **MVP 1**: `AccessibilityService` declarado e habilitavel nas configuracoes
  do aparelho, com log de eventos e indicador de status na tela inicial.
- **MVP 2 (atual)**: clique programatico unico no centro da tela via
  `dispatchGesture()`, disparado ~3s depois de o servico ser ativado.
- **MVP 3**: executar uma sequencia de cliques com intervalos configuraveis.
- **MVP 4**: UI para cadastrar/editar/salvar sequencias (coordenadas + delays) e
  iniciar/parar a execucao.
- **MVP 5**: overlay flutuante (start/stop sem sair do app alvo) e persistencia
  das sequencias.

## Como rodar

1. Clone o repositorio e abra a pasta no Android Studio (`File > Open`), aguardando
   o Gradle sync.
2. Emulador: `Tools > Device Manager > Create Device` (API 24+), depois `Run 'app'`.
3. Dispositivo fisico: habilite `Opcoes do desenvolvedor > Depuracao USB`, conecte o
   cabo, autorize o computador e selecione o aparelho em `Run 'app'`.
4. Via linha de comando: `./gradlew installDebug` (com `adb devices` listando o
   dispositivo) ou `./gradlew assembleDebug` para gerar o APK em
   `app/build/outputs/apk/debug/`.

A tela inicial mostra se o servico de acessibilidade esta ATIVO ou INATIVO e um
botao que abre as configuracoes de acessibilidade do sistema.

## Habilitando o servico de acessibilidade

1. Abra o app e toque em **Abrir configuracoes de acessibilidade**.
2. Em `Apps instalados` (ou `Servicos instalados`), selecione **Automacao de Cliques**
   e ative o servico.
3. Volte ao app: o status deve mudar para **ATIVO**.
4. Os eventos recebidos aparecem no Logcat:
   `adb logcat -s ClickService`.

## Clique automatico (MVP 2)

Ao ativar o servico, ele agenda um clique unico no centro da tela (calculado a
partir de `Resources.getSystem().displayMetrics`) para ~3 segundos depois, tempo
suficiente para sair das Configuracoes e abrir a tela alvo.

Para acompanhar:

```
adb logcat -s ClickService
```

Os logs mostram `Despachando clique em x=... y=...` e, em seguida,
`Gesto concluido` (ou `Gesto cancelado`). Para confirmar visualmente, ative
`Opcoes do desenvolvedor > Mostrar toques`.

Em Android 13+ um app instalado fora da loja (sideload) pode ter o acesso a
acessibilidade bloqueado. Nesse caso abra `Configuracoes > Apps > Automacao Cliques`,
menu (tres pontos) e escolha **Permitir configuracoes restritas** antes do passo 2.
