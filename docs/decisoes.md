# Decisões

Registro das decisões de desenho que não se leem no código, com o motivo e o que
foi recusado. Numeradas e append-only: decisão revista ganha entrada nova que
referencia a antiga, em vez de ser editada no lugar — o histórico do raciocínio
vale mais que a limpeza do arquivo.

---

## 1. A resposta carrega identidade de oferta, não só texto

**Contexto.** A API devolvia `termo`, `rotulo`, `destino` e `origem`. Serve para
desenhar uma lista na tela e não serve para mais nada. Uma jornada de produto que
chama esta API para decidir o que oferecer recebia um texto de exibição e uma URL
— e para voltar ao próprio cadastro teria de casar por `termo`, que é texto de
exibição e muda quando alguém melhora a redação.

**Decisão.** `Candidato`, `Sugestao` e o JSON de resposta carregam `ofertaId`,
`jornada` e `produtoAlvo`. A resposta ganha também um campo `melhorOferta` no
topo, com o identificador, o produto, a jornada e a **posição** em que a oferta
saiu na lista.

**Por que repetir no topo.** Sem ele, quem integra precisaria varrer `itens`,
saber que "oferta" é o item com `ofertaId` preenchido, e confiar que a ordem do
array é o ranking. São três suposições sobre a nossa implementação, dentro do
código de outra equipe, que quebram em silêncio no dia em que mudarmos qualquer
uma. Com o campo, a regra é nossa e a resposta é um campo.

**A posição vai junto** porque é ela que liga esta resposta à impressão que o app
vai registrar. A jornada aciona a oferta, o app registra a linha, e o caminho de
aprendizado precisa dos dois lados falando da mesma posição.

**Ausência é informação.** `melhorOferta` vem nulo quando a lista não tem oferta
nenhuma — prefixo que só casa com artigo de ajuda, ou cliente com todas as
ofertas vetadas. Isso não é erro nem lista degradada: significa que para esta
pessoa e este prefixo não há oferta elegível. Devolver a primeira linha qualquer
para o campo não vir vazio seria inventar oferta.

**É a melhor da lista, não a melhor que existe.** Oferta cortada pelo teto de
resultados ou por cota não entra. Anunciar como melhor oferta algo que não foi
entregue daria ao consumidor um identificador que ele não tem como explicar: a
oferta não apareceu para o cliente, não vai ter impressão registrada, e o
aprendizado nunca saberá o que aconteceu com ela.

**Recusado:** um endpoint separado `/v1/melhor-oferta`. Seria uma segunda
consulta ao índice para responder uma pergunta que a primeira já respondeu, e
duas rotas que podem divergir sobre qual é a melhor oferta.

---

## 2. `Procedencia`, separada de `Origem`

**Contexto.** `Origem.veioDoIndice()` respondia a duas perguntas ao mesmo tempo:
"o piso de k-anonimato se aplica a este candidato?" e "a relevância dele é score
do Solr e precisa ser reescalada?". As duas tinham a mesma resposta enquanto todo
item do índice vinha do log ou de catálogo com contador de clientes distintos
preenchido.

Elas param de ter a mesma resposta no momento em que uma jornada cadastra uma
oferta pela API. A oferta existe, é elegível, tem score do Solr — e tem **zero**
clientes distintos, porque ninguém a buscou ainda. "Não" para a primeira
pergunta, "sim" para a segunda.

O catálogo semeado escapava disso porque o semeador escrevia um contador alto
inventado em cada item, só para o item passar pelo `fq` do piso. A regra estava
certa e aplicada no lugar errado, e o preço era um dado falso no índice —
exatamente onde ninguém olha para auditar privacidade.

**Decisão.** `Procedencia` com três valores: `LOG` (agregado do log de busca de
clientes), `CATALOGO` (curado por pessoa ou cadastrado por jornada) e `CLIENTE`
(histórico do próprio dono). O piso de k-anonimato se aplica **só** a `LOG`. A
normalização de escala se aplica a `LOG` e `CATALOGO`.

**A pergunta certa não é "veio do índice?", é "veio de comportamento de
cliente?".** O piso existe para proteger termo digitado por gente: um termo que
só três pessoas buscaram, devolvido a um quarto, conta algo sobre aquelas três.
Nome de produto que o banco oferece não conta nada sobre ninguém — não há sujeito
para reidentificar.

**A procedência não é sobrescrita por promoção.** `Origem` muda quando um gatilho
promove um candidato a `EVENTO`; `Candidato.com(Origem)` preserva a procedência.
Foi essa a falha que o teste `eventoTambemRespondePeloPiso` passou a guardar:
promover apagava a informação de que o termo tinha nascido no log, e o piso
deixava de valer justamente para a linha mais destacada da lista. Hoje há os dois
testes espelhados — oferta de catálogo promovida continua isenta, termo do log
promovido continua respondendo.

