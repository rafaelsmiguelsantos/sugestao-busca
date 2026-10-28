# Avaliação de ranking

> **O entregável não é o número, é o portão.** Um NDCG medido uma vez é
> curiosidade. Um NDCG que roda a cada mudança de peso e reporta o delta é a
> diferença entre calibrar e chutar.

---

## O que faltava

O ranking tem onze números calibrados à mão:

| onde | números |
|---|---|
| `qf` do Solr | `termo_prefixo^6` · `termo_infixo^2` · `sinonimo^3` |
| `sugestao.peso.*` | 1,6 · 1,25 · 1,5 · 1,2 · 1,15 · 3,6 · 0,6 · 0,15 |

E o projeto media quatro coisas boas — taxa de zero-resultado, desfecho em cinco
valores, posição clicada, motivo de descarte. **Nenhuma delas mede ordem.** Sem
medir ordem, mexer em qualquer um dos onze é fé; e no escuro ninguém mexe, então
o ranking congela.

```
.\scripts\avaliar-ranking.ps1 -Autoteste          # a aritmetica, sem rede
.\scripts\avaliar-ranking.ps1                     # mede e compara com a linha de base
.\scripts\avaliar-ranking.ps1 -SalvarLinhaDeBase  # grava a referencia atual
.\scripts\avaliar-ranking.ps1 -Detalhado          # mostra a lista devolvida
```

Sai com código 1 quando a média cai mais que a tolerância. É passo de build.

---

## A conta

**NDCG@k** — *normalized discounted cumulative gain* — compara a ordem entregue
com a melhor ordem possível entre os itens julgados.

```
ganho(nota)  = 2^nota - 1          3 vale 7 · 2 vale 3 · 1 vale 1 · 0 vale 0
desconto(i)  = log2(i + 2)         posicao 0 divide por 1, posicao 7 por 3,17

DCG   = soma de ganho / desconto sobre as k primeiras linhas devolvidas
IDCG  = o mesmo sobre as k melhores notas que existiam
NDCG  = DCG / IDCG
```

Duas escolhas que mudam o que o número enxerga:

**Ganho exponencial.** A nota 3 vale sete e a nota 1 vale um. Trocar o primeiro
lugar por um vizinho de nota 2 derruba o NDCG para **0,834** — visível. Com ganho
linear a mesma troca quase não apareceria, e o portão deixaria de ver justamente
a regressão que mais importa.

**Corte em k.** `@5` e `@8` — o segundo é o tamanho da lista
(`sugestao.resultado-maximo`). Linha que ninguém vê não conta: um acerto na sexta
posição vale zero em `@5`, e num typeahead isso é a verdade.

**IDCG zero devolve `n/a`, não 1,0.** Consulta sem nada julgado para encontrar
sai da média em vez de entrar como acerto perfeito. Devolver 1,0 faria um
conjunto envelhecido — termos renomeados no catálogo — fazer a média **subir**
enquanto a cobertura desaba.

---

## A rubrica

| nota | significa |
|---|---|
| **3** | é o que a pessoa procurava, e ela pode agir agora |
| **2** | mesma jornada, alternativa plausível |
| **1** | relacionado: ajuda, explicação, ação vizinha |
| **0** | não julgado — toda linha devolvida fora do mapa vale zero |

**O termo é comparado normalizado** — sem acento, minúsculas, pontuação virando
espaço — pela mesma função que normaliza o prefixo digitado. `Tesouro Direto` e
`tesouro direto` são a **mesma** entrada. Isso existe para que uma correção de
copy no catálogo não zere a nota de uma consulta sem que nada de relevância tenha
mudado.

> O `ConvertFrom-Json` do PowerShell 5.1 compara chaves sem distinguir caixa, e
> duas grafias do mesmo termo são erro de chave duplicada. O script recusa com
> uma mensagem que explica isso, porque a nativa não explica.

---

## O que ele mede, e o que não

