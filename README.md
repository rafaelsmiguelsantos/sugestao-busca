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

| rota | o que faz |
|---|---|
| `GET /v1/sugestoes` | a lista, e `melhorOferta` com o ID acionável no topo |
| `POST /v1/ofertas` | a jornada dona cadastra a própria oferta, idempotente |
| `GET /v1/ofertas/{id}` | o estado gravado, para conferência |
| `POST /v1/eventos` | impressão, clique e adesão — com posição |
| `POST /v1/aprendizado/rodar` | roda a agregação agora (só no perfil `local`) |
| `GET /actuator/health` | saúde |

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

**Três formas de bater nos endpoints**, as mesmas chamadas:
[`requisicoes/sugestoes.http`](requisicoes/sugestoes.http) (REST Client do VS
Code), [`requisicoes/bater.ps1`](requisicoes/bater.ps1) (terminal) e
[`requisicoes/postman-sugestao-busca.json`](requisicoes/postman-sugestao-busca.json)
(Postman/newman, 44 asserções).

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

Duas formas, as mesmas chamadas. Não há tela: a evidência é a resposta.

**No VS Code** — [`requisicoes/sugestoes.http`](requisicoes/sugestoes.http), com a
extensão REST Client (`humao.rest-client`). Cada bloco separado por `###` ganha um
link *Send Request*; `Ctrl+Alt+R` envia o bloco do cursor. Os blocos estão
agrupados por pergunta, e cada um explica o que olhar na resposta.

**Postman / newman** — [`requisicoes/postman-sugestao-busca.json`](requisicoes/postman-sugestao-busca.json),
com **44 asserções** sobre o contrato público. Roda sem instalar nada permanente:

```powershell
npx --yes newman@6 run .\requisicoes\postman-sugestao-busca.json
```

Ou `File > Import` na interface do Postman. As asserções não dependem do
`?explicar=true`: elas conferem o que qualquer cliente recebe.

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

Três sinais multiplicativos em cima do score do índice. **Todos só promovem** —
para rebaixar existe a `Peneira`, que também devolve o motivo.

| sinal | o que responde | peso |
|---|---|---|
| escala | põe score do Solr e score do histórico na mesma régua | — |
| jornada líder | qual intenção o prefixo revela, e o produto dela lidera | 1.6 × 1.25 |
| afinidade | o que **esta pessoa** faz, não o que a população faz | até 1.5 |

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

O risco conhecido é a bolha — afinidade reforça afinidade. O teto de 1.5 e a cota
do `Misturador` são o que seguram isso: a cota garante que a lista continue
mostrando caminhos que o ranking sozinho teria enterrado.

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
│                               ClassificadorDeJornada
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
│   ├── saida/agregado/        AgregadosEmMemoria — o lugar do job de janela
│   └── saida/perfil/          PerfisEmMemoria — o lugar do DynamoDB
│
└── configuracao/              o único lugar que sabe quem implementa cada porta,
                               e o único que sabe que existe disjuntor

docs/decisoes.md               as decisões, com o que foi recusado e por quê
docs/cliente-web.md            React e Angular: CORS, debounce, ordem das respostas

requisicoes/sugestoes.http     REST Client do VS Code, agrupado por pergunta
requisicoes/bater.ps1          os mesmos cenários no terminal, sem extensão
requisicoes/postman-sugestao-busca.json   collection com 44 asserções (newman)

compose.yaml                   Solr 10 em container — escrito, nunca executado
scripts/solr-local.ps1         Solr 10 do zip, sem container e sem admin
scripts/habilitar-docker.ps1   obsoleto: o Docker já existe nesta máquina
scripts/criar-colecao.ps1      cria a coleção e aplica o schema
scripts/semear-vocabulario.ps1 vocabulário sintético
scripts/conferir-piso.ps1      portão de publicação: k-anonimato e zero-resultado
solr/schema-sugestoes.json     payload da Schema API
solr/README.md                 por que cada campo existe
```

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
7. **Autorização no cadastro de oferta.** É o buraco mais relevante que sobrou:
   hoje qualquer chamador cadastra em nome de qualquer jornada. O token deveria
   carregar a jornada e o serviço *conferir* o campo do corpo contra ele.
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
