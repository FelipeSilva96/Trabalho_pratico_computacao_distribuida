# Segunda entrega — Etapa 2: armazenamento local persistente

Este pacote prepara o mecanismo de armazenamento de resultados segmentados, **sem ativar ainda a replicação via gRPC e sem implementar o protocolo Two-Phase Commit**. Os arquivos não modificam o coordenador nem a segmentação SLIC existente.

## Conteúdo

- `worker/replica_store.py`: armazena arquivos PNG imutáveis em `/app/data/committed`, registra metadados em SQLite, valida SHA-256 na leitura e trata reenvios idênticos de modo idempotente.
- `worker/tests/test_replica_store.py`: oito testes unitários isolados.

## Instalação no notebook (a partir da raiz do repositório)

Extraia este ZIP na raiz do projeto. Para testar **sem reconstruir a imagem Docker**:

```bash
sudo docker run --rm \
  -v "$PWD/worker:/src:ro" \
  -w /src \
  cd-slic-worker \
  python -m unittest discover -s tests -v
```

A execução utiliza o Python e o Pillow da imagem `cd-slic-worker`, mas lê os arquivos diretamente do diretório local montado em modo somente leitura. Cada teste usa uma pasta temporária própria.

## Verificação de persistência no volume do Lubuntu (opcional, após 8 testes passarem)

Use o *volume já existente* `slic-replica-lubuntu`. A imagem Docker não precisa ser reconstruída neste passo porque o código é montado a partir do host:

```bash
sudo docker run --rm \
  -v "$PWD/worker:/src:ro" \
  -v slic-replica-lubuntu:/app/data \
  -w /src \
  cd-slic-worker \
  python -c 'from replica_store import ReplicaStore; from PIL import Image; import io; p=io.BytesIO(); Image.new("RGB",(32,32),"blue").save(p,format="PNG"); r=ReplicaStore("/app/data"); x=r.save("teste-local-001",p.getvalue()); print("GRAVADO",x.result_id,x.sha256, x.size_bytes)'
```

Para conferir em outro processo/container que os dados sobreviveram:

```bash
sudo docker run --rm \
  -v "$PWD/worker:/src:ro" \
  -v slic-replica-lubuntu:/app/data \
  -w /src \
  cd-slic-worker \
  python -c 'from replica_store import ReplicaStore; r=ReplicaStore("/app/data"); print("RECUPERADO",len(r.read("teste-local-001")),"bytes")'
```

## Próxima etapa

1. Acrescentar `COPY replica_store.py .` ao `worker/Dockerfile`.
2. Estender `classifier.proto` com operações de preparo, confirmação, cancelamento e leitura de réplica.
3. Integrar o `ReplicaStore` ao servidor gRPC, introduzindo estados e journaling para o 2PC.
4. Implementar o coordenador de 2PC durável no Java.
5. Testar falhas antes, durante e após a decisão global.

**Limitação deliberada:** `save` é uma escrita local imediata; por isso, **não deve ser utilizada como o `PREPARE` de 2PC**. O protocolo futuro exigirá armazenamento provisório, estados duráveis e uma transição idempotente `PREPARED -> COMMITTED`.