**Mede a cadeia inteira.** Bate no endpoint, não no índice: `qf` e `boost` do
Solr, Peneira, Rankeador, Misturador e Recuperador, tudo junto. Isso importa —
uma lista que remove corretamente uma oferta inelegível e põe um artigo no lugar
**é** a lista melhor, e só o endpoint sabe disso.

**Não mede personalização.** Uma pessoa não tem como julgar o que *este* cliente
quer. Afinidade, tendência e fadiga continuam sem medição de qualidade; aqueles
precisam de experimento online ou avaliação contrafactual sobre log. São os dois
rankings do desenho outra vez, e só um deles é mensurável offline.

**Os julgamentos foram escritos sabendo das regras de elegibilidade.** Então o
número mede *qualidade de ordem dada a filtragem*, não a política de filtragem. O
NDCG nunca vai dizer que a peneira está errada — essa é conversa de produto.

**Há tolerância num teste determinístico, e o motivo é o índice.** O `boost`
multiplicativo tem `recip(ms(NOW/HOUR, visto_em_dt), ...)`. O `NOW/HOUR` existe
para a chave do cache de filtro ser estável *dentro* da hora, e a consequência é
que o score muda na virada dela. Tolerância zero produziria um portão que fecha
sozinho de hora em hora.

---

## O dado se move, e por isso a linha de base carrega a impressão digital

> **Isto me pegou.** Numa rodada, `pix` caiu **0,1753** sem nenhuma mudança de
> código. Duas causas, e as duas eram as próprias ferramentas de teste deste
> repositório:
>
> - a collection de cadastro grava ofertas de teste, e o índice foi de **99 para
>   104 documentos**;
> - as sondas e a collection de consumo postam `BUSCA_DIGITADA`, e a rodada de
>   aprendizado escreveu `pix` no **histórico do `pf-novo`**. Uma linha de
>   histórico não julgada entrou na posição 1 e empurrou três linhas julgadas
>   para baixo.
>
> Nenhuma das duas é regressão de ranking. Sem atribuição, as duas aparecem como
> se fossem — o que gasta uma tarde e, pior, ensina a ignorar o portão.

Por isso a linha de base guarda um `corpus`: o total de documentos e a contagem
por tipo, em texto canônico ordenado. Quando ele difere, o script **avisa antes
dos números** — a contagem não prova que o índice é idêntico, mas prova que ele
**mudou**, que é o que falta para atribuir a diferença.

```
corpus : 99 docs [acao=20 consulta=55 entidade=6 oferta=18]
```

**Como voltar a um estado comparável:**

| o que sujou | como limpar |
|---|---|
| ofertas de teste no índice | `.\scripts\limpar-ofertas-de-teste.ps1 -Confirmar` |
| perfis, com `sugestao.dynamo.habilitado=false` | **reiniciar a aplicação** — o perfil é em memória |
| perfis, com o **DynamoDB ligado** | `docker compose down dynamodb -v` e subir de novo |

> **Esta tabela já esteve errada, e vale saber por quê.** Ela dizia apenas
> "reiniciar a aplicação", o que era verdade enquanto o perfil era um
> `ConcurrentHashMap`. Com o DynamoDB ligado, **reiniciar não limpa mais nada**:
> a poluição de teste passou a sobreviver junto com o aprendizado, que é
> precisamente o que a persistência existe para fazer. O `-v` é o que descarta o
> volume; sem ele o `down` preserva as tabelas.
>
> É o custo que ninguém menciona ao defender durabilidade: **o estado sujo também
> fica durável.** A receita de limpeza passa a fazer parte do procedimento de
> medição, em vez de ser um efeito colateral gratuito do restart.

O perfil não aparece na impressão digital, e isso é uma limitação declarada: não
há como lê-lo de fora sem `?explicar=true` por cliente.

