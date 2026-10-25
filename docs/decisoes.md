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

## Limites conhecidos, declarados

Sem número: não é uma decisão, é uma lista viva que encolhe conforme as coisas
são resolvidas. Nenhum destes é descuido — estão aqui para não virarem surpresa.

**~~Não há autorização em `POST /v1/ofertas`.~~** Resolvido na decisão 14: o
token carrega a jornada e o caso de uso confere o corpo contra ela.

**~~A afinidade não esfria sozinha.~~** Resolvido na decisão 15, junto com o
problema irmão de um clique valer afinidade máxima.

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
