# Mapeamento da primeira entrega

O enunciado exige que o sistema seja distribuído, tenha pelo menos dois nós e incorpore no mínimo dois requisitos da lista da primeira entrega.

## Sistema distribuído

No desenvolvimento local:

```text
coordinator
worker-1
worker-2
```

são processos separados que se comunicam pela rede Docker.

Para o experimento final, os workers podem ser executados em máquinas físicas diferentes.

## Requisito 1 — RPC

Implementado com gRPC.

Contrato:

```text
classifier.proto
```

Chamadas:

- `Classify`
- `Health`

O coordenador funciona como cliente gRPC e cada worker como servidor gRPC.

## Requisito 2 — sincronização de relógios lógicos

Implementado manualmente com o algoritmo de Lamport.

Regra local:

```text
antes de um evento/envio:
C = C + 1
```

Regra no recebimento:

```text
C = max(C, timestamp_recebido) + 1
```

O valor acompanha as mensagens RPC.

## Requisito 3 — Web Services

Implementado por uma API REST no coordenador.

Rotas:

```text
GET  /api/health
GET  /api/workers
POST /api/classify
```

A página HTML do projeto é apenas um cliente dessa API; ela não é o Web Service em si.

## Operações paralelizadas

As imagens de um lote são tarefas independentes. O coordenador envia várias tarefas simultaneamente usando um pool de threads.

Os workers também usam um pool de threads do servidor gRPC.

## Falhas de comunicação

A implementação inicial contém:

- deadline nas RPCs;
- tentativa automática em outro worker;
- status `UNAVAILABLE` no health check;
- erro por imagem quando todos os workers falham.

Na segunda entrega esse mecanismo deve ser aprofundado conforme a orientação do professor.
