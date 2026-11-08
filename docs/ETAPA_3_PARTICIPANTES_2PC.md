# Etapa 3 — Participantes persistentes do Two-Phase Commit (laboratório)

**Estado:** preparação do lado dos workers. **Ainda não há coordenador 2PC Java, decisão global durável ou controle automatizado de réplicas.** Não utilizar as operações abaixo como um 2PC de produção. Use apenas em rede local de confiança: as chamadas usam gRPC sem TLS/autenticação.

## O que muda

- `worker/transaction_participant.py`: máquina de estados persistente `PREPARED / COMMITTED / ABORTED` por transação.
- `worker/app.py`: mantém `Classify`, `Segment`, `Health`; acrescenta RPC `PrepareReplica`, `CommitReplica`, `AbortReplica`, `ReplicaStatus`, `ReadReplica`.
- `worker/replica_rpc_cli.py`: cliente manual de teste de RPC; **não** decide automaticamente uma transação entre workers.
- `worker/Dockerfile`: copia os novos módulos.
- `worker/proto/classifier.proto` e `coordinator/src/main/proto/classifier.proto`: contratos idênticos (não alterar números dos campos antigos).
- `worker/tests/test_transaction_participant.py`: 11 casos novos; mais 8 casos anteriores = 19 testes.
- `worker/replica_store.py` e `worker/tests/test_replica_store.py`: iguais aos arquivos entregues na Etapa 2; apenas para garantir que o pacote seja completo.

## Instalação (Lubuntu)

```bash
cd ~/Desktop/Trabalho_pratico_computacao_distribuida
git branch --show-current # DEVE ser features/segunda_entrega
git status
unzip ~/Downloads/Etapa3_Participantes_2PC_gRPC.zip -d .
# Validar primeiro os testes sem rebuild, montando o codigo alterado:
sudo docker run --rm -v "$PWD/worker:/src:ro" -w /src cd-slic-worker python -m unittest discover -s tests -v
```

**ATENÇÃO: O PRÓXIMO COMANDO PODE DEMORAR VÁRIOS MINUTOS NA PRIMEIRA CONSTRUÇÃO OU SE AS DEPENDÊNCIAS PRECISAREM SER BAIXADAS.**

```bash
sudo docker build -t cd-slic-worker ./worker
```

Recrie apenas o worker do Lubuntu, preservando volume:

```bash
sudo docker rm -f slic-worker-lubuntu
sudo docker run -d --name slic-worker-lubuntu --network cd-slic-lan \
  -e WORKER_ID=worker-lubuntu -e GRPC_PORT=50051 -e GRPC_THREADS=4 \
  -e REPLICA_DIR=/app/data -v slic-replica-lubuntu:/app/data cd-slic-worker
sudo docker logs --tail 20 slic-worker-lubuntu
curl -sS http://localhost:8080/api/workers | python3 -m json.tool
```

Não reinicie o coordenador ainda. A RPC `Segment` antiga continua compatível; o coordenador Java existente não chama operações transacionais.

## Enviar para o GitHub e atualizar o Windows

```bash
git add worker/app.py worker/Dockerfile worker/replica_store.py worker/transaction_participant.py worker/replica_rpc_cli.py \
  worker/proto/classifier.proto worker/tests/test_replica_store.py worker/tests/test_transaction_participant.py \
  coordinator/src/main/proto/classifier.proto docs/ETAPA_3_PARTICIPANTES_2PC.md
git diff --cached --stat
git commit -m "Adiciona participantes persistentes 2PC e RPCs de replicas"
git push -u origin features/segunda_entrega
```

No **Windows PowerShell**, dentro do repositório:

```powershell
git fetch origin
# Se a branch nao existe localmente:
git switch --track origin/features/segunda_entrega
# Se ja existir localmente: git switch features/segunda_entrega; git pull --ff-only origin features/segunda_entrega
```

**ATENÇÃO: O PRÓXIMO BUILD PODE DEMORAR VÁRIOS MINUTOS.**

