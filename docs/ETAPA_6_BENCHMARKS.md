# Etapa 6 — Medições de desempenho SLIC distribuído e custo do 2PC

## O que será medido

Com a **mesma imagem, 500 segmentos, compactness=10 e 2×2 tiles**:

1. **um_worker_sem_2pc** — um coordenador e um worker, ambos no notebook Lubuntu, endpoint `/api/segment-distributed`, porta `8081`.
2. **dois_workers_sem_2pc** — um coordenador no Lubuntu e workers Lubuntu + Windows, endpoint `/api/segment-distributed`, porta `8080`.
3. **dois_workers_com_2pc** — a mesma segmentação nos dois workers mais replicação persistente nos dois computadores via `/api/segment-transactional`, porta `8080`. Inclui uma chamada subsequente de leitura `/api/results/{id}` para que o cliente também receba o PNG, como nas outras duas condições.

O script mede `perf_counter()` do cliente, grava também o tempo de segmentação informado pelo servidor e testa integridade do arquivo via SHA-256. A ordem das condições é embaralhada com seed fixo a cada rodada. **As requisições são sequenciais** (nenhuma carga externa simultânea); dentro de cada requisição, os tiles continuam sendo executados concorrentemente pelo coordenador.

### 1. Pré-requisito: dois workers UP

No **Lubuntu**:

```bash
cd ~/Desktop/Trabalho_pratico_computacao_distribuida
curl -fsS http://127.0.0.1:8080/api/workers | python3 -m json.tool
```

Esperado: `worker-lubuntu` e `worker-windows`, ambos `UP`. No Windows, o worker atual deve continuar **sem variáveis INJECT_2PC**.

### 2. Criar coordenador de comparação com um único worker

O coordenador original `slic-coordinator-lan` na porta 8080 permanecerá intacto. Vamos iniciar uma **segunda instância, separada, na porta local 8081**, conectada apenas ao worker Lubuntu. **O journal usa outro volume** — jamais compartilhe `slic-coordinator-state`, porque existe um lock exclusivo.

```bash
sudo docker run -d \
  --name slic-coordinator-single-benchmark \
  --network cd-slic-lan \
  -p 127.0.0.1:8081:8080 \
  -e WORKER_ADDRESSES='slic-worker-lubuntu:50051' \
  -e GRPC_DEADLINE_MS=15000 \
  -e REPLICATION_JOURNAL_DIR=/app/data/transactions \
  -v slic-benchmark-single-state:/app/data \
  cd-slic-coordinator
```

Confira:

```bash
sudo docker ps --filter name=slic-coordinator-single-benchmark
curl -fsS http://127.0.0.1:8081/api/workers | python3 -m json.tool
```

Esperado: apenas `worker-lubuntu` como `UP`. Se você já tiver criado esse container numa tentativa anterior, **não execute `docker run` outra vez**: use `sudo docker start slic-coordinator-single-benchmark`.

**Importante:** a instância de um worker é usada **somente** com `/api/segment-distributed`. Não pode fazer 2PC com uma única réplica.

### 3. Extrair o pacote

No Lubuntu, na raiz do projeto:

```bash
unzip ~/Downloads/Etapa6_Benchmarks_SLIC_2PC.zip -d .
python3 benchmarks/benchmark_slic.py --help
```

### 4. Executar as medições

**ATENÇÃO: O COMANDO A SEGUIR PODERÁ DEMORAR VÁRIOS MINUTOS. ELE EXECUTARÁ 36 SEGMENTAÇÕES FULL HD (6 AQUECIMENTOS + 30 AMOSTRAS), COM 12 TRANSAÇÕES 2PC E 12 LEITURAS ADICIONAIS. NÃO DESLIGUE OS COMPUTADORES DURANTE O TESTE.**

```bash
python3 benchmarks/benchmark_slic.py \
  --input imagem_teste_grande.jpg \
  --single-url http://127.0.0.1:8081 \
  --dual-url http://127.0.0.1:8080 \
  --segments 500 \
  --compactness 10.0 \
  --rows 2 --cols 2 \
  --warmups 2 --repeats 10 \
  --seed 42 \
  --expected-sha256 870f39d917ae03fae238b882fc957b7ed85c5b944d9abc8c252b982dda2b9f62
```

Os arquivos são escritos em `benchmarks/resultados/amostras.csv` e `benchmarks/resultados/resumo.json`. O CSV é atualizado **a cada amostra**, logo há registro parcial mesmo se o processo for interrompido. Não execute builds, jogos ou tarefas computacionais pesadas em nenhum dos dois computadores durante as medições; mantenha o PC na mesma rede. Sempre confira se o Windows continua `192.168.18.2`.

### 5. Como interpretar corretamente

- `client_end_to_end` mede **upload da entrada + execução + resposta + download da imagem final**. No modo 2PC, inclui a requisição de segmentação transacional, os dois commits e a **leitura adicional** do resultado. Essa métrica é uma comparação de entregas equivalentes do ponto de vista do usuário, com as ressalvas de um fluxo HTTP diferente.
- `client_request` no modo 2PC para no retorno do JSON da transação; nos modos sem 2PC inclui o PNG na resposta. **Não compare essas duas medições isoladamente como se fossem o mesmo serviço.**
- `server_segmentation` permite comparar somente o custo da segmentação e montagem reportados pelo coordenador. `segmentationMs` **não** inclui o 2PC.
- `speedup_one_vs_two_workers_client_e2e` é `mediana(1-worker) / mediana(2-workers)` sem 2PC. **Um worker ainda pode usar threads internas e processar os 4 tiles em paralelo**; não equivale a um algoritmo sequencial SLIC puro.
- `transactional_overhead_client_e2e_percent` é o sobrecusto de entregar um PNG ao cliente incluindo o 2PC e a leitura, não o tempo puro das duas fases do protocolo. Para isolar PREPARE/COMMIT, precisaríamos instrumentar o coordenador Java com métricas por fase.
- O script verifica hashes: se um resultado divergir, sinaliza falha; não trate imagens diferentes como saídas equivalentes.
- Dez amostras são uma exploração acadêmica razoável; reportar mediana, média, desvio-padrão, p95 e condições do ambiente. Evite afirmações fortes de significância estatística com N=10.

### 6. Próximas etapas

1. Envie `benchmarks/resultados/resumo.json` e, se possível, `amostras.csv`, para avaliação estatística e criação dos gráficos do relatório.
2. Opcional: preserve `docs/ETAPA_6_BENCHMARKS.md` e `benchmarks/benchmark_slic.py` no Git. Os dados das medições podem ficar fora do repositório ou ser publicados de maneira consciente depois de revisados.
3. Para encerrar apenas a instância de baseline: `sudo docker stop slic-coordinator-single-benchmark`. **Não remova `slic-coordinator-lan`, os workers ou seus volumes**.

## Limitações do ambiente

- Coordenador Java e um worker residem no mesmo notebook Lubuntu, enquanto o outro worker está em PC Windows na rede local, com hardware potencialmente diferente. O speedup reflete essa arquitetura específica, não um ganho generalizável para qualquer cluster.
- Todos os cenários utilizam 4 tiles e processamento via gRPC. O caso `um_worker_sem_2pc` não é uma implementação sequencial pura do SLIC, mas uma execução distribuída com apenas um nó trabalhador.
- O worker em Windows está em Docker Desktop; o Linux usa Docker Engine. Isso pode afetar tempos.
- O protocolo transacional exige 2 ACKs antes de permitir a publicação do resultado. Com falha, o teste não deve ser contabilizado como sucesso; investigue primeiro.
