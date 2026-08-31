# MVP 4 — Especificação final: sessões em JSON, desempenho e interface intermediária

Status: **aprovada** (respostas do usuário de 2026-08-29 incorporadas).
Escopo: substituir a execução por termos de texto por um motor de sessões declaradas em
arquivos JSON, reduzir o tempo de reconhecimento visual e simplificar a interface para uso
intermediário (a interface final vem depois).

Convenções deste documento: **DEVE** = requisito; **PODE** = opcional; valores entre
`crase` = identificador no código ou no JSON.

---

## 1. Decisões tomadas

| # | Assunto | Decisão |
| --- | --- | --- |
| 1 | Semântica das ações de uma sessão | **Opção A**: as ações são avaliadas em ordem e **apenas a primeira cuja imagem for localizada é executada**; as demais são ignoradas |
| 2 | Ciclos entre sessões | **Permitidos de propósito** (farm). Não há limite de passos. A parada é a falha das tentativas (ex.: o usuário troca de tela manualmente) |
| 3 | `onFailure` por sessão | **Fora do escopo** desta etapa |
| 4 | Coordenadas | Absolutas, **escalonadas** pela resolução de referência `screen`; a escala aplicada **DEVE** aparecer no log |
| 5 | Formato das coordenadas | Chaves nomeadas: `{ "x": .., "y": .. }` e `{ "left": .., "top": .., "right": .., "bottom": .. }` |
| 6 | Tempos | Defaults `retryDelayMs` 1000, `clickIntervalMs` 300, `waitAfterMs` 1000; campos ausentes no JSON assumem o default (tela de configurações fica para depois) |
| 7 | Log em arquivo | Não |
| 8 | `launchPackage` | Não |
| 9 | Clique por texto (MVP 3) | **Código removido** do projeto |

---

## 2. Modelo de dados

### 2.1 Localização dos arquivos

```text
Android/data/com.example.automacaocliques/files/templates/   (imagens, já existe)
Android/data/com.example.automacaocliques/files/sessions/     (arquivos .json, novo)
```

O caminho das duas pastas **DEVE** aparecer na tela inicial (como hoje acontece com
`templates/`). A execução começa **obrigatoriamente** em `sessions/mainSession.json`; os
demais nomes são livres.

### 2.2 Esquema do arquivo de sessão

```json
{
  "name": "tela inicial",
  "screen": { "width": 1080, "height": 2400 },
  "retries": 5,
  "retryDelayMs": 1000,
  "actions": [
    {
      "name": "fechar promocao",
      "locate": "fechar_x",
      "threshold": 0.8,
      "scales": [1.0],
      "searchArea": { "left": 700, "top": 100, "right": 1080, "bottom": 500 },
      "clicks": [
        { "x": 980, "y": 220 },
        { "x": 540, "y": 1800, "delayMs": 500 }
      ],
      "clickIntervalMs": 300,
      "waitAfterMs": 1000,
      "call": "menu_principal"
    }
  ]
}
```

#### Sessão

| Campo | Tipo | Obrigatório | Default | Significado |
| --- | --- | --- | --- | --- |
| `name` | string | sim | — | Nome exibido no log |
| `screen` | `{width, height}` | não | resolução atual | Resolução em que as coordenadas foram medidas |
| `retries` | int ≥ 0 | não | 3 | Tentativas **adicionais**; total = `1 + retries` |
| `retryDelayMs` | int ≥ 0 | não | 1000 | Espera entre tentativas |
| `actions` | lista | sim, ≥ 1 | — | Avaliadas em ordem (opção A) |

#### Ação

| Campo | Tipo | Obrigatório | Default | Significado |
| --- | --- | --- | --- | --- |
| `name` | string | sim | — | Nome da **ação** |
| `locate` | string | sim | — | Nome do template (com ou sem extensão) |
| `threshold` | 0.0–1.0 | não | 0.80 | Escore mínimo para considerar localizado |
| `scales` | lista de double | não | `[1.0]` | Escalas testadas (ver §3) |
| `searchArea` | `{left,top,right,bottom}` | não | tela inteira | Região onde procurar |
| `clicks` | lista de `{x, y, delayMs?}` | não | um clique no centro da imagem localizada | Cliques em ordem |
| `clickIntervalMs` | int ≥ 0 | não | 300 | Espera entre cliques (o `delayMs` do ponto tem precedência) |
| `waitAfterMs` | int ≥ 0 | não | 1000 | Espera após o último clique, antes de a próxima sessão capturar |
| `call` | string | não | — | Próxima sessão; **ausente = fim com sucesso** |

Regras adicionais:

- coordenadas de `clicks` e `searchArea` são interpretadas na resolução `screen` e escalonadas
  para a resolução real: `fator = telaReal.largura / screen.width` (idem altura);
- se `screen` for omitido, o fator é 1.0 e o log registra `Escala 1.000 (sem referencia)`;
- `searchArea` recortada aos limites da tela; a busca **DEVE** falhar com mensagem clara se o
  template não couber na área.

