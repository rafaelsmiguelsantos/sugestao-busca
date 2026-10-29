# Como a pontuação do ranking funciona

Escrito para duas pessoas ao mesmo tempo: quem vai **explicar a lista** numa reunião
de produto, e quem vai **mexer nos números** sem quebrar nada. Cada seção tem as
duas leituras — o que o código faz, e qual pergunta de negócio aquilo responde.

Nada aqui é modelo treinado. São seis sinais, todos legíveis, todos com o número
escrito em `application.properties`. Dá para calcular à mão qualquer linha de
qualquer resposta, e a [seção 10](#10-um-exemplo-calculado-à-mão) faz exatamente
isso.

---

## 1. A ideia em uma frase

Duas perguntas diferentes, respondidas por duas fontes diferentes, e uma
multiplicação no fim.

| pergunta | quem responde | de onde vem |
|---|---|---|
| "o que **as pessoas** fazem com esse termo?" | o índice | Solr, agregado da população |
| "o que **esta** pessoa faz?" | o perfil | DynamoDB, lido em paralelo |

```
relevancia_final  =  score_do_indice_normalizado  ×  fator_do_perfil
```

**Por que multiplicar e não somar.** Somar faria o peso de cada sinal depender da
escala do score do Solr, que muda com o tamanho do índice e com o prefixo
digitado. Uma jornada líder valeria muito num prefixo e quase nada em outro, sem
ninguém ter decidido isso. Multiplicando, uma jornada líder vale 1,6 vez mais
sempre — com mil termos no índice ou com um milhão.

> **Leitura de negócio.** O índice sozinho entrega a melhor sugestão *média*. O
> fator do perfil é o que transforma a melhor sugestão média na melhor sugestão
> **para quem está digitando**. Se o perfil não chega em 8 ms, o fator vira 1,0 e
> a resposta sai com `degradada: true` — a lista média, honestamente rotulada
> como média.

---

## 2. A fórmula completa, na ordem exata

Está em `Rankeador.java`. A ordem das linhas é parte do desenho, não acaso:

```
0.  normaliza  os scores do índice para [0 .. 1]
1.  elege      a jornada líder (ou nenhuma)

2.  fator = 1,0
      se é da jornada líder            →  fator ×= 1,6
          e tem produto alvo           →  fator ×= 1,25
      se tem afinidade com a jornada   →  fator ×= 1 + afinidade        × 0,5
      se tem afinidade com o produto   →  fator ×= 1 + afinidadeProduto × 0,2
      se a tendência está em alta      →  fator ×= 1 + excedente        × 0,15

3.  fator = min(3,6 , fator)                    ← o teto, ANTES da fadiga

4.  fator ×= (1 − fadiga × 0,4)                 ← o único passo que rebaixa

5.  relevancia_final = score_normalizado × fator
```

O maior valor possível é `1,0 × 3,6 = 3,60`. O menor que uma linha promovida ao
máximo pode receber, já cansada, é `3,6 × 0,6 = 2,16`. Uma linha sem promoção
nenhuma e totalmente cansada fica em `0,6`.

---

## 3. Passo 0 — normalizar, para que a comparação exista

O score do Solr e o score do histórico **não nascem comparáveis**. O do Solr é um
número de BM25 com `boost` multiplicativo: pode sair 0,8 num prefixo e 14 em
outro. O do histórico nasce aqui dentro, já entre 0 e 1.

Sem normalizar, quem ganha é acidente de magnitude. Então:

- os candidatos **do índice** são divididos pelo maior score do índice daquela
  consulta — o primeiro colocado fica exatamente em `1,00`;
- os candidatos **do histórico** passam intactos, porque já estão na escala.

> **Leitura de negócio.** Isto é o que permite dizer "o termo que você buscou na
> semana passada concorre de igual para igual com o que o índice achou". Sem a
> normalização, o histórico ora sumiria, ora dominaria, e ninguém saberia por quê.

---

## 4. Passo 1 — qual jornada lidera

O índice devolve candidatos de várias jornadas misturados. Quando uma delas
**domina o conjunto**, ela é a intenção que o prefixo revela.

Como a eleição funciona:

1. soma o score normalizado de cada jornada entre os candidatos **do índice**
   (o histórico não vota, e `Jornada.AUSENTE` não concorre);
2. a primeira só é eleita se superar a segunda por **15% em termos relativos**:
   `(primeira − segunda) / primeira ≥ 0,15`;
3. empate não elege ninguém, e aí nenhum candidato recebe o `×1,6`.

**A margem é relativa, não absoluta** — 15% à frente significa o mesmo com dois
candidatos ou com vinte.

> **Leitura de negócio.** `limite` serve a cartão e a Pix. Quando o prefixo é
> ambíguo assim, eleger uma jornada por diferença de terceira casa decimal é
> **inventar uma intenção que o cliente não demonstrou**. O empate é a resposta
> honesta: mostra as duas, deixa a pessoa escolher.

E dentro da jornada que lidera, quem empurra **produto** vem antes de quem empurra
**artigo** (o `×1,25` extra). É a diferença entre responder "o que você quer
fazer" e responder "sobre o que você quer ler". Quem digita `emprestim` quer
contratar, não entender — o artigo continua na lista, uma linha abaixo.

---

## 5. Os quatro sinais que promovem

| sinal | peso | a pergunta técnica | a pergunta de negócio |
|---|---|---|---|
| **jornada líder** | `1,6` | esta linha é da jornada que dominou o conjunto? | o que essa pessoa está tentando **fazer** agora? |
| **produto da jornada** | `1,25` | dentro da líder, esta linha tem `produtoAlvo`? | ela quer **contratar** ou quer **entender**? |
| **afinidade de jornada** | `1,5` | `min(1, pontos / 6,0)` sobre 90 dias | o que **esta** pessoa costuma querer? |
| **afinidade de produto** | `1,2` | `min(1, pontos / 4,0)` sobre 90 dias | **qual** produto, dentro do que ela quer? |
| **tendência** | `1,15` | razão entre a janela de 7 e a de 30 dias | o interesse está **esquentando agora**? |

Três observações que o peso sozinho não conta:

**As afinidades multiplicam uma à outra, não se substituem.** Querer crédito e
querer consignado são duas afirmações, e a segunda não anula a primeira.

**A afinidade de produto tem teto menor (1,2) porque acredita com menos
evidência.** O piso de confiança dela é 4,0 — dois cliques — contra 6,0 da
jornada, que são três. Evidência por produto é escassa. Dar a ela o mesmo teto da
jornada seria somar as duas concessões e deixar dois cliques num produto valerem
mais que três cliques numa jornada.

**A tendência só promove, nunca rebaixa.** Ela vive em torno de 1,0 e *pode* vir
abaixo — interesse esfriando. Mas rebaixar por tendência fria seria punir quem
demonstrou interesse há três semanas, e o decaimento da afinidade já cuida do
esquecimento com uma curva que tem física por trás. Só o lado de cima é usado:
`excedente = min(1, tendência − 1)`.

### Os sinais da população, que vêm antes de tudo isto

O score que o Solr entrega já carrega comportamento — só que **da população, não
da pessoa**. É um `boost` multiplicativo na consulta:

```
boost = sum( product(taxa_adesao_f, 10),      ← contratação pesa 10
             product(ctr_f, 3),                ← clique pesa 3
             recip(ms(NOW/HOUR, visto_em_dt), 3.16e-11, 1, 1) )   ← recência
```

E os campos têm pesos próprios: `termo_prefixo^6 termo_infixo^2 sinonimo^3` — casar
no começo do termo vale três vezes mais que casar no meio.

> **Leitura de negócio.** Adesão pesa mais que clique porque **sugestão com clique
> alto e adesão baixa é isca**, e o índice aprende a repeti-la se ninguém separar
> os pesos. A recência usa `NOW/HOUR` em vez de `NOW` por um motivo de
> infraestrutura, não de ranking: com `NOW` a chave do cache muda a cada
> milissegundo e a camada popular nunca acerta o cache.

---

## 6. O teto de 3,6, e por que não 3,0

`sugestao.peso.fator-maximo=3.6` é o teto do produto de todas as promoções. Ele
existe para conter a bolha — afinidade reforça afinidade, e sem teto um cliente
que clicou três vezes em crédito passaria a ver crédito e nada mais.

**A primeira tentativa foi 3,0, e era um defeito.** Parecia a escolha
conservadora: `1,6 × 1,25 × 1,5` dá exatamente 3,0, que era o teto que o sistema
já tinha de fato. Um teste de ranking expôs o problema em dois minutos.

Com teto 3,0, uma linha de jornada líder com produto e afinidade máxima de jornada
**já está no teto**. A afinidade por produto — o sinal construído justamente para
distinguir consignado de empréstimo dentro de `credito` — ficava multiplicando um
valor que o limite ia cortar de volta. Os dois candidatos saíam em 3,0 e o
desempate não acontecia. **O recurso não ficava fraco, ficava inerte** — e inerte
para exatamente quem tem mais comportamento registrado, que é quem ele existe para
servir.

3,6 é `1,6 × 1,25 × 1,5 × 1,2`: o produto dos quatro fatores que **precisam**
caber. A tendência (1,15) fica acima dele e é cortada no topo, e isso é a decisão
que o teto expressa: **no limite, o sinal de menos confiança é o primeiro a ser
descartado.**

O teto é declarado em vez de emergente, e esse é o conserto de um problema que a
versão anterior tinha sem saber. Antes, 3,0 estava implícito no produto dos pesos
e não escrito em lugar nenhum — acrescentar qualquer fator novo mudava o teto em
silêncio. Com o teto explícito, mexer num peso não move o máximo do sistema sem
alguém decidir isso.

---

## 7. A fadiga, o único sinal que rebaixa

```
multiplicador = 1 − fadiga × (1 − 0,6)        fadiga ∈ [0 .. 1]
```

Entre `1,0` (sem fadiga) e `0,6` (no teto). E a fadiga em si:

```java
if (cliques > 0 || impressoes <= 5)  return 0.0;
return min(15, impressoes − 5) / 15;
```

| impressões sem clique | fadiga | multiplicador |
|---|---|---|
| até 5 | `0,00` | `1,000` |
| 10 | `0,33` | `0,867` |
| 15 | `0,67` | `0,733` |
| 20 ou mais | `1,00` | `0,600` |

Quatro regras que o número não mostra:

- **um clique zera** — `cliques > 0` devolve 0,0 imediatamente;
- **piso de evidência de 5 impressões**, para oferta nova não ser penalizada antes
  do primeiro clique;
- **janela de 30 dias**, e no máximo 50 termos por cliente (os mais cansados);
- **nunca zera a linha**: o piso de 0,6 é obrigatório, e um valor 0 seria rejeitado
  na construção de `PesosDeRanking`.

### A invariante que caiu, e o que ficou no lugar

Até a rodada que introduziu a fadiga, **todos os sinais só promoviam**, e o
comentário no `Rankeador` dizia que rebaixar sem dizer por quê é como remover sem
registrar. A frase continua certa. O que mudou é que havia um defeito maior do
outro lado: sem sinal negativo, o ranking só sabia concordar consigo mesmo — uma
linha exibida quarenta vezes e nunca clicada não perdia nada, porque o `ctr` mede
a **população**, não esta pessoa.

A invariante foi substituída por uma mais fraca e verificável:

> rebaixar é permitido, **apagar não**, e todo rebaixamento vai para o rastro.

Para remover continua existindo a `Peneira`, que devolve `Descarte` com motivo.

> **Leitura de negócio.** A fadiga é o primeiro mecanismo do projeto capaz de
> **desfazer** uma preferência em vez de só deixá-la decair. Sem ela, "mostramos
> consignado para essa pessoa trinta vezes e ela nunca clicou" não era informação
> que o sistema soubesse usar. Com ela, é — e o rebaixamento aparece linha por
> linha na explicação, para que ninguém precise adivinhar se a linha está baixa
> por falta de sinal ou por sinal contrário. São duas situações que pedem ações
> opostas.

---

## 8. A ordem não é intercambiável — e dá para mostrar com número

Promover, **depois** limitar no teto, **depois** rebaixar pela fadiga.

O produto máximo das promoções é `1,6 × 1,25 × 1,5 × 1,2 × 1,15 = 4,14`, acima do
teto de 3,6. Para uma linha no máximo de tudo e totalmente cansada:

| ordem | cálculo | fator final |
|---|---|---|
| **a correta** — teto, depois fadiga | `min(3,6; 4,14) = 3,60` → `× 0,6` | **`2,16`** |
| a invertida — fadiga, depois teto | `4,14 × 0,6 = 2,48` → `min(3,6; 2,48)` | `2,48` |

Na ordem invertida a fadiga **é diluída**: o excedente que o teto ia cortar de
qualquer forma passa a absorver parte da penalidade. Hoje a diferença é de 15%.

E ela deixa de funcionar por completo se o produto das promoções chegar a
`3,6 ÷ 0,6 = 6,0` — aí `6,0 × 0,6 = 3,6`, o teto corta de volta para 3,6, e a
fadiga teria efeito **zero**. Com os pesos de hoje o produto máximo é 4,14, então
estamos longe disso; mas é exatamente para esse limite que a ordem foi escrita,
porque quem mexer num peso amanhã não vai se lembrar disto.

A consequência de errar a ordem, dita de outro jeito: **a fadiga pararia de
funcionar exatamente para quem tem mais afinidade** — que é quem mais precisa
dela, porque é quem mais recebe a mesma sugestão.

---

## 9. De onde vêm afinidade, tendência e fadiga

Nenhuma delas é calculada durante a busca. Todas chegam prontas no perfil, lidas
numa única ida ao DynamoDB — no orçamento de 30 ms não cabe uma segunda volta. É o
agendador, a cada 15 segundos, que as recalcula a partir dos eventos
(`AprenderComComportamentoCasoDeUso`).

### Pontos, e o que não gera ponto

| evento | pontos | por quê |
|---|---|---|
| `SUGESTAO_CLICADA` | `2,0` | interesse demonstrado |
| `ADESAO` | `5,0` | o único sinal que o negócio reconhece como **resultado** |
| `BUSCA_DIGITADA` | — | **nenhum** |
| `SUGESTAO_EXIBIDA` | — | nenhum; conta só para a fadiga |

**Busca não gera afinidade, e isso é decisão.** Busca é declaração de *dúvida*, e
já está representada no histórico. Transformá-la em afinidade faria quem procura
**entender** um produto ser tratado como quem o **quer** — exatamente o erro que a
distinção entre produto e artigo (seção 4) existe para evitar.

### Afinidade: decaimento por meia-vida, normalizado por um piso de confiança

```
pontos_com_idade = peso_base × 0,5 ^ (idade_em_dias / 7)
afinidade        = min(1,0 , pontos / piso_de_confiança)
```

Meia-vida de **7 dias**, janela de **90 dias**. Piso de confiança de **6,0** para
jornada e **4,0** para produto.

| comportamento | pontos hoje | afinidade de jornada |
|---|---|---|
| 1 clique | `2,0` | `0,33` |
| 2 cliques | `4,0` | `0,67` |
| 3 cliques | `6,0` | `1,00` (teto) |
| 1 adesão | `5,0` | `0,83` |
| 3 cliques, há 7 dias | `3,0` | `0,50` (metade, uma meia-vida) |

**A divisão é pelo piso de confiança, não pelo máximo da própria pessoa.** Essa
era a versão anterior, e ela fazia o primeiro clique de qualquer um valer 1,0 —
porque o máximo da pessoa era aquele próprio clique.

### Tendência: razão entre duas janelas, sem decaimento

```
neutra    = 7 dias / 30 dias  =  0,233
tendência = (pontos_7d / pontos_30d) / 0,233
```

Com atividade distribuída uniformemente, `pontos_7d / pontos_30d` dá justamente
7/30, e a tendência sai `1,0` — sem efeito. Se toda a atividade dos 30 dias
aconteceu nos últimos 7, a razão é `1,0` e a tendência vai a `4,29`, que o
`excedente = min(1, …)` corta em 1,0 e aplica o `×1,15` inteiro.

**Sem decaimento aqui, de propósito.** Aplicar meia-vida sobre uma razão entre
períodos contaria o tempo duas vezes — uma na janela, outra no decaimento — e toda
tendência pareceria de alta.

E há um **piso de 4,0 pontos na janela longa**: abaixo disso a tendência devolve
`1,0`. Dois cliques não autorizam afirmar que um interesse está esquentando.

### Fadiga: impressões e cliques por (cliente, termo)

Contadas da janela de 30 dias, direto dos eventos `SUGESTAO_EXIBIDA` e
`SUGESTAO_CLICADA`. É a razão pela qual o front **precisa** registrar a impressão
com `termosExibidos` — sem ela, metade deste sinal não existe.

---

## 10. Um exemplo calculado à mão

**Cenário.** Alguém digita `consignad`. Essa pessoa clicou **2 vezes em crédito**
nos últimos dias, e as duas foram em **consignado**. Toda a atividade dos últimos
30 dias está dentro dos últimos 7.

**O que o perfil traz:**

| grandeza | conta | valor |
|---|---|---|
| afinidade de jornada `credito` | `2 cliques × 2,0 = 4,0` → `4,0 / 6,0` | `0,67` |
| afinidade de produto `CONSIGNADO` | `4,0 / 4,0` | `1,00` (teto) |
| tendência de `credito` | `4,0 / 4,0 = 1,0` → `/ 0,233` | `4,29` → excedente `1,00` |
| fadiga | nenhum termo com mais de 5 impressões | `0,00` |

**O que o índice trouxe, já normalizado:**

| termo | jornada | produtoAlvo | score |
|---|---|---|---|
| crédito consignado | `credito` | `CONSIGNADO` | `1,00` |
| como funciona o consignado | `ajuda` | — | `0,72` |
| antecipação do consignado | `credito` | `CONSIGNADO` | `0,55` |

**Jornada líder.** Força de `credito` = `1,00 + 0,55 = 1,55`; de `ajuda` = `0,72`.
A margem é `(1,55 − 0,72) / 1,55 = 0,54`, bem acima de `0,15`. **Líder: `credito`.**

**Linha 1 — "crédito consignado":**

| passo | multiplicador | fator acumulado |
|---|---|---|
| é da jornada líder | `× 1,60` | `1,600` |
| tem produto alvo | `× 1,25` | `2,000` |
| afinidade de jornada `0,67` | `× (1 + 0,67 × 0,5) = 1,333` | `2,667` |
| afinidade de produto `1,00` | `× (1 + 1,00 × 0,2) = 1,200` | `3,200` |
| tendência, excedente `1,00` | `× (1 + 1,00 × 0,15) = 1,150` | `3,680` |
| **teto** | `min(3,6; 3,680)` | **`3,600`** ← a tendência foi cortada |
| fadiga `0,00` | `× 1,000` | `3,600` |

`relevancia_final = 1,00 × 3,600 =` **`3,60`**

**Linha 3 — "antecipação do consignado":** mesmo fator (mesma jornada, mesmo
produto), score menor.

`relevancia_final = 0,55 × 3,600 =` **`1,98`**

**Linha 2 — "como funciona o consignado":** não é da líder, jornada `ajuda` sem
afinidade, sem produto, tendência neutra. Fator `1,000`.

`relevancia_final = 0,72 × 1,000 =` **`0,72`**

**A ordem final:**

```
3,60   crédito consignado            ← o produto
1,98   antecipação do consignado
0,72   como funciona o consignado    ← o artigo, uma linha abaixo
```

### O mesmo cenário, com fadiga

Suponha que "antecipação do consignado" foi exibida **20 vezes** para essa pessoa
e nunca clicada. Fadiga `1,00`, multiplicador `0,6`:

```
fator = 3,600 × 0,6 = 2,160
relevancia_final = 0,55 × 2,160 = 1,19
```

Cai de `1,98` para `1,19` — e **continua acima** do artigo em `0,72`. É isso que
"rebaixar é permitido, apagar não" significa em número.

---

## 11. Tabela de referência

Todos em `src/main/resources/application.properties`.

| propriedade | valor | o que é |
|---|---|---|
| `sugestao.peso.jornada-lider` | `1.6` | promoção da jornada que dominou |
| `sugestao.peso.produto-da-jornada` | `1.25` | produto antes de artigo, dentro da líder |
| `sugestao.peso.afinidade-maxima` | `1.5` | teto da afinidade de jornada |
| `sugestao.peso.afinidade-de-produto-maxima` | `1.2` | teto da afinidade de produto |
| `sugestao.peso.tendencia-maxima` | `1.15` | teto da tendência |
| `sugestao.peso.fator-maximo` | `3.6` | teto do produto de todas as promoções |
| `sugestao.peso.fadiga-minima` | `0.6` | piso do multiplicador da fadiga |
| `sugestao.peso.margem-para-liderar` | `0.15` | margem relativa para eleger a líder |
| `...afinidade.piso-de-confianca` | `6.0` | pontos para afinidade de jornada = 1,0 |
| `...afinidade.piso-de-confianca-do-produto` | `4.0` | idem, para produto |
| `...afinidade.meia-vida-dias` | `7` | meia-vida do decaimento |
| `...afinidade.janela-dias` | `90` | janela de acumulação |
| `...afinidade.tendencia-curta-dias` | `7` | janela curta da tendência |
| `...afinidade.tendencia-longa-dias` | `30` | janela longa da tendência |
| `...afinidade.piso-da-tendencia` | `4.0` | pontos mínimos para a tendência valer |
| `...fadiga.impressoes-minimas` | `5` | piso de evidência |
| `...fadiga.impressoes-para-o-teto` | `20` | onde a fadiga chega a 1,0 |
| `...fadiga.janela-dias` | `30` | janela da fadiga |
| `...fadiga.tamanho-maximo` | `50` | termos cansados guardados por cliente |

As validações estão nos construtores de `PesosDeRanking`, `PesosDeAfinidade` e
`PesosDeFadiga`, e elas **recusam a subida**, não avisam. Peso de promoção abaixo
de 1 é rejeitado porque rebaixaria o candidato por um caminho que não registra
motivo. `fadiga-minima` em 0 é rejeitado porque zeraria a linha, que é remover sem
dizer por quê.

---

## 12. O que a pontuação não faz

**Não remove.** Rebaixar é tudo que ela sabe fazer. Quem remove é a `Peneira` —
segmento errado, conta inadimplente, produto já contratado, sem elegibilidade — e
ela devolve `Descarte` com motivo. Pontuação baixa e remoção são coisas diferentes,
e misturá-las esconderia a razão.

**Não escolhe o tamanho nem a composição da lista.** Depois do ranking vem o
`Misturador`, com cotas por jornada e por origem. É ele que garante que a lista
continue mostrando caminhos que o ranking sozinho teria enterrado — e é o segundo
dos três freios contra a bolha, junto com o teto de 3,6 e a fadiga.

**Não aprende sozinho.** Todos os números desta página foram escolhidos por alguém
e estão num arquivo de propriedades. Mudá-los é um commit, revisável — e existe um
portão que mede a consequência: `.\scripts\avaliar-ranking.ps1` calcula NDCG@5 e
NDCG@8 sobre **32 consultas julgadas** e **fecha** se o @5 cair além da tolerância
contra a linha de base versionada. A rubrica está em
[`avaliacao/README.md`](../avaliacao/README.md).

---

## 13. Como ver os números na prática

A resposta tem um modo que mostra o cálculo:

```
GET /v1/sugestoes?q=consignad&explicar=true
```

O bloco `explicacao` traz `jornadaLider`, `candidatosDoIndice`,
`candidatosDoHistorico`, `promovidosAEvento`, a lista de `descartes` com motivo, e
`linhas` com `relevancia` e `fadiga` **separadas** por linha. A fadiga vem separada
de propósito: sem isso a resposta mostraria um número só e ninguém saberia se a
linha está baixa por não ter sido promovida ou por ter sido rebaixada.

> **`explicar` nasce desligado em produção**, e por privacidade, não por
> desempenho: a lista de descartes diz quais produtos aquele cliente **não pode
> contratar**. Só `true` e `1` ligam — qualquer outro valor desliga, para que uma
> consulta mal escrita não vaze nada. O segundo freio é
> `sugestao.explicacao-habilitada`.

Para ver os dois lados do sinal lado a lado:

```powershell
.\requisicoes\verificar-comportamento.ps1
```

Ele cria afinidade e fadiga ao vivo e mostra a mesma consulta antes e depois.

### Onde o código está

| arquivo | o que contém |
|---|---|
| `dominio/Rankeador.java` | a fórmula inteira, e a nota sobre a ordem |
| `dominio/PesosDeRanking.java` | os pesos, o teto, e por que 3,6 e não 3,0 |
| `dominio/agregado/PesosDeAfinidade.java` | decaimento, pisos de confiança, tendência |
| `dominio/agregado/PesosDeFadiga.java` | `fadigaDe(impressoes, cliques)` |
| `adaptador/saida/agregado/AgregadosEmMemoria.java` | onde os pontos são somados |
| `adaptador/saida/solr/ConsultaDeCandidatos.java` | o `boost` da população e os pesos de campo |

E [`docs/decisoes.md`](decisoes.md) tem o registro das decisões com o que foi
**recusado** em cada uma.
