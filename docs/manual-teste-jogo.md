# Manual: testando a automação num jogo real (Android Studio + espelhamento)

Guia prático para rodar o app num aparelho físico, ver a tela do jogo espelhada no
computador e — principalmente — **extrair os recortes de imagem** que o app usa para
reconhecer as telas e clicar por coordenadas.

Vale para o MVP 4: roteiros declarados em **sessões JSON** + reconhecimento visual por
*template matching*. O app não tem mais campo de termos digitados: tudo que ele faz vem
dos arquivos em `sessions/`.

---

## 0. As capturas de tela ficam salvas no aparelho?

**Não.** O reconhecimento visual funciona assim:

1. o serviço pede a imagem ao sistema com `AccessibilityService.takeScreenshot()`;
2. o sistema devolve um `HardwareBuffer` (memória de vídeo), que é convertido em `Bitmap`;
3. o app converte para tons de cinza, calcula os escores e **descarta o bitmap**.

Nenhum arquivo de captura é gravado, nem no armazenamento interno, nem no externo, nem
em cache — não existe no código nenhuma chamada de escrita de imagem em disco. A imagem
nunca sai do aparelho (não há rede no app) e vive só o tempo do cálculo (alguns segundos).

Os **únicos** arquivos de imagem envolvidos são os recortes que **você** coloca na pasta de
templates (seção 4). Eles ficam em `Android/data/com.example.automacaocliques/files/templates/`
e são apagados junto com o app quando você o desinstala.

Duas observações honestas:

- Enquanto o serviço de acessibilidade estiver ligado, ele **pode** capturar a tela a
  qualquer momento (é o que dá poder à automação). O app só captura durante uma execução
  iniciada por você, mas a permissão é essa.
- O Android mostra o aviso de "controle total do dispositivo" ao ativar o serviço
  justamente por isso. Desligue o serviço quando não estiver testando.

---

## 1. Requisitos

| Item | Versão / observação |
| --- | --- |
| Android do aparelho | **11 (API 30) ou superior** — `takeScreenshot()` não existe antes disso |
| Android Studio | Ladybug ou mais recente (qualquer versão com a janela *Running Devices*) |
| Cabo USB + Depuração USB | Configurações > Sobre o telefone > toque 7x em "Número da versão" → Opções do desenvolvedor > Depuração USB |
| JDK | 17 (o próprio Android Studio já traz) |

Os comandos deste manual podem ser rodados no terminal embutido do Android Studio
(`View > Tool Windows > Terminal`, Alt+F12). Onde o exemplo aparece como `bash` e você
estiver no Windows (PowerShell), veja as variantes na seção 4.1 — o redirecionamento `>`
do PowerShell corrompe arquivos binários.

Confirme que o aparelho aparece:

```bash
adb devices          # deve listar seu aparelho como "device", não "unauthorized"
adb shell getprop ro.build.version.sdk    # deve ser >= 30
```

> Em API 24–29 não há captura pela API de acessibilidade e o app não funciona: toda
> tentativa registra `Captura de tela exige Android 11 (API 30)` no log.

---

## 2. Instalar o app

### Opção A — pelo Android Studio (recomendada para testar/alterar código)

1. `File > Open` e abra a pasta do projeto (`automacao-cliques-android`).
2. Espere o *Gradle sync*.
3. Selecione o aparelho no seletor de dispositivos (barra superior).
4. `Run > Run 'app'` (Shift+F10).

### Opção B — APK pronto

```bash
adb install -r automacao-cliques-debug.apk
```

Se o Android 13+ bloquear o acesso à acessibilidade por ser instalação externa:
`Configurações > Apps > Automação de Cliques > menu (⋮) > Permitir configurações restritas`.

## 3. Ativar o serviço e ver o espelhamento

1. Abra o app e toque em **Abrir configurações de acessibilidade**.
2. `Apps instalados > Automação de Cliques > ativar o botão > Permitir`.
3. Volte ao app: o status deve ficar **ATIVO** (o app relê o estado ao voltar à tela).
4. Nada acontece sozinho: a execução só começa quando você toca em **Iniciar**.

### Espelhamento no Android Studio

