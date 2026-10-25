# sugestao-busca

Camada de sugestão de busca parametrizada por perfil e comportamento, sobre Solr.
Implementa o desenho de solução *Sugestão de busca no app*.

Este repositório é o ambiente de desenvolvimento local do POC. Nada aqui usa dado
real: o vocabulário é sintético e os perfis são falsos, em memória.

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

### Bater nos endpoints

Duas formas, as mesmas chamadas. Não há tela: a evidência é a resposta.

**No VS Code** — [`requisicoes/sugestoes.http`](requisicoes/sugestoes.http), com a
extensão REST Client (`humao.rest-client`). Cada bloco separado por `###` ganha um
link *Send Request*; `Ctrl+Alt+R` envia o bloco do cursor. Os blocos estão
agrupados por pergunta, e cada um explica o que olhar na resposta.

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

## Estrutura

A arquitetura é hexagonal: o domínio e a aplicação são Java comum, sem nenhuma
anotação de framework, e tudo o que é tecnologia vive em `adaptador`.

```
src/main/java/dev/sugestao/busca/
├── dominio/                   regras. Zero Spring, zero SolrJ.
│   ├── Prefixo, Normalizacao  normalização única, usada também pelo semeador
│   ├── Candidato, Restricoes  linha em potencial e o que ela exige do cliente
│   ├── Origem, Cotas          de onde veio e quanto cada origem pode ocupar
│   ├── Cliente, ContextoDeBusca   token: identidade / coorte sem identidade
│   ├── Perfil, SituacaoDaConta    comportamento e estado de conta
│   ├── ClassificadorDeOrigem  gatilho ativo promove candidato a EVENTO
│   ├── Peneira                o que o perfil tira depois que o índice respondeu
│   ├── Rankeador, PesosDeRanking   escala, jornada lider, afinidade
│   ├── Misturador             dedup, cota, limite
│   ├── Descarte               por que cada candidato ficou de fora
│   ├── evento/Saneador        o portão de PII, na ingestão
│   └── agregado/              TermoAgregado, ComportamentoDoCliente,
│                               ClassificadorDeJornada
│
├── aplicacao/
│   ├── porta/entrada/         SugerirSugestoes, RegistrarEventos,
│   │                          AprenderComComportamento
│   ├── porta/saida/           CatalogoDeSugestoes, RepositorioDePerfil,
│   │                          FilaDeEventos, RepositorioDeAgregados,
│   │                          PublicadorDeVocabulario, EscritorDePerfil
│   ├── Prazos, Colheita, RastroDaSugestao, Parametros*
│   └── casodeuso/             SugerirSugestoes (leitura),
│                              RegistrarEventos + AprenderComComportamento (escrita)
│
├── adaptador/
│   ├── entrada/web/           controllers, DTOs, resolvedor do cliente
│   ├── entrada/agendador/     o relógio que aciona o aprendizado
│   └── saida/solr/            ConsultaDeCandidatos (as 4 camadas),
│       │                      CatalogoSolr (lê), PublicadorSolr (escreve)
│       saida/fila/            FilaEmMemoria — o lugar do MSK
│       saida/agregado/        AgregadosEmMemoria — o lugar do job de janela
│       saida/perfil/          PerfisEmMemoria — o lugar do DynamoDB
│
└── configuracao/              o único lugar que sabe quem implementa cada porta

compose.yaml                   Solr 10 em container — escrito, nunca executado
scripts/solr-local.ps1         Solr 10 do zip, sem container e sem admin
scripts/habilitar-docker.ps1   obsoleto: o Docker já existe nesta máquina
scripts/criar-colecao.ps1      cria a coleção e aplica o schema
scripts/semear-vocabulario.ps1 vocabulário sintético
scripts/conferir-piso.ps1      portão de publicação: k-anonimato e zero-resultado
solr/schema-sugestoes.json     payload da Schema API
solr/README.md                 por que cada campo existe
```

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
4. Página de demonstração: digitar as mesmas letras com perfis diferentes.
5. Camada 4 (LTR). Agora há impressão com posição, que era o que faltava. O que
   falta é volume: com 101 documentos não há o que um modelo aprenda.

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

### Duas perguntas em aberto

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
