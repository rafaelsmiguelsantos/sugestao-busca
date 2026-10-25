# sugestao-busca

Camada de sugestão de busca parametrizada por perfil e comportamento, sobre Solr.
Implementa o desenho de solução *Sugestão de busca no app*.

Este repositório é o ambiente de desenvolvimento local do POC. Nada aqui usa dado
real: o vocabulário é sintético e os perfis são falsos, em memória.

A pergunta que o serviço responde não é "quais palavras completam o que a pessoa
digitou". É **qual oferta apresentar a esta pessoa agora** — com o estado da
conta, a faixa de relacionamento e o comportamento dela entrando na decisão, e
com o motivo de cada ausência registrado.

## A API

| rota | o que faz | escopo exigido |
|---|---|---|
| `GET /v1/sugestoes` | a lista, e `melhorOferta` com o ID acionável no topo | `sugestao:ler` |
| `POST /v1/ofertas` | a jornada dona cadastra a própria oferta, idempotente | `ofertas:escrever` |
| `GET /v1/ofertas/{id}` | o estado gravado, para conferência | `ofertas:ler` |
| `POST /v1/eventos` | impressão, clique e adesão — com posição | `eventos:escrever` |
| `POST /v1/aprendizado/rodar` | roda a agregação agora (só no perfil `local`) | `aprendizado:executar` |
| `GET /actuator/health` | saúde | — pública |

Tudo o mais é negado por padrão. Ver *Autenticação e autorização* abaixo.

```jsonc
// GET /v1/sugestoes?q=consignad   (cliente elegível)
{
  "prefixo": "consignad",
  "degradada": false,
  "melhorOferta": {
    "ofertaId": "OFR-CREDITO-CONSIGNADO",   // o que a jornada aciona
    "produtoAlvo": "CONSIGNADO",
    "jornada": "credito",
    "posicao": 0                            // liga a resposta à impressão
  },
  "itens": [ /* cada linha com ofertaId quando é oferta, "" quando não é */ ]
}
```

A mesma letra, duas contas, e é aqui que o desenho se justifica:

| cliente | jornada líder | topo da lista | `melhorOferta` |
|---|---|---|---|
| elegível | `credito` | **crédito consignado** (o produto) | `OFR-CREDITO-CONSIGNADO` |
| sem elegibilidade | `ajuda` | como funciona o consignado (o artigo) | — |

A segunda linha não é a API falhando: quem não pode contratar recebe a
explicação, não a oferta. E `melhorOferta` ausente é **informação**, não erro.

**Quatro formas de bater nos endpoints**, as mesmas chamadas:
[`requisicoes/sugestoes.http`](requisicoes/sugestoes.http) (REST Client do VS
Code), [`requisicoes/bater.ps1`](requisicoes/bater.ps1) (terminal),
[`requisicoes/postman-exploracao.json`](requisicoes/postman-exploracao.json)
(Postman, para clicar e explorar) e
[`requisicoes/postman-sugestao-busca.json`](requisicoes/postman-sugestao-busca.json)
(newman, 56 asserções).

**As decisões de desenho, com o que foi recusado e por quê, estão em
[`docs/decisoes.md`](docs/decisoes.md).**

## O que já está instalado nesta máquina

O projeto foi montado sem privilégio de administrador, e por isso tudo abaixo do
Docker mora em pastas do usuário, fora do `Program Files`.

| | versão | onde |
|---|---|---|
| Temurin JDK | 25.0.4 LTS | `C:\Users\migue\tools\jdk-25` |
| Apache Solr | 10.0.0 (com módulo `ltr`) | `C:\Users\migue\tools\solr-10.0.0` |
| PortableGit | 2.56.0 | `C:\Users\migue\tools\PortableGit` |
| Maven | via `mvnw`, baixado na primeira build | — |
| Docker Desktop | 29.9.2, com WSL 2 | instalado em 24/10/2026 |

O JDK 17 continua sendo o padrão do sistema. O Solr 10 exige Java 21+, então os
scripts apontam o `SOLR_JAVA_HOME` para o JDK 25 apenas no processo que sobem.

## Subir o ambiente

### Uma vez só: política de execução

O padrão do Windows é `Restricted` e nenhum `.ps1` deste repositório roda. Não há
política de grupo por cima, então isto resolve de vez — terminal normal, sem admin:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

`RemoteSigned` deixa rodar script local e continua exigindo assinatura em script
baixado. Se preferir não mudar a política, dá para rodar cada um avulso:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\solr-local.ps1 start
```

### Solr

Do zip, sem container e sem admin:

```powershell
.\scripts\solr-local.ps1 start
.\scripts\criar-colecao.ps1
```

Painel em <http://localhost:8983/solr>. Para derrubar: `.\scripts\solr-local.ps1 stop`.

### A aplicação

```powershell
$env:JAVA_HOME = 'C:\Users\migue\tools\jdk-25'
.\mvnw spring-boot:run
```

Saúde em <http://localhost:8080/actuator/health>.

## Solr em Docker

**O Docker Desktop passou a existir nesta máquina em 24/10/2026** — servidor
29.9.2, com WSL 2 e a distribuição `docker-desktop`. Até então não havia runtime
de container nenhum aqui, e este README dizia isso; o `scripts/habilitar-docker.ps1`
existe por causa daquele momento e **não é mais necessário**. Fica no repositório
como o registro de um pré-requisito que já foi resolvido.

O `compose.yaml` aponta para `solr:10.0.0` com o módulo `ltr`, na mesma porta e
com a mesma coleção:

```powershell
docker compose up -d
.\scripts\criar-colecao.ps1
```

O `criar-colecao.ps1` é o mesmo nos dois caminhos — ele fala com `localhost:8983`
e não sabe se do outro lado está o zip ou o container. Trocar de um para o outro
não muda nada no projeto, mas **os dois não sobem juntos**: disputam a porta 8983.
Antes de subir o container, `.\scripts\solr-local.ps1 stop`.

Uma ressalva honesta: tudo o que está neste README foi verificado contra o Solr
do zip. O caminho do container está escrito e nunca foi executado.

### Semear e conferir

```powershell
.\scripts\semear-vocabulario.ps1 -Limpar
.\scripts\conferir-piso.ps1
```

O `conferir-piso.ps1` é o portão de publicação, não um teste de relevância. Ele
sai com código 1 se um termo abaixo do piso aparecer em qualquer consulta, ou se
um termo publicado não for alcançável por nenhum prefixo plausível. Nenhum dos
dois tem faixa aceitável.

### Um cliente React ou Angular

Precisa de uma configuração, e é a que mais engana: **sem CORS a API responde
normalmente a `curl` e é bloqueada pelo navegador**. O `200` chega e o JavaScript
nunca vê a resposta, porque faltam os cabeçalhos `Access-Control-*`. No DevTools
aparece como erro de rede, o que manda investigar o lugar errado.

```properties
# application-local.properties ja lista as portas de 3000, 4200 e 5173
sugestao.cors.origens=http://localhost:3000,http://127.0.0.1:3000
```

Vazio significa **nenhuma** origem, não todas — e `localhost` e `127.0.0.1` são
origens diferentes para o CORS.

[`docs/cliente-web.md`](docs/cliente-web.md) tem o hook React e o serviço Angular
completos, e os quatro problemas de busca-ao-digitar — sobretudo o terceiro, que
não aparece em rede local: **resposta fora de ordem**, quando a resposta de
`cons` chega depois da de `consignado` e sobrescreve a lista correta.

### Bater nos endpoints

Quatro formas, as mesmas chamadas. Não há tela: a evidência é a resposta.

**No VS Code** — [`requisicoes/sugestoes.http`](requisicoes/sugestoes.http), com a
extensão REST Client (`humao.rest-client`). Cada bloco separado por `###` ganha um
link *Send Request*; `Ctrl+Alt+R` envia o bloco do cursor. Os blocos estão
agrupados por pergunta, e cada um explica o que olhar na resposta.

