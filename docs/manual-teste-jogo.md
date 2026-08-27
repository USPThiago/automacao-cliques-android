# Manual: testando a automação num jogo real (Android Studio + espelhamento)

Guia prático para rodar o app num aparelho físico, ver a tela do jogo espelhada no
computador e — principalmente — **extrair os recortes de imagem** que o app usa para
reconhecer as telas e clicar por coordenadas.

Vale para o MVP 3.5: reconhecimento visual por *template matching*.

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
  qualquer momento (é o que dá poder à automação). O app só captura quando você aciona
  "Reconhecer tela" ou um passo `@template`, mas a permissão é essa.
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

> Em API 24–29 o app continua funcionando para cliques por texto/id (MVP 3), mas todo
> passo visual falha com `Captura de tela exige Android 11 (API 30)` no log.

---

## 2. Instalar o app

### Opção A — pelo Android Studio (recomendada para testar/alterar código)

1. `File > Open` e abra a pasta do projeto (`automacao-cliques-android`).
2. Espere o *Gradle sync*.
3. Selecione o aparelho no seletor de dispositivos (barra superior).
4. `Run > Run 'app'` (Shift+F10).

### Opção B — APK pronto

```bash
adb install -r automacao-cliques-mvp35-debug.apk
```

Se o Android 13+ bloquear o acesso à acessibilidade por ser instalação externa:
`Configurações > Apps > Automação de Cliques > menu (⋮) > Permitir configurações restritas`.

## 3. Ativar o serviço e ver o espelhamento

1. Abra o app e toque em **Abrir configurações de acessibilidade**.
2. `Apps instalados > Automação de Cliques > ativar o botão > Permitir`.
3. Volte ao app: o status deve ficar **ATIVO** (o app relê o estado ao voltar à tela).
4. **Atenção**: 6 segundos após cada ativação o serviço dispara um clique de teste no
   centro da tela. É esperado (MVP 2) — não ative com uma tela sensível aberta.

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
der**, em várias escalas, e clica no **centro do recorte encontrado**. Então a qualidade do
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

**Com o script do repositório** (evita erro de redimensionamento acidental) —
`tools/recortar_template.py`, precisa de `pillow` (`pip install pillow`):

```bash
# recorte por retângulo: --caixa ESQUERDA TOPO DIREITA BAIXO
python tools/recortar_template.py tela_loja.png loja.png --caixa 716 706 885 875

# ou: centro + tamanho (mais natural quando você sabe onde quer clicar)
python tools/recortar_template.py tela_loja.png loja.png --centro 800 790 --tamanho 170
```

O script imprime o retângulo e o centro do recorte — o centro é exatamente onde o
`dispatchGesture` vai tocar quando esse template casar.

Para descobrir as coordenadas: a maioria dos editores mostra a posição do cursor em pixels;
ou ative `Configurações > Sistema > Opções do desenvolvedor > Local do ponteiro`, toque no
botão do jogo e leia o X/Y na barra superior.

### 4.4 Nomes dos arquivos

- minúsculas, sem espaços e sem acentos: `loja.png`, `fechar_x.png`, `melhorar_titas.png`;
- o nome **sem extensão** é o que você usa na sequência, com `@`: `loja.png` → `@loja`;
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

1. Deixe **o jogo** na tela correspondente ao template.
2. Rode no computador, com o logcat aberto: toque em **Reconhecer tela** no app...
   — mas atenção: para tocar no app você precisa sair do jogo. Use este truque, que
   agenda a volta ao jogo **dentro do aparelho**:

   ```bash
   adb logcat -c
   adb shell "nohup sh -c 'sleep 6; monkey -p COLOQUE.O.PACOTE.DO.JOGO -c android.intent.category.LAUNCHER 1' >/dev/null 2>&1 &"
   ```

   (descubra o pacote do jogo com `adb shell dumpsys window | grep -m1 mCurrentFocus`
   enquanto ele está aberto)
3. No app, digite `@loja` no campo de termos e toque em **Executar sequência (6s)**; volte
   ao jogo imediatamente (ou deixe o comando acima fazer isso).
4. Leia o log:

```text
Template 'loja' encontrado: score=0.978 escala=1.00 centro=(812.0, 1466.5) regiao=[727,1381][897,1552]
Despachando clique em x=812.0 y=1466.5
Gesto concluido em x=812.0 y=1466.5
```

