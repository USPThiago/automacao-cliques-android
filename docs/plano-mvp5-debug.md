# MVP 5 — Modo debug com popup passo a passo

Base: `main` do `USPThiago/automacao-cliques-android` (MVP 4 já mergeado, PR #12).
Escopo aprovado pelo usuário; **um único popup após o último clique da ação**.

## 1. Requisitos

1. Toggle "Modo debug" na tela do app, ligado/desligado pelo usuário e persistido.
2. Com o modo ligado, cada ação executada mostra um popup com:
   - nome da sessão;
   - número da tentativa (retry);
   - nome da ação que localizou o template;
   - posição do template na tela (`left,top` e `right,bottom`);
   - posição do(s) clique(s) despachado(s).
3. O popup aparece **depois do último clique da ação** e **antes** da transição para a
   próxima sessão (`call`).
4. Dois botões: `OK` segue para a próxima sessão; `Cancel` interrompe o processamento e
   traz a tela do app de volta ao primeiro plano.
5. O popup deve indicar visualmente **onde o toque foi aplicado** na tela.

## 2. Restrição de arquitetura que define o desenho

Durante a execução a `MainActivity` está em segundo plano (`moveTaskToBack(true)` em
`MainActivity.start()`), e o roteiro roda na `runnerExecutor` do serviço, com
`SessionRunner` bloqueando essa thread. Logo:

- **não** dá para usar `AlertDialog` da Activity: não há Activity em primeiro plano;
- o popup **DEVE** ser uma janela de sobreposição do próprio `AccessibilityService`,
  criada no `WindowManager` com `TYPE_ACCESSIBILITY_OVERLAY` (não exige
  `SYSTEM_ALERT_WINDOW`, já que o serviço de acessibilidade tem esse direito);
- a view é inflada/anexada na **thread principal** (`mainHandler.post`), enquanto a
  thread do roteiro espera a resposta com um `CountDownLatch` — mesmo padrão já usado em
  `ServiceEnvironment.capture()` e `ServiceEnvironment.click()`.

## 3. Alterações previstas

### 3.1 `RunnerEnvironment` (novo ponto de extensão)

```kotlin
/** Informações mostradas no popup de debug. */
data class DebugStep(
    val sessionName: String,
    val attempt: Int,
    val attempts: Int,
    val actionName: String,
    val match: Area,          // posição do template na tela real
    val clicks: List<ClickPoint>,
    val nextSession: String?  // action.call, ou null quando é o fim
)

enum class DebugChoice { CONTINUE, CANCEL }

interface RunnerEnvironment {
    // ... existente ...
    /** `true` quando o modo debug está ligado. */
    fun debugEnabled(): Boolean
    /** Mostra o popup e bloqueia até o usuário responder. */
    fun confirmStep(step: DebugStep): DebugChoice
}
```

Sem `debugEnabled()`, o `SessionRunner` teria de conhecer `SharedPreferences`; mantendo
tudo no ambiente, os testes JVM continuam controlando o comportamento.

### 3.2 `SessionRunner`

- `attempt()` passa a receber o número da tentativa (hoje o laço externo já o tem) para
  poder preencher `DebugStep.attempt`;
- `dispatchClicks()` passa a **devolver os pontos efetivamente despachados** (já
  escalonados), em vez de só o motivo da falha, algo como:

```kotlin
private sealed class ClicksOutcome {
    data class Ok(val points: List<ClickPoint>) : ClicksOutcome()
    data class Failed(val reason: String) : ClicksOutcome()
}
```

- ponto de inserção, dentro de `attempt()`, exatamente na ordem pedida no requisito 3:

```text
dispatchClicks(...)                     // todos os cliques da ação
if (env.debugEnabled()) {
    log.add("Debug", "aguardando confirmacao")
    if (env.confirmStep(step) == CANCEL) {
        cancel()                        // marca cancelado
        log.add("Debug", "cancelado pelo usuario")
        return AttemptOutcome.Aborted("cancelado no modo debug")
    }
}
pause(action.waitAfterMs)
log.add("Transicao", "OK")
return AttemptOutcome.Executed(action.call)
```

`waitAfterMs` fica **depois** do popup: o tempo de espera existe para a tela alvo
reagir, e enquanto o popup está aberto ela não vai mudar.

- o `RunOutcome` do cancelamento por debug reaproveita `RunOutcome.Cancelled` (o
  `cancelled` já é checado no laço externo), para que a mensagem de log seja a mesma de
  "interrompida pelo usuario".

### 3.3 `DebugOverlay` (novo arquivo)

Responsável por criar, mostrar e remover a sobreposição:

- `WindowManager.LayoutParams` com `type = TYPE_ACCESSIBILITY_OVERLAY`,
  `FLAG_NOT_TOUCH_MODAL` **desligado** na área do card (o popup precisa receber toques) e
  fundo escurecido;
- layout novo `debug_overlay.xml`: um card com as linhas rótulo/valor (mesma redação do
  log: `Sessao`, `Tentativa`, `Acao`, `Posicao inicial`, `Posicao final`, `Clique`,
  `Proxima sessao`) e os botões `Cancel`/`OK`;
- **requisito 5** (mostrar onde o clique foi aplicado): uma `View` desenhada em tela cheia
  dentro da mesma sobreposição, com
  - um retângulo contornando a área do template (`DebugStep.match`), e
  - uma cruz/círculo em cada ponto de clique;
  o card é posicionado na metade da tela **oposta** ao clique (se o clique está na metade
  de cima, o card vai para baixo, e vice-versa), para não cobrir o marcador;
- remoção garantida em `OK`, em `Cancel`, no `stop()` e em `onUnbind()` — uma sobreposição
  vazada continuaria por cima do jogo.

### 3.4 `ClickAccessibilityService`

- `ServiceEnvironment.debugEnabled()` lê a preferência (leitura por execução, não por
  ação, para não pagar I/O em cada passo — decisão: lida uma vez em `start()` e mantida
  na execução);
- `ServiceEnvironment.confirmStep()`:

```kotlin
val latch = CountDownLatch(1)
var choice = DebugChoice.CONTINUE
mainHandler.post { overlay.show(step) { choice = it; latch.countDown() } }
if (!latch.await(DEBUG_TIMEOUT_MS, MILLISECONDS)) { ...trata timeout... }
mainHandler.post { overlay.hide() }
return choice
```

- decisão de timeout: **sem timeout** (espera indefinida) seria mais simples, mas deixa a
  execução presa se a sobreposição falhar; usar timeout longo (5 min) que, ao expirar,
  cancela a execução e registra `Debug: sem resposta - execucao parada`;
- em `Cancel`, o serviço traz o app de volta:
  `startActivity(Intent(this, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT))`
  (requisito 4). Isso deve acontecer **depois** de remover a sobreposição.

### 3.5 Preferência e interface

- novo `DebugPreference` (ou `AppPreferences`) encapsulando
  `SharedPreferences("automacao", MODE_PRIVATE)`, chave `debug_enabled`, default `false`;
- `activity_main.xml`: `MaterialSwitch` (`com.google.android.material.materialswitch`)
  com `@string/debug_mode`, logo abaixo da linha dos botões Iniciar/Parar;
- `MainActivity`: `binding.debugSwitch.isChecked = prefs.debugEnabled`;
  `setOnCheckedChangeListener { prefs.debugEnabled = it }` + linha no log
  (`Modo debug: ligado/desligado`), útil porque o log é o registro do teste.

## 4. Testes (JVM apenas — sem emulador, por decisão do usuário)

Em `SessionRunnerTest`, o `FakeEnv` ganha `debugEnabled`/`confirmStep` programáveis:

1. debug desligado → `confirmStep` nunca é chamado;
2. debug ligado + `CONTINUE` → segue para a sessão de `call`, um popup por ação
   executada;
3. debug ligado + `CANCEL` → `RunOutcome.Cancelled`, nenhum clique adicional, nenhuma
   sessão carregada depois;
4. ação com vários cliques → `confirmStep` chamado **uma vez**, e `DebugStep.clicks`
   contém **todos** os pontos escalonados, na ordem (é o requisito principal desta etapa);
5. cliques implícitos (sem `clicks` no JSON) → `DebugStep.clicks` traz o centro do
   template;
6. `DebugStep.match` reflete a posição escalonada do template na tela real;
7. popup ocorre depois do último clique e antes do `call` (verificado pela ordem dos
   eventos registrados no `FakeEnv`).

`./gradlew --offline test lint assembleDebug` deve passar. **Não** rodar emulador.

## 5. Riscos / pontos de atenção

| Risco | Mitigação |
| --- | --- |
| Sobreposição não removida cobre o jogo | remover em `OK`/`Cancel`/`stop()`/`onUnbind()`, e `hide()` idempotente |
| `TYPE_ACCESSIBILITY_OVERLAY` indisponível em API < 22 | `minSdk` é 24 e o app já exige API 30 para captura: sem problema real |
| Popup dentro da própria captura | o popup só existe depois da captura da tentativa; a próxima tentativa/sessão captura **depois** do `hide()` — garantir a ordem no código |
| Toque de `OK` interpretado pelo jogo | a sobreposição consome o toque; conferir manualmente |
| Execução presa esperando resposta | timeout de 5 min que cancela |
| `startActivity` do serviço bloqueado em background | serviço de acessibilidade tem exceção de background-activity-launch; se falhar, registrar no log em vez de silenciar |

## 6. Documentação

- `README.md`: seção "Modo debug" (o que o popup mostra, quando aparece, o que cada botão
  faz);
- `docs/manual-teste-jogo.md`: como usar o modo debug para conferir se a `searchArea` e as
  coordenadas de clique estão certas antes de deixar o roteiro rodar sozinho;
- `docs/spec-mvp4-sessoes.md` não muda (o modo debug não altera o contrato JSON).

## 7. Fora do escopo

Passo a passo também nas tentativas que **não** localizaram nada (o requisito fala da ação
que encontrou o template), gravação do popup em arquivo, ajuste de coordenadas pelo popup,
e qualquer mudança no formato das sessões.
