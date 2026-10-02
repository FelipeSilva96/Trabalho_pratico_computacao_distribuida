# Arquitetura do sistema

## Entidades

### Cliente

É quem inicia a classificação. Pode ser o navegador, `curl` ou futuramente outro aplicativo.

O cliente não conhece os workers. Ele conversa somente com o coordenador.

### Coordenador

Responsabilidades:

- expor o Web Service REST;
- receber uma ou várias imagens;
- manter o relógio lógico de Lamport;
- escolher um worker;
- executar chamadas gRPC de forma concorrente;
- aplicar deadline;
- tentar outro worker em caso de falha;
- agregar as respostas.

### Worker

Responsabilidades:

- receber uma chamada RPC;
- atualizar seu relógio lógico;
- classificar a imagem;
- responder ao coordenador;
- informar seu identificador e timestamp lógico.

## Principais mensagens

### Cliente → Coordenador

Protocolo: HTTP REST.

Conteúdo:

- uma ou várias imagens.

### Coordenador → Worker

Protocolo: gRPC.

Mensagem `ClassifyRequest`:

- `request_id`;
- `filename`;
- bytes da imagem;
- timestamp lógico do coordenador.

### Worker → Coordenador

Mensagem `ClassifyResponse`:

- `request_id`;
- nome do arquivo;
- classe;
- confiança;
- `worker_id`;
- status;
- timestamp lógico do worker;
- tempo local de processamento;
- indicação de modo de demonstração.

## Paralelismo

Se a requisição contém várias imagens, o coordenador cria tarefas independentes. Essas tarefas podem ser executadas simultaneamente e distribuídas entre workers diferentes.

Exemplo:

```text
imagem A ──> worker-1
imagem B ──> worker-2
imagem C ──> worker-1
imagem D ──> worker-2
```

## Falha de comunicação

Cada RPC possui deadline.

Se uma chamada falhar:

1. o coordenador registra implicitamente a falha daquela tentativa;
2. tenta o próximo worker;
3. se nenhum worker responder, devolve erro somente para aquela imagem.

Esse mecanismo é simples e pode ser expandido na segunda entrega com health checking periódico, circuit breaker, replicação ou outras estratégias.