**No Postman, para clicar e explorar** —
[`requisicoes/postman-exploracao.json`](requisicoes/postman-exploracao.json).
`File > Import` e pronto: **36 requisições em 7 pastas**, cada uma com uma
descrição em markdown dizendo o que olhar na resposta. Não pede token: um
`prerequest` de collection assina os JWTs a cada envio com o CryptoJS que o
Postman embute, e cada requisição declara o seu na aba *Authorization* — onde dá
para trocar à mão e ver o que acontece.

As pastas respondem a uma pergunta cada:

| pasta | a pergunta |
|---|---|
| 1 · Busca, o básico | como eu uso isto |
| 2 · O estado da conta decide | por que a mesma letra dá resposta diferente |
| 3 · Cadastrar uma oferta | como a minha jornada publica |
| 4 · O caminho de escrita | onde o comportamento é mapeado |
| 5 · Segurança | o que é recusado, e com qual status |
| 6 · CORS | por que o navegador falha e o Postman não |
| 7 · Direto no Solr | o que o índice guarda — e o que ele não guarda |

Ela **muda os dados enquanto você clica**, de propósito: a pasta 4 escreve no
perfil que a pasta 1 lê, e a segunda passada responde diferente da primeira. É o
mapeamento funcionando. Reiniciar a aplicação zera o perfil; as ofertas que a
pasta 3 grava ficam no índice e saem com
[`scripts/limpar-ofertas-de-teste.ps1`](scripts/limpar-ofertas-de-teste.ps1).

**No newman, para CI** —
[`requisicoes/postman-sugestao-busca.json`](requisicoes/postman-sugestao-busca.json),
com **56 asserções** sobre o contrato público. Roda sem instalar nada permanente:

```powershell
npx --yes newman@6 run .\requisicoes\postman-sugestao-busca.json
```

As asserções não dependem do `?explicar=true`: elas conferem o que qualquer
cliente recebe. Esta é a collection a **não** abrir para explorar — ela escolhe o
token pelo **número** no nome da requisição, e renomear uma quebra o token dela.

**No terminal** — [`requisicoes/bater.ps1`](requisicoes/bater.ps1), sem precisar de
extensão nenhuma. Ele resume a resposta no que importa para cada pergunta:

```powershell
.\requisicoes\bater.ps1 -Lista                  # cenarios disponiveis
.\requisicoes\bater.ps1 -Cenario oferta         # a melhor oferta, identificada
.\requisicoes\bater.ps1 -Cenario conta          # os dados da conta mudam a resposta
.\requisicoes\bater.ps1 -Cenario cadastro       # a jornada cadastra a propria oferta
.\requisicoes\bater.ps1 -Cenario validacao      # todas as violacoes de uma vez
.\requisicoes\bater.ps1 -Cenario privacidade    # o piso de k-anonimato, medido
.\requisicoes\bater.ps1 -Cenario entrada        # esta rota nunca devolve erro
.\requisicoes\bater.ps1 -Cenario latencia       # percentis, nao media
.\requisicoes\bater.ps1                         # todos
```

Use o perfil `local` para os dois: ele liga `?explicar=true` e afrouxa os prazos.

### A oferta, de ponta a ponta

A pergunta que o desenho precisa responder não é "quais palavras completam o que
a pessoa digitou", é **"qual oferta eu apresento a esta pessoa agora"**. As duas
pontas disso são o cadastro e a resposta.

Uma jornada cadastra a própria oferta:

```powershell
curl.exe -s -X POST "http://localhost:8080/v1/ofertas" -H "Content-Type: application/json" -d "@exemplo.json"
# 201 Created, Location: /v1/ofertas/OFR-CREDITO-CONSORCIO
```

E a busca já a devolve identificada, com a melhor no topo:

```jsonc
{
  "prefixo": "consorc",
  "degradada": false,
  "melhorOferta": {
    "ofertaId": "OFR-CREDITO-CONSORCIO",   // o que a jornada aciona
    "produtoAlvo": "CONSORCIO_IMOVEL",
    "jornada": "credito",
    "posicao": 0,                          // liga a resposta a impressao
    "relevancia": 2.0
  },
  "itens": [ /* ... a lista, cada linha com ofertaId quando e oferta ... */ ]
}
```

O cadastro **não aceita** `ctr`, `taxaAdesao` nem `clientesDistintos`: são os
números que mais mexem no ranking e nenhum é declarável. A oferta nova entra com
os quatro em zero e sobe pelo que acontecer com ela. Ver
[`docs/decisoes.md`](docs/decisoes.md), decisão 3.

E a oferta nasce visível com zero clientes distintos, porque o piso de
k-anonimato vale só para termo vindo do log — não há ninguém para reidentificar
num nome de produto. Decisão 2.

### A demonstração

A mesma letra, perfis diferentes. O segmento e a identidade viajam em cabeçalho
porque o POC não tem emissor de token:

```powershell
curl.exe -s "http://localhost:8080/v1/sugestoes?q=ante" -H "X-Cliente-Id: pf-novo"
curl.exe -s "http://localhost:8080/v1/sugestoes?q=ante" -H "X-Cliente-Id: pf-pix-recusado"
curl.exe -s "http://localhost:8080/v1/sugestoes?q=ante" -H "X-Cliente-Id: pj-lojista" -H "X-Cliente-Segmento: PJ"
```

| perfil | o que ele demonstra |
|---|---|
| `pf-novo` | linha de base: lista popular do segmento |
| `pf-pix-recusado` | gatilho ativo abre a lista com rótulo próprio |
| `pf-investidor` | produto já contratado some da lista |
| `pf-sem-elegibilidade` | falha fechada: a oferta que exige elegibilidade some |
| `pj-lojista` | o segmento do token muda a lista antes de qualquer perfil |

Os prazos do `application.properties` são os do desenho — 18 ms para o Solr, 8 ms
para o perfil. Num Solr recém-subido as primeiras consultas estouram isso e a
lista volta vazia, o que é o comportamento correto e não um defeito. Depois de
alguns aquecimentos a lista aparece. Para demonstrar sem aquecer, suba
`sugestao.prazo.solr-ms`.

### O caminho de escrita, de ponta a ponta

O app registra o que aconteceu; nada disso está no orçamento de 30 ms. O
identificador do cliente vem do token, **nunca do corpo** — se viesse, quem
controlasse o corpo controlaria o piso de k-anonimato.

```powershell
# 1. trinta pessoas distintas digitam o mesmo termo
1..30 | ForEach-Object {
  curl.exe -s -X POST http://localhost:8080/v1/eventos `
    -H "Content-Type: application/json" -H "X-Cliente-Id: cliente-$_" `
    -d '[{"tipo":"BUSCA_DIGITADA","termo":"trocar titularidade da conta"}]'
}

# 2. roda a agregação na hora, em vez de esperar o relógio
curl.exe -s -X POST http://localhost:8080/v1/aprendizado/rodar

# 3. o termo agora é sugestão
curl.exe -s "http://localhost:8080/v1/sugestoes?q=trocar+titu" -H "X-Cliente-Id: pf-novo"
```

Com 29 pessoas a resposta é `publicados=0 retidos=1`; com a trigésima, o termo
entra. **Repetição não substitui gente**: cem buscas de uma pessoa só continuam
sendo `retidos=1`, porque o que se conta é cliente distinto, não sessão.

Os quatro tipos de evento são `BUSCA_DIGITADA`, `SUGESTAO_EXIBIDA`,
`SUGESTAO_CLICADA` e `ADESAO`. A impressão é registrada **com posição** e mesmo
quando ninguém clica — sem ela o CTR seria clique sobre busca, que mede
popularidade do termo, não se a sugestão acertou.

### Os dois portões, e por que são dois

```powershell
curl.exe -s -X POST http://localhost:8080/v1/eventos `
  -H "Content-Type: application/json" -H "X-Cliente-Id: c-1" `
  -d '[{"tipo":"BUSCA_DIGITADA","termo":"adiantar para cpf 111.444.777-35"}]'
# -> {"aceitos":0,"descartados":1,"motivos":{"CPF":1}}
```

