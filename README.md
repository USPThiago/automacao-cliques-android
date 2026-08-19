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
- **MVP 2**: clique programatico unico no centro da tela via
  `dispatchGesture()`, disparado ~3s depois de o servico ser ativado.
- **MVP 3 (atual)**: leitura da tela via `AccessibilityNodeInfo` (texto, content
  description, view id, classe e coordenadas) e execucao de varios cliques por
  tela, resolvidos por termo no momento do clique.
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

Ao ativar o servico, ele agenda um clique unico no centro da tela para ~3 segundos
depois, tempo suficiente para sair das Configuracoes e abrir a tela alvo.

Para acompanhar:

```
adb logcat -s ClickService
```

Os logs mostram `Despachando clique em x=... y=...` e, em seguida,
`Gesto concluido` (ou `Gesto cancelado`). Para confirmar visualmente, ative
`Opcoes do desenvolvedor > Mostrar toques`.

O centro vem de `WindowManager.currentWindowMetrics` (API 30+) ou de
`Display.getRealMetrics()` nas versoes anteriores, ou seja, o centro fisico da
tela, incluindo status bar e barra de navegacao.

## Leitura da tela e sequencia de cliques (MVP 3)

O servico le a janela ativa e resolve cada clique no momento em que ele vai ser
executado, o que permite varios cliques na mesma tela e passos que dependem do
resultado dos anteriores.

- **Ler tela agora**: registra no Logcat todos os nos visiveis com texto,
  content description, view id, classe, `bounds` e centro (`ClickService`).
- **Executar sequencia (3s)**: digite termos separados por virgula (ex.:
  `5, 0, Iniciar`); cada termo casa com texto, content description ou view id do
  elemento, sem diferenciar maiusculas. O primeiro clique acontece 3s depois
  (tempo de abrir a tela alvo) e os seguintes a cada 1s. Termos nao encontrados
  geram `nenhum no encontrado para termo~...` no Logcat e a sequencia continua.

Na API de acessibilidade nao ha acesso aos pixels da tela: elementos graficos sao
identificados por `contentDescription`, `className`, `viewIdResourceName` e
posicao. Reconhecimento visual de imagens exigiria captura de tela + visao
computacional.

API programatica (`ClickAccessibilityService`):

```kotlin
service.readScreen()                                  // List<ScreenNode>
service.findNode(NodeSelector(term = "Iniciar"))      // busca por texto/desc/id
service.runSequence(
    listOf(
        ClickStep.OnNode(NodeSelector(term = "5"), delayMs = 3_000),
        ClickStep.OnNode(NodeSelector(term = "0")),
        ClickStep.AtPoint(540f, 1170f, delayMs = 500)
    )
)
service.cancelSequence()
```

Em Android 13+ um app instalado fora da loja (sideload) pode ter o acesso a
acessibilidade bloqueado. Nesse caso abra `Configuracoes > Apps > Automacao Cliques`,
menu (tres pontos) e escolha **Permitir configuracoes restritas** antes do passo 2.
