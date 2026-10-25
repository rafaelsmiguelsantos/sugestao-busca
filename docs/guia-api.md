# Chamando a Sugestão de Busca

Guia de integração · v1

A pergunta que esta API responde não é "quais palavras completam o que a pessoa
digitou". É **qual oferta apresentar a esta pessoa agora** — com o estado da
conta, a faixa de relacionamento e o comportamento dela entrando na decisão, e com
o motivo de cada ausência registrado.

São três passos para a primeira chamada. Depois deles, o que acontece por dentro —
e os dois campos de evento que deixaram de ser decorativos, que é a parte que mais
muda o seu código.

> **Duas versões deste guia.** Este arquivo é o canônico: ele vive no repositório,
> aparece no diff de qualquer PR e é o que se corrige quando o código muda. Há
> também uma versão em página, com o mesmo conteúdo e mais cuidado visual, útil
> para compartilhar link ou imprimir em PDF pelo navegador:
> [`guia-api.html`](guia-api.html). Quando os dois divergirem, **este** está certo.

---

## Índice

1. [Consiga um token](#01--consiga-um-token)
2. [Faça a requisição](#02--faça-a-requisição)
3. [Leia a resposta](#03--leia-a-resposta)
4. [O que acontece por dentro](#04--o-que-acontece-por-dentro)
5. [Por que uma oferta não apareceu](#05--por-que-uma-oferta-não-apareceu)
6. [Quando a pessoa erra a digitação](#06--quando-a-pessoa-erra-a-digitação)
7. [Devolva o que aconteceu](#07--devolva-o-que-aconteceu)
8. [Cadastre a oferta da sua jornada](#08--cadastre-a-oferta-da-sua-jornada)
9. [Os códigos de resposta](#09--os-códigos-de-resposta)
10. [Antes de ir para produção](#10--antes-de-ir-para-produção)

---

## 01 · Consiga um token

Todas as rotas são protegidas. O serviço é servidor de recurso OAuth 2.0: ele
*valida* o token e não emite nenhum. Em produção quem emite é o provedor de
identidade; em desenvolvimento, um script fora da aplicação.

```powershell
# defina o segredo uma vez (não há valor padrão; sem ele nada sobe)
$env:SUGESTAO_SEGREDO = 'qualquer-coisa-com-ao-menos-32-bytes-aqui'

# o app, em nome de uma pessoa
.\scripts\gerar-token.ps1 -Tipo cliente -ClienteId pf-pix-recusado

# uma jornada de produto, dona das próprias ofertas
.\scripts\gerar-token.ps1 -Tipo jornada -Jornada credito

# operação: carga de catálogo e métricas
.\scripts\gerar-token.ps1 -Tipo operacao
```

Há três tipos de chamador, e a diferença decide o que você pode fazer.

| chamador | reivindicações no token | escopos |
|---|---|---|
| **cliente**<br>o app, por uma pessoa | `sub`, `segmento`, `relacionamento` | `sugestao:ler`, `eventos:escrever` |
| **jornada**<br>um serviço de produto | `jornada` | `ofertas:escrever`, `ofertas:ler` |
| **operação**<br>carga de catálogo e ops | — | `ofertas:todas`, `aprendizado:executar`, `metricas:ler` |

> **Segmento e faixa vêm no token, não no corpo nem em cabeçalho.** Não é detalhe
> de autenticação: eles são atributos de coorte, conhecidos antes de qualquer
> leitura de perfil, e é isso que permite a consulta ao índice arrancar junto da
> leitura do perfil em vez de atrás dela. É o que faz o orçamento de 30 ms fechar —
> e agora eles vêm *provados*, não ditos.

`ofertas:todas` e `metricas:ler` têm nome próprio de propósito. O primeiro é o
privilégio de cadastrar em qualquer jornada; o segundo lê a métrica de qualidade de
busca, que não carrega prefixo nem cliente mas carrega a *forma* do serviço — taxa
de zero-resultado por faixa de prefixo e volume de descarte por motivo contam a
quem perguntar onde este serviço é frágil e quais produtos pouca gente pode
contratar. Numa auditoria, procurar quem recebe cada um é uma busca de uma linha.

---

## 02 · Faça a requisição

```bash
curl -s "http://localhost:8080/v1/sugestoes?q=emprestim" \
     -H "Authorization: Bearer $TOKEN"
```

| parâmetro | o que faz |
|---|---|
| `q` | o prefixo digitado, cru. A normalização é regra de domínio e acontece dentro. |
| `limite` | opcional. Pedir `N` devolve **exatamente os N primeiros** de pedir o máximo: o teto corta o fim da lista, nunca reordena o começo. |
| `explicar` | opcional, e desligado em produção. Devolve o rastro da decisão — inclusive os descartes, que dizem quais produtos o cliente não pode contratar. |

> **Esta rota não devolve erro de aplicação.** Prefixo de um caractere, `q`
> ausente, `limite=NaN`, faixa inexistente — tudo isso é `200` com lista vazia,
> limitada ou degradada. Escreva tratamento de **lista vazia**, não de `4xx`. Há
> duas fronteiras, e as duas são do servidor: acima de ~7,9 KB de URL o container
> responde `400` antes de a aplicação existir, e acima do limite de taxa vem `429`.

### O limite de taxa, e o que fazer com ele

Toda resposta traz os cabeçalhos — não só o `429`. Um cliente que só descobre o
limite ao ser recusado não tem como se comportar antes.

```http
RateLimit-Limit:     600
RateLimit-Remaining: 412   # aumente o debounce quando a sobra encolher
RateLimit-Reset:     38

# estourou:
HTTP 429  application/problem+json
Retry-After: 50            # obedeça. Tentar em laço piora o que o limite contém.
```

**600 por minuto é calibrado para deixar a pessoa digitar**, não para impedir
raspagem: quem digita "financiamento imobiliário" rápido gasta ~25 requisições em
segundos. Com `debounce` de 120 ms e `AbortController`, um usuário real não chega
perto do teto. Se o seu cliente chega, o problema é o cliente — provavelmente uma
requisição por tecla sem cancelar a anterior.

---

## 03 · Leia a resposta

```json
{
  "prefixo": "emprestim",
  "degradada": false,

  "melhorOferta": {
    "ofertaId":    "OFR-CREDITO-EMPRESTIMO",
    "produtoAlvo": "EMPRESTIMO",
    "jornada":     "credito",
    "destino":     "/app/credito/emprestimo-pessoal",
    "posicao":     0,
    "relevancia":  2.0
  },

  "itens": [
    { "posicao": 0, "ofertaId": "OFR-CREDITO-EMPRESTIMO",
      "termo": "empréstimo pessoal", "origem": "CATALOGO", "relevancia": 2.0 },
    { "posicao": 1, "ofertaId": "",
      "termo": "contratar emprestimo", "origem": "POPULAR", "relevancia": 0.15 }
  ]
}
```

`"degradada": true` significa que o perfil não carregou — a lista ainda é útil, mas
é a popular do segmento. `"ofertaId": ""` significa **"esta linha não é oferta"**,
nunca "oferta sem id".

### `melhorOferta`

A oferta acionável de maior ranking **entre as entregues**. É redundante de
propósito: sem ela, você varreria `itens`, adivinharia que oferta é o item com
`ofertaId` preenchido, e confiaria que a ordem do array é o ranking. Três
suposições sobre a nossa implementação, dentro do seu código.

### Ausente não é erro

Significa que para esta pessoa e este prefixo **não há oferta elegível** — conta em
atraso, produto já contratado, sem elegibilidade, ou fora da faixa. Não insista com
outro prefixo: a ausência é a resposta.

> **Não case nada contra `relevancia`.** Ela vive entre ~0,1 e 3,6, o teto é
> configurável e os fatores que a compõem mudam entre versões — um cliente que
> testa `relevancia > 2` para decidir se destaca a oferta quebra na próxima
> calibração. Use a **posição**: o `itens[0]` é o melhor que o ranking tinha, e
> `melhorOferta` é a melhor oferta acionável. O número está na resposta para ser
> inspecionado, não para ser comparado.

> **E ela pode descer.** Uma linha que esta pessoa viu muitas vezes e nunca clicou
> é *rebaixada*. É o único sinal do sistema que rebaixa, e existe porque sem ele o
> ranking só sabe concordar consigo mesmo: o `ctr` do índice mede a **população**,
> não esta pessoa.

### O que cada origem significa

| origem | de onde veio | na tela |
|---|---|---|
| `EVENTO` | gatilho recente no perfil (Pix recusado há duas horas) | merece destaque; tem cota de uma linha e abre a lista |
| `HISTORICO` | o que esta pessoa já buscou | ícone de relógio |
| `CATALOGO` | oferta curada ou cadastrada por uma jornada | a linha que carrega `ofertaId` |
| `POPULAR` | o que gente de coorte parecida busca | sugestão de texto, sem produto |

---

## 04 · O que acontece por dentro

Entre o `GET` e a resposta há cinco etapas, e a ordem não é intercambiável. O
orçamento inteiro é de 30 ms, contado do início da requisição.

| etapa | o que faz | custo |
|---|---|---|
| **Leque** | Índice e perfil são lidos **ao mesmo tempo**, em threads virtuais. Sem nova tentativa: a próxima tecla já vem a caminho. | 18 ms índice · 8 ms perfil |
| **Classificar** | Gatilho ativo no perfil promove um candidato a `EVENTO`. Cota de uma linha: ele é oportuno, não dominante. | CPU |
| **Peneira** | O perfil remove o que o cliente não pode contratar — e registra o motivo. **Booleano, não peso.** | ~2 ms |
| **Ranquear** | Normaliza a escala, elege a jornada líder, aplica o comportamento desta pessoa. | CPU |
| **Misturar** | Deduplica, reserva a linha do evento, respeita a cota do histórico, corta no limite. | CPU |

> **Elegibilidade não é nota baixa — é remoção.** Se fosse peso, um prefixo muito
> casado faria a oferta vetada subir de volta. Então a melhor oferta não é a de
> maior nota: é a de maior nota *entre as que sobreviveram aos portões*, e os
> portões vêm antes.

### A conta do ranking

```
relevancia_final = relevancia_normalizada × min(3.6, promocoes) × fadiga

promocoes = 1.00
          × 1.60   se a jornada do candidato é a líder
          × 1.25   e, dentro dela, se ele empurra um produto
          × (1 + afinidade_jornada × 0.50)    0 a 1
          × (1 + afinidade_produto × 0.20)    0 a 1, piso de evidência menor
          × (1 + tendencia_excedente × 0.15)  janela de 7 d contra a de 30 d

fadiga = 1.00 ... 0.60   exibida muitas vezes e nunca clicada

teto declarado: 3.60. A ordem importa — promover, limitar, rebaixar.
```

Os números que você vê na resposta *são* essa fórmula. A relevância normalizada do
primeiro colocado é sempre 1,0, então:

| linha observada | conta |
|---|---|
| `r=2,0` | 1,0 × 1,6 × 1,25 — líder + produto, sem comportamento |
| `r=3,0` | × 1,5 — a mesma pessoa, com afinidade de jornada |
| `r=3,6` | × 1,2 — e com afinidade no *produto* específico |
| `r=1,8` | 3,6 × 0,6 — no teto das promoções e com fadiga no piso |
| `r=0,15` | termo do log: normalizado e **sem fator nenhum** |

> **O teto é declarado, não emergente — e isso conserta um defeito.** Ele era 3,0
> porque `1,6 × 1,25 × 1,5` dá 3,0, e esse número não estava escrito em lugar
> nenhum. Com ele, uma linha com afinidade máxima de jornada *já estava no teto*, e
> a afinidade por produto multiplicava um valor que o limite cortava de volta: os
> dois produtos saíam em 3,0 e o desempate não acontecia. O sinal não ficava fraco,
> ficava **inerte**.

> **O comportamento é lido dentro da requisição, não mapeado.** A afinidade que
> entra no fator foi calculada antes, por outra rodada. Mapear acontece *entre* uma
> busca e a próxima — porque o sinal que mais vale, o clique, acontece depois da
> resposta.

---

## 05 · Por que uma oferta não apareceu

Com `?explicar=true`, cada ausência vem com motivo. Numa camada de sugestão, a
pergunta que chega do negócio quase nunca é por que uma linha apareceu — é por que
a outra não apareceu.

| motivo | o que aconteceu |
|---|---|
| `CONTA_INADIMPLENTE` | crédito novo para quem está em atraso. O caminho certo para essa pessoa é a renegociação, que entra por gatilho. |
| `CONTA_NAO_OPERANTE` | operação que exige conta ativa. Conta desconhecida cai aqui junto com a bloqueada: falha fechada. |
| `SEM_ELEGIBILIDADE` | sem concessão explícita, a oferta não aparece. |
| `PRODUTO_JA_CONTRATADO` | não se oferece de novo o que a pessoa já tem. |
| `ABAIXO_DO_PISO` | termo do log buscado por menos de 30 clientes distintos. Devolvê-lo contaria algo sobre quem o buscou. |
| `TERMO_DUPLICADO` | duas linhas com o mesmo texto; sobrou a de maior ranking. |
| `COTA_DE_HISTORICO`<br>`ALEM_DO_LIMITE` | coube na peneira, não coube na lista. |

> **Em produção `explicar` fica desligado.** A lista de descartes diz quais produtos
> aquele cliente não pode contratar, e isso é informação sobre a pessoa, não sobre a
> busca. A leitura do parâmetro falha fechada: só `true` e `1` ligam.

---

## 06 · Quando a pessoa erra a digitação

Uma letra errada zerava a lista. Em teclado de celular, isso é a maioria dos casos
ruins — e não é um caso de borda: `emprestmo`, `enprestimo` e `consignadu`
devolviam **zero itens**.

| prefixo | antes | agora | `explicacao.tolerancia` |
|---|---|---|---|
| `emprestimo` | 4 | 4 | `NAO_PRECISOU` |
| `emprestmo` | 0 | 4 | `ACIONADA_COM_GANHO` |
| `enprestimo` | 0 | 4 | `ACIONADA_COM_GANHO` |
| `consignadu` | 0 | 3 | `ACIONADA_COM_GANHO` |
| `car` | 8 | 8 | `NAO_ELEGIVEL` |

**Custa zero quando não precisa.** É uma *segunda consulta*, disparada só quando a
primeira trouxe menos de três candidatos. Como cláusula na primeira, o fuzzy iria
para os campos de prefixo — onde cada palavra já está expandida em dezenas de
gramas — e *toda* busca pagaria por um recurso que só importa quando a busca
falhou.

**Mínimo de 5 letras.** Com quatro, distância de edição 1 alcança um vizinho
enorme: `card` casa `carro` e `cara`. Lista errada com confiança é pior que lista
vazia — oferecer consórcio a quem digitou "consignado" torto não é recuperação
simpática, é produto errado.

> **Isto conserta erro de digitação, não falta de vocabulário.** `grana` continua
> devolvendo zero, e nenhuma distância de edição conserta sinônimo que não existe no
> catálogo. Se o seu público usa um termo que a lista não conhece, o caminho é
> cadastrar o sinônimo — não esperar que a busca adivinhe.

Para o seu código, nada muda. O campo `tolerancia` aparece só com `explicar=true`,
e está lá para diagnóstico: `ACIONADA_SEM_GANHO` repetido num prefixo é sinal de
vocabulário faltando, não de busca quebrada.

---

## 07 · Devolva o que aconteceu

A busca não registra nada por conta própria — a leitura nunca escreve. É o app que
manda impressão, clique e adesão, e é **a impressão com posição** que torna o
aprendizado possível: sem ela o modelo aprende que a primeira linha é a melhor,
quando ela é apenas onde a linha estava.

```http
POST /v1/eventos          → 202, e acabou. Ninguém espera.
Authorization: Bearer $TOKEN_DO_CLIENTE
Content-Type: application/json
```

```json
[
  { "tipo": "BUSCA_DIGITADA", "termo": "emprestimo pessoal" },

  { "tipo": "SUGESTAO_EXIBIDA",
    "termosExibidos": ["empréstimo pessoal", "crédito consignado"],
    "listaDegradada": false },

  { "tipo": "SUGESTAO_CLICADA", "termo": "empréstimo pessoal",
    "posicao": 0, "jornada": "credito", "produto": "EMPRESTIMO" }
]
```

### Dois campos deixaram de ser decorativos

Esta é a mudança mais acionável deste guia.

**`produto` no clique.** Sempre existiu no contrato e não era lido por nada. Agora
ele é o que distingue **consignado de empréstimo**: os dois são da jornada
`credito`, e sem este campo clicar num promove o outro na mesma medida. Mandar só
`jornada` faz a personalização parar no nível "esta pessoa quer crédito", sem nunca
chegar em "e quer consignado".

**`termosExibidos` completo.** Antes servia só de denominador do CTR agregado.
Agora é o que alimenta o sinal negativo: linha exibida muitas vezes e nunca clicada
perde posição **para esta pessoa**. Mandar apenas o termo clicado inverte o
significado — o sistema passa a achar que nada mais foi mostrado, e nada nunca é
rebaixado.

### O resto do contrato

- **Não existe `clienteId` no corpo.** Ele vem do `sub` do token. Aceitá-lo no
  corpo deixaria qualquer chamador escrever comportamento em nome de qualquer
  pessoa — e como é esse identificador que a agregação conta para medir clientes
  distintos, quem controlasse o corpo controlaria o piso de k-anonimato.
- **Mande `listaDegradada` junto.** Quando a resposta veio com `degradada: true` o
  perfil não carregou, e registrar aquela impressão como personalizada ensina ruído
  ao aprendizado.
- **Use `keepalive` no clique.** O clique navega; sem ele o navegador cancela a
  requisição ao trocar de página, e é justamente o sinal mais valioso que se perde.
- **Busca não vira afinidade.** Clique vale 2 pontos, adesão vale 5, busca vale
  zero — quem procura entender um produto não é quem o quer.
- **O sistema não acredita no primeiro clique, nem na primeira impressão.** Um
  clique dá 0,33 de afinidade de jornada, não o máximo. E cinco impressões sem
  clique não rebaixam nada: sem esse piso, oferta nova receberia impressão, ainda
  não teria clique, seria penalizada, afundaria e nunca chegaria a receber o clique
  que a tiraria da penalidade.
- **Um clique zera a fadiga — não desconta.** A pergunta que ela responde é "esta
  pessoa ignora esta linha?", e um clique responde não, independentemente de
  quantas impressões vieram antes.

> **Não há como mandar evento com data no passado.** O `ocorridoEm` é atribuído
> pelo servidor na ingestão, de propósito: relógio de aparelho erra, e um evento
> adiantado renderia mais que um honesto. A consequência para quem testa é que
> *reproduzir um histórico de trinta dias pela API é impossível* — as janelas longas
> só se observam deixando o tempo passar, ou nos testes de unidade, que injetam
> relógio.

### PII é barrada na entrada

```json
[
  { "tipo": "BUSCA_DIGITADA", "termo": "pagar boleto 529.982.247-25" },
  { "tipo": "BUSCA_DIGITADA", "termo": "emprestimo pessoal" }
]
```

```json
{ "aceitos": 1, "descartados": 1, "motivos": { "CPF": 1 } }
```

CPF com dígito verificador válido, cartão que passa no Luhn, e-mail e chave Pix em
UUID são recusados. **A resposta nunca devolve o termo barrado** — devolvê-lo
recriaria exatamente o vazamento que o saneamento evitou.

---

## 08 · Cadastre a oferta da sua jornada

É o lado de escrita do catálogo, e ele tem o perfil de falha oposto ao da busca:
cada recusa é informação que você precisa receber inteira. Precisa de um token de
**jornada**.

```http
POST /v1/ofertas          → 201 criada · 200 substituída, + Location
```

```json
{
  "ofertaId":    "OFR-CREDITO-CONSORCIO",
  "termo":       "consórcio de imóvel",
  "sinonimos":   ["consorcio", "carta de credito"],
  "rotulo":      "sem juros, com parcelas fixas",
  "destino":     "/app/credito/consorcio",
  "jornada":     "credito",
  "produtoAlvo": "CONSORCIO_IMOVEL",
  "vetaInadimplencia": true,
  "exigeContaAtiva":   true,
  "segmentos":       ["PF"],
  "relacionamentos": ["VAREJO", "UNICLASS"],
  "vigenciaInicio": "2026-10-01T00:00:00Z",
  "vigenciaFim":    "2027-10-01T00:00:00Z"
}
```

A resposta devolve o **estado gravado**, não um eco: `termo` volta normalizado
(`consorcio de imovel`), `sinonimos` volta deduplicado, e os contadores de
desempenho voltam zerados.

### O que o cadastro recusa

`ctr`, `taxaAdesao`, `clientesDistintos`. São os números que mais mexem no ranking
e nenhum é declarável — eles são medidos a partir de impressão e clique reais. Se o
cadastro os aceitasse, o ranking mediria a confiança de quem preenche formulário.

### A oferta nasce visível

Com os contadores em zero, e aparece na busca no mesmo instante. O piso de
k-anonimato vale só para termo vindo do log: não há ninguém para reidentificar num
nome de produto.

### Idempotente por identificador

Mesmo `ofertaId` reenviado responde `200` em vez de `201`. Não é conveniência: é o
que permite a sua jornada reenviar depois de um timeout sem precisar saber se a
primeira tentativa chegou. E um `200` onde você esperava `201` avisa que o
identificador colidiu.

> **O escopo diz que você pode cadastrar; a jornada diz o quê.** `ofertas:escrever`
> autoriza cadastrar alguma coisa; a reivindicação `jornada` do token diz o quê.
> Cadastrar na jornada de outro time dá `403`, com o seu token válido — sem isso,
> seguros publicaria oferta de crédito com rótulo e destino escolhidos por ela.

### Erro de cadastro: todas as violações de uma vez

```json
{
  "status": 422,
  "title": "Oferta invalida",
  "detail": "3 campos recusados",
  "violacoes": [
    { "campo": "destino", "codigo": "formato",
      "mensagem": "caminho relativo comecando com '/', sem esquema e sem '//'" },
    { "campo": "segmentos", "codigo": "fora_do_dominio",
      "mensagem": "valor 'PESSOA_FISICA' nao reconhecido; aceitos: PF, PJ" },
    { "campo": "vigenciaFim", "codigo": "ordem_invalida",
      "mensagem": "deve ser posterior a vigenciaInicio" }
  ]
}
```

Case contra `codigo`, nunca contra `mensagem`: a redação muda quando melhora, o
código é contrato. E a lista vem completa de propósito — recusar no primeiro
problema transformaria a integração numa sequência de tentativas, uma por campo.

Dois casos valem comentário. **`destino` tem de ser relativo:** o destino volta
para o app e o app navega para ele, então aceitar `https://` faria do cadastro um
redirecionador aberto com o nome do banco na frente. E **`jornada: "busca"` é
recusada:** é o balde dos termos sem jornada, e uma oferta ali nunca lideraria
jornada nenhuma nem receberia afinidade.

---

## 09 · Os códigos de resposta

| código | quando | o que fazer |
|---|---|---|
| `200` / `201` | sucesso. `201` no cadastro criou; `200` substituiu. | um `200` onde esperava `201` avisa que o identificador colidiu |
| `202` | eventos aceitos na fila | nada; ninguém está esperando |
| `400` | não consegui ler o que você mandou | corrigir o serializador |
| `401` | token ausente, expirado ou inválido | renovar o token |
| `403` | escopo insuficiente, **ou** oferta de outra jornada | `type` distingue os dois casos |
| `404` | oferta não cadastrada | conferir o identificador |
| `415` | `Content-Type` não suportado | mandar `application/json` |
| `422` | li perfeitamente e recusei | corrigir o *valor*, com a lista de violações |
| `429` | limite de taxa estourado | respeitar `Retry-After`; se acontece com usuário real, o `debounce` está frouxo |
| `503` | índice indisponível no cadastro | reenviar; o cadastro é idempotente por `ofertaId` |

A diferença entre `400` e `422` poupa tempo: o primeiro diz que a correção é no
serializador, o segundo que é no valor. E `403` em rota que não existe não é
engano: toda rota nasce fechada, e de passagem não se conta a quem sonda quais
caminhos existem.

Todo erro sai em `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)),
com `type` estável por classe de problema.

---

## 10 · Antes de ir para produção

- **CORS.** Sem a origem do seu app autorizada, a API responde normalmente a
  `curl` e é bloqueada pelo navegador — o `200` chega e o JavaScript nunca vê o
  corpo. No DevTools isso aparece como erro de rede, o que manda investigar o lugar
  errado. E `localhost` e `127.0.0.1` são origens *diferentes*.
- **Debounce de ~150 ms.** Dez teclas não são dez requisições.
- **Resposta fora de ordem.** A de `cons` pode chegar depois da de `consignado` e
  sobrescrever a lista certa. Em rede local quase nunca acontece; em 4G acontece
  sempre. Cancelar ajuda e *não* resolve — o cancelamento é uma corrida. Compare o
  campo `prefixo` que volta com o que está no campo agora: ele existe para isso.
- **Mínimo de 2 caracteres no cliente**, para não gastar ida e volta.
- **Lista vazia é "nada a mostrar"**, nunca falha.
- **Conjunto de cabeçalhos estável.** Mudar o conjunto entre requisições invalida o
  cache do preflight, e aí cada busca vira duas.
- **Mande `produto` no clique e `termosExibidos` completo.** São os dois campos que
  deixaram de ser decorativos.
- **Leia `RateLimit-Remaining`.** É o aviso antes do `429`.

### O que vale medir do seu lado

O serviço expõe taxa de zero-resultado, distribuição da posição clicada e volume de
descarte por motivo em `/actuator/metrics`, com escopo `metricas:ler`. Dois números
dali valem olhar na sua integração:

- se a **posição clicada** concentra em 3 e 4 em vez de 0 e 1, as duas primeiras
  linhas estão ocupando lugar que não merecem;
- se **`SEM_OFERTA`** cresce, as pessoas estão encontrando texto e não produto.

---

## Medido nesta implementação

p50 de 9,9 ms e p99 de 32 ms; 546 req/s com 40 chamadas em paralelo e nenhuma
falha, em 400 requisições. Cliente, serviço e índice na mesma máquina — serve para
comparar mudanças, **não para dimensionar capacidade**.

Verificado: 173 testes de unidade, 56 asserções de contrato e 47 sondas
adversariais, mais um caçador de teste instável que roda a suíte sob contenção de
CPU ([`scripts/cacar-flaky.ps1`](../scripts/cacar-flaky.ps1)). Três asserções deste
projeto afirmavam igualdade exata sobre valor decaído e passavam por sorte; a última
só apareceu com os doze núcleos ocupados.

O raciocínio por trás de cada decisão, com o que foi recusado e por quê, está em
[`decisoes.md`](decisoes.md).
