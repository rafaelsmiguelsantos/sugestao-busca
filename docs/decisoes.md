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

## 13. O disjuntor entra como decorador da porta

**Contexto.** O caminho de leitura já tinha prazo por dependência e caminho
alternativo declarado. Faltava o terceiro mecanismo, e a diferença entre os três
é a parte que se confunde: o prazo protege a *requisição*, o caminho alternativo
protege a *resposta*, e o disjuntor protege a *dependência e o processo*.

Com o Solr fora e só o prazo, cada tecla ainda abre uma conexão TCP e espera o
prazo vencer — dezenas de tentativas por segundo contra um host morto, cada uma
segurando descritor e thread. O prazo faz a falha ser rápida; o disjuntor faz a
falha ser barata. E o meio-aberto existe para o outro lado do problema: quando a
dependência volta, ela volta fria, e devolver de uma vez toda a carga que estava
sendo recusada é a forma mais comum de derrubá-la outra vez.

**Decisão.** `Disjuntor` (máquina de estados, sem framework, testável sem subir
nada) mais `CatalogoComDisjuntor`, que implementa a mesma porta que decora. Quem
empilha os dois é `ConfiguracaoDeSugestao`.

**O que isto custou ao resto do projeto: nada.** Dois arquivos novos e quatro
linhas na configuração. O domínio não sabe que existe disjuntor, o caso de uso
não sabe, e o `CatalogoSolr` não sabe. Quando o disjuntor abre, a aplicação vê
uma exceção da porta — igual à que já via quando o Solr respondia com erro — e a
converte em `Colheita.FALHOU` e lista degradada, por um caminho testado desde
antes deste arquivo existir. É o melhor argumento que este repositório tem a
favor de portas e adaptadores: resiliência contra dependência que cai é
exatamente o requisito que costuma vazar para todo lado.

**As três alternativas, e o que cada uma acopla.**

*No caso de uso:* uma classe da camada de aplicação passaria a conhecer um modo
de falha de rede, e teria de ser alterada no dia em que a dependência virasse
outra coisa.

*Dentro do `CatalogoSolr`:* funcionaria, e todo adaptador que precisasse de
disjuntor reimplementaria o seu. Pior, ligar e desligar exigiria mexer no código
que fala com o Solr — o código que menos se quer mexer.

*Com `@Aspect` ou proxy:* resolveria sem escrever classe nenhuma e deixaria o
comportamento invisível. Ninguém lendo o adaptador descobriria que existe um
disjuntor, e ninguém lendo a configuração descobriria a ordem das coisas. Um
decorador explícito custa um arquivo e é legível de ponta a ponta.

**Por fora do adaptador, não por dentro.** Por dentro, o disjuntor contaria
falhas que o adaptador já pagou em rede. Por fora, ele recusa antes de abrir
conexão.

**Janela, e não falhas consecutivas.** Consecutivas é mais simples e não abre no
caso que mais importa: uma dependência que responde metade das vezes nunca
acumula a sequência, e metade das pessoas fica sem lista. O `minimoDeChamadas`
impede o oposto — abrir com amostra de tamanho um, que é o cenário do Solr
recém-subido estourando o prazo de 18 ms legitimamente.

**Desligar é não empilhar.** `sugestao.disjuntor.habilitado=false` faz a
configuração devolver o adaptador cru. Um disjuntor no-op passaria a existir no
caminho de execução para não fazer nada, e "não fazer nada" é o tipo de coisa
que, um dia, faz algo.

**Só na porta de leitura.** O cadastro de oferta é de volume baixo e tem uma
jornada esperando resposta: recusar sem tentar faria um cadastro falhar por causa
de falhas de *outras* chamadas. A publicação roda no agendador, que já é o
mecanismo de nova tentativa.

**Um limite declarado:** o rastro não distingue "índice fora" de "disjuntor
aberto" — as duas aparecem como falha da dependência. Distinguir exigiria um
motivo novo em `Colheita`, o que significaria mudar a camada de aplicação para
acomodar um detalhe do adaptador, contradizendo o que esta decisão defende. Quem
distingue é o log do disjuntor, que registra cada transição de estado.

**Medido com o Solr derrubado:** `msTotal` de 10 ms (tentando e falhando) para
0–1 ms (nem tentando) depois de abrir, e o ciclo
`ABERTO → MEIO_ABERTO → ABERTO → MEIO_ABERTO → FECHADO` no log, com a busca
devolvendo 200 o tempo inteiro e se recuperando sem reiniciar o app.

---

## 14. Servidor de recurso, e a regra de jornada na aplicação

**Contexto.** As rotas eram abertas e a identidade vinha de cabeçalho — dito pelo
cliente, não provado. Quem escolhia o próprio `X-Cliente-Id` escolhia o histórico
de quem quisesse, e quem escolhia o próprio `X-Cliente-Relacionamento` via oferta
de faixa que não era a dele. E qualquer chamador cadastrava oferta em nome de
qualquer jornada.

**Decisão.** Servidor de recurso OAuth 2.0 com JWT. Escopo por rota,
`anyRequest().denyAll()`, e `401`/`403` em `problem+json` como o resto dos erros.

### Valida e não emite

Não entra o starter de *authorization server*. Emitir token é papel do provedor
de identidade, e um BFF que emite o próprio token é um BFF que decide quem ele
mesmo é.

