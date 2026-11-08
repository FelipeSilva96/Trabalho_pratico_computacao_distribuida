# Etapa 4 — Coordenador 2PC durável em Java (laboratório acadêmico)

**Base:** branch `features/segunda_entrega`, commit `23241d3`, que já contém os participantes 2PC Python nos dois workers. **Este ZIP inclui apenas mudanças no coordenador Java**, preservando a versão antiga do SLIC, a RPC Health e a interface de classificação. Não substitua os workers atuais.

## Que problema esta etapa resolve?

Até a Etapa 3, os comandos `prepare` e `commit` foram enviados manualmente. Agora o coordenador toma UMA decisão global e persiste essa decisão antes de enviá-la aos workers. A decisão COMMIT nunca é substituída por ABORT diante de um timeout posterior. O job periódico reenviará a decisão persistida aos participantes sem ACK, incluindo após reinicialização.

Cada transação usa um `transactionId` e um `resultId` imutáveis. Os dois workers são participantes fixos; **o 2PC não faz failover de PREPARE para outra réplica**. Para uma escrita estrita com duas réplicas, se qualquer uma falhar antes de votar, a decisão é ABORT. Se falhar após votação e decisão COMMIT, o resultado ficará `COMMIT_PENDING` até o retorno do participante.

### Endpoints novos

- `POST /api/replicas`: recebe **PNG já processado** em `multipart/form-data` (`file`) e executa 2PC.
- `POST /api/segment-transactional`: recebe imagem (`file`) e `segments`, `compactness`, `rows`, `cols`. Primeiro usa SLIC distribuído, depois replica o resultado via 2PC.
- `GET /api/transactions/{transactionId}`: retorna decisão, estado e ACKs registrados no journal.
- `POST /api/transactions/recover`: força uma rodada de recuperação; normalmente há rotina automática a cada 15 segundos.
- `GET /api/results/{resultId}`: recupera um PNG confirmado, conferindo SHA-256 e tentando outra réplica caso a primeira falhe. **Somente permite leitura pública depois de todos os ACKs de COMMIT**.

Estados: `PREPARING`, `COMMIT_PENDING`, `COMMITTED`, `ABORT_PENDING`, `ABORTED`.

## 0. Atenção ao que NÃO foi testado fora do ambiente do usuário

Foi conferida a estrutura real dos `.proto`, `WorkerClient`, `WorkerPool` e dos servidores Python da branch. **O journal Java foi compilado isoladamente e testado contra uma pasta persistente**, inclusive reabertura. A compilação completa do Spring Boot/gRPC e os testes JUnit **ainda precisam ser executados no seu Docker/Maven**; não trate a etapa como concluída antes desses testes e da integração ponta a ponta.

## 1. Backup e instalação, NO LUBUNTU

```bash
cd ~/Desktop/Trabalho_pratico_computacao_distribuida
git branch --show-current
# Deve ser: features/segunda_entrega
git status
# Guarde quaisquer alteracoes pendentes antes de extrair.
cp coordinator/src/main/java/br/pucminas/cd/waste/grpc/WorkerClient.java \
   /tmp/WorkerClient-antes-da-etapa4.java
unzip ~/Downloads/Etapa4_Coordenador_2PC_Duravel.zip -d .
git diff --stat
```

Este ZIP altera `WorkerClient.java` e adiciona os seguintes arquivos:

```text
coordinator/src/main/java/br/pucminas/cd/waste/replication/
  TransactionJournal.java
  ReplicationCoordinator.java
  ReplicationSchedulingConfig.java
coordinator/src/main/java/br/pucminas/cd/waste/controller/
  ReplicationController.java
coordinator/src/test/java/br/pucminas/cd/waste/replication/
  TransactionJournalTest.java
  ReplicationCoordinatorTest.java
```

## 2. TESTE JAVA ANTES DE ALTERAR CONTAINERS

**ATENÇÃO: O COMANDO ABAIXO PODE LEVAR VÁRIOS MINUTOS NA PRIMEIRA EXECUÇÃO, POIS O MAVEN PODE BAIXAR DEPENDÊNCIAS.**

A partir da raiz do repositório:

```bash
sudo docker run --rm \
  -v "$PWD/coordinator:/app" \
  -w /app \
  maven:3.9.9-eclipse-temurin-17 \
  mvn -B test
```

Esperamos `BUILD SUCCESS` e **os testes antigos mais seis testes novos** (3 do journal e 3 do coordenador). Se aparecer `BUILD FAILURE`, **NÃO prossiga** para reconstrução nem remova o coordenador atual. Envie o trecho de erro da compilação.

