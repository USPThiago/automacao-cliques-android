---
name: regras-de-execucao
description: Regras obrigatórias de execução para qualquer tarefa neste repositório — proibição de testes end-to-end em VM/emulador, respeito ao limite diário de consumo e validação padrão por build + testes unitários. Leia antes de começar qualquer trabalho.
---

# Regras de execução do projeto

Estas regras valem para toda tarefa neste repositório e têm precedência sobre qualquer outra skill
(inclusive `testing-android-emulator`).

## 1. Proibido testes end-to-end em VM/emulador
- **Não** instalar, criar ou iniciar emuladores Android (AVD), nem baixar imagens de sistema
  (`system-images;...`) ou o pacote `emulator`.
- **Não** executar testes end-to-end, gravações de tela ou validação visual da UI na VM.
- **Não** acionar o agente de testes (testing agent) nem oferecer ao usuário testes end-to-end/gravações.
- Comandos que dependem de um dispositivo (`adb install`, `./gradlew installDebug`,
  `connectedAndroidTest`, `adb shell ...`) **não** devem ser executados.
- Quando uma mudança exigir validação em dispositivo, descreva no PR/mensagem os passos para o
  usuário executar manualmente no próprio aparelho (a skill `testing-android-emulator` pode servir de
  referência para esses passos).

## 2. Respeitar o limite diário de consumo
- Trabalhe de forma objetiva: faça apenas o necessário para concluir a tarefa pedida.
- Evite builds, downloads e execuções repetidas ou desnecessárias; rode novamente só o que foi afetado.
- Não inicie tarefas longas ou paralelas (sessões filhas, workflows, varreduras amplas) sem pedido
  explícito do usuário.
- Se a tarefa ameaçar ultrapassar o limite diário de consumo, pare, informe o usuário o estado atual e
  aguarde orientação antes de continuar.

## 3. Validação padrão: build + testes unitários
A validação padrão de qualquer mudança de código é:
```bash
./gradlew assembleDebug   # build
./gradlew test            # testes unitários (app/src/test/java/)
```
- Ambos devem passar antes de commitar/abrir PR.
- Mudanças apenas em documentação (`docs/`, `README.md`, skills) não exigem build nem testes.
- Ao relatar o resultado, informe que a validação foi feita por build + testes unitários e liste o que
  ficou sem validação em dispositivo (para o usuário testar manualmente).