Os tokens de desenvolvimento saem de `scripts/gerar-token.ps1` — um script,
**fora da aplicação**. Um endpoint `/dev/token`, por bem gateado que fosse, seria
um caminho de emissão vivendo no mesmo processo que valida, e "bem gateado" é uma
propriedade que se perde num refactor. Num script não há nada para gatear: o
arquivo não sobe com a aplicação.

### A regra de jornada mora no caso de uso

`ofertas:escrever` diz que o chamador pode cadastrar **alguma coisa**. A
reivindicação `jornada` diz **o quê**.

A separação é o ponto. O escopo é permissão e é decidido na cadeia de segurança;
"a oferta tem de ser da jornada de quem cadastra" é regra de negócio e é decidida
no `CadastrarOfertaCasoDeUso`, que recebe um `Autorizacao`. Com isso a regra é
testável sem HTTP e continuaria valendo se o cadastro passasse a entrar por uma
fila, por um lote ou por um console administrativo.

`Autorizacao` é deliberadamente pobre: tem a jornada e o privilégio irrestrito, e
nada mais. Toda informação que entrasse ali passaria a ser informação que a
aplicação sabe extrair de um token, e token é assunto da borda.

**Separada do comando, e não é cerimônia:** o comando é o que o chamador *pediu*,
a autorização é o que ele *pode*. Juntar os dois permitiria a quem monta o pedido
declarar a própria permissão.

**Autorização depois da validação.** Autorizar primeiro pareceria mais seguro e
diria menos: o chamador receberia 403 para um corpo que também estava malformado,
corrigiria o token, reenviaria, e só então descobriria os oito campos errados. E
não há risco — a validação é pura, não toca o registro e não revela nada sobre
ofertas existentes.

**A jornada comparada é a normalizada pelo domínio**, não a que veio no corpo.
Sem isso, `" Crédito "` passaria ou não dependendo de espaço em branco.

**Jornada não é namespace.** `credito` não autoriza `credito-imobiliario`. Se um
dia precisar ser hierárquico, a hierarquia tem de ser declarada, nunca inferida
de casamento de texto.

### Detalhes que custaram algo

**O segredo padrão é vazio e a aplicação recusa subir.** Um segredo embutido
funcionaria em qualquer lugar, inclusive em produção, e assinaria tokens que
qualquer leitor deste repositório sabe forjar. Falhar na subida é ruidoso,
acontece uma vez, e acontece para quem está configurando.

Isso quebrou o teste de contexto — exatamente como quebraria um deploy sem
segredo. A primeira correção foi um `src/test/resources/application.properties`,
e **estava errada**: arquivo de mesmo nome **substitui** o do `src/main` inteiro,
não soma, e o contexto passou a falhar com `NullPointerException` em
`sugestao.aprendizado`. Arquivo de *perfil* é aditivo. Virou
`application-teste.properties` com `@ActiveProfiles("teste")`.

**Substituir o ponto de entrada derruba o `WWW-Authenticate`.** Trocar o corpo do
401 por `problem+json` substitui o `BearerTokenAuthenticationEntryPoint`, que era
quem emitia aquele cabeçalho — e o RFC 6750 o exige. Quem apontou foi a asserção
13 da collection, depois de o comentário no código ter afirmado por escrito que o
cabeçalho continuava saindo. Ele agora é escrito à mão, sem o parâmetro `error=`,
que é justamente onde se contaria "expirado" ou "assinatura inválida".

**O escopo é comparado exato, nunca por `contains()`.** A reivindicação `scope` é
uma lista separada por espaço; um `contains("ofertas:todas")` casaria o privilégio
mais alto desta API dentro de qualquer escopo que o tenha como pedaço —
`relatorio:ofertas:todas-as-regioes` concederia tudo.

**Não há CSRF**, e não é esquecimento: não há sessão nem cookie. A credencial vai
no `Authorization`, que o navegador não anexa sozinho, e é a anexação automática
que o CSRF existe para conter.

**CORS antes da autenticação.** O preflight não leva `Authorization`, por
especificação — se ele passasse pela cadeia de autenticação, tomaria 401 e nenhum
cliente de navegador passaria do primeiro `OPTIONS`.

**`/actuator/metrics` não é público**, só `health`. Latência por rota conta o
desenho do serviço a quem perguntar; `health` é o que o balanceador consulta, e
ele não tem token.

**Trocar HMAC por RSA com JWKS remoto é trocar um método.** Nem o resolvedor de
cliente, nem as regras de rota, nem o caso de uso mudam.

**Verificado:** 14 de 14 combinações de rota e token, incluindo
`POST /v1/ofertas` numa jornada alheia dando 403 com o token que *tem* o escopo
de escrita, e `GET /v1/nao-existe` com token válido dando 403 em vez de 404 —
`denyAll` fazendo rota nova nascer fechada.

---

## 15. A afinidade: piso de confiança e meia-vida

**Contexto.** Duas decisões anteriores pareciam boas isoladas e, juntas, faziam o
sistema acreditar rápido demais e nunca esquecer.

