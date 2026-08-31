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
- **MVP 3**: leitura da tela via `AccessibilityNodeInfo` e cliques resolvidos por
  termo (removido no MVP 4).
- **MVP 3.5**: reconhecimento visual por captura de tela + template matching,
  para telas sem arvore de acessibilidade (jogos).
- **MVP 4 (atual)**: roteiros declarados em sessoes JSON no aparelho, busca
  restrita por `searchArea` com escala unica e early exit, e interface
  intermediaria com log de execucao (Iniciar/Parar/Limpar/Copiar).
- **MVP 5**: overlay flutuante (start/stop sem sair do app alvo) e edicao das
  sessoes pela propria interface.

Pendencias abertas e o que esta fora do escopo atual:
[docs/backlog.md](docs/backlog.md).

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

## Sessoes em JSON (MVP 4)

O roteiro nao e mais digitado na tela: ele vive em arquivos JSON no aparelho, em

```
Android/data/com.example.automacaocliques/files/sessions/
```

e os recortes usados no reconhecimento visual em

```
Android/data/com.example.automacaocliques/files/templates/
```

Os dois caminhos aparecem na tela inicial do app. A execucao **sempre** comeca em
`sessions/mainSession.json`.

Cada sessao descreve **uma tela** do app alvo: uma lista de acoes avaliadas na
ordem declarada, das quais **apenas a primeira localizada e executada**. Ao
terminar, a acao pode chamar (`call`) outra sessao; sem `call`, a execucao
termina com sucesso. Ciclos (A chama B, B chama A) sao permitidos e a parada
natural e a exaustao das tentativas de uma sessao.

### Exemplo comentado

O JSON nao aceita comentarios; os `//` abaixo sao apenas explicativos.

```jsonc
{
  "name": "tela inicial",                       // nome exibido no log
  "screen": { "width": 1080, "height": 2400 },  // resolucao onde as coordenadas foram medidas
                                                // (omita se foram medidas no proprio aparelho)
  "retries": 5,                                 // tentativas adicionais (total = 1 + retries); padrao 3
  "retryDelayMs": 1000,                         // espera entre tentativas; padrao 1000

  "actions": [                                  // avaliadas em ordem; so a primeira localizada roda
    {
      "name": "fechar promocao",
      "locate": "fechar_x",                     // templates/fechar_x.png
      "threshold": 0.8,                         // escore minimo; padrao 0.80
      "scales": [1.0],                          // padrao [1.0]
      "searchArea": {                           // restringe a busca (muito mais rapido);
        "left": 700, "top": 100,                // padrao: tela inteira
        "right": 1080, "bottom": 500
      },
      "clicks": [                               // omita para clicar no centro do recorte encontrado
        { "x": 980, "y": 220 },
        { "x": 540, "y": 1800, "delayMs": 500 } // delayMs do ponto tem precedencia sobre clickIntervalMs
      ],
      "clickIntervalMs": 300,                   // espera entre cliques; padrao 300
      "waitAfterMs": 1000,                      // espera apos o ultimo clique; padrao 1000
      "call": "menu_principal"                  // proxima sessao: sessions/menu_principal.json
    },
    {
      "name": "entrar no jogo",                 // so e avaliada se a anterior nao for localizada
      "locate": "botao_jogar"                   // sem "call": termina com sucesso
    }
  ]
}
```

Coordenadas e `searchArea` sao escalonadas de `screen` para a resolucao real do
aparelho; sem `screen`, sao usadas como estao.

### Validacao da carga inicial

Ao abrir o app e ao tocar em **Iniciar**, tudo e conferido antes de qualquer
clique; qualquer falha impede o inicio e o log indica o arquivo e o campo:

- `mainSession.json` existe e e JSON valido;
- toda sessao tem `name` e ao menos uma acao; toda acao tem `name` e `locate`;
- todo `call` aponta para uma sessao existente em `sessions/`;
- todo `locate` tem imagem correspondente em `templates/`;
- `searchArea` tem os quatro campos, com `right > left`, `bottom > top`, dentro
  da tela e comportando o template depois do escalonamento;
