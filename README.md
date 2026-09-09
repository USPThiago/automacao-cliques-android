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
- **MVP 4**: roteiros declarados em sessoes JSON no aparelho, busca
  restrita por `searchArea` com escala unica e early exit, e interface
  intermediaria com log de execucao (Iniciar/Parar/Limpar/Copiar).
- **MVP 5**: modo debug com popup passo a passo, mostrando onde cada
  acao localizou o template e onde clicou antes de seguir para a proxima sessao.
- **Pos-MVP 5 (atual)**: robustez (excecoes na captura/clique, memoria no
  casamento), retangulos de busca/match na tela, `clickArea` (toque aleatorio
  numa area), chave **Gravar log**, **Modo teste** (pastas `*_teste`), resumo
  ao encerrar (`Total de salas`, `Tempo total`, `Quantidade de cliques`),
  layout em duas colunas com o log sempre visivel e `onLocateFailure` (sessao
  de recuperacao ao esgotar as tentativas).
- **Proximos**: overlay flutuante (start/stop sem sair do app alvo) e edicao das
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

## Sessoes em JSON

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
  "onLocateFailure": "fechar_popup",            // opcional: sessao executada quando as 1 + retries
                                                // tentativas esgotam sem localizar acao alguma

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
      "clicks": [                               // lista de toques, na ordem
        { "x": 980, "y": 220 },
        { "x": 540, "y": 1800, "delayMs": 500 } // delayMs do ponto tem precedencia sobre clickIntervalMs
      ],
      "clickIntervalMs": 300,                   // espera entre cliques; padrao 300
      "waitAfterMs": 1000,                      // espera apos o ultimo clique; padrao 1000
      "call": "menu_principal"                  // proxima sessao: sessions/menu_principal.json
                                                // ("call" e "onLocateFailure" usam o nome do
                                                // ARQUIVO, com ou sem .json, nao o "name")
    },
    {
      "name": "coletar recompensa",             // so e avaliada se a anterior nao for localizada
      "locate": "bau",                          // templates/bau.png
      "clickArea": {                            // alternativa a "clicks": UM toque em ponto
        "left": 400, "top": 1500,               // aleatorio dentro da area (varia a cada execucao,
        "right": 680, "bottom": 1650            // util contra deteccao de padrao); erro se
      },                                        // declarada junto com "clicks"
      "call": "mainSession"                     // ciclo: volta para este mesmo arquivo
    },
    {
      "name": "entrar no jogo",
      "locate": "botao_jogar"                   // sem clicks/clickArea: toca no centro do recorte
                                                // localizado; sem "call": termina com sucesso
    }
  ]
}
```

Formas de clicar, em ordem de precedencia: `clickArea` (um toque aleatorio na
area), `clicks` (lista de toques) ou, sem os dois, um toque no centro do
template localizado. Coordenadas de `clicks`, `clickArea` e `searchArea` sao
escalonadas de `screen` para a resolucao real do aparelho; sem `screen`, sao
usadas como estao. Uma sessao chamada `Resultado` e contada no resumo final
(`Total de salas`).

`onLocateFailure` (opcional, mesma convencao de nomes de `call`) e a sessao de
recuperacao: quando as `1 + retries` tentativas terminam sem localizar nenhuma
acao - inclusive quando a captura falha -, o log registra
`Sessao <nome>: nenhuma acao localizada em N tentativa(s)` e
`Transicao: onLocateFailure -> <sessao>`, e a sessao indicada comeca em seguida,
sem espera adicional, com seus proprios `retries`/`retryDelayMs` e demais
tempos. A sessao de recuperacao pode ter o proprio `onLocateFailure`, mas o
tratamento fica desarmado ate a proxima acao executada com sucesso
(`Transicao: OK`): se a recuperacao esgotar as tentativas antes disso, a
execucao encerra com `... - encerrado`, como hoje. Falhas de gesto e
cancelamento nunca acionam a recuperacao.

### Validacao da carga inicial

Ao abrir o app e ao tocar em **Iniciar**, tudo e conferido antes de qualquer
clique; qualquer falha impede o inicio e o log indica o arquivo e o campo:

- `mainSession.json` existe e e JSON valido;
- toda sessao tem `name` e ao menos uma acao; toda acao tem `name` e `locate`;
- todo `call` e `onLocateFailure` aponta para uma sessao existente em `sessions/`;
- todo `locate` tem imagem correspondente em `templates/`;
- `searchArea` tem os quatro campos, com `right > left`, `bottom > top`, dentro
  da tela e comportando o template depois do escalonamento;
- `clickArea` segue as mesmas regras e nao pode aparecer junto com `clicks`;
- `threshold` entre 0.0 e 1.0; tempos e `retries` nao negativos.

## Interface

A tela do app e apenas o painel de controle (paisagem fixa, em duas colunas:
controles a esquerda — rolaveis se a tela for baixa — e log a direita, sempre
visivel):

- status do servico e atalho para as configuracoes de acessibilidade;
- caminhos de `templates/` e `sessions/` (ou `templates_teste/` e
  `sessions_teste/` no modo teste);
- **Iniciar** (valida a carga e espera ate 15 s o app alvo chegar ao primeiro
  plano antes da primeira captura; a troca de app e feita pelo usuario),
  **Parar**, **Limpar** e **Copiar**;
- chaves **Modo debug** (ver abaixo), **Retangulos na tela**, **Gravar log** e
  **Modo teste**, todas persistidas entre execucoes;
- caixa de log com as ultimas 500 linhas, mantida pelo servico (sobrevive ao
  fechamento da tela) e espelhada no Logcat com a tag `ClickService`.

**Retangulos na tela**: durante a execucao, desenha em amarelo a `searchArea`
onde o template foi pesquisado (tela inteira quando a acao nao declara uma) e
em vermelho a regiao exata onde ele foi localizado. Cada acao localizada
substitui o desenho anterior, removido ao encerrar. Os retangulos sao
escondidos durante cada captura para nao contaminar o casamento. Nao pausa a
execucao. A janela do overlay cobre o display inteiro (barras do sistema e
recortes inclusive) e as coordenadas da captura sao convertidas para o canvas
pela posicao real da janela, de modo que os retangulos coincidem com o que o
casamento viu.

**Gravar log**: desligada, a caixa de log deixa de gravar as linhas comuns;
o Logcat e as linhas de erro/resultado continuam sempre.

**Modo teste**: le sessoes e templates das pastas `sessions_teste/` e
`templates_teste/`, ao lado das originais. Permite ajustar e testar roteiros
sem sobrescrever a versao em uso; ao alternar, a carga e revalidada e os
caminhos exibidos mudam. O par de pastas e fixado no inicio de cada execucao:
alternar no meio do roteiro so vale para a proxima.

Rotulos do log: `Carga inicial`, `Modo debug`, `Retangulos`, `Gravacao do log`,
`Modo teste` (estado das chaves), `Execucao` (iniciada, concluida, parada ou
encerrada com o motivo), `Sessao`, `Tentativa`, `Acao`, `Escala`,
`Tempo captura`, `Tempo localizacao`, `Tempo desde ultimo clique`,
`Resolucao da tela`, `Posicao`, `Clique`, `Transicao` (`OK`, `NOK - <motivo>`
ou `onLocateFailure -> <sessao>`), `Debug`. Acoes que nao
localizam o template nao geram linhas. Ao encerrar (sucesso, falha ou
cancelamento — inclusive falhas antes do roteiro existir), o log termina com o
resumo: `Total de salas` (sessoes chamadas `Resultado` iniciadas), `Tempo
total` (HH:MM:SS do inicio ao fim do processamento) e `Quantidade de cliques`
(apenas gestos aceitos pelo sistema; um toque rejeitado nao entra na conta).

## Modo debug

Com a chave **Modo debug** ligada, cada acao que localiza o seu template mostra,
**depois do ultimo clique** e **antes** de seguir para a sessao do `call`, um popup
desenhado pelo proprio servico de acessibilidade por cima do app alvo (a tela do
app esta em segundo plano durante a execucao). O popup traz:

- `Sessao`, `Tentativa` (ex.: `2 de 3`) e `Acao` que localizou o template;
- `Posicao inicial` (`left,top`) e `Posicao final` (`right,bottom`) do template na
  tela real, ja escalonadas;
- `Clique`: as coordenadas reais de cada toque despachado, na ordem;
- `Proxima sessao`: o `call` da acao, ou `(fim do roteiro)`.

Na tela, um retangulo amarelo contorna o template; o card fica na metade da
tela oposta aos cliques para nao cobrir a regiao localizada. Ha **um unico
popup por acao**, mesmo quando ela tem varios `clicks`. Os botoes:

- **OK**: espera `waitAfterMs` e segue para a proxima sessao;
- **Cancel**: interrompe a execucao (`Debug: cancelado pelo usuario`,
  `Execucao: interrompida pelo usuario`) e traz a tela do app de volta ao primeiro
  plano.

**Parar** com o popup aberto equivale a Cancel. Sem resposta em 5 minutos a
execucao e cancelada (`Debug: sem resposta - execucao parada`). O modo debug nao
muda o formato das sessoes nem aparece nas tentativas que nao localizaram nada.

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
- a busca para assim que atinge escore >= 0.95 (ou >= `threshold`, se maior);
- os tempos de captura, localizacao e o intervalo desde o ultimo clique
  aparecem no log, medidos com relogio monotonico
  (`SystemClock.elapsedRealtime`).

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