`View > Tool Windows > Running Devices` e escolha o aparelho físico. Se o aparelho não
aparecer, habilite em `Settings > Tools > Device Mirroring`.

A janela espelha a tela e aceita mouse/teclado. Alternativa fora do Android Studio:
[`scrcpy`](https://github.com/Genymobile/scrcpy) (`scrcpy --stay-awake`), que costuma ter
latência menor para jogos.

> O espelhamento serve para **ver e navegar**. Para gerar os recortes, use a captura
> em resolução nativa da seção 4.1 — nunca um print da janela do espelhamento.

### Logcat

`View > Tool Windows > Logcat` e no campo de filtro digite:

```text
tag:ClickService
```

Equivalente no terminal (útil para copiar e colar aqui):

```bash
adb logcat -c && adb logcat -s ClickService
```

---

## 4. Extraindo as imagens de comparação (a parte que importa)

O app não procura "o botão de loja": ele procura **exatamente os pixels do recorte que você
der** e, por padrão, clica no **centro do recorte encontrado**. Então a qualidade do
recorte é o que determina se a automação funciona.

### 4.1 Capturar a tela do jogo em resolução nativa

Deixe o jogo exatamente na tela que quer automatizar. Há dois caminhos.

**Caminho A — sem terminal (mais simples).** Na janela **Running Devices** (o
espelhamento), use o botão de **captura de tela** (ícone de câmera) da barra de
ferramentas do dispositivo e salve o PNG. A captura sai na resolução real do aparelho,
não na resolução da janela espelhada.

**Caminho B — pelo terminal.** No Android Studio: `View > Tool Windows > Terminal`
(Alt+F12). Ele abre na pasta do projeto.

- Linux / macOS (bash, zsh):
  ```bash
  adb exec-out screencap -p > tela_loja.png
  ```
- **Windows (PowerShell, o padrão do terminal do Android Studio)**: o `>` do PowerShell
  grava em UTF-16 e **corrompe o PNG**. Use:
  ```powershell
  adb exec-out screencap -p | Set-Content tela_loja.png -Encoding Byte    # PowerShell 5
  adb exec-out screencap -p | Set-Content tela_loja.png -AsByteStream     # PowerShell 7
  ```
  Alternativa: salvar no aparelho e trazer depois (funciona em qualquer shell):
  ```powershell
  adb shell screencap -p /sdcard/tela_loja.png
  adb pull /sdcard/tela_loja.png
  ```

Se o terminal responder `adb: command not found` / `não é reconhecido`, chame pelo caminho
completo do SDK (ou adicione `platform-tools` ao PATH):

- Windows: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Linux: `~/Android/Sdk/platform-tools/adb`
- macOS: `~/Library/Android/sdk/platform-tools/adb`

O que **não** serve, em nenhum dos caminhos: `Print Screen` do computador ou recortar a
janela do espelhamento — ela está redimensionada, os pixels ficam reamostrados e o
casamento falha.

Para conferir a resolução esperada:

```bash
adb shell wm size        # ex.: Physical size: 1080x2400
```

Abra o PNG salvo e confirme que ele tem esse mesmo tamanho antes de recortar.

### 4.2 Escolher o que recortar

Regras que fazem diferença, em ordem de importância:

1. **Recorte um retângulo interno do botão**, centralizado — não o botão inteiro com borda.
   O centro do recorte é onde o clique vai cair, então centralize no que você quer tocar.
2. **Fuja de tudo que muda**: contadores de moedas, barras de progresso, brilho animado,
   fundo com nuvens/partículas passando, timers, número de nível. Se metade do recorte
   muda, o escore cai abaixo do limite e o clique não sai.
3. **Prefira o ícone/símbolo estável** dentro do botão (a engrenagem, o "X", o desenho do
   martelo) a textos pequenos.
4. **Tamanho**: mire em algo entre **80x80 e 300x300 px**. Muito pequeno casa em qualquer
   lugar (falso positivo); muito grande inclui fundo variável e derruba o escore.
5. **Não redimensione, não converta com perda, não passe filtro**. Salve o recorte como
   **PNG**, cortado do PNG original.
6. Um recorte por variante de tela: se o botão muda de aparência (ativo/inativo,
   comprado/bloqueado), faça dois templates com nomes diferentes.

Bons candidatos na tela que você enviou: o **X** de fechar (canto), o ícone de
**Melhorar titãs**, o ícone de uma aba fixa do menu inferior.

### 4.3 Recortar

**Com qualquer editor** (GIMP, Paint.NET, Photoshop, Pré-visualização do macOS, Paint):
abra `tela_loja.png`, use a ferramenta de seleção retangular, corte e salve como
`loja.png`. No GIMP: `Ferramentas > Ferramentas de seleção > Seleção retangular` →
`Imagem > Cortar para a seleção` → `Arquivo > Exportar como...`.

Anote o retângulo do recorte: ele é útil para escrever a `searchArea` da ação (seção 5) e o
centro dele é onde o toque cai quando a ação não declara `clicks`.

Para descobrir as coordenadas: a maioria dos editores mostra a posição do cursor em pixels;
ou ative `Configurações > Sistema > Opções do desenvolvedor > Local do ponteiro`, toque no
botão do jogo e leia o X/Y na barra superior.

### 4.4 Nomes dos arquivos

- minúsculas, sem espaços e sem acentos: `loja.png`, `fechar_x.png`, `melhorar_titas.png`;
- o nome **sem extensão** é o que você usa no campo `locate` da ação: `loja.png` → `"locate": "loja"`;
- formatos aceitos: `png`, `jpg`, `jpeg`, `webp` (prefira PNG).

### 4.5 Enviar os recortes para o aparelho

A pasta aparece na tela inicial do app e é:

```text
/storage/emulated/0/Android/data/com.example.automacaocliques/files/templates
```

Ela só existe depois de abrir o app pelo menos uma vez.

**Por adb** — o `push` direto nessa pasta é bloqueado pelo *scoped storage*
(`secure_mkdirs failed: Operation not permitted`), então copie em dois passos:

```bash
adb push loja.png /data/local/tmp/loja.png
adb shell cp /data/local/tmp/loja.png \
  /sdcard/Android/data/com.example.automacaocliques/files/templates/loja.png

# conferir
adb shell ls -l /sdcard/Android/data/com.example.automacaocliques/files/templates/
```

**Sem computador**: copie os PNGs para o aparelho (Drive, cabo, WhatsApp) e mova-os com um
gerenciador de arquivos que abra `Android/data` (o "Arquivos" do próprio Android costuma
abrir; alguns aparelhos exigem um app como o Files do Google via atalho de pasta).

**No Android Studio**: `View > Tool Windows > Device Explorer`, navegue até
`/sdcard/Android/data/com.example.automacaocliques/files/templates` e arraste os arquivos
(botão direito > `Upload...`). É o caminho mais simples se você já está com a IDE aberta.

### 4.6 Conferir se o recorte presta (faça sempre isto antes de automatizar)

Escreva uma sessão mínima com uma única ação, sem `call`, e rode (seção 5):

```json
{
  "name": "teste do recorte",
  "retries": 0,
  "actions": [ { "name": "achar loja", "locate": "loja" } ]
}
```

Deixe o jogo na tela correspondente, toque em **Iniciar** e volte ao app: a caixa de log
mostra o que aconteceu.

```text
Sessao: teste do recorte
Tentativa: 1 de 1
Tempo captura: 412 ms
Acao: achar loja
Tempo localizacao: 630 ms
Escala: 1.000 (sem referencia)
Posicao inicial: x=727,y=1381
Posicao final: x=897,y=1552
Clique: x=812,y=1466
Transicao: OK
```

Se a ação não for localizada, o log traz o melhor escore obtido:

```text
Acao achar loja: nao localizada (melhor escore=0.412, limite=0.80)
```

Interpretação dos escores (vão de -1 a 1; o limite padrão para clicar é **0.80**):

| Escore | Leitura |
| --- | --- |
| 0.95 – 1.00 | recorte ótimo |
| 0.80 – 0.95 | funciona; fundo com alguma variação |
| < 0.80 | **não clica** — recorte pegou área animada, tela errada, ou o jogo mudou de resolução |

---

## 5. Escrevendo as sessões

Um roteiro é um conjunto de arquivos `.json` em:

```text
/storage/emulated/0/Android/data/com.example.automacaocliques/files/sessions
```

A execução **sempre** começa em `mainSession.json`. Cada sessão descreve **uma tela** do
jogo: uma lista de ações avaliadas em ordem, onde **apenas a primeira ação localizada é
executada**. Ao terminar, a ação pode chamar (`call`) a próxima sessão; sem `call`, a
execução termina com sucesso. Ciclos são permitidos (A chama B, B chama A), e a parada
natural é a exaustão das tentativas de uma sessão.

### 5.1 Exemplo comentado de `mainSession.json`

O JSON não aceita comentários; os `//` abaixo são só para leitura.

```jsonc
{
  "name": "tela inicial",                       // nome exibido no log
  "screen": { "width": 1080, "height": 2400 },  // resolução em que você mediu as coordenadas
                                                // (omita se mediu no próprio aparelho)
  "retries": 5,                                 // tentativas adicionais (total = 1 + retries); padrão 3
  "retryDelayMs": 1000,                         // espera entre tentativas; padrão 1000

  "actions": [                                  // avaliadas em ordem; só a primeira localizada roda
    {
      "name": "fechar promocao",                // nome da ação no log
      "locate": "fechar_x",                     // templates/fechar_x.png
      "threshold": 0.8,                         // escore mínimo; padrão 0.80
      "scales": [1.0],                          // padrão [1.0]; use mais escalas só se o recorte
                                                // veio de um aparelho de outra resolução
      "searchArea": {                           // procura só aqui (muito mais rápido);
        "left": 700, "top": 100,                // padrão: tela inteira
        "right": 1080, "bottom": 500
      },
      "clicks": [                               // omita para clicar no centro do recorte encontrado
        { "x": 980, "y": 220 },
        { "x": 540, "y": 1800, "delayMs": 500 } // delayMs do ponto tem precedência sobre clickIntervalMs
      ],
      "clickIntervalMs": 300,                   // espera entre cliques; padrão 300
      "waitAfterMs": 1000,                      // espera após o último clique; padrão 1000
      "call": "menu_principal"                  // próxima sessão: sessions/menu_principal.json
    },
    {
      "name": "entrar no jogo",                 // avaliada só se a anterior não for localizada
      "locate": "botao_jogar"
                                                // sem "call": termina com sucesso ao clicar
    }
  ]
}
```

Envie os arquivos para o aparelho do mesmo jeito que os templates (seção 4.5), trocando
`templates` por `sessions`:

```bash
adb push mainSession.json /data/local/tmp/mainSession.json
adb shell cp /data/local/tmp/mainSession.json \
  /sdcard/Android/data/com.example.automacaocliques/files/sessions/mainSession.json
```

### 5.2 Rodando

1. Abra o jogo e deixe-o na tela inicial do roteiro.
2. Abra o app de automação (o jogo fica atrás, em segundo plano).
3. Toque em **Iniciar**: o app valida a carga (`Carga inicial: OK`), vai para segundo plano
   e espera o jogo voltar ao primeiro plano (até 15 s) antes da primeira captura.
4. Para acompanhar ou interromper, volte ao app: a caixa de log mostra tudo que aconteceu e
   o botão **Parar** cancela a execução. **Limpar** esvazia o log e **Copiar** joga as
   linhas na área de transferência.

O log guarda as últimas 500 linhas e sai também no Logcat (`tag:ClickService`).

### 5.3 Validação da carga inicial

Antes de começar, o app confere tudo e recusa a execução com o arquivo e o campo culpados:

- `mainSession.json` existe e é JSON válido;
- toda sessão tem `name` e ao menos uma ação; toda ação tem `name` e `locate`;
- todo `call` aponta para um arquivo existente em `sessions/`;
- todo `locate` tem imagem correspondente em `templates/`;
- `searchArea` tem os quatro campos, `right > left`, `bottom > top` e cabe na tela;
- o template cabe na `searchArea` depois do escalonamento;
- `threshold` entre 0.0 e 1.0; tempos e `retries` não negativos.

Exemplo de recusa: `Carga inicial: NOK - menu.json: acao 'abrir loja': template 'loja' nao
encontrado em templates/`.

### 5.4 Desempenho

- Cada tentativa faz **uma** captura, compartilhada por todas as ações daquela tentativa.
- `searchArea` é o que mais economiza tempo: o casamento roda só sobre o recorte.
- `scales` com um único valor (padrão) é ~7x mais rápido que a lista completa do MVP 3.5.
- A busca para assim que encontra um escore ≥ 0.95.
- `Tempo captura`, `Tempo localizacao`, `Tempo acao` e `Tempo total` aparecem no log: use-os
  para ajustar `searchArea` e `scales`.

### Limitações que você vai encontrar (são reais, não bugs)

- **Trazer o jogo de volta**: nenhum app pode colocar outro app arbitrário em primeiro
  plano. O app só sai da frente (`moveTaskToBack`); se o jogo tiver sido descarregado da
  memória, quem aparece é a tela inicial e a execução falha com
  `Transicao NOK - app em primeiro plano`.
- **Custo**: cada ação custa de centenas de milissegundos a alguns segundos numa tela
  1080x2400, dependendo da `searchArea`.
- **Resolução**: o ideal é recortar no aparelho onde vai rodar; para reaproveitar recortes
  de outro aparelho, declare `screen` e/ou acrescente escalas.
- **Jogo em outra orientação** (paisagem vs. retrato) exige recortes próprios.

---

## 6. Problemas comuns

| Sintoma no log | Causa provável | O que fazer |
| --- | --- | --- |
| `Carga inicial: NOK - mainSession.json nao encontrado ou ilegivel` | arquivo ausente na pasta `sessions/` | envie o arquivo (seção 5.1) |
| `Carga inicial: NOK - <arquivo>: campo '...'` | erro de digitação no JSON | corrija o campo indicado e reenvie |
| `Carga inicial: NOK - ...: template 'x' nao encontrado em templates/` | nome/extensão diferente ou arquivo ausente | confira com `adb shell ls` (seção 4.5) |
| `Acao ...: nao localizada (melhor escore=0.4..., limite=0.80)` | recorte com área animada, tela errada, ou jogo em outra resolução | recorte menor e centrado no ícone estável; refaça a captura no próprio aparelho |
| `Transicao NOK - app em primeiro plano` | o jogo não voltou ao primeiro plano em 15 s | abra o jogo antes de tocar em Iniciar; evite que ele seja descarregado da memória |
| `Transicao NOK - captura falhou (codigo=...)` | jogo com bloqueio de captura (`FLAG_SECURE`) | nada a fazer no app se o conteúdo for protegido |
| `Captura de tela exige Android 11 (API 30)` | aparelho antigo | o app não funciona nesse aparelho |
| `Sessao x: nenhuma acao localizada em N tentativa(s) - encerrado` | a tela esperada não apareceu | aumente `retries`/`retryDelayMs` ou revise os templates da sessão |
| `Transicao NOK - gesto rejeitado` / `gesto cancelado` | o sistema recusou o toque (outro gesto em curso, serviço desligando) | tente de novo com o jogo em primeiro plano |
| Nada acontece e não há log | serviço desligado ou processo morto | reative o serviço; status deve estar **ATIVO** |
| `Transicao OK` mas o jogo não reage | o jogo ignora toques injetados ou o clique não caiu na área clicável | recentralize o recorte ou declare `clicks` explícitos; alguns jogos com anti-cheat descartam toques de acessibilidade |

---

## 7. Checklist rápido

- [ ] Android 11+, depuração USB ativa, `adb devices` reconhece o aparelho
- [ ] APK instalado e serviço **ATIVO**
- [ ] Espelhamento aberto (*Running Devices* ou `scrcpy`)
- [ ] Logcat filtrado por `tag:ClickService` (ou a caixa de log do app)
- [ ] `adb exec-out screencap -p > tela.png` com o jogo na tela desejada
- [ ] Recorte interno, centralizado, sem áreas animadas, PNG, 80–300 px
- [ ] Arquivo copiado para a pasta de templates (via `/data/local/tmp` ou Device Explorer)
- [ ] `mainSession.json` na pasta `sessions/` e `Carga inicial: OK` no log
- [ ] Cada template validado com escore ≥ 0.80 numa sessão de teste antes de montar o roteiro