O **saneamento** barra padrão conhecido na ingestão, não na publicação: CPF e
CNPJ por dígito verificador, cartão por Luhn, e-mail, chave Pix aleatória, e uma
regra larga de sequência numérica que apanha agência e conta. Descarta o termo
inteiro — mascarar o CPF e guardar o resto da frase guarda o resto da frase, e é
no resto que costuma estar o nome.

O **piso de k-anonimato** pega o que nenhuma expressão regular pega.
`adiantamento para maria silva` passa pelo saneamento, porque nome próprio não
tem padrão, e fica retido na publicação por ter vindo de uma pessoa só. Os dois
são complementares: um sabe a forma do dado, o outro sabe quantas pessoas o
disseram.

A resposta da ingestão **não devolve o termo barrado**, só a contagem por
motivo. Devolvê-lo recriaria no corpo da resposta — e no log de quem o
imprimisse — exatamente o vazamento que o portão acabou de impedir.

### Como a ordem é decidida

Sinais multiplicativos em cima do score do índice, com **teto declarado** em 3.6.

| sinal | o que responde | peso |
|---|---|---|
| escala | põe score do Solr e score do histórico na mesma régua | — |
| jornada líder | qual intenção o prefixo revela, e o produto dela lidera | 1.6 × 1.25 |
| afinidade de jornada | o que **esta pessoa** faz, não o que a população faz | até 1.5 |
| afinidade de produto | e **qual produto** dentro da jornada | até 1.2 |
| tendência | esse interesse está subindo ou esfriando | até 1.15 |
| **fadiga** | esta linha ela já viu e ignorou | piso 0.6 |

A ordem não é intercambiável: **promover, limitar no teto, rebaixar pela
fadiga**. Rebaixar antes do teto faria a fadiga desaparecer quando o produto das
promoções estourasse o limite — ou seja, ela pararia de funcionar exatamente para
quem tem mais afinidade, que é quem mais precisa dela.

O teto é **declarado** e não emergente, e isso conserta um defeito que existia
sem ninguém saber. Antes o máximo era 3.0 porque `1.6 × 1.25 × 1.5` dá 3.0, e
esse número não estava escrito em lugar nenhum — acrescentar um fator mudava o
máximo do sistema em silêncio. Foi o que aconteceu nesta rodada, e um teste pegou:
com teto 3.0, uma linha de jornada líder com afinidade máxima **já estava no
teto**, e a afinidade por produto multiplicava um valor que o limite cortava de
volta. Os dois produtos saíam em 3.0 e o desempate não acontecia. O recurso não
ficava fraco — ficava **inerte**, e justamente para quem tem mais comportamento
registrado.

A **fadiga** é o primeiro sinal do projeto que rebaixa, e isso contraria uma
invariante que esta seção afirmava: "todos só promovem; para rebaixar existe a
`Peneira`, que devolve o motivo". A frase continua certa, e havia um defeito maior
do outro lado — sem sinal negativo o ranking só sabe concordar consigo mesmo. Uma
linha exibida quarenta vezes e nunca clicada não perdia nada, porque `ctr` mede a
**população** e não esta pessoa. A invariante foi trocada por outra, mais fraca e
verificável: **rebaixar é permitido, apagar não, e todo rebaixamento vai para o
rastro**.

A **jornada líder** é a que domina os candidatos, desde que supere a segunda por
15%. Empate não elege ninguém: "limite" serve a cartão e a Pix, e escolher um por
diferença de terceira casa decimal é inventar uma intenção. `busca` nunca lidera
— não é jornada, é o balde do log.

```
q='emprestim'  →  jornada 'credito'
   0. [CATALOGO] empréstimo pessoal        ← o produto
   1. [POPULAR ] contratar emprestimo
```

A **afinidade** é o ranking por comportamento, e é diferente do `ctr` e da
`taxa_adesao` que já estão no `boost` do Solr: aqueles são agregados da
população. Ela vem de clique e contratação do próprio cliente — buscar não conta,
porque buscar é declarar dúvida, e quem procura entender um produto não é quem o
quer. Normaliza contra a jornada mais forte **da própria pessoa**, não contra um
máximo global; normalizar contra a população criaria um segundo índice de
popularidade, que já existe.

Quando nenhuma jornada lidera, o comportamento decide sozinho:

```
q='contratar'   (jornada líder: nenhuma)
pf-novo            pf-pix-recusado       pf-uniclass          pf-negativado
(sem afinidade)    (crédito 1.0)         (investimento 1.0)   (renegociação 1.0,
                                                               cartão 0.5)
contratar cartao   contratar emprestimo  contratar cdb        contratar cartao
contratar seguro   contratar cartao      contratar cartao     contratar seguro
contratar empr...  contratar seguro      contratar seguro     contratar empr...
contratar cdb      contratar cdb         contratar empr...    contratar cdb
```

Para que isso alcançasse a camada popular, o termo vindo do log precisou ganhar
jornada. Ele nasce sem nenhuma — ninguém o curou — e os dois sinais são chaveados
por jornada, então sem isso a personalização seria decorativa, valendo só para as
linhas de catálogo. A publicação classifica cada termo aprendido cruzando suas
palavras com as do catálogo curado, com a mesma regra de margem: na dúvida fica
em `busca`, que é onde ele estava antes.

A jornada herdada serve ao **ranking** e nunca às **restrições**: `contratar
emprestimo` herda crédito, mas não herda o veto de inadimplência do produto.
Restrição é decisão de quem cura, e ninguém curou esse termo.

O risco conhecido é a bolha — afinidade reforça afinidade. Três coisas seguram
isso: o teto de 3.6, pelo qual todos os sinais competem; a cota do `Misturador`,
que garante que a lista continue mostrando caminhos que o ranking sozinho teria
enterrado; e agora a fadiga, que é o primeiro mecanismo capaz de **desfazer** uma
preferência em vez de só deixá-la decair.

### Os dois níveis da afinidade, e por que dois

A afinidade era um escalar por jornada, e isso a tornava incapaz de responder à
pergunta mais comum: **financiamento, empréstimo e consignado são todos da jornada
`credito`**, então clicar em consignado promovia empréstimo exatamente igual. O
sistema sabia que a pessoa queria crédito e não tinha como saber qual.

| nível | piso de confiança | teto no ranking |
|---|---|---|
| jornada | 6.0 — três cliques de hoje | 1.5 |
| produto | 4.0 — dois cliques de hoje | 1.2 |

O piso do produto é **mais baixo** e o teto é **menor**, e os dois andam juntos.
Evidência por produto é muito mais escassa: há meia dúzia de jornadas e vinte e
tantos produtos, e o clique se espalha. Com piso 6.0 nos dois níveis, a afinidade
por produto ficaria matematicamente correta e praticamente sempre zero — um
recurso inerte que ninguém notaria estar inerte. Baixar o piso é o conserto, e
tem preço declarado: o nível do produto acredita com menos evidência. O teto menor
é quem paga esse preço.

Medido ao vivo, com `pf-uniclass` e três cliques em LCI:

```
                        antes      depois    ganho
OFR-INVESTIMENTO-LCI    2.1813     2.2767    1.044x   ← o produto clicado
OFR-INVESTIMENTO-CDB    3.0830     3.0839    1.000x   ← controle, mesma jornada
```

Os dois são da jornada `investimento`, então a afinidade de jornada promoveu os
dois na mesma medida. Que o alvo tenha subido **mais** é o nível do produto
funcionando — e era impossível antes.

### A tendência: 7 dias contra 30

Uma exponencial só diz "quanto", não "para onde". A meia-vida de sete dias já faz
evento recente dominar, mas não distingue **disparou esta semana** de **constante
há um mês** — e essa é a diferença entre necessidade transitória e interesse
durável.