## 3. Reconstruir somente o coordenador (depois dos testes)

**ATENÇÃO: O BUILD ABAIXO PODE LEVAR VÁRIOS MINUTOS POR CAUSA DO MAVEN E DAS DEPENDÊNCIAS JAVA.**

```bash
sudo docker build -t cd-slic-coordinator ./coordinator
```

Confira os workers sem removê-los:

```bash
sudo docker ps
```

Verifique o volume (se ele nao existir, `docker volume create` apenas o criará):

```bash
sudo docker volume create slic-coordinator-state
```

Atualize apenas o container do coordenador (mantenha os workers e seus volumes):

```bash
sudo docker rm -f slic-coordinator-lan
sudo docker run -d \
  --name slic-coordinator-lan \
  --network cd-slic-lan \
  -p 127.0.0.1:8080:8080 \
  -e WORKER_ADDRESSES="slic-worker-lubuntu:50051,192.168.18.2:50051" \
  -e GRPC_DEADLINE_MS=15000 \
  -e REPLICATION_JOURNAL_DIR=/app/data/transactions \
  -v slic-coordinator-state:/app/data \
  cd-slic-coordinator
```

**NUNCA use `docker volume rm`, `docker compose down -v` nem crie outro coordenador apontando simultaneamente ao mesmo journal.** O journal usa um lock no filesystem para barrar uma segunda instância no mesmo diretório.

Verifique:

```bash
sudo docker logs --tail 60 slic-coordinator-lan
curl -sS http://localhost:8080/api/workers | python3 -m json.tool
```

Os dois workers devem continuar `UP`.

## 4. Testar 2PC automaticamente, sem executar SLIC de novo

O arquivo `resultado_duas_maquinas.png` que você já possui servirá como input. Ele tem ~4,6 MB, abaixo do limite de 14 MiB do endpoint transacional.

```bash
curl -fsS \
  -F "file=@resultado_duas_maquinas.png" \
  http://localhost:8080/api/replicas \
  | tee transacao_2pc.json | python3 -m json.tool
```

O JSON deverá incluir `transactionId`, `resultId`, `sha256`, `status`, `decision`, `acknowledgedCount`, `replicaCount`, `participants`, `acknowledged`. O esperado quando ambos estão saudáveis: `"decision":"COMMIT"`, `"status":"COMMITTED"`, `"replicaCount":2`, `"acknowledgedCount":2`.

Extraia os IDs sem precisar copiá-los manualmente:

```bash
TX=$(python3 -c 'import json; print(json.load(open("transacao_2pc.json"))["transactionId"])')
RESULT=$(python3 -c 'import json; print(json.load(open("transacao_2pc.json"))["resultId"])')

echo "TX=$TX"
echo "RESULT=$RESULT"
curl -fsS "http://localhost:8080/api/transactions/$TX" | python3 -m json.tool
curl -fS "http://localhost:8080/api/results/$RESULT" \
  --output resultado_recuperado_2pc.png
sha256sum resultado_duas_maquinas.png resultado_recuperado_2pc.png
cmp resultado_duas_maquinas.png resultado_recuperado_2pc.png && echo "RESULTADOS IDENTICOS"
```

Os hashes devem ser idênticos. Nos logs de **ambos os workers**, procure `PREPARE`, `COMMIT` com o mesmo `TX`:

```bash
sudo docker logs --tail 60 slic-worker-lubuntu
sudo docker logs --tail 100 slic-coordinator-lan
```

No Windows:

```powershell
docker logs --tail 60 slic-worker-windows
```

## 5. Testar segmentacao + replicacao automaticas

```bash
curl -fsS \
  -F "file=@imagem_teste_grande.jpg" \
  -F "segments=500" \
  -F "compactness=10.0" \
  -F "rows=2" \
  -F "cols=2" \
  http://localhost:8080/api/segment-transactional \
  | tee segmentacao_2pc.json | python3 -m json.tool
```

O JSON devolverá `segmentationRequestId`, `segmentationMs`, `tiles`, `workers` e a estrutura `transaction`.

Extraia o `resultId`:

```bash
RESULT=$(python3 -c 'import json; print(json.load(open("segmentacao_2pc.json"))["transaction"]["resultId"])')
curl -fS "http://localhost:8080/api/results/$RESULT" --output resultado_segmentacao_2pc.png
xdg-open resultado_segmentacao_2pc.png
```

Esse é o fluxo completo da segunda entrega: imagem -> SLIC em 2 computadores -> montagem -> PREPARE -> decisao duravel -> COMMIT -> consulta.

## 6. Testes de falha controlados

