# Próximos passos

> **A ordem importa mais que a lista.** Cada posição aqui tem um motivo, e o motivo
> é quase sempre o mesmo: *o que destrava a medição vem antes do que precisa ser
> medido.* Uma lista na ordem errada produz trabalho que roda e não se sabe se
> funcionou.

---

## Onde o projeto está

| | |
|---|---|
| **feito e verificado** | busca com perfil, cadastro por jornada, caminho de escrita, autenticação, resiliência, tolerância a erro de digitação, três sinais de comportamento, medição de qualidade, limite de taxa |
| **medido** | 528 req/s · p50 9 ms · p99 33 ms · 173 testes · 56 asserções de contrato · 47 sondas sem achados |
| **em memória, de propósito** | fila de eventos, agregação, perfil |
| **não existe** | aprendizado de máquina |

A última linha é uma **decisão**, não uma lacuna — e o porquê está no passo 0.

---

## 0 · Evento durável

**O bloqueio real, e ele vem antes de tudo.**

Hoje o caminho é evento → `ConcurrentLinkedQueue` → agregado em memória → perfil em
`ConcurrentHashMap`. **Reiniciar a aplicação apaga tudo.** Qualquer modelo treinado
hoje usa o que aconteceu desde a última vez que o serviço subiu.

Não precisa de Kafka para destravar. Um adaptador que escreve os eventos em
**Parquet**, lido com **DuckDB** do lado de análise, resolve para POC: embutido,
zero operação, lê Parquet direto, e é o que o mundo de dados usa nesse tamanho. É
mais um adaptador atrás da `FilaDeEventos` que já existe — a arquitetura não muda.

**Em produção isso não serve.** Com 60M buscas/dia são ~150M eventos/dia, e
DuckDB não é o lugar: é Kinesis ou MSK → S3 → agregação em streaming. O desenho
original já dizia MSK; o ponto do passo 0 é que a **porta** fique pronta para
trocar.

> **Por que é o passo 0 e não o 1:** ele não entrega nada visível. Entrega a
> condição de possibilidade de todos os outros. Pular é construir sobre dado que
> evapora.

---

## 1 · Embeddings pré-treinados

**Resolve um defeito declarado, e não precisa de nenhum rótulo.**

Está nas limitações conhecidas: `grana` devolve zero, e nenhuma distância de edição
conserta sinônimo que não existe no catálogo. Isso é **recuperação semântica**, e
modelo pré-treinado resolve sem treino.

- vetor por documento, calculado **offline**, em Python (`sentence-transformers`)
- `DenseVectorField` no Solr 10, que já suporta HNSW e consulta KNN
- KNN entra como **terceira tentativa**, depois da exata e da tolerante — mesmo
  padrão de escalonamento que a tolerância já usa

Modelo: `intfloat/multilingual-e5-small` (384 dimensões, bom em português) ou
`paraphrase-multilingual-MiniLM-L12-v2`, quantizado para ONNX.

**Por que é o primeiro passo de ML:** é o único que entrega valor com 104
documentos. Com esse volume, nenhum modelo *aprende* nada — mas um modelo
pré-treinado não precisa aprender.

---

## 2 · Cache de prefixo

**Este passo subiu de lugar, e é a parte contraintuitiva.**

O embedding da consulta é o único pedaço de ML que cairia no caminho quente, e os
números decidem:

```
8 ms de CPU por embedding → 1 núcleo serve 125/s

  1.667 req/s  →  13 núcleos saturados só em embedding
  2.800 req/s  →  22 núcleos
```

Isso não é latência, é uma frota. **Mas a distribuição de prefixos num typeahead é
brutalmente Zipfiana:** `emprestim` é digitado por milhares de pessoas por hora. Com
60M req/dia, os ~10 mil prefixos mais frequentes cobrem 95–99% do tráfego.

```
acerto de 99%  →  17 req/s chegam ao modelo  →  0,13 núcleo
acerto de 95%  →  83 req/s                   →  0,67 núcleo
```

**Quanto maior o volume, mais barato fica o embedding por requisição** — porque a
cauda relativa encolhe. É o oposto de quase todo problema de escala.

Um job offline embeda os top-N prefixos do log; o modelo só é chamado na cauda. E
com 99% de acerto, **onde o modelo mora deixa de importar** — o que resolve a
pergunta do passo 3 antes de ela ser feita.

---

## 3 · O embedder da cauda

Só depois do cache, e aí a escolha é fácil porque o volume é pequeno.

| opção | latência | quando |
|---|---|---|
| ONNX / DJL **dentro do app** | 5–15 ms CPU | volume de POC: uma frota, um deploy |
| **sidecar** Python | +1–3 ms de salto | em escala: versiona e escala separado |

**Em escala o sidecar ganha**, e não por desempenho: porque você não quer 20
instâncias do serviço de busca carregando 300 MB de modelo cada, nem o modelo
amarrado ao ciclo de deploy da busca.