A tendência é a razão entre os pontos da janela curta e os da longa. Como a curta
está contida na longa, a razão vive em [0,1] e **o ponto neutro não é 0,5: é 7/30
≈ 0,23**. Acima disso a atividade está concentrada no presente; abaixo, está
esfriando. Só o lado de cima promove — rebaixar por tendência fria puniria quem
demonstrou interesse há três semanas, e o decaimento da afinidade já cuida do
esquecimento com uma curva que tem física por trás.

**O limite deste sinal está declarado, e eu recomendei adiá-lo.** Razão entre
números pequenos é ruído: com três cliques no total, um clique a mais move a razão
de 0,33 para 0,66, e a "tendência" dobraria por causa de um toque. O
`piso-da-tendencia` de 4,0 pontos é a resposta — abaixo dele a tendência devolve
neutro exato. Quantas vezes isso acontece é visível na métrica, e é assim que se
descobre se o recurso está dizendo algo ou está desligado de fato.

Há uma segunda limitação, e ela é estrutural: **a tendência não é verificável de
ponta a ponta pela API pública**, porque `ocorridoEm` é atribuído pelo servidor no
momento da ingestão. Não há como injetar um evento de trinta dias atrás por fora.
A matemática tem teste de unidade; o comportamento em produção só a métrica dirá.

### A fadiga, medida ao vivo

```
                        impressões sem clique    relevância    razão
OFR-INVESTIMENTO-CDB    0                        3.0839        —
OFR-INVESTIMENTO-CDB    20                       1.8503        0.600   ← = fadiga-minima
OFR-INVESTIMENTO-CDB    20, depois 1 clique      3.2179        1.739x  ← zerou
```

Três defesas, e cada uma impede um defeito concreto:

1. **Piso de evidência (5 impressões).** Sem ele, oferta nova morre antes de
   nascer: recebe impressão, ainda não tem clique, é penalizada, perde posição e
   nunca chega a receber clique. É o mesmo laço que o piso de confiança da
   afinidade já corrigiu uma vez, na direção oposta.
2. **Piso do fator (0.6).** A fadiga nunca zera a linha. Zerar seria remover, e
   remover é trabalho da `Peneira`, que devolve motivo.
3. **Janela (30 dias).** Ignorar uma oferta em março não deve pesar em outubro.

E **um clique zera** — não desconta. A pergunta que a fadiga responde é "esta
pessoa ignora esta linha?", e um clique responde não, independentemente de quantas
impressões vieram antes.

**O que a fadiga não corrige, e fica declarado:** viés de posição. Uma linha na
posição 5 é vista menos que uma na posição 0, e contar "exibiu e não clicou" trata
as duas igual — a linha que já estava embaixo acumula fadiga mais rápido e afunda
mais. Corrigir isso exige modelo de propensão, que é trabalho de outra ordem de
grandeza. Esperar por ele para ter sinal negativo nenhum deixaria o defeito maior
no lugar para evitar o menor.

### Tolerância a erro de digitação

Uma letra errada zerava a lista. Em teclado de celular, isso é a maioria dos casos
ruins.

```
                  antes   depois   desfecho
emprestimo        4       4        NAO_PRECISOU
emprestmo         0       4        ACIONADA_COM_GANHO
enprestimo        0       4        ACIONADA_COM_GANHO
consignadu        0       3        ACIONADA_COM_GANHO
financiemento     0       1        ACIONADA_COM_GANHO
```

**É uma segunda consulta, não uma cláusula a mais na primeira.** Com
`q = "emprestmo OR emprestmo~1"` no edismax, o fuzzy seria aplicado a todos os
campos do `qf` — inclusive aos dois EdgeNGram, onde cada palavra já está expandida
em dezenas de gramas e a enumeração de termos difusos multiplica por isso. **Toda
busca pagaria por um recurso que só importa quando a busca falhou.** Assim, a
segunda ida só acontece quando a primeira trouxe menos de três candidatos — que é
exatamente quando não há nada a perder.

Vai contra `termo_tolerante`, um campo **sem EdgeNGram**: fuzzy sobre grama de
prefixo compara pedaço com pedaço e acha semelhança onde não há — o grama `emp`
está a distância 1 de `amp`, `emb` e `imp`.

E a distância cresce com o tamanho da palavra, com mínimo de 5 caracteres. Abaixo
disso não é tentada: com quatro letras, distância 1 alcança um vizinho enorme —
`card` casa `cart`, `cardi`, `carro`, `cara`. Lista errada com confiança é pior
que lista vazia, e em banco oferecer consórcio a quem digitou "consignado" torto
não é recuperação simpática, é produto errado.

Os filtros da segunda consulta são **os mesmos** da primeira, e isso não é
duplicação a remover: um fuzzy sem o `fq` do piso de k-anonimato devolveria termo
de pouca gente com erro de digitação por cima — vazamento com disfarce.

### Medir a qualidade da busca

**Esta parte foi construída antes das outras, de propósito.** Nenhuma melhoria de
relevância é verificável sem ela: tolerância a erro de digitação, sinal negativo e
afinidade por produto são todos afirmações sobre quantas buscas passam a encontrar
algo, e sem taxa de zero-resultado medida antes e depois, "melhorou" é opinião.

```powershell
$op = .\scripts\gerar-token.ps1 -Tipo operacao -Silencioso
curl.exe -s "http://localhost:8080/actuator/metrics/sugestao.buscas" -H "Authorization: Bearer $op"
```

| métrica | o que responde |
|---|---|
| `sugestao.buscas` | desfecho, faixa de prefixo, estado do perfil, jornada |
| `sugestao.descartes` | **por que** a lista veio curta, por motivo |
| `sugestao.tolerancia` | quantas vezes a segunda consulta rodou, e se ganhou algo |
| `sugestao.latencia` | p50/p95/p99 por etapa — total, índice, perfil |
| `sugestao.posicao.clicada` | se o ranking acerta: massa em 0–1 ou em 3–4 |

O desfecho tem **quatro** valores, e o que faltava é o que esconde defeito:

```
PREFIXO_RECUSADO   nem era busca — alguém no meio da primeira letra
ZERO_RESULTADO     buscou e não achou nada
SEM_OFERTA         achou linhas, nenhuma acionável   ← este
COM_OFERTA         achou oferta
```

`SEM_OFERTA` é a categoria que faltava e que escondia o caso real:
`q=financiamento` devolvia uma linha de ação e nenhuma oferta. Não era
zero-resultado, então nenhum contador de lista vazia acusaria; e não era sucesso,
porque quem buscou financiamento não recebeu proposta nenhuma. Sem esse valor, o
caso é invisível.

E `prefixoAceito` separa **"não encontrou"** de **"nem era busca"**. As duas
respondem lista vazia e são coisas opostas: a primeira é falha de cobertura e
merece alarme, a segunda é alguém digitando a primeira letra. Misturadas num
contador só, a taxa de zero-resultado fica dominada pelo segundo caso e para de
significar qualquer coisa.

**Nada que uma pessoa digitou vira rótulo.** O prefixo entra como *faixa de
comprimento* ("4-6", "7-10") e o identificador do cliente não entra. Dois motivos,
e cada um basta sozinho: prefixo é texto de busca de uma pessoa, e o piso de
k-anonimato existe para que termo raro não saia do serviço — um contador
`busca{prefixo="adiantamento para maria"}` desfaria isso pela porta da
observabilidade, onde ninguém procura vazamento; e rótulo por prefixo é
cardinalidade ilimitada, que derruba o coletor junto com as métricas que iam ser
úteis.

A rota pede escopo próprio, `metricas:ler`. Ela não carrega prefixo nem cliente,
mas carrega a **forma** do serviço: taxa de zero-resultado por faixa, latência por
etapa e volume de descarte por motivo contam a quem perguntar onde este serviço é
frágil e quais produtos pouca gente pode contratar.

### Limite de taxa

O único item desta rodada que é **risco** e não qualidade de busca. Uma rota de
typeahead sem limite é duas coisas ao mesmo tempo: amplificador de carga — um
cliente em laço consome a capacidade medida do serviço — e via de raspagem do
catálogo, porque qualquer portador de token de cliente enumera as ofertas varrendo
prefixos.