Interpretação dos escores (vão de -1 a 1; o limite para clicar é **0.80**):

| Escore | Leitura |
| --- | --- |
| 0.95 – 1.00 | recorte ótimo |
| 0.80 – 0.95 | funciona; fundo com alguma variação |
| < 0.80 | **não clica** (`abaixo do limite`) — recorte pegou área animada, tela errada, ou o jogo mudou de resolução |

O botão **Reconhecer tela (templates)** compara *todos* os templates da pasta com a tela
atual e lista os escores em ordem — use-o para descobrir em qual variante de tela o jogo
está. Ele só faz sentido acionado com a tela alvo em primeiro plano, pelo mesmo truque do
passo 2.

---

## 5. Rodando uma sequência no jogo

No campo de termos, separe os passos por vírgula. Você pode misturar:

| Termo | O que faz |
| --- | --- |
| `@loja` | procura o template `loja` e clica no centro dele |
| `Iniciar` | procura um nó de acessibilidade com esse texto/descrição/id e clica (só funciona em telas que expõem nós — normalmente **não** em jogos) |

Exemplo: `@loja, @comprar, @fechar_x`.

Toque em **Executar sequência (6s)** e traga o jogo para a frente. Cada passo espera 1 s
antes de executar, e cada passo visual leva mais alguns segundos de processamento.

### Limitações que você vai encontrar (são reais, não bugs)

- **Janela de 6 s**: se o jogo não estiver em primeiro plano quando o passo visual rodar,
  o passo é **ignorado** de propósito, com o log
  `Template 'loja' ignorado: o proprio app esta em primeiro plano`. Isso existe para não
  clicar na interface do próprio app de automação. Em jogos pesados, que demoram para
  voltar do segundo plano, 6 s pode não ser suficiente — o delay configurável está previsto
  para o MVP 4.
- **Custo**: cada template custa alguns segundos numa tela 1080x2400. Sequências longas de
  passos visuais são lentas por natureza.
- **Resolução**: o template é testado de 0.66x a 1.5x, então um recorte feito no seu
  aparelho tende a funcionar em outro com resolução diferente — mas o ideal é recortar no
  aparelho onde vai rodar.
- **Jogo em outra orientação** (paisagem vs. retrato) exige recortes próprios.

---

## 6. Problemas comuns

| Sintoma no log | Causa provável | O que fazer |
| --- | --- | --- |
| `Template 'x' nao encontrado em ...` | arquivo não está na pasta, nome/extensão diferente | confira com `adb shell ls` (seção 4.5); nome sem acento/espaço |
| `Template 'x' abaixo do limite: score=0.4...` | recorte com área animada, tela errada, ou jogo em outra resolução | recorte menor e centrado no ícone estável; refaça a captura no próprio aparelho |
| `Template 'x' ignorado: o proprio app esta em primeiro plano` | o jogo não subiu dentro dos 6 s | agende a troca dentro do aparelho (seção 4.6, passo 2) |
| `Captura de tela exige Android 11 (API 30)` | aparelho antigo | passos visuais não são suportados nesse aparelho |
| `Falha na captura de tela (codigo=...)` | jogo com bloqueio de captura (`FLAG_SECURE`) ou app em segundo plano | nada a fazer no app se o conteúdo for protegido |
| `Nenhum template em /storage/...` | pasta vazia | envie os recortes (seção 4.5) |
| Nada acontece e não há log | serviço desligado ou processo morto | reative o serviço; status deve estar **ATIVO** |
| `Gesto concluido` mas o jogo não reage | o jogo ignora toques injetados ou o centro do recorte não está sobre a área clicável | recentralize o recorte no botão; alguns jogos com anti-cheat descartam toques de acessibilidade |

---

## 7. Checklist rápido

- [ ] Android 11+, depuração USB ativa, `adb devices` reconhece o aparelho
- [ ] APK instalado e serviço **ATIVO**
- [ ] Espelhamento aberto (*Running Devices* ou `scrcpy`)
- [ ] Logcat filtrado por `tag:ClickService`
- [ ] `adb exec-out screencap -p > tela.png` com o jogo na tela desejada
- [ ] Recorte interno, centralizado, sem áreas animadas, PNG, 80–300 px
- [ ] Arquivo copiado para a pasta de templates (via `/data/local/tmp` ou Device Explorer)
- [ ] `@nome` validado com escore ≥ 0.80 antes de montar a sequência
