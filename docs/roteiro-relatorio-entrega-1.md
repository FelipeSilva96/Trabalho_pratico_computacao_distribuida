# Roteiro para o relatório da primeira entrega

O relatório final deve seguir o template SBC e o limite definido pelo professor. Este arquivo é apenas um roteiro de estudo e organização.

## 1. Introdução e problema

Explicar:

- o problema de classificação de resíduos;
- por que o processamento de várias imagens pode ser dividido;
- por que isso é adequado para um sistema distribuído;
- objetivo específico do trabalho de Computação Distribuída.

Evitar transformar essa seção no artigo do TI6. O foco aqui deve ser a arquitetura distribuída.

## 2. Arquitetura

Descrever:

- cliente;
- coordenador;
- workers;
- comunicação REST externa;
- comunicação gRPC interna;
- conteúdo das principais mensagens;
- forma de distribuição das imagens;
- execução em pelo menos dois nós.

Incluir um diagrama.

## 3. Requisitos implementados

### 3.1 RPC com gRPC

Explicar:

- por que gRPC foi usado;
- arquivo `.proto`;
- cliente e servidor;
- request/response.

### 3.2 Relógio lógico de Lamport

Explicar:

- problema de ordenação de eventos;
- contador lógico;
- regra de envio;
- regra de recebimento;
- pequeno exemplo de timestamps.

### 3.3 Web Service REST

Explicar:

- endpoint de classificação;
- relação entre cliente e coordenador;
- diferença entre REST e gRPC no sistema.

## 4. Paralelismo e distribuição

Mostrar que as imagens são unidades independentes de trabalho e que podem ser enviadas simultaneamente para workers diferentes.

## 5. Falhas de comunicação

Na primeira entrega, relatar o mecanismo inicial de timeout e failover.

## 6. Desafios encontrados

Registrar problemas reais encontrados pelo grupo, por exemplo:

- geração de código a partir do Protobuf;
- comunicação entre containers;
- portas;
- serialização de imagens;
- execução em máquinas diferentes;
- dependências do modelo.

Não inventar desafios que não aconteceram.

## 7. Participação de cada integrante

Descrever de forma objetiva o papel real de cada pessoa.

## Observação

Na segunda entrega, o relatório será expandido com tratamento de falhas mais completo e pelo menos um requisito adicional entre os definidos pelo enunciado.