```
RateLimit-Limit: 600        em TODA resposta, não só no 429
RateLimit-Remaining: 599    para o front-end poder aumentar o debounce
RateLimit-Reset: 59

requisição 600 → 429 application/problem+json + Retry-After: 50
```

Posição na cadeia: **depois** do filtro que lê o Bearer, **antes** do que autoriza.
Depois da leitura, a chave do limite é o `sub` provado, que é a única chave não
falsificável; antes da autorização, o limite ainda se aplica a quem mandou token
inválido ou nenhum — e esse é o tráfego que mais interessa limitar, porque validar
assinatura HMAC em laço é gasto de CPU.

**E o que ele honestamente não resolve.** O contador é por processo e em memória:
não sobrevive a restart e não é compartilhado entre instâncias — com três
instâncias atrás de um balanceador, o limite efetivo é o triplo do configurado. A
camada que resolve isso é o gateway, que vê todo o tráfego. Também não separa quem
digita rápido de quem raspa devagar: quem digita "financiamento imobiliário" gasta
~25 requisições em segundos, e quem raspa com paciência fica abaixo de qualquer
teto que não quebre a digitação. Os 600/min protegem a digitação; o resto é
trabalho de detecção de comportamento, não de contador.

Vale fazer **declarando isso**. O pior resultado possível seria alguém marcar
"limite de taxa" como resolvido.

### Ver a decisão por dentro

```powershell
curl.exe -s "http://localhost:8080/v1/sugestoes?q=cdb&explicar=true" -H "X-Cliente-Id: pf-investidor"
```

```json
"explicacao": {
  "perfil": "ENCONTRADO", "msTotal": 6, "candidatosDoIndice": 2,
  "descartes": [ { "termo": "CDB", "origem": "CATALOGO", "motivo": "PRODUTO_JA_CONTRATADO" } ],
  "linhas":    [ { "posicao": 0, "termo": "cdb", "origem": "HISTORICO", "relevancia": 1.0 } ]
}
```

Numa camada de sugestão a pergunta que chega do negócio quase nunca é por que
uma linha apareceu — é por que a outra não apareceu. O rastro responde isso, e
sai em `DEBUG` a cada requisição mesmo sem o parâmetro.

**O `?explicar=true` só funciona no perfil `local`.** No `application.properties`
versionado ele é `false`, junto com o gatilho manual do aprendizado:

```powershell
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

O default que viaja no repositório é o seguro. Um comentário dizendo "desligar em
produção" ao lado de um default ligado protege quem lê o arquivo, não quem faz
deploy sem ler. O perfil `local` também afrouxa os prazos para 250/100/400 ms, de
modo que a demonstração não dependa de o Solr estar aquecido.

## Autenticação e autorização

O serviço é **servidor de recurso** OAuth 2.0: valida o token que chega e **não
emite nenhum**. Emitir é papel do provedor de identidade, e um BFF que emite o
próprio token é um BFF que decide quem ele mesmo é.

No POC não há provedor, então a assinatura é HMAC-SHA256 com segredo
compartilhado e os tokens de desenvolvimento saem de um **script fora da
aplicação** — para não existir caminho de emissão no serviço nem por acidente:

```powershell
.\scripts\gerar-token.ps1 -Tipo cliente -ClienteId pf-pix-recusado
.\scripts\gerar-token.ps1 -Tipo jornada -Jornada credito
.\scripts\gerar-token.ps1 -Tipo operacao
```

### Dois tipos de chamador

| | quem é | reivindicações | escopos |
|---|---|---|---|
| **cliente** | o app, em nome de uma pessoa | `sub`, `segmento`, `relacionamento` | `sugestao:ler`, `eventos:escrever` |
| **jornada** | um serviço de produto | `jornada` | `ofertas:escrever`, `ofertas:ler` |
| **operação** | carga de catálogo e ops | — | `ofertas:todas`, `aprendizado:executar` |

**Segmento e faixa saírem do token não é detalhe de autenticação, é decisão de
arquitetura.** Eles são atributos de coorte, conhecidos antes de qualquer leitura
de perfil, e é isso que permite à consulta ao Solr arrancar junto da leitura do
perfil em vez de atrás dela — o que faz o orçamento de 30 ms fechar. Agora eles
vêm **provados** em vez de ditos por cabeçalho, o que é a diferença entre uma
coorte e uma coorte escolhida por quem chama.

### O escopo diz que você pode cadastrar; a jornada diz o quê

É a regra que fecha o buraco que este README declarou por várias versões.
`ofertas:escrever` autoriza cadastrar **alguma coisa**; a reivindicação `jornada`
diz **o quê**. Sem a segunda, a jornada de seguros publicaria uma oferta de
crédito, com rótulo e destino escolhidos por ela, e ela apareceria na busca como
se fosse do time de crédito.

**Essa regra não está na configuração de segurança.** Ela está no caso de uso,
que recebe um `Autorizacao` — porque "a oferta tem de ser da jornada de quem
cadastra" é regra de negócio, e continuaria valendo se o cadastro passasse a
entrar por uma fila, por um lote ou por um console. A borda só traduz token em
`Autorizacao`.

### A matriz, medida

```
rota                                       token             status
GET  /v1/sugestoes                         (nenhum)             401
GET  /v1/sugestoes                         cliente              200
GET  /v1/sugestoes                         jornada              403   escopo errado
POST /v1/ofertas (credito)                 cliente              403   escopo errado
POST /v1/ofertas (credito)                 jornada credito      201
POST /v1/ofertas (SEGUROS)                 jornada credito      403   <- a regra
POST /v1/ofertas (SEGUROS)                 operacao             201   ofertas:todas
GET  /v1/ofertas/{id}                      jornada              200
GET  /v1/ofertas/{id}                      cliente              403
POST /v1/aprendizado/rodar                 cliente              403
POST /v1/aprendizado/rodar                 operacao             200
GET  /actuator/health                      (nenhum)             200   pública
GET  /actuator/metrics                     (nenhum)             401   não é pública
GET  /v1/nao-existe                        cliente              403   nega por padrão
```

A última linha é a que vale mais a longo prazo: `anyRequest().denyAll()` faz uma
rota nova **nascer fechada**. O contrário só se descobre em revisão de segurança.

`/actuator/metrics` fica fora do público de propósito: latência por rota conta o
desenho do serviço a quem perguntar.

### O que fica de fora, e por quê

**O segredo padrão é vazio e a aplicação recusa subir sem ele.** É a escolha
deliberadamente inconveniente: um segredo embutido funcionaria em qualquer lugar,
inclusive em produção, e assinaria tokens que qualquer pessoa com acesso a este
repositório sabe forjar. Falhar na subida é ruidoso, acontece uma vez, e acontece
para quem está configurando — não para quem está sendo atacado. O perfil `local`
traz um segredo visivelmente falso.

**`sugestao.seguranca.habilitada=false`** abre todas as rotas e faz a identidade
voltar a vir de cabeçalho. Existe para demonstrar sem emitir token, **grita no
log na subida**, e é o único parâmetro deste projeto cuja troca muda o serviço de
protegido para aberto.

**Não há CSRF**, e não é esquecimento: não há sessão nem cookie. A credencial é um
token no cabeçalho `Authorization`, que o navegador não anexa sozinho — e é a
anexação automática que o CSRF existe para conter.

**Trocar HMAC por RSA com JWKS remoto é trocar o decodificador**, um método em
`ConfiguracaoDeSeguranca`. Nem o resolvedor de cliente, nem as regras de rota,
nem o caso de uso mudam. É a mesma propriedade que o disjuntor demonstra do
outro lado: a tecnologia fica num canto.

## Resiliência

São **três mecanismos**, e a confusão entre eles é o erro mais comum nesta parte
do desenho. Cada um protege uma coisa diferente.

| mecanismo | protege | quando age |
|---|---|---|
| **prazo por dependência** | a *requisição* | 18 ms para o Solr, 8 ms para o perfil |
| **caminho alternativo** | a *resposta* | prazo vencido ou falha → lista degradada |
| **disjuntor** | a *dependência e o processo* | 10 falhas nas últimas 20 chamadas |

### Prazo e caminho alternativo

O leque dispara Solr e perfil ao mesmo tempo, em threads virtuais, e colhe cada
um com prazo absoluto contado **do início da requisição** — não de quando a
espera começou. Se fosse relativo, a segunda espera ganharia de presente o tempo
que a primeira gastou, e o orçamento de 30 ms viraria 26 sem ninguém ter
decidido isso.

Não há nova tentativa, em nenhum dos dois. A próxima tecla já vem a caminho e ela
é a nova tentativa — com um prefixo mais específico, que é uma pergunta melhor.

O perfil que não chega vira `Perfil.NEUTRO` e a resposta sai com
`degradada: true`. O app usa isso para **não registrar a impressão como
personalizada**: sem essa marca, a camada de aprendizado aprenderia com ruído.

### O disjuntor, e por que o prazo não bastava

O prazo faz a falha ser **rápida**. O disjuntor faz a falha ser **barata**.

Com o Solr fora do ar e só o prazo, cada tecla digitada ainda abre uma conexão
TCP e espera o prazo vencer. Numa caixa de busca isso são dezenas de tentativas
por segundo contra um host morto, cada uma segurando um descritor de arquivo e
uma thread, e nenhuma com a menor chance de responder.

E há o outro lado, que quase ninguém lembra: **quando o Solr volta, ele volta
frio**. Devolver de uma vez toda a carga que estava sendo recusada é a forma mais
comum de derrubá-lo outra vez. É para isso que existe o meio-aberto.

```
FECHADO ──10 falhas em 20 chamadas──> ABERTO ──5 s──> MEIO_ABERTO
   ^                                     ^                 │
   └──────── sonda deu certo ────────────┼── sonda falhou ─┘
                                         └── (reabre na hora)
