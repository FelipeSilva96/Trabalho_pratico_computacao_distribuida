# Etapa 5 — Falha controlada durante COMMIT e recuperacao de COMMIT_PENDING

## Objetivo e limites

Simular a **queda real do processo worker do Windows** ao receber a primeira RPC `CommitReplica`, quando o coordenador ja registrou de maneira duravel a decisao global `COMMIT`.
O crash acontece **ANTES** da chamada local `participant.commit()`. Assim, o worker continua `PREPARED`, o coordenador registra `COMMIT_PENDING` com apenas um ACK, e a retomada do worker permite reenviar COMMIT e finalizar. A injecao e **desativada por padrao**, so atua quando `WORKER_ID=worker-windows`, `INJECT_2PC_COMMIT_CRASH_ONCE=1` e `INJECT_2PC_FAULT_TOKEN` valido. No Lubuntu nao e preciso reconstruir a imagem do worker.

Nao e um teste de falha de energia ou queda fisica da rede, e sim terminacao abrupta do servidor Python no Windows. Nao prova todas as janelas de falha do 2PC. O marcador de uso unico e armazenado no volume persistente para que a reinicializacao nao provoque um loop de crashes.

## 1. Lubuntu: instalar e validar

```bash
cd ~/Desktop/Trabalho_pratico_computacao_distribuida
git branch --show-current # features/segunda_entrega
git status
unzip ~/Downloads/Etapa5_Falha_Controlada_COMMIT_PENDING.zip -d .
# Digite y quando solicitar substituir app.py e Dockerfile.
sudo docker run --rm -v "$PWD/worker:/src:ro" -w /src cd-slic-worker python -m unittest discover -s tests -v
# Esperado: Ran 24 tests; OK (19 anteriores + 5 novos)
git add worker/app.py worker/Dockerfile worker/fault_injection.py worker/tests/test_fault_injection.py docs/ETAPA_5_FALHA_COMMIT_PENDENTE.md
git diff --cached --stat
git commit -m "Adiciona falha controlada de uso unico para testar COMMIT_PENDING"
git push origin features/segunda_entrega
```

**ATENCAO: NAO USE `git add .` SEM REVISAR. O PROJETO TEM ARQUIVOS GERADOS EM coordinator/target E PNGs DE EXPERIMENTOS.**

## 2. Windows: atualizar a branch e reconstruir worker

No PowerShell do Windows, na pasta do repositório:

```powershell
git status
git pull --ff-only origin features/segunda_entrega
Test-Path workerault_injection.py
```

**ATENCAO: `docker build` PODE DEMORAR VARIOS MINUTOS E INSTALAR DEPENDENCIAS.**

```powershell
docker build -t cd-slic-worker .\worker
docker rm -f slic-worker-windows
docker run -d --name slic-worker-windows -p 50051:50051 -e WORKER_ID=worker-windows -e GRPC_PORT=50051 -e GRPC_THREADS=4 -e REPLICA_DIR=/app/data -e INJECT_2PC_COMMIT_CRASH_ONCE=1 -e INJECT_2PC_FAULT_TOKEN=demo-commit-20261108-01 -v slic-replica-windows:/app/data cd-slic-worker
docker logs --tail 20 slic-worker-windows
```

Manter coordenador Java e worker Lubuntu originais. Nao apagar nenhum volume. Escolha um TOKEN novo em caso de repeticao (o anterior ja tem marcador no volume).

## 3. Lubuntu: provocar COMMIT_PENDING

Confira os dois workers UP antes:

```bash
curl -sS http://localhost:8080/api/workers | python3 -m json.tool
```

**ATENCAO: A REQUISICAO PODE LEVAR 15 SEGUNDOS OU MAIS DEVIDO A QUEDA ABRUPTA E AO TIMEOUT GRPC.**

```bash
curl -fsS -F "file=@resultado_duas_maquinas.png"   http://localhost:8080/api/replicas   | tee transacao_commit_pendente.json | python3 -m json.tool
```

Esperado: `decision=COMMIT`, `status=COMMIT_PENDING`, `replicaCount=2`, `acknowledgedCount=1` (Lubuntu). Se houve `COMMITTED` direto ou `ABORT`, interrompa este teste e analise os logs antes de prosseguir.

```bash
TX_PENDING=$(python3 -c 'import json; print(json.load(open("transacao_commit_pendente.json"))["transactionId"])')
RESULT_PENDING=$(python3 -c 'import json; print(json.load(open("transacao_commit_pendente.json"))["resultId"])')
curl -fsS "http://localhost:8080/api/transactions/$TX_PENDING" | python3 -m json.tool
```

No PowerShell do Windows:

```powershell
docker ps -a --filter name=slic-worker-windows
docker logs --tail 20 slic-worker-windows
```

Esperado: `Exited (77)` e `[FAULT-2PC] CRASH_ON_COMMIT_ONCE ...`.

## 4. Windows: reativar worker

```powershell
docker start slic-worker-windows
docker logs --tail 20 slic-worker-windows
```

O marcador persistente evita repetir a falha. No Lubuntu, confirme os workers UP e force recovery:

```bash
curl -sS http://localhost:8080/api/workers | python3 -m json.tool
curl -fsS -X POST http://localhost:8080/api/transactions/recover | python3 -m json.tool
curl -fsS "http://localhost:8080/api/transactions/$TX_PENDING" | python3 -m json.tool
```

Esperado `decision=COMMIT`, `status=COMMITTED`, `acknowledgedCount=2`. A rotina automatica pode agir antes da chamada manual.

Recuperacao e integridade:

```bash
curl -fS "http://localhost:8080/api/results/$RESULT_PENDING"   --output resultado_recuperado_commit_pendente.png
sha256sum resultado_duas_maquinas.png resultado_recuperado_commit_pendente.png
cmp resultado_duas_maquinas.png resultado_recuperado_commit_pendente.png && echo 'RECUPERACAO IDENTICA'
```

## 5. Desativar falha e voltar ao worker normal

No Windows, depois de finalizar o teste:

```powershell
docker rm -f slic-worker-windows
docker run -d --name slic-worker-windows -p 50051:50051 -e WORKER_ID=worker-windows -e GRPC_PORT=50051 -e GRPC_THREADS=4 -e REPLICA_DIR=/app/data -v slic-replica-windows:/app/data cd-slic-worker
```

**NUNCA apague `slic-replica-windows` nem `slic-coordinator-state`.** A imagem com injecao pode continuar sendo usada normalmente sem as variaveis de ambiente especiais.