---

## 3. Desempenho do reconhecimento (motivador desta etapa)

Comportamento atual: 7 escalas fixas e busca sempre na tela inteira; o tempo cresce
linearmente com a área do recorte e com o número de escalas.

Requisitos:

1. **Escala única por padrão** (`scales` default `[1.0]`), em vez das 7 atuais. Ganho ~7x.
   Escalas extras continuam disponíveis por ação para quem usa recorte de outro aparelho.
2. **Busca restrita a `searchArea`**: o casamento **DEVE** operar sobre o recorte da captura,
   não sobre a tela inteira; ganho proporcional à redução de área.
3. **Early exit**: no refino dos candidatos, ao encontrar escore ≥ `earlyExitScore` (0.95),
   **PODE** parar de refinar os demais candidatos.
4. **Uma única captura por tentativa de sessão** compartilhada por todas as ações avaliadas
   (comportamento já existente, agora explícito).
5. Captura e casamento continuam **fora da thread principal** (`visionExecutor`).

Os tempos de captura e de localização **DEVEM** ser medidos com relógio monotônico
(`SystemClock.elapsedRealtime`) e registrados no log.

---

## 4. Motor de execução

### 4.1 Carga inicial

Ao abrir o app (e ao tocar em Iniciar), **DEVE** validar tudo e registrar
`Carga inicial: OK` ou `Carga inicial: NOK - <motivo>`:

1. `sessions/mainSession.json` existe e é JSON válido;
2. cada sessão tem `name` e ao menos uma ação; cada ação tem `name` e `locate`;
3. todo `call` aponta para um arquivo de sessão existente;
4. todo `locate` tem imagem correspondente em `templates/`;
5. `searchArea` com os quatro campos, `right > left`, `bottom > top`, dentro da tela;
6. template cabe na `searchArea` (após escalonamento);
7. `threshold` em 0.0–1.0; tempos ≥ 0; `retries` ≥ 0.

Falha em qualquer item **DEVE** impedir o início da execução e nomear o arquivo e o campo.

### 4.2 Laço de execução

```text
sessao := mainSession
enquanto verdadeiro:
    tentativa := 1
    repetir:
        log "Sessao <nome>", "Tentativa <n>"
        captura := capturaTela()                  # a cada tentativa, sempre
        log "Tempo captura <ms>"
        para cada acao em sessao.actions:         # ordem do arquivo
            log "Acao <nome>"
            resultado := localiza(captura, acao)  # dentro de searchArea, escalas da acao
            log "Tempo localizacao <ms>"
            se localizado:
                log "Posicao inicial/final", "Escala <fator>"
                para cada clique: despacha; log "Clique x=..,y=.."; espera intervalo
                espera acao.waitAfterMs
                log "Transicao OK" (ou o motivo da falha)
                se acao.call ausente: encerra com sucesso
                sessao := carrega(acao.call)
                continua o laco externo
        # nenhuma acao localizada
        tentativa := tentativa + 1
        espera sessao.retryDelayMs
    ate tentativa > 1 + sessao.retries
    log "Sessao <nome>: nenhuma acao localizada em <N> tentativa(s) - encerrado"
    encerra com falha
```

Notas:

- **ciclos são permitidos**: `call` pode voltar a uma sessão anterior indefinidamente; não há
  limite de passos. A parada natural é a exaustão das tentativas.
- a execução **DEVE** ser cancelável (§5, botão Parar) e **DEVE** parar sozinha se o serviço
  de acessibilidade for desligado.
- duas execuções simultâneas **DEVEM** ser impedidas.
- o guard atual (passo visual ignorado quando o próprio app está em primeiro plano) é
  substituído por: ao iniciar, **esperar até a janela ativa não pertencer a este app**, com
  timeout de 15 s; se o timeout expirar, `Carga inicial`/execução falha com
  `Transicao NOK - app em primeiro plano`. Isso substitui o atraso fixo de 6 s.

### 4.3 Mensagens de falha de `Transicao`

| Situação | Mensagem |
| --- | --- |
| Cliques despachados e concluídos | `Transicao OK` |
| `dispatchGesture` devolveu false | `Transicao NOK - gesto rejeitado` |
| Callback `onCancelled` | `Transicao NOK - gesto cancelado` |
| `call` aponta para sessão ilegível em tempo de execução | `Transicao NOK - sessao <nome> ilegivel` |
| Captura falhou | `Transicao NOK - captura falhou (codigo=<n>)` |

---

## 5. Interface intermediária

### 5.1 Estrutura da tela

- **Orientação fixa em paisagem** (`android:screenOrientation="landscape"`).
- Elementos mantidos: status do serviço (ATIVO/INATIVO), botão para abrir as configurações de
  acessibilidade, caminho das pastas `templates/` e `sessions/`.