```

Três decisões dentro disso:

- **Janela, não falhas consecutivas.** Consecutivas é mais simples e não abre no
  caso que mais importa: uma dependência que responde metade das vezes nunca
  acumula a sequência, e metade das pessoas fica sem lista.
- **Mínimo de chamadas antes de decidir.** Impede que uma falha isolada num Solr
  recém-subido abra o disjuntor com amostra de tamanho um — e Solr frio estoura
  o prazo de 18 ms legitimamente.
- **Timeout conta como falha.** Numa camada de sugestão, lento e morto são a
  mesma coisa: uma lista que chega depois da próxima tecla não serve para nada.

**Só na porta de leitura.** O cadastro de oferta é de volume baixo, síncrono, e
tem uma jornada esperando resposta — recusar sem tentar faria um cadastro falhar
por causa de falhas de *outras* chamadas. A publicação roda no agendador, que já
é o mecanismo de nova tentativa. O volume de teclas digitadas é o que justifica
o disjuntor, e ele existe num lugar só.

### Medido com o Solr derrubado

Matei o processo do Solr e deixei a busca sendo chamada:

```
                          HTTP   itens   msTotal (servidor)
Solr no ar                 200       3        6-15 ms
Solr fora, antes de abrir  200       0       10 ms  (tenta e falha)
Solr fora, DEPOIS de abrir 200       0        0-1 ms  (nem tenta)
Solr de volta              200       3        6-15 ms
```

E o log do próprio disjuntor, do ciclo inteiro:

```
disjuntor 'catalogo-solr' ABERTO (10 falhas nas ultimas 13 chamadas): parando de chamar por 5000 ms
disjuntor 'catalogo-solr' ABERTO -> MEIO_ABERTO: 2 sonda(s) liberada(s)
disjuntor 'catalogo-solr' ABERTO (a sonda falhou): parando de chamar por 5000 ms
disjuntor 'catalogo-solr' ABERTO -> MEIO_ABERTO: 2 sonda(s) liberada(s)
disjuntor 'catalogo-solr' MEIO_ABERTO -> FECHADO: a dependencia voltou
```

A busca **nunca devolveu erro** e **se recuperou sozinha**, sem reiniciar o app.

Duas honestidades sobre essa medição. Primeiro: medida de fora pelo
`Invoke-WebRequest` do PowerShell, os números ficam em 80–90 ms em todos os casos
— o custo do cliente domina e esconde o efeito. O que prova o disjuntor é o
`msTotal` do servidor, que vai a 0–1 ms. Segundo: `localhost` recusa conexão
instantaneamente, então aqui não há timeout a economizar. Contra um host que
*engole* o pacote — firewall com `DROP`, ou um Solr sobrecarregado — a economia é
o prazo inteiro, por chamada.

### Reproduzir

```powershell
# com o app no ar, derrube o Solr e observe
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -like '*solr*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

.\requisicoes\bater.ps1 -Cenario oferta     # 200, lista vazia, nunca erro
.\scripts\solr-local.ps1 start              # e a busca volta sozinha
```

## Estrutura

A arquitetura é hexagonal: o domínio e a aplicação são Java comum, sem nenhuma
anotação de framework, e tudo o que é tecnologia vive em `adaptador`.

```
src/main/java/dev/sugestao/busca/
├── dominio/                   regras. Zero Spring, zero SolrJ.
│   ├── Prefixo, Normalizacao  normalização única, usada também pelo semeador
│   ├── Candidato, Restricoes  linha em potencial e o que ela exige do cliente
│   ├── Sugestao, ListaDeSugestoes  a linha entregue; elege a melhor oferta
│   ├── Origem                 onde a linha APARECE (muda sob promoção)
│   ├── Procedencia            de onde o DADO veio (nunca muda) — decide o piso
│   ├── Jornada                o sentinela de "sem jornada", em um lugar só
│   ├── Cotas                  quanto cada origem pode ocupar
│   ├── Cliente, ContextoDeBusca   token: identidade / coorte sem identidade
│   ├── Perfil, SituacaoDaConta    comportamento e estado de conta
│   ├── ClassificadorDeOrigem  gatilho ativo promove candidato a EVENTO
│   ├── Peneira                o que o perfil tira depois que o índice respondeu
│   ├── Rankeador, PesosDeRanking   escala, jornada lider, afinidade
│   ├── Misturador             dedup, cota, limite
│   ├── Descarte               por que cada candidato ficou de fora
│   ├── oferta/                Oferta (o agregado e TODA a validação),
│   │                          OfertaInvalida, Violacao
│   ├── evento/Saneador        o portão de PII, na ingestão
│   └── agregado/              TermoAgregado, ComportamentoDoCliente,
│                              ClassificadorDeJornada,
│                              PesosDeAfinidade (meia-vida, piso de confianca)
│
├── aplicacao/
│   ├── porta/entrada/         SugerirSugestoes, RegistrarEventos,
│   │                          AprenderComComportamento, CadastrarOferta
│   ├── porta/saida/           CatalogoDeSugestoes, RepositorioDePerfil,
│   │                          FilaDeEventos, RepositorioDeAgregados,
│   │                          PublicadorDeVocabulario, EscritorDePerfil,
│   │                          CatalogoCurado, RegistroDeOfertas
│   ├── Prazos, Colheita, RastroDaSugestao, Parametros*
│   └── casodeuso/             SugerirSugestoes (leitura), CadastrarOferta,
│                              RegistrarEventos + AprenderComComportamento (escrita)
│
├── adaptador/
│   ├── entrada/web/           controllers, DTOs, resolvedor do cliente,
│   │                          TratadorDeErros (RFC 9457)
│   ├── entrada/agendador/     o relógio que aciona o aprendizado
│   ├── saida/solr/            ConsultaDeCandidatos (as 4 camadas),
│   │                          CatalogoSolr (lê), PublicadorSolr (publica),
│   │                          RegistroSolr (cadastra), CatalogoCuradoSolr
│   ├── saida/resiliencia/     Disjuntor (máquina de estados, sem framework),
│   │                          CatalogoComDisjuntor (DECORADOR da porta)
│   ├── saida/fila/            FilaEmMemoria — o lugar do MSK
│   ├── saida/agregado/        AgregadosEmMemoria — o lugar do job de janela,
│   │                          EventosDaDemonstracao (cliques falsos: a
│   │                          afinidade da demo e calculada, nao afirmada)
│   └── saida/perfil/          PerfisEmMemoria — o lugar do DynamoDB
│
└── configuracao/              o único lugar que sabe quem implementa cada porta,
    │                          e o único que sabe que existe disjuntor
    ├── ConfiguracaoDeSeguranca    escopo por rota, decodificador de token,
    │                              401/403 em problem+json
    ├── ConfiguracaoDeCors         o que um cliente de navegador precisa
    └── SugestaoProperties         todo numero discutivel, num lugar

