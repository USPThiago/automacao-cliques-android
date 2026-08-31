# Backlog de desenvolvimento

Lista viva das atividades ainda pendentes. Ponto de partida: `main` com o **MVP 4**
concluído (sessões em JSON, `searchArea` com escala única e early exit, interface
intermediária com caixa de log) e os seis apontamentos do review corrigidos
(PR [#13](https://github.com/USPThiago/automacao-cliques-android/pull/13)).

Como usar: cada item tem um **critério de pronto** verificável. Ao concluir um item,
remova-o daqui (ou mova para o histórico do PR que o resolveu) e atualize o
`README.md` se o comportamento visível ao usuário mudar. Itens da seção
[Fora do escopo atual](#fora-do-escopo-atual) **não** devem ser implementados sem
decisão explícita.

Prioridades: **P0** = bloqueia a confiança no que já existe; **P1** = próximo
incremento de funcionalidade; **P2** = qualidade/manutenção.

---

## P0 — Validar o que já está implementado

### 1. Validação end-to-end do MVP 4 em aparelho real

Nada do MVP 4 foi executado em aparelho ou emulador: os 58 testes são todos JVM, com
captura simulada. É a única pendência que pode invalidar decisões de projeto.

- Roteiro a seguir: [`docs/manual-teste-jogo.md`](manual-teste-jogo.md).
- Conferir na caixa de log: `Carga inicial OK`, `Escala`, `Tempo captura`,
  `Tempo localizacao`, `Tempo acao`, `Tempo total`, `Transicao OK`.
- Registrar os tempos medidos (captura e localização, com e sem `searchArea`) para
  comparar com a expectativa de ganho de ~7x da escala única.
- **Pronto quando**: um ciclo de pelo menos duas sessões (com `call` de volta) roda no
  jogo real e os tempos estão anotados neste arquivo ou no README.

### 2. Divergência de orientação na validação da tela inicial

`MainActivity.validateLoad()` valida com a resolução da própria interface, que é
sempre retrato (`screenOrientation="portrait"`); o serviço revalida depois da troca de
app, já na orientação do alvo. Em jogo em paisagem a tela inicial pode mostrar
`Carga inicial: NOK - searchArea ... fora da tela` para um roteiro que funciona.

- Opções: validar as duas orientações na Activity, ou rotular a linha como
  "prévia (retrato)" deixando a validação definitiva para o Iniciar.
- **Pronto quando**: o log da tela inicial não contradiz o resultado da execução, ou
  deixa explícito que é uma prévia.

### 3. Comportamento em Android 10 ou anterior

`minSdk` é 24, mas `takeScreenshot()` exige API 30. Em Android ≤ 10 o app instala,
ativa o serviço e só falha na primeira captura, com
`Transicao NOK - captura falhou (codigo=-1)`.

- Opções: subir `minSdk` para 30, ou avisar na tela inicial ("este aparelho não
  suporta captura de tela") antes de o usuário montar templates e sessões.
- **Pronto quando**: aparelho incompatível é identificado antes da execução.

---

## P1 — Próximo incremento (MVP 5)

### 4. Overlay flutuante para Iniciar/Parar

Hoje Parar exige voltar ao app de automação, o que interrompe o jogo. Previsto no
roteiro de MVPs do README como MVP 5.

- Botão flutuante com `TYPE_ACCESSIBILITY_OVERLAY` (não precisa de
  `SYSTEM_ALERT_WINDOW`), mostrando estado (executando/parado) e a última linha do log.
- Deve sobreviver à troca de app e desaparecer quando o serviço é desligado.
- **Pronto quando**: é possível iniciar e parar um roteiro sem sair do app alvo.

### 5. Edição das sessões pela própria interface

Hoje o roteiro só existe como arquivo em `files/sessions/`, editado via `adb` ou
gerenciador de arquivos. Também previsto como MVP 5.

- Mínimo útil: listar as sessões instaladas, abrir uma, editar o JSON com validação
  antes de salvar (reaproveitar `SessionValidator`) e criar `mainSession.json` a
  partir de um modelo.
- **Pronto quando**: um roteiro novo pode ser criado sem computador.

### 6. Escalas amplas acessíveis ao usuário

`TemplateMatcher.WIDE_SCALES` (as 7 escalas do MVP 3.5) existe no código mas nenhum
caminho a usa: quem recorta template em outro aparelho precisa listar as escalas à
mão em cada ação.

- Opções: aceitar `"scales": "wide"` no JSON, ou um campo de sessão que sirva de
  default para as ações — ou remover a constante se a decisão for não expor.
- **Pronto quando**: não há constante morta e o caso "recorte de outro aparelho" tem
  caminho documentado.

---

## P2 — Qualidade e manutenção

### 7. CI no GitHub

Não existe `.github/workflows/`: `test`, `lint` e `assembleDebug` só rodam localmente,
e os PRs entram sem verificação automática.

- Workflow em `pull_request` com JDK 17, cache do Gradle e
  `./gradlew test lint assembleDebug`.
- **Pronto quando**: um PR mostra o resultado dos testes sem intervenção manual.

### 8. Testes instrumentados

Não há `app/src/androidTest/`. Sem eles, `TemplateStore` (decodificação de PNG/JPEG e
conversão para `GrayImage`), `SessionStore` (leitura em `getExternalFilesDir`) e o
caminho de captura ficam sem cobertura automatizada.

- **Pronto quando**: existe pelo menos um teste instrumentado cobrindo carga de
  template e leitura de sessão do disco.

### 9. Custo de memória da busca grosseira

`TemplateMatcher.searchTop()` materializa **todas** as posições candidatas em uma
`ArrayList` e ordena para pegar as 8 melhores. Na tela inteira reduzida por 8 são
milhares de objetos por escala e por tentativa; com `searchArea` pequena o custo é
irrelevante, mas o caminho sem `searchArea` é o padrão.

- Trocar por uma fila de prioridade limitada (ou seleção parcial) mantendo os testes
  atuais de early exit e de área verdes.
- **Pronto quando**: o consumo não cresce com a área varrida e os testes seguem passando.

### 10. Limpeza de API não usada

`ClickAccessibilityService.isExecuting()` não tem chamador (a interface descobre o
estado pelo retorno de `start()`). Remover ou usar para desabilitar o botão Iniciar
enquanto há execução.

- **Pronto quando**: não há membro público sem uso nem duplicidade de estado.

### 11. Documentação de tempos e limites reais

Os defaults (`retries` 3, `retryDelayMs` 1000, `clickIntervalMs` 300,
`waitAfterMs` 1000, `threshold` 0.80, timeout de foreground 15 s) foram escolhidos
antes de qualquer medição em jogo real. Revisar depois do item 1 e ajustar README e
especificação se os números mudarem.

### 12. Build de release assinado

Só existe o APK de debug, assinado com a chave de debug. Para instalar de forma
estável no aparelho do usuário (sem reinstalar a cada troca de máquina), gerar
keystore própria e configurar `signingConfig` para `assembleRelease` — sem versionar a
keystore nem a senha.

---

## Fora do escopo atual

Decidido explicitamente na especificação do MVP 4
([`docs/spec-mvp4-sessoes.md`](spec-mvp4-sessoes.md), §9) — não implementar sem nova decisão:

- `onFailure` por sessão (ação alternativa quando nada é localizado);
- tela de configurações para os tempos e limites (hoje só no JSON);
- notificação persistente durante a execução;
- gravação do log em arquivo;
- `launchPackage` (abrir o app alvo automaticamente — o Android não permite trazer um
  app arbitrário ao primeiro plano);
- gestos além do toque simples: swipe, arrastar, toque longo;
- espera por uma imagem **desaparecer** como condição.

Também fora de escopo, por limitação de plataforma e não por falta de implementação:

- automatizar elementos que só existem na árvore de acessibilidade (o app é
  exclusivamente visual desde o MVP 4);
- capturar telas com `FLAG_SECURE`;
- contornar anti-cheat que descarta toques injetados por acessibilidade.
