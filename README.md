# sugestao-busca

Camada de sugestão de busca parametrizada por perfil e comportamento, sobre Solr.
Implementa o desenho de solução *Sugestão de busca no app*.

Este repositório é o ambiente de desenvolvimento local do POC. Nada aqui usa dado
real: o vocabulário é sintético e os perfis são falsos, em memória.

## O que já está instalado nesta máquina

Tudo fora do `Program Files`, em pastas do usuário, sem privilégio de administrador.

| | versão | onde |
|---|---|---|
| Temurin JDK | 25.0.4 LTS | `C:\Users\migue\tools\jdk-25` |
| Apache Solr | 10.0.0 (com módulo `ltr`) | `C:\Users\migue\tools\solr-10.0.0` |
| PortableGit | 2.56.0 | `C:\Users\migue\tools\PortableGit` |
| Maven | via `mvnw`, baixado na primeira build | — |

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

Hoje, do zip — não há runtime de container nesta máquina:

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

O `compose.yaml` está pronto e aponta para `solr:10.0.0` com o módulo `ltr`, na
mesma porta e com a mesma coleção. Ele não roda ainda: **não há Docker, Podman nem
WSL nesta máquina**, e habilitar qualquer um deles exige elevação, que não dá para
fazer a partir daqui.

Para habilitar, em um PowerShell **aberto como Administrador**:

```powershell
.\scripts\habilitar-docker.ps1
```

O script habilita a **Virtual Machine Platform** e o **WSL 2** — é exatamente o que
o instalador do Docker Desktop reclama faltar. O usuário desta máquina é
administrador local, então o UAC pede só confirmação, sem senha.

Se o terminal elevado ainda estiver com a política `Restricted`, não vale a pena
contornar por causa de um comando. O script inteiro se resume a isto, e dá para
digitar direto:

```powershell
wsl --install --no-distribution
```

Depois: reiniciar, rodar de novo o instalador do Docker Desktop (**x86_64**, não o
Arm), e então, de volta a um terminal normal:

```powershell
docker compose up -d
.\scripts\criar-colecao.ps1
```

O `criar-colecao.ps1` é o mesmo nos dois caminhos — ele fala com `localhost:8983`
e não sabe se do outro lado está o zip ou o container. Trocar de um para o outro
não muda nada no projeto.

### Semear e conferir

```powershell
.\scripts\semear-vocabulario.ps1 -Limpar
.\scripts\conferir-piso.ps1
```

O `conferir-piso.ps1` é o portão de publicação, não um teste de relevância. Ele
sai com código 1 se um termo abaixo do piso aparecer em qualquer consulta, ou se
um termo publicado não for alcançável por nenhum prefixo plausível. Nenhum dos
dois tem faixa aceitável.

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

## Estrutura

A arquitetura é hexagonal: o domínio e a aplicação são Java comum, sem nenhuma
anotação de framework, e tudo o que é tecnologia vive em `adaptador`.

```
src/main/java/dev/sugestao/busca/
├── dominio/                   regras. Zero Spring, zero SolrJ.
│   ├── Prefixo, Normalizacao  normalização única, usada também pelo semeador
│   ├── Candidato, Sugestao    linha em potencial / linha pronta
│   ├── Origem, Cotas          de onde veio e quanto cada origem pode ocupar
│   ├── Perfil                 histórico, gatilhos, elegibilidade, NEUTRO
│   ├── ClassificadorDeOrigem  gatilho ativo promove candidato a EVENTO
│   ├── Peneira                o que o perfil tira depois que o índice respondeu
│   └── Misturador             dedup, cota, limite
│
├── aplicacao/
│   ├── porta/entrada/         SugerirSugestoes (o contrato do caso de uso)
│   ├── porta/saida/           CatalogoDeSugestoes, RepositorioDePerfil
│   ├── Prazos, ParametrosDeSugestao
│   └── casodeuso/             SugerirSugestoesCasoDeUso: leque, prazos, ordem
│
├── adaptador/
│   ├── entrada/web/           controller, DTO, resolvedor do cliente
│   └── saida/solr/            ConsultaDeCandidatos (as 4 camadas), CatalogoSolr
│       saida/perfil/          perfis falsos em memória
│
└── configuracao/              o único lugar que sabe quem implementa cada porta

compose.yaml                   Solr 10 em container (quando houver runtime)
scripts/solr-local.ps1         Solr 10 do zip, sem container e sem admin
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

- **Não existe starter do Spring para Solr** desde o Boot 2.4. O SolrJ 10 entra como
  dependência comum e o client vira um `@Bean` nosso.
- **Duas análises de prefixo**, uma para a frase e outra por palavra, com pesos
  diferentes na consulta. O porquê está em `solr/README.md`.
- **Sinônimo é campo do documento**, não arquivo de configuração: o negócio edita a
  redação alternativa junto do termo, sem reload.
- **`clientes_distintos_i` entra como `fq`**, nunca como pós-filtro. É o piso de
  k-anonimato, e é o único parâmetro do projeto que, afrouxado, vira incidente de
  privacidade em vez de queda de relevância.
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

## Próximos passos

1. ~~Vocabulário sintético~~ — feito, mas com **95 termos, não os 2 a 5 mil** do
   desenho. O mecanismo inteiro aparece com esse tamanho; o que não aparece é
   concorrência de verdade entre candidatos no mesmo prefixo.
2. ~~`GET /v1/sugestoes` com o leque paralelo em threads virtuais~~ — feito.
3. Página de demonstração: digitar as mesmas letras com perfis diferentes.
4. Registro de impressão **com posição**. É o que falta para a camada 4 ser
   treinável e para os dois números de relevância do desenho existirem.

### Uma pergunta em aberto

O histórico do próprio cliente não passa pelo filtro de elegibilidade, porque ele
não é uma oferta: é o que a pessoa já digitou. Mas o destino é o mesmo. Hoje, um
cliente que buscou "aumentar limite do cartão" e não é elegível continua vendo
essa linha pelo histórico, embora a linha de catálogo tenha sumido. Apagar o
histórico de alguém por inelegibilidade e levar alguém a uma porta fechada são
dois erros diferentes, e qual deles é pior é decisão de negócio, não de código.