docs/decisoes.md               as decisões, com o que foi recusado e por quê
docs/cliente-web.md            React e Angular: CORS, debounce, ordem das respostas
docs/guia-api.html             guia de integração em 10 passos (abre no navegador)

requisicoes/sugestoes.http     REST Client do VS Code, agrupado por pergunta
requisicoes/bater.ps1          os mesmos cenários no terminal, sem extensão
requisicoes/sondar.ps1         47 sondas adversariais em 5 grupos
requisicoes/verificar-comportamento.ps1   afinidade de produto e fadiga, ao vivo
requisicoes/postman-exploracao.json       36 requisições em 7 pastas, para clicar
requisicoes/postman-sugestao-busca.json   collection com 56 asserções (newman)

compose.yaml                   Solr 10 em container — escrito, nunca executado
scripts/solr-local.ps1         Solr 10 do zip, sem container e sem admin
scripts/habilitar-docker.ps1   obsoleto: o Docker já existe nesta máquina
scripts/criar-colecao.ps1      cria a coleção e aplica o schema
scripts/semear-vocabulario.ps1 vocabulário sintético
scripts/conferir-piso.ps1      portão de publicação: k-anonimato e zero-resultado
scripts/gerar-token.ps1        JWT de desenvolvimento, FORA da aplicação
scripts/limpar-ofertas-de-teste.ps1  remove do índice o que o teste manual criou
scripts/cacar-flaky.ps1        roda a suíte sob contenção, caçando teste instável
solr/schema-sugestoes.json     payload da Schema API
solr/schema-tolerancia.json    só o delta da tolerância, aplicável sobre o acima
solr/README.md                 por que cada campo existe
```

Sobre `schema-tolerancia.json`: a Schema API do Solr processa o lote **inteiro**
de forma atômica, então reaplicar `schema-sugestoes.json` numa coleção que já
existe falha com "already exists" campo a campo e **nada** é adicionado — nem os
campos novos. O delta separado é o caminho para evoluir o esquema sem recriar a
coleção. E como `copyField` só age na indexação, aplicar o delta não preenche os
documentos existentes: depois dele, `.\scripts\semear-vocabulario.ps1 -Limpar`.

**O decorador do disjuntor é o melhor argumento que este repositório tem a favor
da arquitetura hexagonal.** Resiliência contra uma dependência que cai é
exatamente o tipo de requisito que costuma vazar para todo lado. Aqui ele entrou
como dois arquivos novos em `saida/resiliencia/` mais quatro linhas na
configuração — e o domínio, o caso de uso e o `CatalogoSolr` não souberam. Ver a
seção *Resiliência* acima e a decisão 13.

Os scripts são **ASCII puro de propósito**. O Windows PowerShell 5.1 lê `.ps1`
sem BOM como ANSI, e um travessão dentro de uma string vira três bytes cujo
último o parser entende como aspa de fechamento: a string termina cedo e o resto
do arquivo passa a ser lido como código.

## Decisões que já estão no código

O registro completo, com o que foi recusado e por quê, está em
[`docs/decisoes.md`](docs/decisoes.md). O resumo:

- **`clientes_distintos_i` entra como `fq`, e só para `tipo:consulta`.** O piso de
  k-anonimato protege termo digitado por gente; nome de produto não conta nada
  sobre ninguém. Por muito tempo valeu para tudo, e o preço era um contador falso
  escrito no catálogo só para o item passar — dado inventado exatamente onde se
  audita privacidade. Decisão 2.
- **`Procedencia` é separada de `Origem`.** Uma diz de onde o dado veio e não
  muda; a outra diz onde a linha aparece e é sobrescrita por promoção a evento.
  Juntas, uma oferta promovida por gatilho escapava do piso.
- **O cadastro de oferta recusa `ctr`, `taxaAdesao` e `clientesDistintos`.** Eles
  são medidos, não declarados. Decisão 3.
- **`destino` é caminho relativo, nunca URL absoluta.** O app navega para ele;
  aceitar `https://` faria do cadastro um redirecionador aberto. Decisão 4.
- **A validação acumula todas as violações** e sai em `problem+json`. Decisão 5.
- **`GET /v1/sugestoes` não tem caminho de erro.** Nenhuma entrada produz status
  diferente de 200. Decisão 9.
- **O disjuntor é um decorador da porta, não código no caso de uso.** Entrou sem
  o domínio, o caso de uso e o `CatalogoSolr` saberem — e desligá-lo é não
  empilhar o decorador, não um disjuntor que finge estar fechado. Decisão 13.
- **Disjuntor por fora do adaptador, nunca por dentro.** Por dentro ele contaria
  falhas que o adaptador já pagou em rede; por fora ele recusa antes de abrir
  conexão, que é a razão de existir.
- **O serviço valida token e não emite nenhum.** Os tokens de desenvolvimento
  saem de um script fora da aplicação, para não existir caminho de emissão no
  processo que valida. Decisão 14.
- **A regra de jornada mora no caso de uso, não na cadeia de segurança.** O
  escopo é permissão; "a oferta tem de ser da jornada de quem cadastra" é regra
  de negócio.
- **`anyRequest().denyAll()`.** Rota nova nasce fechada.
- **A afinidade tem piso de confiança e meia-vida.** Um clique vale 0,33 e não
  1,0; três cliques de um mês atrás valem 0,05. O decaimento é por **idade do
  evento**, nunca por rodada — por rodada, trocar o intervalo do agendador
  mudaria a velocidade do esquecimento. Decisão 15.
- **A afinidade da demonstração é calculada, não afirmada.** Ela era um
  `Map.of("credito", 1.0)` escrito no código; virou cliques semeados, de onde o
  pipeline a deriva. A demonstração prova o caminho em vez de contorná-lo.
- **Não existe starter do Spring para Solr** desde o Boot 2.4. O SolrJ 10 entra como
  dependência comum e o client vira um `@Bean` nosso.
- **Duas análises de prefixo**, uma para a frase e outra por palavra, com pesos
  diferentes na consulta. O porquê está em `solr/README.md`.
- **Sinônimo é campo do documento**, não arquivo de configuração: o negócio edita a
  redação alternativa junto do termo, sem reload.
- **O módulo `ltr` sobe junto, mas não é usado.** As camadas 1 a 3 do desenho
  entregam sem modelo nenhum; a 4 espera impressão registrada com posição.
- **Produto contratado e elegibilidade não são `fq`.** O segmento vem do token, e é
  isso que deixa a consulta ao Solr arrancar junto da leitura do perfil em vez de
  atrás dela. Produto e elegibilidade só existem depois que o perfil chega —
  transformá-los em `fq` custaria enfileirar as duas chamadas. O corte acontece na
  `Peneira`, sobre vinte candidatos.
- **O gatilho também não é `bq`.** Pelo mesmo motivo, e porque quem garante a linha
  do evento é a cota, não o score: um `bq` pagaria duas vezes pelo mesmo efeito,
  uma delas no caminho crítico.
