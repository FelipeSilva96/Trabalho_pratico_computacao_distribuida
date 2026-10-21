# Modelo

Esta pasta é mantida vazia no Git.

O worker procura por padrão:

```text
waste_mobilenet_v3_small.pt
```

O checkpoint esperado deve ser compatível com uma `torchvision.models.mobilenet_v3_small` cuja última camada tenha seis saídas, na ordem:

```text
battery
glass
metal
organic
paper
plastic
```

Formatos aceitos pelo carregador:

1. um `state_dict` salvo diretamente;
2. um dicionário contendo a chave `model_state_dict`.

Sem o arquivo, o worker pode funcionar em modo de demonstração para testar apenas a infraestrutura distribuída.