E "Python é lento" é o enquadramento errado: a inferência acontece em C++ de
qualquer forma, o `onnxruntime` libera o GIL durante `session.run()`, e o
tokenizador da HuggingFace é Rust. O custo do sidecar é o salto de rede, o segundo
artefato de deploy e um novo modo degradado para o disjuntor cobrir — não a
velocidade da linguagem.

---

## 4 · Lista de julgamento por LLM

**O destravamento mais barato da lista, e o menos óbvio.**

Não é preciso tráfego para ter dado de avaliação. Gere pares (consulta, documento)
e peça a um LLM uma nota de relevância de 0 a 3. Com ~300 consultas × 10 candidatos
são ~3 mil julgamentos — **poucos dólares de API, uma vez, offline.**

Com isso aparece o **NDCG@5**, que é o número que falta. Hoje há taxa de
zero-resultado, posição clicada e motivo de descarte — bons, e nenhum deles diz se
a **ordem** está certa. Sem medir ordem, mexer no ranking é fé.

**Limite declarado:** julgamento de LLM tem viés próprio e não substitui clique
real. Serve para comparar duas versões do ranker entre si, não para afirmar "a
busca é boa".

---

## 5 · Learning to rank

Só agora, e é o passo mais barato de servir que existe.

- **LightGBM** treina (`lambdarank`) com as features que já são coletadas
- exporta como `MultipleAdditiveTreesModel`
- o módulo **`ltr` do Solr 10** reranqueia o top-N — **e ele já está instalado**

Reranquear top-50 com GBDT de 100 árvores custa **microssegundos**, e acontece
dentro do processo que já tem os documentos. Nenhum salto de rede, nenhum serviço
novo, nenhuma frota. Por requisição, é a opção mais barata de reranking que existe.

O que já está pronto para isso e foi construído sem precisar de ML: impressão com
posição, clique, adesão, e `ofertaId` ligando resposta a evento.

---

## 6 · Correção de viés de posição

*Inverse propensity scoring*, e **exige tráfego real**. Não dá para pular os passos
anteriores: sem tráfego, a propensão não é estimável.

Está nas limitações conhecidas: linha na posição 5 é vista menos que na 0, e a
contagem de "exibiu e não clicou" trata as duas igual — a que já estava embaixo
afunda mais rápido.

---

## Ferramentas, com custo real

| para | ferramenta | custo |
|---|---|---|
| evento durável (POC) | Parquet + **DuckDB** | grátis, embutido |
| evento durável (produção) | Kinesis ou MSK → S3 | ~US$ 500–1.000/mês em 150M/dia |
| embeddings offline | `sentence-transformers` | grátis, local |
| embedding no app | **DJL** ou `onnxruntime` | grátis, Apache-2.0 |
| vetor no índice | `DenseVectorField` do Solr | **já tem** |
| treinar ranker | **LightGBM** | grátis |
| servir ranker | módulo `ltr` do Solr | **já tem** |
| avaliação IR | `ranx`, ou NDCG à mão (~30 linhas) | grátis |
| rastrear experimento | **MLflow** com backend SQLite | grátis, self-hosted |
| julgamento | API de LLM, uma vez | ~US$ 5–20 |

**O que evitar, e por quê:**

- **plataforma gerenciada** (SageMaker, Vertex): paga-se endpoint parado
- **vector DB separado** (Pinecone, Weaviate cloud): o Solr já faz KNN, e seriam
  **dois** índices para o disjuntor proteger em vez de um — mais um modo de falha
  para ganhar o que já se tem
- **fine-tuning**: não há dado
- **LLM no caminho quente**: os 30 ms encerram a discussão, e em escala o custo
  também

---

## O encaixe arquitetural

Nada disso muda o domínio. A porta `CatalogoDeSugestoes` já abstrai o índice, e um
reranqueador entra como **decorador da porta** — exatamente o padrão do disjuntor.
O caso de uso não fica sabendo que existe modelo, igual a hoje ele não sabe que
existe disjuntor nem tolerância a erro de digitação.

E **os 30 ms são o melhor filtro de ideias que o projeto tem.** A maior parte das
propostas de ML em busca morre nesse número, e morrer cedo é barato: cross-encoder
de reranking (~50 ms) está fora; embedding remoto por tecla está fora; LLM na
requisição está fora. É uma restrição já medida, e ela economiza meses.

---

## Fora do caminho de ML

Três itens que não dependem de nada acima e cabem em uma tarde cada:

**Teste de alcance de oferta.** Nenhum perfil de demonstração tem elegibilidade de
`FINANCIAMENTO_*`, então as duas ofertas de financiamento são inalcançáveis pelos
oito perfis. O conserto não é conceder a elegibilidade: é um **teste que falhe
quando existir oferta que nenhum perfil alcança**, para isso parar de ser descoberta
manual. Já aconteceu duas vezes.

**Uniformizar o `charset` do `Content-Type`.** O caminho de erro declara
`;charset=UTF-8`, o de sucesso não. Os dois são defensáveis, a inconsistência não.

**Teste de carga com concorrência real.** O que existe é uma medição numa máquina
com cliente, serviço e índice juntos. Compara mudanças entre si; não afirma que o
orçamento de 18/8/30 ms de produção fecha.