*A primeira:* a afinidade era normalizada pela jornada mais forte **da própria
pessoa**. A intenção estava certa — não transformar a medida num segundo índice
de popularidade, que já existe no `ctr`. O efeito era que **um clique dava
afinidade 1,0**: com `{credito: 2.0}`, o máximo da pessoa é 2,0, e 2,0/2,0 = 1.
Um clique acidental punha a pessoa no teto de promoção da jornada.

*A segunda:* a afinidade era substituída a cada rodada. Isso apagava tudo quando
a janela não tinha cliques (decisão 13 corrigiu para preservar), e preservar fez
nada esfriar nunca.

**Decisão.** `PesosDeAfinidade`, no domínio, com três números:

```
peso_efetivo(evento) = peso_base × 0.5^(idade / meia_vida)
pontos(j)            = Σ peso_efetivo dos eventos da jornada j na janela
afinidade(j)         = min(1, pontos(j) / piso_de_confianca)
```

Com `piso = 6`, `meia_vida = 7 dias`, `janela = 90 dias`, e clique valendo 2 e
adesão 5:

| evidência | afinidade | fator no ranking |
|---|---|---|
| 1 clique de hoje | 0,33 | 1,17x |
| 1 adesão de hoje | 0,83 | 1,42x |
| 3 cliques de hoje | 1,00 | 1,50x |
| 3 cliques, 7 dias | 0,50 | 1,25x |
| 3 cliques, 30 dias | 0,05 | 1,03x |

**O divisor constante substitui o máximo da pessoa, e a propriedade que
importava fica.** Três cliques e trezentos cliques chegam ao mesmo 1,0, porque o
valor é limitado em 1 — e nenhum dos dois é comparado com a população. O que
muda é que pouca evidência passa a valer pouco, que é o que pouca evidência é.

### Por idade do evento, nunca por rodada

Decaimento por rodada é o que parece natural e é uma armadilha, e foi a primeira
coisa que eu ia implementar. Três problemas:

1. O agendador roda a cada 15 s. Um fator por rodada esvaziaria a afinidade em
   minutos.
2. Trocar o intervalo do agendador mudaria a velocidade do esquecimento — uma
   decisão de produto virando efeito colateral de configuração de
   infraestrutura.
3. `comportamentos()` recalcula a janela inteira a cada chamada, então acumular
   entre rodadas contaria o mesmo clique centenas de vezes.

Por idade, rodar a agregação uma vez ou cem vezes no mesmo minuto dá a mesma
resposta. Há teste para isso (`cadenciaNaoMudaOResultado`).

### Janela e meia-vida fazem trabalhos diferentes

A **meia-vida** decide a velocidade do esquecimento. A **janela** só decide
quando parar de somar, e é generosa — 90 dias — porque interesse em crédito
imobiliário não se demonstra todo dia, e cortar em 24 h chamaria de desinteresse
o que é só intervalo entre acessos. A meia-vida já tornou irrelevante o que é
velho; a janela só evita somá-lo.

O construtor recusa janela menor que a meia-vida: isso cortaria o evento antes de
ele ter decaído, e o esquecimento voltaria a ser o corte seco que esta decisão
substitui.

### Consequências que vieram de carona