**O `fq` ficou disjuntivo** e lido em voz alta é a regra:
`(*:* -tipo:consulta) OR clientes_distintos_i:[30 TO *]` — "ou não é termo do
log, ou limpa o piso".

**Medido depois da mudança**, em 102 documentos: 94 visíveis (os 8 que faltam são
os termos de log abaixo do piso), 19 ofertas visíveis com `clientes_distintos=0`,
e **zero** termos de log abaixo do piso alcançáveis. Sob o `fq` anterior eram 49
visíveis — ele escondia as 44 linhas de catálogo assim que o contador falso saiu.

---

## 3. O cadastro não aceita os números que ele mais gostaria de aceitar

**Decisão.** `POST /v1/ofertas` recusa `ctr`, `taxaAdesao`, `clientesDistintos` e
`freq`. Eles não existem no corpo, não existem no comando da aplicação, e não
existem no agregado de domínio — a ausência é estrutural, não validada.

**Por que.** São os quatro números que mais mexem no ranking, e nenhum deles é
declarável. Eles são medidos pelo caminho de aprendizado a partir de impressão,
clique e adesão reais. Se o cadastro os aceitasse, a jornada com a oferta mais
fraca e o integrador mais otimista ficaria em primeiro lugar, e o ranking
passaria a medir confiança de quem preenche formulário.

A oferta nova entra com os quatro em zero e sobe — ou não — pelo que acontecer
com ela. O que ela tem de saída é o casamento com o prefixo e a jornada líder, e
isso é sinal legítimo.

**A resposta devolve um bloco `desempenho`** com os quatro em zero e a frase
"medido pelo caminho de aprendizado; não aceito no cadastro". Fica num objeto
próprio e não solto na raiz porque a fronteira importa: tudo fora de
`desempenho` foi declarado por quem cadastrou, tudo dentro foi medido pelo
sistema. Misturar os dois níveis faria parecer que `ctr` é tão declarável quanto
`rotulo`.

---

## 4. `destino` é caminho relativo, nunca URL absoluta

**Decisão.** O cadastro recusa `destino` que não comece com `/`, e recusa
`//` junto.

**Por que.** O `destino` é devolvido ao app e o app navega para ele. Aceitar
`https://` aqui faria do cadastro de oferta um redirecionador aberto, com o nome
do banco na frente — um endpoint autenticado que leva o cliente para onde quem
cadastrou quiser. `//` entra na mesma recusa porque o navegador o trata como
absoluto (`//evil.example` herda o esquema da página).

É a validação desta API que mais parece preciosismo e menos é.

---

## 5. A validação acumula; o erro é `problem+json`

**Decisão.** `Oferta.de` coleta **todas** as violações antes de recusar, e
`OfertaInvalida` as carrega. A borda traduz para `422` com
`application/problem+json` (RFC 9457) e uma lista `violacoes`, cada item com
`campo`, `codigo` e `mensagem`.

**Por que acumular.** Uma jornada integrando contra esta API envia um corpo com
quinze campos. Recusar no primeiro problema transforma a integração numa
sequência de tentativas, uma por campo errado, cada uma precisando de um ciclo de
deploy do lado de quem integra. Devolver quinze violações de uma vez é a
diferença entre uma tarde e uma semana.

**Por que `codigo` separado de `mensagem`.** Os dois têm leitores diferentes. A
mensagem é para a pessoa que está integrando e lê o log; o código é para o
programa dela, que precisa distinguir "esqueci o campo" de "mandei no formato
errado" sem casar texto em português. Texto de mensagem muda quando a redação
melhora; código é contrato.

**Por que 422 e não 400.** 400 diz "não consegui ler o que você mandou" e a
correção é no serializador; 422 diz "li perfeitamente e recusei" e a correção é
no valor. Corpo com JSON quebrado dá 400; oferta com jornada reservada dá 422.

**Nada do que sai no corpo conta como o sistema é feito por dentro.** Nome de
classe, caminho de arquivo, consulta ao Solr e pilha de chamadas ficam no log. A
mensagem do Jackson, em particular, cita nome de campo Java e por vezes um trecho
do que foi enviado — útil no log, indesejado na resposta.

**O tratador estende `ResponseEntityExceptionHandler`**, e isso não é detalhe de
framework. O apanhador de `Exception` é rede de segurança, e sem a classe-mãe ele
seria uma armadilha: caminho inexistente, método HTTP errado e `Content-Type` não
suportado são exceções do próprio Spring MVC e sairiam todas como 500. Um 404
reportado como 500 manda quem integra investigar o nosso serviço quando o erro é
um caminho mal digitado do lado dele.

---

## 6. `Boolean` e não `boolean` no corpo recebido

**Decisão.** As três restrições em `OfertaRecebida` são `Boolean`.