- `threshold` entre 0.0 e 1.0; tempos e `retries` nao negativos.

## Interface

A tela do app e apenas o painel de controle (paisagem fixa):

- status do servico e atalho para as configuracoes de acessibilidade;
- caminhos de `templates/` e `sessions/`;
- **Iniciar** (valida a carga e espera ate 15 s o app alvo chegar ao primeiro
  plano antes da primeira captura; a troca de app e feita pelo usuario),
  **Parar**, **Limpar** e **Copiar**;
- caixa de log com as ultimas 500 linhas, mantida pelo servico (sobrevive ao
  fechamento da tela) e espelhada no Logcat com a tag `ClickService`.

Rotulos do log: `Carga inicial`, `Sessao`, `Tentativa`, `Acao`, `Escala`,
`Tempo captura`, `Tempo localizacao`, `Posicao inicial`, `Posicao final`,
`Clique`, `Transicao`, `Tempo acao`, `Tempo total`.

## Reconhecimento visual

O servico captura a tela (`AccessibilityService.takeScreenshot()`, **Android 11 /
API 30+**), converte para tons de cinza e procura os templates por correlacao
cruzada normalizada. Em Android 10 ou anterior a captura nao existe e o app nao
funciona.

- uma captura por tentativa, compartilhada por todas as acoes daquela tentativa;
- escala unica (`[1.0]`) por padrao; declare mais escalas so quando o recorte vier
  de um aparelho de resolucao diferente;
- `searchArea` limita o casamento ao recorte informado, que e o maior ganho de
  desempenho;
- a busca para assim que atinge escore >= 0.95;
- os tempos de captura, localizacao, acao e total aparecem no log, medidos com
  relogio monotonico (`SystemClock.elapsedRealtime`).

### Criando e instalando os templates

1. Capture a tela do app alvo (`adb exec-out screencap -p > tela.png`).
2. Recorte apenas o elemento a ser clicado, sem fundo variavel nem animacao;
   recortes de 80 a 300 px de lado funcionam bem. O utilitario
   `tools/recortar_template.py` recorta sem redimensionar e imprime o centro do
   recorte (o ponto onde o clique cai quando a acao nao declara `clicks`):
   ```
   python tools/recortar_template.py tela.png loja.png --centro 800 790 --tamanho 170
   ```
3. Salve como PNG com nome curto, minusculo e sem espacos: `loja.png`.
4. Copie para o aparelho (o `adb push` direto em `Android/data/...` costuma ser
   bloqueado pelo scoped storage):

```
adb push loja.png /data/local/tmp/loja.png
adb shell cp /data/local/tmp/loja.png \
  /sdcard/Android/data/com.example.automacaocliques/files/templates/loja.png
```

Extensoes aceitas: `png`, `jpg`, `jpeg`, `webp`. O nome do template e o nome do
arquivo sem extensao, e e ele que vai no campo `locate`.

Passo a passo completo de captura, recorte e teste num jogo real:
[docs/manual-teste-jogo.md](docs/manual-teste-jogo.md).

## Limitacoes conhecidas

- Nenhum app pode trazer outro app arbitrario para o primeiro plano: o app apenas
  sai da frente e espera. Se o app alvo nao voltar em 15 s, o log registra
  `Transicao NOK - app em primeiro plano` e a execucao termina.
- Telas protegidas com `FLAG_SECURE` nao podem ser capturadas
  (`Transicao NOK - captura falhou (codigo=...)`).
- O app e exclusivamente visual: elementos que so existem na arvore de
  acessibilidade, sem aparencia estavel, nao sao automatizaveis. A arvore e usada
  apenas para saber qual app esta em primeiro plano, o que exige manter
  `canRetrieveWindowContent="true"` no `accessibility_service_config.xml`.
- Alguns jogos com anti-cheat descartam toques injetados por acessibilidade.

Em Android 13+ um app instalado fora da loja (sideload) pode ter o acesso a
acessibilidade bloqueado. Nesse caso abra `Configuracoes > Apps > Automacao Cliques`,
menu (tres pontos) e escolha **Permitir configuracoes restritas** antes de ativar
o servico.