- **A promoção a evento respeita a cota na origem.** Promover todo candidato que
  casa com um gatilho seria pior do que não promover nenhum: a cota aceitaria um e
  descartaria o resto, e um item de catálogo perderia a linha que teria ganhado sem
  gatilho nenhum. Gatilho só pode ajudar.
- **A recência no `boost` usa `NOW/HOUR`, não `NOW`.** Com `NOW`, a chave do cache
  muda a cada milissegundo e a camada popular nunca acerta o cache. Uma hora de
  granularidade não muda ranking nenhum.
- **O prazo limita espera, não descarta trabalho pronto.** As duas leituras correm
  em paralelo mas são colhidas em sequência: quando a primeira consome o orçamento,
  a segunda chega com prazo vencido mesmo tendo respondido em 1 ms. Esperar zero
  devolve o valor se ele já está pronto.
- **O filtro de tipo inclui `entidade`.** O desenho escreve `tipo:(consulta OR acao)`,
  mas o catálogo marca produto e artigo de ajuda como `entidade` — deixar esse tipo
  de fora apagaria a camada de catálogo inteira.
- **Dois clientes Solr, não um.** A leitura tem 18 ms e precisa desistir; a
  publicação roda sem ninguém esperando e não pode. Compartilhar um cliente só
  parece economia e é acoplamento: foi assim que a primeira versão ficou, e a
  publicação estourava em 18 ms a cada rodada — em silêncio, porque o agendador
  engole o erro e tenta de novo. O índice simplesmente parava de aprender.
- **Leitura e escrita de perfil são duas interfaces.** O mesmo objeto atende as
  duas, mas o caso de uso de sugestão recebe só `RepositorioDePerfil` e por isso
  não tem como escrever. "Nenhuma seta do caminho de leitura escreve" deixa de ser
  promessa e vira assinatura de construtor.
- **O identificador do cliente vem do token, nunca do corpo do evento.** É ele que
  a agregação conta para medir clientes distintos: quem controlasse o corpo
  controlaria o piso de k-anonimato, e bastaria fabricar trinta identificadores
  para publicar o que quisesse como sugestão para todo mundo.
- **O vocabulário publicado usa a forma normalizada, não a redação de quem digitou.**
  Guardar a redação crua faria o índice carregar o jeito de escrever de uma pessoa
  específica, e com acento e maiúscula preservados a chave de dedup do BFF deixaria
  de bater com o que está no índice.
- **A escrita de perfil mescla, não sobrescreve.** A agregação de busca só sabe de
  histórico; gatilho, elegibilidade, produto contratado e situação da conta vêm de
  outros produtores. Sobrescrever tudo era o bug silencioso óbvio: uma busca
  qualquer apagaria a inadimplência do cliente e o crédito novo voltaria a ser
  oferecido na tecla seguinte.

## Próximos passos

1. ~~Vocabulário sintético~~ — feito, mas com **95 termos, não os 2 a 5 mil** do
   desenho. O mecanismo inteiro aparece com esse tamanho; o que não aparece é
   concorrência de verdade entre candidatos no mesmo prefixo.
2. ~~`GET /v1/sugestoes` com o leque paralelo em threads virtuais~~ — feito.
3. ~~Registro de impressão **com posição**~~ — feito, junto do caminho de escrita
   inteiro: ingestão, saneamento, agregação em janelas e publicação pelo piso.
4. ~~Identidade de oferta e cadastro pela jornada dona~~ — feito. `ofertaId` do
   schema ao JSON, `melhorOferta` no topo da resposta, `POST /v1/ofertas`
   idempotente com validação acumulada em `problem+json`.
5. ~~Resiliência: disjuntor na porta de leitura~~ — feito, e verificado com o
   Solr derrubado e religado. Ver a seção *Resiliência*.
6. ~~Consumo por cliente de navegador~~ — feito: CORS configurável, desligado
   por padrão, e [`docs/cliente-web.md`](docs/cliente-web.md) com React e
   Angular.
7. ~~Autenticação e autorização~~ — feito. Servidor de recurso OAuth 2.0, escopo
   por rota, `denyAll` por padrão, e a jornada do token conferida contra o corpo
   do cadastro. Ver *Autenticação e autorização*.
8. **Teste de carga com concorrência de verdade.** O que existe hoje é medição
   sequencial (p50 12 ms, p99 26 ms, uma thread, perfil `local` com prazos
   afrouxados). Isso compara mudanças entre si; não afirma que o orçamento de
   18/8/30 ms de produção fecha.
9. Página de demonstração: digitar as mesmas letras com perfis diferentes. Menos
   urgente agora que há três formas de bater nos endpoints.
10. Camada 4 (LTR). Agora há impressão com posição, que era o que faltava. O que
    falta é volume: com ~102 documentos não há o que um modelo aprenda.

### O que é de mentira, e vale saber qual

| peça | aqui | no desenho |
|---|---|---|
| fila de eventos | `FilaEmMemoria` | Amazon MSK, particionado por cliente |
| agregação | recálculo em memória a cada rodada | job de janela deslizante |
| perfil | `ConcurrentHashMap` | DynamoDB |
| gatilhos | só os dos perfis de demonstração | produtores próprios (Pix, fatura, fraude) |

A fila em memória não finge ser Kafka: não tem partição, não tem
reprocessamento, não sobrevive a reinício, e um evento consumido se perde se a
agregação falhar depois. As três propriedades que faltam são exatamente as que
justificam existir um MSK no desenho. O que ela preserva é a *forma* — publicar e
consumir são as mesmas duas chamadas, e trocar é escrever outro adaptador.

**O índice não é reproduzível só pelo repositório.** O `semear-vocabulario.ps1`
regenera o vocabulário curado; os termos que o sistema *aprendeu* em execução
não. Rodar `-Limpar` apaga os dois tipos e só o primeiro volta. É consequência
esperada de existir um caminho de escrita — o índice passa a ter estado que o
git não tem —, mas vale saber antes de limpar achando que é idempotente.

### Três perguntas em aberto

**1. O histórico não passa pelo filtro de elegibilidade.** Ele não é uma oferta:
é o que a pessoa já digitou. Mas o destino é o mesmo. Hoje, um cliente que buscou
"aumentar limite do cartão" e não é elegível continua vendo essa linha pelo
histórico, embora a de catálogo tenha sumido. Apagar o histórico de alguém por
inelegibilidade e levar alguém a uma porta fechada são dois erros diferentes, e
qual deles é pior é decisão de negócio, não de código.

**2. Termo vindo do log herda jornada, mas não herda restrição.** A publicação
já classifica o termo aprendido numa jornada, e é isso que faz o ranking alcançar
a camada popular. As restrições continuam de fora, de propósito: um cliente
inadimplente não recebe a oferta `empréstimo pessoal`, mas continua recebendo
`contratar emprestimo`, que leva a uma página de busca e não a uma proposta.

Herdar o veto junto da jornada seria fácil e provavelmente errado — a mesma
inferência que acerta "contratar emprestimo" vai errar em algum termo, e errar
numa restrição significa esconder da pessoa uma busca que ela tinha o direito de
fazer. Restrição é decisão de quem cura. Isto deixa de ser defensável no dia em
que a camada popular ganhar deep link para dentro de uma jornada.

**3. O cliente não consegue distinguir "índice fora" de "nada casou".** Com o
Solr derrubado, a resposta é `200` com `itens: []` e `degradada: false` — porque
`degradada` significa "o perfil não carregou", não "o índice não carregou". Para
a tela isso não muda nada: nos dois casos não há o que mostrar. Para quem opera,
muda — e hoje quem conta a diferença é o log do disjuntor, não a resposta.

Resolver seria acrescentar um motivo novo em `Colheita` e carregá-lo até o JSON,
o que significa mudar a camada de aplicação para acomodar um detalhe de
infraestrutura. Vale a pena no dia em que um cliente precisar reagir
diferente — por exemplo, mantendo em tela a última lista boa em vez de esvaziar.
Enquanto nenhum cliente precisa, o log basta.