**Por que — e isto só apareceu ao rodar.** Com primitivo, um campo ausente no
JSON **não** vira `false`: o Jackson recusa o corpo inteiro com
`Cannot map null into type boolean`, porque `FAIL_ON_NULL_FOR_PRIMITIVES` vem
ligado no Spring Boot e um record não tem valor inicial para ele cair. O
resultado era `400` com corpo vazio para quem simplesmente não declarou uma
restrição que não se aplica — o erro menos acionável possível, numa chamada
perfeitamente razoável. O javadoc afirmava o contrário com confiança.

**A direção do padrão importa.** Restrição ausente significa oferta *sem*
restrição, que é mais visível e não mais permissiva — quem esconde oferta é a
restrição, não a ausência dela. Assumir `true` faria uma oferta sem restrições
desaparecer para todo mundo por causa de um campo esquecido, e desaparecer é o
fracasso que ninguém reporta.

---

## 7. `Jornada.AUSENTE`: o sentinela em um lugar só

**Contexto.** `"busca"` é o balde dos termos sem jornada — o que veio do log e o
histórico do cliente. O literal estava escrito à mão em três arquivos que
precisam concordar: o `Rankeador` o excluía da eleição de jornada líder, `Oferta`
o recusava no cadastro, e `Candidato.completarCom` não o conhecia.

**O bug que isso produziu.** Um termo do histórico chega com `jornada = "busca"`,
que não é vazio. Na deduplicação contra o item de catálogo, o histórico vence e
herda o que não tem — e a herança testava "vazio?", então ele herdava
identificador, produto e destino da oferta e **mantinha** a jornada sentinela. O
resultado era a pior combinação possível: uma oferta identificada, acionável, que
o ranking tratava como se não pertencesse a jornada nenhuma. Sem impulso de
jornada líder e sem afinidade do cliente — justamente na linha de sinal mais
forte do conjunto, a que a própria pessoa já procurou.

**Decisão.** `Jornada.AUSENTE` e `Jornada.ausente(String)`, que trata nulo, vazio
e sentinela como a mesma coisa. Os três usos passam por ela.

**Verificado ao vivo** depois do conserto: a linha de histórico na posição 0 sai
com `ofertaId=OFR-CREDITO-EMPRESTIMO` e `jornada=credito`, e é ela a
`melhorOferta`.

---

## 8. `?limite=` corta o fim da lista e não reordena o começo

**Decisão.** O `limite` da requisição muda só o teto de `Cotas`, via
`comLimite`. As cotas de origem — histórico e evento — **não** encolhem junto.

**A propriedade que isso dá.** Pedir N devolve exatamente os N primeiros de pedir
o máximo. Uma jornada que pede uma linha recebe a mesma linha que receberia na
posição 0 pedindo oito. Ela vem do `Misturador`, que monta a lista numa ordem
fixa — evento primeiro, depois o resto por relevância com a cota de histórico
sendo gasta na mesma ordem — e só para mais cedo quando o teto chega.

**Encolher as cotas junto quebraria exatamente isso.** Com cota de evento
proporcional, `limite=1` zeraria a vaga do evento e devolveria o primeiro
candidato por relevância, que é outra linha. O chamador perderia a capacidade de
pedir pouco sem mudar a resposta.

**O limite não chega à consulta ao índice.** Pedir menos candidatos ao Solr
porque o chamador pediu uma linha economizaria rede e trocaria a resposta: a
peneira derruba candidato por perfil, e com cinco candidatos buscados pode não
sobrar nenhum. O leque é sempre largo; o corte é no fim.

---

## 9. `GET /v1/sugestoes` não tem caminho de erro

**Decisão.** Nenhuma entrada faz esta rota devolver status diferente de 200.
Prefixo curto demais, prefixo longo demais, `q` ausente, `limite` ilegível,
`limite` acima do teto, `explicar` com texto sem sentido, faixa de relacionamento
inexistente no cabeçalho, nenhum cabeçalho de cliente — tudo isso é lista vazia,
lista limitada ou lista degradada.

**Por que.** Uma caixa de busca que devolve 400 enquanto a pessoa digita é pior
do que uma que devolve nada. E recusar com erro informa ao chamador abusivo onde
exatamente está o limite que ele deveria respeitar.

**Os dois parâmetros opcionais chegam como texto**, não como `boolean` e `int`,
pelo mesmo motivo: a conversão do Spring falha alto, e `?explicar=sim` viraria
400 antes de qualquer código nosso rodar.

**`limite` acima do teto é limitado em silêncio**, não recusado. O teto existe
para proteger o serviço, e devolver oito é uma resposta perfeitamente útil.

