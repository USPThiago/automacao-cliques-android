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
  `dispatchGesture()`, disparado ~6s depois de o servico ser ativado.
- **MVP 3**: leitura da tela via `AccessibilityNodeInfo` (texto, content
  description, view id, classe e coordenadas) e execucao de varios cliques por
  tela, resolvidos por termo no momento do clique.
- **MVP 3.5 (atual)**: reconhecimento visual por captura de tela + template
  matching, para telas sem arvore de acessibilidade (jogos).
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

Ao ativar o servico, ele agenda um clique unico no centro da tela para ~6 segundos
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
- **Executar sequencia (6s)**: digite termos separados por virgula (ex.:
  `5, 0, Iniciar`); cada termo casa com texto, content description ou view id do
  elemento, sem diferenciar maiusculas. O primeiro clique acontece 6s depois
  (tempo de abrir a tela alvo) e os seguintes a cada 1s. Termos nao encontrados
  geram `nenhum no encontrado para termo~...` no Logcat e a sequencia continua.

A arvore de acessibilidade so mostra o que o app alvo publica nela. Jogos (Unity,
Unreal, canvas) desenham tudo numa unica `SurfaceView` e nao publicam textos nem
botoes, entao nenhuma busca por termo funciona nessas telas - use o
reconhecimento visual do MVP 3.5.

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

## Reconhecimento visual por template (MVP 3.5)

Para telas sem arvore de acessibilidade, o servico captura a tela
(`AccessibilityService.takeScreenshot()`, **Android 11 / API 30+**), converte para
tons de cinza e procura recortes ("templates") por correlacao cruzada normalizada,
clicando no centro da regiao encontrada. Em Android 10 ou anterior a captura nao
esta disponivel e apenas o MVP 3 funciona.

As capturas **nao sao gravadas** em lugar nenhum: o bitmap vem do sistema em
memoria, e usado no calculo dos escores e descartado. Os unicos arquivos de
imagem sao os templates que o usuario instala.

Passo a passo completo para um jogo real (Android Studio, espelhamento de tela e
extracao dos recortes): [`docs/manual-teste-jogo.md`](docs/manual-teste-jogo.md).

### Criando os templates

1. Capture a tela do jogo (`adb exec-out screencap -p > tela.png` ou a captura do
   proprio aparelho).
2. Recorte apenas o elemento a ser clicado (o botao, o icone), sem fundo variavel
   nem animacao; recortes de 80 a 300 px de lado funcionam bem. O utilitario
   `tools/recortar_template.py` recorta sem redimensionar e mostra o centro do
   recorte (o ponto onde o clique cai):
   ```
   python tools/recortar_template.py tela.png loja.png --centro 800 790 --tamanho 170
   ```
3. Salve como PNG com um nome curto e sem espacos, ex.: `loja.png`, `fechar_x.png`.

### Instalando os templates no aparelho

Os arquivos ficam em `Android/data/com.example.automacaocliques/files/templates/`
(o caminho exato aparece na tela inicial do app):

```
adb push loja.png /data/local/tmp/loja.png
adb shell cp /data/local/tmp/loja.png \
  /sdcard/Android/data/com.example.automacaocliques/files/templates/loja.png
```

(o `push` direto na pasta do app costuma ser bloqueado pelo scoped storage; no
Android Studio, o `Device Explorer` tambem faz o upload)

Extensoes aceitas: `png`, `jpg`, `jpeg`, `webp`. O nome do template e o nome do
arquivo sem extensao, em minusculas.

### Usando

- **Reconhecer tela (templates)**: compara a captura atual com todos os templates
  instalados e registra no Logcat os escores em ordem decrescente, identificando a
  variante de tela mais provavel:

  ```
  Reconhecendo tela 1080x2340 com 3 template(s)
    loja -> score=0.941 escala=1.00 centro=(864.0, 1520.0) regiao=[...]
    batalha -> score=0.402 ...
  Tela reconhecida como 'loja'
  ```

- Numa sequencia, prefixe o termo com `@` para clicar por imagem em vez de por
  texto: `@loja, @fechar_x, Iniciar` mistura cliques visuais e por nos.
- O escore vai de -1 a 1 (1 = identico) e o limite padrao para aceitar um
  casamento e **0.80**; abaixo dele o clique nao e despachado e o Logcat registra
  `abaixo do limite`. Escores baixos costumam indicar recorte com fundo animado,
  resolucao muito diferente ou tela errada.
- O template e testado em varias escalas (0.66x a 1.5x), o que tolera aparelhos
  com resolucao diferente daquela usada no recorte.

### Limitacoes conhecidas

- Cada template custa alguns segundos numa tela 1080x2340. A captura e a
  correlacao rodam numa thread de background (na thread principal causavam ANR),
  mas uma sequencia com muitos passos visuais e naturalmente lenta.
- Passos visuais sao ignorados quando o proprio app de automacao esta em primeiro
  plano (`Template ... ignorado: o proprio app esta em primeiro plano`); sem isso
  o casamento acertaria a propria interface e clicaria nela. Ainda assim, abra a
  tela alvo dentro da janela de 6s - em aparelhos lentos pode ser necessario mais
  tempo (delay configuravel fica para o MVP 4).
- `adb push` direto para `Android/data/...` pode ser bloqueado pelo scoped
  storage; nesse caso use
  `adb push loja.png /data/local/tmp/ && adb shell cp /data/local/tmp/loja.png <pasta de templates>`.

API programatica:

```kotlin
service.identifyScreen { name -> /* melhor template acima do limite */ }
service.findTemplate("loja") { match -> /* TemplateMatch com centro e escore */ }
service.clickTemplate("loja", threshold = 0.85)
service.runSequence(listOf(ClickStep.OnTemplate("loja", delayMs = 6_000)))
```

Em Android 13+ um app instalado fora da loja (sideload) pode ter o acesso a
acessibilidade bloqueado. Nesse caso abra `Configuracoes > Apps > Automacao Cliques`,
menu (tres pontos) e escolha **Permitir configuracoes restritas** antes do passo 2.