```powershell
docker build -t cd-slic-worker .\worker
docker rm -f slic-worker-windows
docker run -d --name slic-worker-windows -p 50051:50051 -e WORKER_ID=worker-windows -e GRPC_PORT=50051 -e GRPC_THREADS=4 -e REPLICA_DIR=/app/data -v slic-replica-windows:/app/data cd-slic-worker
docker logs --tail 30 slic-worker-windows
```

**Não execute `docker volume rm` nem `docker compose down -v`.** As réplicas persistem nos volumes existentes.

## Teste remoto de PREPARE / COMMIT / leitura (Lubuntu)

A partir do notebook, copie o PNG para o container cliente local:

```bash
sudo docker cp resultado_duas_maquinas.png slic-worker-lubuntu:/tmp/resultado.png
```

PREPARE em ambos os workers (mesma transação e mesmo arquivo; cada um tem disco próprio):

```bash
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py prepare --worker slic-worker-lubuntu:50051 --tx demo2pc-tx-01 --result demo2pc-res-01 --file /tmp/resultado.png
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py prepare --worker 192.168.18.2:50051 --tx demo2pc-tx-01 --result demo2pc-res-01 --file /tmp/resultado.png
```

Consulta de estado deve retornar `PREPARED` em ambos:

```bash
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py status --worker slic-worker-lubuntu:50051 --tx demo2pc-tx-01
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py status --worker 192.168.18.2:50051 --tx demo2pc-tx-01
```

Uma leitura `read` antes do COMMIT deve retornar `NOT_FOUND` — isso é esperado. O dado ainda não é visível.

**Para este experimento manual, confirme em AMBOS os workers (não é coordenador 2PC durável):**

```bash
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py commit --worker slic-worker-lubuntu:50051 --tx demo2pc-tx-01
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py commit --worker 192.168.18.2:50051 --tx demo2pc-tx-01
```

Depois recupere e compare:

```bash
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py read --worker slic-worker-lubuntu:50051 --result demo2pc-res-01 --out /tmp/replica_lubuntu.png
sudo docker exec slic-worker-lubuntu python /app/replica_rpc_cli.py read --worker 192.168.18.2:50051 --result demo2pc-res-01 --out /tmp/replica_windows.png
sudo docker cp slic-worker-lubuntu:/tmp/replica_lubuntu.png ./replica_lubuntu.png
sudo docker cp slic-worker-lubuntu:/tmp/replica_windows.png ./replica_windows.png
sha256sum resultado_duas_maquinas.png replica_lubuntu.png replica_windows.png
cmp replica_lubuntu.png replica_windows.png && echo 'REPLICAS IDENTICAS'
```

Teste de durabilidade: reinicie `slic-worker-windows` (`docker restart`) no Windows e repita as operações `status` e `read` a partir do notebook. Deve continuar `COMMITTED`.

## Teste de ABORT (usar outro ID)

Repetir PREPARE com `--tx demo2pc-tx-abort --result demo2pc-res-abort`, em **ambos** os workers. Em vez de `commit`, enviar `abort` para ambos. O `status` deverá retornar `ABORTED` e `read` deverá retornar `NOT_FOUND`.

## Limitações técnicas explicitamente assumidas

1. Os workers **não elegem a decisão global**: recebem PREPARE/COMMIT/ABORT. O coordenador Java durável virá na Etapa 4.
2. Um participante que reinicia em `PREPARED` **não aborta automaticamente**; aguarda decisão. Isso modela o bloqueio do 2PC.
3. O protocolo é idempotente para retries de COMMIT/ABORT. Uma falha durante COMMIT pode ser recuperada por um novo COMMIT.
4. O catálogo do coordenador será responsável por publicar resultados somente depois das confirmações exigidas; `ReadReplica` é RPC interna para testes e não deve ser exposta como API pública sem esse controle.
5. O gRPC ainda usa conexão sem autenticação/TLS. Manter o acesso à porta 50051 restrito ao IP do notebook no firewall privado do Windows.
6. `ReplicaStore` permite PNG de até 16 MiB, mas mensagens Protobuf têm overhead. Para o experimento, use o PNG já validado (~4,4 MiB).
7. Para validar atomicidade entre ambos os workers, ainda serão necessários testes com decisão global persistida e recuperação do coordenador.