> **O alarme já nasceu errado uma vez, e o conserto está comentado no código.** A
> primeira versão guardava um `[ordered]@{}` e montava a descrição na hora de
> comparar; enumerar `PSObject.Properties` de um `OrderedDictionary` devolve
> `Count`, `IsReadOnly`, `Keys`, `Values` — não as entradas. O alarme disparava
> **sempre**, dizendo que o corpus mudou quando ele estava idêntico. Um alarme que
> grita em toda rodada é pior que alarme nenhum: ele ensina quem lê a ignorá-lo, e
> aí não serve no dia em que o corpus mudar de verdade.

---

## Duas consultas carregam defeito conhecido de propósito

| consulta | o que ela guarda |
|---|---|
| `emprestmo`, `consignadoo` | erro de digitação. NDCG baixo aqui significa que a tolerância parou de funcionar |
| `veiculos` | **dois defeitos num caso.** O plural só alcança o documento pela tolerância — não há *stemmer* de português, e a tolerância está fazendo o trabalho de um. E nenhum perfil de demonstração tem elegibilidade de `FINANCIAMENTO_VEICULO`, então a oferta sai de todo jeito |
| `imobiliario` | o mesmo buraco de elegibilidade, em `FINANCIAMENTO_IMOVEL` |

Isso é intencional. Um conjunto de avaliação que só contém casos que passam é um
conjunto que não mede nada. O número sobe quando o dado de elegibilidade for
corrigido, **e é assim que se descobre que foi.**

---

## Como escalar para ~300 consultas com LLM

O conjunto atual tem **32 consultas e 107 pares julgados**, escritos à mão sobre
o catálogo semeado. O caminho para três centenas é o mesmo, com o julgamento
terceirizado:

1. **Amostre os prefixos do log real**, não invente. A distribuição de um
   typeahead é Zipfiana: os ~300 prefixos mais frequentes cobrem a maior parte do
   tráfego, e avaliar o que ninguém digita é medir a cauda errada.
2. **Monte os pares** `(consulta, documento)` com os 10 primeiros candidatos de
   cada prefixo. ~3 mil pares.
3. **Julgue com LLM**, uma vez, offline, com a rubrica acima no prompt e o termo
   mais o rótulo do documento. Poucos dólares de API.
4. **Revise a mão uma amostra de 10%.** Se a concordância com o julgamento humano
   for baixa, o problema está na rubrica, não no modelo.

**Limite declarado:** julgamento de LLM tem viés próprio e não substitui clique
real. Serve para **comparar duas versões do ranker entre si**, nunca para afirmar
"a busca é boa".

---

## Honestidade sobre a autoria deste conjunto

Três notas foram acrescentadas **depois** da primeira medição, ao ver linhas
devolvidas que eu não havia julgado: `cdb` no histórico do próprio cliente, e
`LCI e LCA` e `CDB` na consulta `investi`. As três se defendem pelo mérito — são
produtos de investimento que aquele cliente pode contratar e não contratou — mas
a ordem dos acontecimentos fica registrada aqui porque **acrescentar nota depois
de ver a saída é exatamente como um conjunto de avaliação deixa de medir
qualquer coisa.**

A regra que separa as duas coisas: um item recebe nota se eu defendo que ele é
uma boa resposta, **nunca porque ele apareceu**. Por isso `capital de giro` e
`transferir para minha conta` continuam sem nota na consulta `conta pj` — eles
apareceram e eu não os defendo.

O mesmo vale para a nota 1 nas linhas substitutas de `imobiliario` e `veiculos`:
é **posição editorial explícita** — uma linha de jornada vale mais que lista
vazia. Quem discordar edita o número, e é para isso que ele está num arquivo em
vez de dentro do código.

---

## Arquivos

| arquivo | o quê |
|---|---|
| `julgamentos.json` | as consultas, as identidades e as notas |
| `linha-de-base.json` | a medição de referência, gerada por `-SalvarLinhaDeBase` |
| `antes.json` | medição guardada antes da linha substituta existir, para comparação |
| `..\scripts\avaliar-ranking.ps1` | o avaliador e o portão |