**`explicar` falha fechada, e aqui isso tem peso.** Só `true` e `1` ligam. A
explicação lista os descartes, e os descartes dizem quais produtos aquele cliente
não pode contratar. Interpretar generosamente um `?explicar=qualquercoisa` como
"sim" seria vazar informação sobre a pessoa por causa de uma consulta mal
escrita. A chave `sugestao.explicacao-habilitada` é o segundo freio, e em
produção fica desligada.

**O teto do prefixo é custo, não relevância.** O analisador de `EdgeNGram` roda
sobre o texto da consulta a cada tecla: um "prefixo" de dez mil caracteres é
trabalho real de CPU no Solr por uma requisição que ninguém digitou.

---

## 10. Leitura estrita no cadastro, leniente no token

**Decisão.** `Segmento` e `Relacionamento` ganharam `talvez(String)` devolvendo
`Optional`, usado só pelo cadastro. O `de(String)` leniente continua atendendo o
token, inalterado.

**Por que os dois.** No token, faixa desconhecida cair em `VAREJO` é degradação
aceitável e erra para o lado seguro: a lista fica mais pobre, nunca mais rica.
Errar para cima ofereceria produto de faixa alta a quem não tem direito.

No cadastro a conta é outra. Uma jornada que escreve `"Uniclass Plus"` está
declarando para quem a oferta vale, e cair em `VAREJO` em silêncio publicaria
para a base inteira uma oferta destinada a uma faixa. O erro silencioso aqui é
exposição de oferta, não lista mais pobre.

Pelo mesmo motivo, segmento e faixa **vazios** são recusados: documento sem eles
fica fora do `{!terms}` e ninguém o encontra — invisível sem erro em lugar
nenhum.

---

## 11. `tipo: oferta` como tipo de primeira classe

**Decisão.** Item de catálogo com produto, e tudo que entra por
`POST /v1/ofertas`, é `tipo: oferta` no índice. Antes caía em `entidade` junto
com vocabulário sem produto.

**Por que.** O índice não tinha como distinguir "coisa que o cliente pode
contratar" de "palavra que ele pode digitar". E oferta semeada e oferta
cadastrada precisam ser o mesmo tipo de documento, ou os dois caminhos divergem.

**Cadastrar e exibir são decisões separadas.** O tipo precisa estar em
`sugestao.tipos-sugeriveis` para a oferta aparecer. Desligar a exibição de todas
as ofertas num incidente não deve exigir apagar o cadastro de ninguém — e é por
isso que isso é parâmetro e não constante.

---

## 12. Reconstruir sem revalidar, na leitura

**Decisão.** `RegistroSolr.porId` monta a `Oferta` pelo construtor canônico, não
por `Oferta.de`.

**Por que.** `Oferta.de` valida, e validar na leitura faz uma regra nova
transformar oferta antiga em `500` na hora de consultá-la. A regra decide o que
entra; o que já entrou se lê como está. É o que permite endurecer a validação
depois sem quebrar o que está gravado.

---

## 13. Limites conhecidos, declarados

Nenhum destes é descuido. Estão aqui para não virarem surpresa.

**Não há autorização em `POST /v1/ofertas`.** Qualquer chamador pode cadastrar
oferta em nome de qualquer jornada. Num sistema de verdade o token carrega a
jornada e o serviço recusa cadastro fora dela — o campo `jornada` do corpo seria
*conferido* contra o token, não aceito.

**Não há transação no cadastro.** A leitura de existência e a gravação são duas
chamadas ao índice, e dois cadastros simultâneos do mesmo identificador podem
ambos reportar `criada`. A gravação continua correta — é idempotente e a última
vence —, só o status fica otimista. Num registro de ofertas de verdade isso é um
`INSERT ... ON CONFLICT`; aqui o alternativo seria um bloqueio distribuído para
acertar um código de status.

**O índice é o registro.** Oferta deveria viver num cadastro próprio, com
histórico e aprovação, e o índice ser uma projeção dele. A porta
`RegistroDeOfertas` foi desenhada para suportar essa separação sem mudar o caso
de uso: `gravar` e `porId` não falam de documento, de coleção nem de commit.

**O `ofertaId` do catálogo semeado é derivado** de `(jornada, produto)` em vez de
vir de um cadastro. Derivado e não sorteado, para semear de novo não trocar a
identidade das ofertas — se o id mudasse a cada execução, toda impressão e todo
clique registrados antes apontariam para oferta que não existe mais. Em produção
isto não se deriva: o `POST` exige o identificador no corpo.

**A latência medida não é a de produção.** O perfil `local` afrouxa os prazos de
18/8/30 ms para 250/100/400 ms. Os números de `requisicoes\bater.ps1 -Cenario
latencia` (p50 12 ms, p99 26 ms, medidos de fora com o cliente HTTP do
PowerShell numa thread) servem para comparar mudanças entre si, não para afirmar
que o orçamento de produção fecha.