- Elementos **removidos**: campo de digitação de sequência, botão "Executar sequência",
  botão "Ler tela agora", botão "Reconhecer tela (templates)".
- Elementos **novos**: botão **Iniciar**, botão **Parar** e a **caixa de log**.

`Parar` não constava da sua especificação; incluo porque com ciclos permitidos é a única
forma de interromper sem desligar o serviço — e o botão fica visível assim que você volta ao
app. Sem notificação persistente nesta etapa.

Ao tocar em **Iniciar**: valida a carga, chama `moveTaskToBack(true)` (revela o app que estava
atrás) e espera o foreground deixar de ser este app antes da primeira captura. Limitação do
Android: nenhum app pode trazer outro app arbitrário ao primeiro plano; se o jogo tiver sido
descarregado da memória, quem aparece é a tela inicial.

### 5.2 Caixa de log

- Vive no **serviço**, não na Activity (o app fica em segundo plano durante a execução); a tela
  reflete o estado ao voltar.
- Rolagem automática para a última linha, limite de **500 linhas** (descarta as mais antigas).
- Botões **Limpar** e **Copiar**.
- Cada linha continua saindo também no Logcat com a tag `ClickService`.

Linhas previstas (rótulo + valor):

| Rótulo | Valor |
| --- | --- |
| `Carga inicial` | `OK` / `NOK - <motivo>` |
| `Sessao` | nome da sessão em execução |
| `Tentativa` | número da tentativa |
| `Acao` | nome da ação sendo avaliada |
| `Escala` | fator aplicado às coordenadas (ex.: `1.000`, `0.900 (1080x2400 -> 972x2160)`) |
| `Tempo captura` | ms entre o pedido e a resposta da captura |
| `Tempo localizacao` | ms entre o início da busca e o resultado localizado/não localizado |
| `Posicao inicial` | `x=..,y=..` do canto superior esquerdo da imagem localizada |
| `Posicao final` | `x=..,y=..` do canto inferior direito |
| `Clique` | `x=..,y=..` de cada clique despachado |
| `Transicao` | `OK` ou mensagem de falha (§4.3) |
| `Tempo acao` | ms entre o início e o fim do processamento da ação |
| `Tempo total` | ms entre o início e o fim do processamento da sessão |

Rótulos usam `x=`/`y=` explícitos para eliminar a ambiguidade vertical/horizontal.

---

## 6. Remoção do clique por texto (MVP 3)

Excluir do projeto:

- `ScreenReader.kt`, `ScreenNode`, `NodeSelector`, `ClickStep` e o parser de termos;
- `readScreen()`, `logScreen()`, `findNode()`, `clickNode()`, `runSequence(List<ClickStep>)`;
- os testes `ClickStepTest`, testes de `ScreenReader` e as strings/layout correspondentes;
- as seções do README sobre o MVP 3.

Mantido: `AccessibilityNodeInfo` continua sendo usado **apenas** para saber qual app está em
primeiro plano (`rootInActiveWindow.packageName`), o que exige preservar
`canRetrieveWindowContent` no `accessibility_service_config.xml`.

Consequência: o app passa a ser exclusivamente visual — telas que só expõem texto acessível,
mas cujo elemento não é visualmente estável, deixam de ser automatizáveis.

---

## 7. Arquivos afetados

| Arquivo | Ação |
| --- | --- |
| `TemplateMatcher.kt` | `searchArea`, escalas por chamada, early exit |
| `ClickAccessibilityService.kt` | motor de sessões, medições, cancelamento, espera por foreground |
| `SessionStore.kt`, `Session.kt`, `SessionParser.kt`, `SessionValidator.kt`, `SessionRunner.kt` | novos |
| `ExecutionLog.kt` | novo (buffer de 500 linhas no serviço) |
| `MainActivity.kt`, `activity_main.xml`, `strings.xml` | interface intermediária |
| `AndroidManifest.xml` | `screenOrientation="landscape"` |
| `ScreenReader.kt`, `ClickStep.kt`, `ClickStepTest.kt`, `ScreenReaderTest.kt` | removidos |
| `docs/manual-teste-jogo.md`, `README.md` | atualizados (sessões em vez de termos) |

## 8. Testes

Unitários (JVM, sem aparelho): parser (campos obrigatórios, defaults, JSON inválido),
validador (cada um dos 7 itens de §4.1), escalonamento de coordenadas, `searchArea` no
matcher, early exit, e a máquina de estados do executor com captura simulada — incluindo
opção A (só a primeira ação), retentativas, ciclo A→B→A e exaustão de tentativas.

Instrumentado/manual: emulador com sessões sintéticas (eu) e jogo real (usuário), usando a
caixa de log para conferir os tempos antes/depois das otimizações de §3.

## 9. Fora do escopo desta etapa

`onFailure`, tela de configurações, notificação persistente, gravação do log em arquivo,
`launchPackage`, gravação de sessões pela própria interface, gestos além do toque
(swipe/long press) e espera por imagem desaparecer.