### 6.1. Leitura sobrevivendo a uma replica fora do ar

**Execute somente depois de confirmar uma transacao COMMITTED pelo coordenador.** No Windows:

```powershell
docker stop slic-worker-windows
```

No Lubuntu, use o `RESULT` da transacao confirmada:

```bash
curl -fS "http://localhost:8080/api/results/$RESULT" --output leitura_sem_windows.png
sha256sum resultado_duas_maquinas.png leitura_sem_windows.png
```

Se a transacao correspondeu a `resultado_duas_maquinas.png`, os hashes devem ser iguais; a leitura virá da replica Lubuntu. Restaure no Windows:

```powershell
docker start slic-worker-windows
```

### 6.2. ABORT quando o segundo worker nao responde ao PREPARE

Pare o worker Windows, depois tente um novo `POST /api/replicas` no Lubuntu. O coordenador deve registrar `ABORT`, possivelmente `ABORT_PENDING` enquanto o Windows estiver inacessivel. **O protocolo nao aceita uma gravacao em apenas uma das duas replicas como COMMIT.**

Depois que o Windows retornar, aguarde o job de recuperacao ou execute:

```bash
curl -fsS -X POST http://localhost:8080/api/transactions/recover | python3 -m json.tool
```

Consulte o estado da nova transacao e verifique `ABORTED`. O endpoint `GET /api/results/{resultId}` dessa transacao deve recusar a publicacao.

### 6.3. Durabilidade da decisao do coordenador

Crie uma transacao concluida e salve o `TX`. Reinicie apenas o container do coordenador:

```bash
sudo docker restart slic-coordinator-lan
curl -sS "http://localhost:8080/api/transactions/$TX" | python3 -m json.tool
```

A decisao, ACKs e identificadores devem permanecer registrados. Isso exercita o journal no volume.

### 6.4. Cenarios de janela critica

Quedas especificamente **apos PREPARE e antes da decisao, ou depois do registro COMMIT mas antes dos ACKs**, exigem injeção de falhas controlada para validar de ponta a ponta. A implementacao fornece a logica de recuperacao, e os testes unitarios simulam perda do ACK de COMMIT; a prova de crash-recovery entre processos sera uma etapa seguinte. Nao alegue que todos os interleavings de falha foram testados sem executar esses cenarios.

## 7. Preservar commits e enviar ao GitHub

Depois de `mvn test`, build e integracao serem confirmados:

```bash
git status
git add coordinator/src/main/java/br/pucminas/cd/waste/grpc/WorkerClient.java \
 coordinator/src/main/java/br/pucminas/cd/waste/replication/ \
 coordinator/src/main/java/br/pucminas/cd/waste/controller/ReplicationController.java \
 coordinator/src/test/java/br/pucminas/cd/waste/replication/ \
 docs/ETAPA_4_COORDENADOR_2PC.md
git diff --cached --stat
git commit -m "Adiciona coordenador 2PC duravel e recuperacao de transacoes"
git push origin features/segunda_entrega
```

Nao rode `git add .` sem revisar: os PNGs e arquivos de resposta podem ser grandes.

## 8. Limitacoes de projeto (documentar no relatorio)

- Um unico coordenador com arquivo de journal local: ponto unico de falha. Nao e eleicao de lider, Paxos, Raft ou consenso.
- Two-Phase Commit e bloqueante: se participante PREPARED nao receber decisao, aguarda. O coordinator so publica depois dos dois ACKs.
- Enderecos dos participantes sao fixados no journal. Nao altere `WORKER_ADDRESSES` enquanto houver transacoes pendentes sem manter compatibilidade/roteamento.
- A regra e **todas as replicas devem confirmar**. Se uma ficar fora antes de PREPARE, novas escritas abortam, mesmo que a outra possa continuar processando imagens.
- O acesso HTTP deve ficar em localhost. O gRPC continua sem TLS/autenticacao; manter a rede privada e o firewall restrito ao notebook.
- O limite do endpoint de replicacao e 14 MiB para manter margem abaixo dos 16 MiB gRPC. Nao e streaming. Imagens maiores requerem transferencia em chunks.
- Recuperacao de falhas de energia depende das garantias de `fsync`/atomic rename de um filesystem local compatível com o volume Docker. Nao e equivalente a garantia irrestrita contra qualquer falha de hardware.
- `ReadReplica` nos workers e RPC interna, acessivel a quem pode acessar a porta gRPC; a politica de publicacao esta no endpoint HTTP do coordenador, nao no worker.
- Nao confundir replicacao de resultados com aceleração do SLIC. Benchmarks comparativos serao feitos em seguida.
