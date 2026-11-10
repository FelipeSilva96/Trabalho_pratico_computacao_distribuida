# Análise consolidada de desempenho — SLIC distribuído e Two-Phase Commit

## Dados e metodologia

Foram analisados os arquivos originais `amostras.csv` de três rodadas (`rodada_01`, `rodada_02`, `rodada_03`). Cada rodada incluiu duas execuções de aquecimento e dez medições por cenário. As 18 execuções de aquecimento não entram nas estatísticas. Todas as 90 medições apresentaram HTTP 200, `ok=True` e SHA-256 idêntico, com 4.607.902 bytes por PNG. No cenário transacional, todas as 30 transações encerraram como `COMMITTED`, com dois ACKs.

Os três cenários foram: **A** (uma máquina trabalhadora Lubuntu, SLIC em quatro blocos, sem 2PC), **B** (duas máquinas trabalhadoras Lubuntu e Windows, sem 2PC) e **C** (duas máquinas trabalhadoras com 2PC e recuperação do PNG após a confirmação). A imagem de entrada foi `imagem_teste_grande.jpg` (1.773.491 bytes) com `segments=500`, `compactness=10.0`, `rows=2`, `cols=2`, quatro tiles. O script alternou a ordem dos cenários por rodadas; as sementes foram 42, 43 e 44.

Para as comparações, usamos o **tempo de ponta a ponta observado pelo cliente** (`end_to_end_ms`). O cenário B retorna a imagem na própria resposta; o cenário C retorna o JSON da transação e depois realiza um `GET` para obter a imagem. Assim, ambas as medições representam a obtenção do resultado final pelo cliente, mas envolvem fluxos HTTP distintos.

## Estatísticas consolidadas (30 medições por cenário)

| Indicador | A: 1 worker | B: 2 workers | C: 2 workers + 2PC |
|---|---:|---:|---:|
| Média (ms) | 2796.76 | 1956.47 | 2482.78 |
| Mediana (ms) | 2717.48 | 1907.29 | 2419.33 |
| Desvio-padrão amostral (ms) | 138.54 | 145.89 | 132.06 |
| P95 (ms; interpolação linear) | 3067.81 | 2200.92 | 2704.78 |
| Erros | 0 | 0 | 0 |

### Ganho da distribuição

Speedup da configuração B em relação a A (razão entre as medianas):

`S = mediana(A) / mediana(B) = 2717.484 / 1907.288 = 1.425 vezes`.

Redução relativa da mediana: `100 × [1 − mediana(B)/mediana(A)] = 29.81%`.

### Custo transacional

Sobrecusto de C frente a B: `mediana(C) − mediana(B) = 512.05 ms`, equivalente a `26.85%` em relação à mediana de B. Apesar desse custo, a mediana de C permaneceu `10.97%` inferior à de A.

**Cuidado:** esse sobrecusto não é uma medição isolada do protocolo 2PC. Inclui persistência, transferências, confirmação, orquestração HTTP e leitura posterior do PNG.

### Separação do tempo de segmentação e de outros componentes

| Componente (média) | B: 2 workers | C: 2 workers + 2PC |
|---|---:|---:|
| Segmentação registrada pelo servidor (ms) | 1917.83 | 1915.70 |
| Requisição HTTP (ms) | 1943.16 | 2337.06 |
| Leitura posterior do PNG (ms) | 0.00 | 120.68 |
| Tempo total ponta a ponta (ms) | 1956.47 | 2482.78 |

Os tempos médios da **segmentação propriamente dita** foram similares nas duas configurações distribuídas (1917.8 ms versus 1915.7 ms). O acréscimo no tempo total está associado principalmente às operações posteriores ao SLIC. Os campos da tabela não são todos mutuamente exclusivos: `server_segmentation_ms` está contido no tempo da requisição HTTP; não deve ser somado a ele.

Decomposição **aritmética pelas médias** da diferença de ponta a ponta entre C e B:

- Diferença no tempo da requisição: 393.90 ms.
- Tempo de leitura adicional: 120.68 ms.
- Diferença nos demais custos do cliente: 11.72 ms.
- Diferença total das médias: 526.31 ms.

## Repetibilidade

As medianas por rodada foram: 

| Rodada | A (ms) | B (ms) | C (ms) | Speedup A/B | Sobrecusto C/B |
|---|---:|---:|---:|---:|---:|
| 1 | 2717.48 | 1846.48 | 2395.60 | 1.472× | 29.74% |
| 2 | 2742.43 | 2018.37 | 2418.15 | 1.359× | 19.81% |
| 3 | 2711.94 | 2006.29 | 2571.64 | 1.352× | 28.18% |


## Incerteza exploratória (opcional para discussão)

Um *bootstrap* estratificado por rodada, reamostrando conjuntamente as trincas de medições de cada ordem/rodada, com 10.000 reamostragens e semente 42, produziu intervalos percentis de 95% **exploratórios**: speedup de 1.346× a 1.496× e sobrecusto transacional de 20.06% a 36.28%. Esses intervalos dependem da suposição de que os blocos reamostrados representam variação amostral relevante. Não eliminam efeitos sistemáticos de temperatura, agendamento de CPU, carga, diferenças de hardware ou rede.

## Limitações relevantes para o relatório

1. Apenas uma imagem de entrada, com parâmetros SLIC fixos. Os resultados não se generalizam automaticamente a outras resoluções, compactações ou quantidades de segmentos.
2. A configuração de um worker **não é processamento estritamente sequencial**: o serviço pode processar tiles em paralelo em uma mesma máquina.
3. Lubuntu e Windows possuem configurações diferentes; o ganho observado é do sistema heterogêneo real, não uma medida de eficiência paralela com dois nós idênticos.
4. A latência inclui rede, serialização, HTTP e processos do sistema. Não foi obtida instrumentação independente de CPU, temperatura, uso de rede e disco.
5. As 30 medições por cenário não equivalem automaticamente a 30 experimentos independentes; os tempos da mesma sessão podem ser correlacionados.
6. O modo transacional tem fluxos HTTP diferentes dos outros dois cenários. O script inclui a leitura final para que o cliente receba um PNG em todos os casos, mas isso impede atribuir toda a diferença apenas às mensagens do 2PC.
7. Não houve falhas nas medições de desempenho. A recuperação de falhas foi exercitada em experimentos separados e **não** está contemplada nas médias de latência aqui apresentadas.

## Arquivos de apoio

- `medicoes_consolidadas_sem_aquecimento.csv`: 90 medições.
- `amostras_consolidadas.csv`: todas as 108 execuções, incluindo aquecimentos.
- `estatisticas_consolidadas.csv` e `.json`: estatísticas recalculadas.
- `grafico_01_distribuicao_tempos.png`: dispersão e distribuição dos tempos totais.
- `grafico_02_medianas_por_rodada.png`: estabilidade entre rodadas.
- `grafico_03_decomposicao_tempo.png`: tempos médios de requisição, leitura e demais custos do cliente.
- `grafico_04_comparacao_geral.png`: comparação central para apresentação e relatório.

**Fonte primária:** seis arquivos `amostras.csv`/`resumo.json` enviados pelo grupo, preservados no repositório no commit `a7d9d30`, branch `features/segunda_entrega`.