**A escrita de perfil voltou a sobrescrever a afinidade** — inclusive com vazio —
e agora está certo, porque o vazio voltou a querer dizer algo: "nenhum clique em
três meses". O histórico continua protegido contra vazio, e a assimetria é o
ponto: ele é *acumulado* ("o que você digitou ultimamente"), ela é *medida* ("em
que você demonstrou interesse").

**A afinidade da demonstração deixou de ser afirmada.** Ela era
`Map.of("credito", 1.0)` escrito no código — uma afirmação sobre o resultado,
sem nada por trás, que a sobrescrita agora zeraria na primeira rodada. Virou
`EventosDaDemonstracao`: cliques e adesões falsos, semeados no agregado, de onde
a afinidade é **calculada** pelo mesmo caminho que calcularia a de um cliente de
verdade. A demonstração passou a provar o pipeline em vez de contorná-lo.

**O atraso inicial do agendador foi a zero.** Ele era igual ao intervalo, o que
dava uma janela cega de 15 s depois da subida: nesse intervalo nenhum perfil
tinha afinidade calculada. Numa demonstração parece defeito; em produção é o
primeiro usuário do dia recebendo lista pior sem razão. Deixou de ser necessário
porque a rodada agora atualiza perfil antes de tocar o índice (decisão 13).

**Termo vazio não vira vocabulário.** Os eventos semeados são cliques sem termo —
neles o que importa é a jornada — e criavam um agregado de chave `""`. O corte
vale também para evento de cliente cujo termo o saneamento reduz a nada: seria
uma entrada que nenhuma busca alcança e que o portão de publicação nem examina,
porque ele ignora chave com menos de dois caracteres.

**Verificado ao vivo**, 2 s depois da subida e sem gatilho manual:
`pf-pix-recusado` tem `r=2,999243` em crédito contra `r=2,0` de `pf-novo`. E o
`2,999243` em vez de `3,0` exato é o decaimento aparecendo: os cliques semeados
têm minutos de idade, e `0,5^(minutos/7 dias)` tira aquele fiapo. O código
anterior dava `3,0` redondo porque não decaía nada.

---

## 16. Medir antes de mexer no ranking

**Decisão.** A porta `MedidorDeBusca` e o adaptador Micrometer foram escritos
*antes* das três mudanças de relevância desta rodada, não depois.

**Por quê.** Tolerância a erro de digitação, sinal negativo e afinidade por
produto são todos afirmações sobre quantas buscas passam a encontrar algo. Sem
taxa de zero-resultado medida antes e depois, "melhorou" é opinião — e pior,
opinião que ninguém consegue contestar com número.

**O que a medição custou admitir.** Ela não melhora nada que o cliente veja. É
instrumentação pura, e num POC de entrevista é o item mais difícil de justificar
porque não há demonstração. O argumento que sustenta é outro: os três sinais que
vieram depois têm limites conhecidos — esparsidade da afinidade por produto, ruído
da tendência, viés de posição da fadiga — e nenhum desses limites é observável sem
isto.

**O desfecho em quatro valores, e não dois.** Lista vazia ou não vazia é a
classificação óbvia e ela esconde o caso que motivou a rodada:

| desfecho | significado |
|---|---|
| `PREFIXO_RECUSADO` | nem era busca — alguém no meio da primeira letra |
| `ZERO_RESULTADO` | buscou e não achou nada |
| `SEM_OFERTA` | achou linhas, nenhuma acionável |
| `COM_OFERTA` | achou oferta |

`q=financiamento` devolvia uma linha de ação e nenhuma oferta. Não era
zero-resultado, então nenhum contador de lista vazia acusaria; não era sucesso,
porque quem buscou financiamento não recebeu proposta. Com dois valores, o caso é
invisível.

E `prefixoAceito` foi acrescentado ao rastro só para separar "não encontrou" de
"nem era busca". Sem isso, a taxa de zero-resultado fica dominada por gente
digitando a primeira letra e para de significar qualquer coisa — justamente a
métrica que ia dizer se a tolerância funcionou.

**Recusado: chamar o `MeterRegistry` direto do caso de uso.** Economizaria dois
arquivos e custaria a propriedade que sustenta o desenho — o caso de uso não
conhece framework nenhum e é testável inteiro sem subir contexto.

**Recusado: rótulo com o prefixo.** Dois motivos, e cada um basta. Privacidade:
prefixo é texto de busca de uma pessoa, e o piso de k-anonimato existe para que
termo raro não saia do serviço; um contador
`busca{prefixo="adiantamento para maria"}` desfaria isso pela porta da
observabilidade, onde ninguém procura vazamento — e painel de métrica costuma ser
mais acessível que índice de busca. Custo: cardinalidade ilimitada derruba o
coletor, e com ele as métricas que iam ser úteis. O prefixo entra como faixa de
comprimento, que responde "a partir de quantas letras a busca encontra?" sem
guardar nenhuma letra.

---

## 17. Tolerância a erro de digitação como segunda consulta

**Decisão.** Quando a consulta exata traz menos de três candidatos, o adaptador
faz uma **segunda** consulta ao Solr, com distância de edição, contra um campo
próprio sem EdgeNGram.

**Por quê não uma cláusula na primeira.** Com `q = "emprestmo OR emprestmo~1"` no
edismax, o fuzzy é aplicado a todos os campos do `qf` — inclusive aos dois
EdgeNGram, onde cada palavra já está expandida em dezenas de gramas e a
enumeração de termos difusos multiplica por isso. Toda busca pagaria por um
recurso que só importa quando a busca falhou. Como segunda tentativa, o custo no
caminho quente é **zero**, e a ida extra acontece exatamente quando a alternativa
era a lista vazia.

**Por quê campo próprio.** Fuzzy sobre grama de prefixo compara pedaço com pedaço
e acha semelhança onde não há: o grama `emp` está a distância 1 de `amp`, `emb` e
`imp`, que são começos de palavras sem relação nenhuma.

**O limite de 5 caracteres não é economia, é precisão.** Com quatro letras,
distância 1 alcança um vizinho enorme — `card` casa `cart`, `cardi`, `carro`,
`cara`. Lista errada com confiança é pior que lista vazia, e em banco oferecer
consórcio a quem digitou "consignado" torto não é recuperação simpática, é produto
errado.

**O enum em vez de um booleano.** `ToleranciaAplicada` distingue
`ACIONADA_COM_GANHO` de `ACIONADA_SEM_GANHO` porque esta é a única melhoria da
rodada que pode **piorar a precisão enquanto melhora a cobertura**. Sem separar os
dois, o único número visível seria a queda da taxa de zero-resultado, que cairia
igual se metade das recuperações fosse lixo.

**O escopo, declarado:** isto conserta erro de **digitação**, não falta de
vocabulário. `grana` continua devolvendo zero, e nenhuma distância de edição
conserta sinônimo que não existe.

**Decisão operacional que apareceu junto.** A Schema API do Solr processa o lote
inteiro de forma atômica: reaplicar `schema-sugestoes.json` numa coleção existente
falha com "already exists" campo a campo e **nada** é adicionado, nem o campo
novo. Daí `solr/schema-tolerancia.json`, com só o delta. E como `copyField` age na
indexação, aplicar o delta não preenche documento existente — precisa de
`semear-vocabulario.ps1 -Limpar` depois.

---

## 18. O sinal negativo, e a invariante que caiu

**Decisão.** Linha exibida muitas vezes e nunca clicada passa a valer menos, por
cliente.

**A invariante que isso quebrou.** O `Rankeador` mantinha, de propósito: "os
sinais só promovem, nunca rebaixam; para rebaixar existe a `Peneira`, que também
devolve o motivo". A frase continua certa. O que havia do outro lado era um
defeito maior: **sem sinal negativo o ranking só sabe concordar consigo mesmo.**
Uma oferta exibida quarenta vezes e nunca clicada não perdia nada, porque `ctr` é
agregado da **população** e não desta pessoa.

Trocada por uma invariante mais fraca e verificável: *rebaixar é permitido, apagar
não, e todo rebaixamento vai para o rastro.*

**A ordem das operações não é intercambiável.** Promover, limitar no teto,
rebaixar pela fadiga. Rebaixar antes do teto faria a fadiga desaparecer quando o
produto das promoções estourasse o limite — ela pararia de funcionar exatamente
para quem tem mais afinidade, que é quem mais precisa dela. Há teste de unidade
dedicado só a essa ordem, porque a forma errada **falha em silêncio**.

**O que foi deliberadamente não corrigido: viés de posição.** Uma linha na posição
5 é vista menos que uma na posição 0, e contar "exibiu e não clicou" trata as duas
igual — a linha que já estava embaixo acumula fadiga mais rápido e afunda mais.
Corrigir exige modelo de propensão: estimar a probabilidade de a linha ter sido
*vista*, não só exibida. É trabalho de outra ordem de grandeza, e esperar por ele
para não ter sinal negativo nenhum deixaria o defeito maior no lugar para evitar o
menor.

**Dois defeitos meus, achados verificando.**

O primeiro: a agregação guardava a chave **normalizada** (`antecipacao de limite`)
e o `Rankeador` consultava com o termo **cru** do índice (`antecipação de limite`,
com acento). A chave nunca casava para nenhum termo acentuado — a maior parte do
catálogo. A fadiga era calculada, gravada no perfil e lida a cada tecla sem
rebaixar nada. Mesma família do defeito que a afinidade já tinha tido com a
jornada. Pior: a verificação de ponta a ponta **passava verde**, porque a linha
subia por outro motivo (afinidade de produto) e o script media só "subiu ou não".
O conserto foi normalizar na leitura, dentro de `Perfil.fadigaDe` — o mapa é
guardado normalizado, então quem o acessa normaliza, em vez de esperar que todos
os chamadores futuros lembrem.

O segundo: havia um `fadigaMaxima` de 0,4 na fadiga e um `fadigaMinima` de 0,6 no
ranking, e os dois se multiplicavam. No teto, o fator saía
`1 − 0,4 × (1 − 0,6) = 0,84` — uma perda de 16% que não é o que nenhum dos dois
números dizia. Apareceu numa verificação esperando 0,6 e encontrando 0,84. Agora a
fadiga devolve só a **fração** da penalidade (0 a 1) e a profundidade é uma decisão
só. Dois tetos para a mesma coisa produzem efeito que ninguém prevê lendo a
configuração.

---

## 19. Afinidade em dois níveis, e o teto que silenciava um sinal

**Decisão.** A afinidade passa a ter dois níveis: jornada e `produtoAlvo`.

**Por quê.** Era um escalar por jornada, e isso a tornava incapaz de responder à
pergunta mais comum: **financiamento, empréstimo e consignado são todos da jornada
`credito`**, então clicar em consignado promovia empréstimo exatamente igual. O
sistema sabia que a pessoa queria crédito e não tinha como saber qual.

**A fraqueza foi declarada antes de o código começar, e é esparsidade.** Há meia
dúzia de jornadas e vinte e tantos produtos; o clique se espalha. Com o mesmo piso
de confiança nos dois níveis (6,0 pontos, três cliques), a afinidade por produto
ficaria matematicamente correta e **praticamente sempre zero** — um recurso inerte
que ninguém notaria estar inerte. O conserto é piso mais baixo no nível do produto
(4,0), com preço declarado: ele acredita com menos evidência. O teto menor no
ranking (1,2 contra 1,5) é quem paga esse preço.

**E um teste encontrou a fraqueza em outro lugar, pior.** O teto do produto de
todos os fatores foi posto em **3,0** — "conservador", porque era o máximo que o
sistema já tinha de fato: `1,6 × 1,25 × 1,5` dá exatamente 3,0. Com esse teto, uma
linha de jornada líder com produto e afinidade máxima de jornada **já está no
teto**, e a afinidade por produto multiplicava um valor que o limite cortava de
volta. Os dois candidatos saíam em 3,0 e o desempate — o motivo de o sinal existir
— não acontecia. O recurso não ficava fraco: ficava **inerte**, e justamente para
quem tem mais comportamento registrado.

O teto virou 3,6 = `1,6 × 1,25 × 1,5 × 1,2`, os quatro fatores que precisam caber.
A tendência (1,15) fica acima e é cortada no topo, de propósito: no limite, o sinal
de menos confiança é o primeiro a ser descartado.

**A lição que vale mais que o número:** o teto era **emergente** do produto dos
pesos e não estava escrito em lugar nenhum, então acrescentar um fator mudava o
máximo do sistema em silêncio. Agora é declarado em `PesosDeRanking.fatorMaximo`.

---

## 20. A tendência: duas janelas, e eu recomendei adiar

**Decisão.** Afinidade calculada em janela curta (7 d) e longa (30 d); a razão
entre as duas, normalizada, promove quem está com interesse em alta.

**Registro honesto:** eu recomendei **adiar** este item e expliquei por quê. A
decisão de incluí-lo foi de quem pediu, depois de ler a objeção. A objeção continua
válida e está codificada no piso.

**O que ela acrescenta que a meia-vida não dá.** Uma exponencial só diz "quanto",
não "para onde". A meia-vida de sete dias já faz evento recente dominar, mas não
distingue *disparou esta semana* de *constante há um mês* — a diferença entre
necessidade transitória e interesse durável.

**O ponto neutro não é 0,5: é 7/30 ≈ 0,23.** Como a curta está contida na longa, a
razão vive em [0,1], e atividade uniforme produz exatamente a razão das janelas.
Comparar com 0,5 chamaria de "esfriando" todo mundo que está normal.

**A fraqueza, codificada.** Razão entre números pequenos é ruído: com três cliques
no total, um clique a mais move a razão de 0,33 para 0,66 e a "tendência" dobraria
por causa de um toque. O `pisoDaTendencia` de 4,0 pontos faz a tendência devolver
neutro **exato** abaixo dele — e quantas vezes isso acontece é visível na métrica
da decisão 16. É assim que se descobre se o recurso está dizendo algo ou está
desligado de fato.

**Limite estrutural, declarado.** A tendência **não é verificável de ponta a ponta
pela API pública**, porque `ocorridoEm` é atribuído pelo servidor na ingestão: não
há como injetar um evento de trinta dias atrás por fora. A matemática tem teste de
unidade; o comportamento em produção só a métrica dirá.

**E a tendência só promove.** Rebaixar por tendência fria puniria quem demonstrou
interesse há três semanas, e o decaimento da afinidade já cuida do esquecimento
com uma curva que tem física por trás.

---

## 21. Limite de taxa como decorador, e o que ele não resolve

**Decisão.** Limite por minuto na rota de sugestões, chaveado pelo `sub` do token
com o endereço remoto como alternativa.

**Por quê.** Único item desta rodada que é **risco** e não qualidade. Typeahead sem
limite é amplificador de carga — um cliente em laço consome a capacidade medida — e
via de raspagem do catálogo: qualquer portador de token de cliente enumera as
ofertas varrendo prefixos.

**A posição na cadeia é a decisão.** Depois do filtro que lê o Bearer, antes do que
autoriza. Depois da leitura, a chave é o `sub` provado, a única não falsificável;
antes da autorização, o limite ainda alcança quem mandou token inválido ou nenhum —
e esse é o tráfego que mais interessa limitar, porque validar assinatura HMAC em
laço é gasto de CPU. Invertida, a ordem daria uma das duas perdas: depois da
autorização, requisição não autenticada nunca chegaria ao limitador; antes da
leitura do token, todo mundo seria limitado por IP e um prédio atrás de NAT
contaria como um chamador.

**`getRemoteAddr` e não `X-Forwarded-For`:** o cabeçalho é dito pelo cliente, e
aceitá-lo sem um proxy confiável configurado transforma o limite em opcional —
basta variar o cabeçalho a cada requisição.

**Teto de chaves rastreadas.** Sem ele o próprio limitador é o vetor: um atacante
com tokens descartáveis faria o mapa crescer até o processo cair, usando a defesa
como ataque.

**Janela fixa, não deslizante.** A fixa admite o dobro do limite na virada do
minuto. Para conter laço isso basta, e o custo é um contador por chave em vez de
uma estrutura. A diferença importaria num limitador de cobrança, onde o número é
contrato; aqui é teto de segurança.

**O que ele honestamente não resolve, e vale escrever.** O contador é por processo
e em memória: não sobrevive a restart e não é compartilhado entre instâncias — com
três instâncias, o limite efetivo é o triplo. A camada que resolve é o gateway.
Também não separa quem digita rápido de quem raspa devagar: os dois limites que
isso exigiria são incompatíveis. Os 600/min protegem a digitação; o resto é
detecção de comportamento, não contador. **O pior resultado possível seria alguém
marcar "limite de taxa" como resolvido.**

---

## 22. Relógio injetado, porque três asserções passavam por sorte

**Decisão.** `AgregadosEmMemoria` recebe um `Clock`. Em produção é
`Clock.systemUTC()`; nos testes é `Clock.fixed`.

**O que isso conserta.** Com `Instant.now()` chamado dentro da classe, um evento
criado no teste e agregado alguns microssegundos depois tem idade maior que zero, e
o decaimento a desconta: três cliques "de agora" dão 5,999999993 pontos em vez de
6,0, e a afinidade sai `0,9999999996` em vez de `1,0`.

**Três asserções afirmavam igualdade exata nessa borda.** Duas apareceram quando
o código ficou marginalmente mais lento e passou a cruzar a virada do
milissegundo. A terceira — `normalizaAJornada` — só apareceu rodando a suíte
**doze vezes com os doze núcleos ocupados**: doze rodadas numa máquina ociosa
passaram doze vezes.

**Recusado: afrouxar a tolerância.** Foi o que fiz primeiro, e funciona. O custo é
escondido: `within(1e-6)` também aceita um **erro de verdade** de 1e-6, e o teste
passa a afirmar menos do que queria. Pior no caso de
`cadenciaNaoMudaOResultado`, cuja afirmação é "rodar a agregação uma vez ou cem
vezes dá a mesma resposta" — com relógio real isso é **falso de direito**, porque a
idade cresce entre as chamadas, e o teste só podia verificar uma aproximação. Com
relógio fixo ele volta a poder dizer `isEqualTo`, que é o que significa.

**O relógio tem de ser fixo nos dois lados.** Agregador com relógio fixo e evento
nascendo em `Instant.now()` não determina nada — a idade continua variável. Os
testes passaram a criar os eventos a partir da mesma constante.

**O que continua dependendo de relógio real, e a margem de cada um.** Não foi
convertido porque a margem é larga e na direção segura:

| teste | relógio | margem |
|---|---|---|
| `DisjuntorTest` | espera 60 ms, `sleep` 80 ms | 20 ms, e dormir **mais** mantém o meio-aberto |
| `perfilLentoFalhaAberta` | prazo 8 ms, stub dorme 200 ms | 25× |
| `perfilProntoSobreviveAoPrazoJaConsumido` | índice dorme 120 ms, perfil responde em ~0 ms | 120 ms para uma tarefa trivial terminar |

**E a caça virou script.** `scripts/cacar-flaky.ps1` roda a suíte N vezes sob
contenção de todos os núcleos, com `-Ordem` para embaralhar a ordem das classes e
pegar dependência de estado entre testes. Rodar dez vezes numa máquina ociosa não
acha quase nada: o que derruba teste sensível a tempo é o agendador, e isso
acontece em CI, não na máquina de quem escreveu o teste.

Estado atual: 22 rodadas verdes sob contenção, 8 delas com ordem aleatória. Isso
**não prova determinismo** — prova que nenhuma instabilidade apareceu nesta
amostra, e o script existe para a amostra poder crescer.

---

## 23. Teto no mapa de fadiga, porque o perfil é lido a cada tecla

**Decisão.** `fadigaPorTermo` guarda no máximo 50 entradas por pessoa — os 50
termos mais cansados.

**O defeito que isso conserta, e de onde ele apareceu.** Os outros três sinais do
perfil têm tamanho limitado por construção: o histórico tem teto de 20 itens, a
afinidade de jornada tem meia dúzia de chaves, a de produto algumas dezenas. A
fadiga é o **único indexado por termo** — ela ganha uma entrada por termo exibido
cinco vezes e nunca clicado, e quem usa o app todo dia acumula indefinidamente.

Isso passou nas três revisões anteriores porque, no volume do POC, não aparece.
Apareceu quando a pergunta virou "e se forem 60 milhões de requisições por dia,
100 mil num minuto".

**E o problema não é a memória do agregador — é o caminho de leitura.** O perfil é
lido **a cada tecla**. Medido, com entrada de ~47 bytes:

| termos | por leitura | a 1.667 req/s |
|---|---|---|
| 50 (com teto) | 2,3 KB | 3,7 MB/s |
| 200 | 9,2 KB | 14,9 MB/s |
| 2.000 | 91,8 KB | **149 MB/s** |

E num armazenamento de documento há limite de tamanho de item que, alcançado, não
degrada: **falha**. Um perfil que cresce sem teto é uma falha agendada para o
cliente mais engajado da base — exatamente o que ninguém quer perder.

**Qual descartar é a decisão, e ela não é arbitrária.** O corte é por valor,
decrescente. Descartar fadiga baixa é invisível: 0,07 de fadiga vira fator 0,973, e
isso não move linha nenhuma de lugar. Descartar fadiga alta custa o efeito inteiro.
A recência não entra no critério porque já está dentro do valor — a janela de 30
dias descarta o que é velho antes de a fadiga ser calculada.

**O desempate é pelo termo, alfabético, e isto é a parte que mais importa.** Quando
muitos termos chegam a 1,0 — o que acontece, porque 1,0 é o teto da curva — há mais
candidatos que vagas. Se o desempate dependesse da ordem de iteração do `HashMap`,
quais sobreviveriam mudaria entre rodadas, e **a lista da pessoa piscaria a cada
quinze segundos sem que nenhum comportamento dela tivesse mudado**.

Instabilidade sem causa observável é a pior espécie: ninguém reproduz, e o relato
chega como "às vezes a sugestão muda sozinha". Há teste dedicado só a isso, com dez
rodadas sobre mapas construídos em ordens diferentes.

**O teto é contado, e cortar em silêncio seria pior que não cortar.** A rodada
devolve `perfisNoTetoDeFadiga`. Com o número à vista a calibragem deixa de ser
palpite: **zero numa base grande** significa teto folgado e nada a fazer; **a
maioria dos perfis** significa que ele está descartando sinal, e aí a conversa é
sobre quanto o perfil pode pesar — não sobre se 50 é um bom número. É a mesma lógica
de `retidosPeloPiso`: limite que ninguém mede é limite que alguém afrouxa por
palpite.

**Verificado ao vivo:** 70 termos cansados para um cliente, rodada devolveu
`perfisNoTetoDeFadiga: 1`, e a busca continuou respondendo normalmente.

---

## Limites conhecidos, declarados

Sem número: não é uma decisão, é uma lista viva que encolhe conforme as coisas
são resolvidas. Nenhum destes é descuido — estão aqui para não virarem surpresa.

**~~Não há autorização em `POST /v1/ofertas`.~~** Resolvido na decisão 14: o
token carrega a jornada e o caso de uso confere o corpo contra ela.

**~~A afinidade não esfria sozinha.~~** Resolvido na decisão 15, junto com o
problema irmão de um clique valer afinidade máxima.

**~~Não há sinal negativo: o ranking só sabe concordar consigo mesmo.~~**
Resolvido na decisão 18 — com viés de posição declarado como limite remanescente.

**~~A afinidade não distingue produtos da mesma jornada.~~** Resolvido na decisão
19. A esparsidade continua sendo o risco, e é observável pela decisão 16.

**~~Uma letra errada zera a lista.~~** Resolvido na decisão 17, para erro de
digitação. Falta de vocabulário (`grana` → zero) continua aberta.

**~~Não há limite de taxa.~~** Resolvido na decisão 21 — como defesa em
profundidade, não como a camada principal, que é o gateway.

**Viés de posição na fadiga.** Linha na posição 5 é vista menos que na 0, e a
contagem de "exibiu e não clicou" trata as duas igual. Precisa de modelo de
propensão. Ver decisão 18.

**A tendência não é verificável de ponta a ponta.** `ocorridoEm` é atribuído pelo
servidor, então não há como injetar evento antigo pela API. Ver decisão 20.

**Falta de vocabulário coloquial.** `grana`, `dinheiro rápido` e afins devolvem
zero. Não é erro de digitação e nenhuma distância de edição conserta: é sinônimo
que não existe no catálogo, e preenchê-lo é trabalho de quem cura.

**Nenhum perfil de demonstração tem elegibilidade de `FINANCIAMENTO_*`.** As duas
ofertas de financiamento são inalcançáveis pelos oito perfis — mesma classe do
buraco que o `CONSIGNADO` já teve. O conserto não é dar a elegibilidade: é um
**teste** que falhe quando existir oferta no índice que nenhum perfil alcança, para
isso parar de ser descoberta manual. Ainda não escrito.

**Não há produto substituto.** Quem busca financiamento, é elegível a empréstimo e
consignado, e recebe zero oferta, continua recebendo zero. Foi levantado,
analisado, e deixado de fora desta rodada porque a decisão é de produto — oferecer
substituto é aceitável neste contexto? — e não de engenharia.

**O `Content-Type` de sucesso e de erro discordam sobre charset.** O caminho de
erro devolve `application/problem+json;charset=UTF-8` — escrito à mão em
`ConfiguracaoDeSeguranca` e no `TratadorDeErros`. O de sucesso devolve
`application/json` puro, que é o padrão do Spring.

**Os dois estão defensáveis; a inconsistência não.** A
[RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) define JSON como UTF-8 e **não**
define parâmetro de charset para `application/json`, então o Spring está certo em
omitir e um cliente correto assume UTF-8.

O problema é que cliente legado não assume. O `Invoke-RestMethod` do PowerShell
5.1 cai para ISO-8859-1 quando falta charset, e `crédito consignado` chega como
`crÃ©dito consignado` **na string em memória** — não é artefato de console. O mesmo
vale para .NET Framework antigo e para algumas bibliotecas Java anteriores.
Descoberto escrevendo o roteiro de demonstração, que mostraria isso na tela.

Por ora o conserto ficou no cliente — `requisicoes/demonstrar.ps1` decodifica os
bytes à mão — porque mudar o servidor exige mexer em negociação de conteúdo, e isso
não se faz na véspera de apresentar. A decisão pendente é qual lado uniformizar:
acrescentar `;charset=UTF-8` ao sucesso (custo semântico zero, e poupa todo
consumidor legado de descobrir isso sozinho) ou remover do erro, ficando
estritamente com a RFC. **A primeira é a que eu recomendaria**, pelo mesmo
raciocínio das outras decisões deste documento: o custo de ser generoso é zero, e o
custo de não ser cai sobre quem integra.

**A afinidade não distingue clique de arrepender.** Clicar e voltar em dois
segundos conta igual a clicar e contratar — o `SUGESTAO_CLICADA` não tem duração
nem desfecho. Um sinal de permanência (`tempo até voltar`) separaria interesse de
engano, e é o tipo de evento que o app pode emitir sem custo. Hoje o que compensa
isso em parte é a adesão valer 5 contra 2 do clique.

**A revogação de token não existe.** Um JWT assinado vale até expirar, e os
tokens deste POC valem 60 minutos. Não há lista de revogados nem introspecção, o
que significa que um token vazado vale uma hora. Com provedor de verdade isso é
tempo de expiração curto mais *refresh*, ou introspecção para os escopos de
escrita — `ofertas:todas` é o candidato óbvio, porque é o privilégio que mais
machuca se vazar.

**A rotação do segredo derruba todos os tokens de uma vez.** Com HMAC e um
segredo só, trocar o segredo invalida tudo o que está em voo. Com RSA e JWKS a
rotação é por `kid` e convive com dois pares de chaves durante a transição — é
outro motivo, além da assimetria, para o decodificador ser a peça que se troca.

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
