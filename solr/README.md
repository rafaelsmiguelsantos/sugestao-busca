# A coleção `sugestoes`

O payload em `schema-sugestoes.json` é enviado por POST em `/solr/sugestoes/schema`.
Ele parte do configset `_default`, então vale igual para o Solr do zip e para o do
container — o schema é o mesmo nos dois caminhos.

JSON não aceita comentário, e a Schema API do Solr rejeita chave desconhecida como
comando inválido. Por isso a explicação de cada campo mora aqui.

## Os dois campos de prefixo

Existem dois, e por motivos diferentes:

| campo | análise | casa |
|---|---|---|
| `termo_prefixo` | `KeywordTokenizer` + `EdgeNGram` | prefixo da **frase inteira** — `adiantar din` → `adiantar dinheiro` |
| `termo_infixo` | `StandardTokenizer` + `EdgeNGram` | prefixo de **qualquer palavra** — `dinh` → `adiantar dinheiro` |

Na consulta o prefixo da frase pesa mais (`qf=termo_prefixo^6 termo_infixo^2`),
porque é o que o cliente está digitando de fato. O infixo é rede de segurança: pega
quem começa a digitar pelo meio do termo.

O n-grama é gerado **na indexação**, nunca na consulta — por isso o analisador de
consulta dos dois tipos não tem `EdgeNGramFilterFactory`. Se tivesse, cada consulta
viraria dezenas de termos e o índice deixaria de servir para o que foi feito.

`minGramSize: 2` casa com o mínimo de 2 caracteres do BFF. Abaixo disso o serviço nem
chama o Solr.

## Sinônimo é campo do documento, não arquivo de configuração

`sinonimo` é multivalorado e copiado para `termo_infixo`. O documento de
`antecipação de limite` carrega `["adiantar dinheiro", "antecipar limite", "dinheiro
adiantado"]`.

A alternativa seria `SynonymGraphFilterFactory` com um `sinonimos.txt` no configset.
Não vale aqui: o arquivo exige reload de configuração para mudar, fica fora do
versionamento do conteúdo e ninguém do negócio consegue editar. Como campo, a redação
alternativa é dado — versionada junto do termo, publicada pelo mesmo caminho.

## Os campos que decidem

- **`clientes_distintos_i`** — o piso de k-anonimato. Entra como `fq`, nunca como
  pós-filtro: abaixo do mínimo o termo não chega a pontuar nem a ser contado. É o
  único campo aqui que, se for usado errado, vira incidente de privacidade.
- **`taxa_adesao_f` e `ctr_f`** — separados de propósito, com pesos diferentes no
  `boost`. Clique alto com adesão baixa é isca, e o índice aprende a repetir isca se
  os dois virarem um número só.
- **`gatilho_ss`** — eventos que tornam o termo oportuno (`pix_recusado_72h`,
  `conta_negativa_30d`). O cruzamento com os gatilhos ativos do perfil acontece no
  BFF, não aqui: o `ClassificadorDeOrigem` promove o candidato a `EVENTO`, e quem
  garante a linha dele é a cota, não o score.
- **`visto_em_dt`** — alimenta o `recip(ms(NOW/HOUR,visto_em_dt),...)` da recência.
  `NOW/HOUR` e não `NOW`: com `NOW` a chave do cache muda a cada milissegundo e a
  camada popular, que é a parte cacheável desta consulta, nunca acerta o cache.

## Os dois cortes de público, e por que só eles são `fq`

`segmento_ss` e `relacionamento_ss` são os únicos filtros de público na consulta,
e isso não é omissão: é o que os separa do resto. Os dois são atributos **estáveis
e declarados no token** — já estão em mãos quando a requisição chega. Por isso a
consulta ao índice pode arrancar junto da leitura do perfil em vez de atrás dela,
e por isso `(prefixo, segmento, relacionamento)` é uma chave de cache estável.

O semeador preenche **todas** as faixas aplicáveis em cada documento. Um item sem
faixa nenhuma seria invisível para o `{!terms f=relacionamento_ss}` — ausência de
valor não casa com nada, então "vale para todo mundo" precisa ser escrito por
extenso.

## Os campos que o perfil julga

Estes três **não** são `fq`, e também por decisão. Eles só são conhecidos quando o
perfil chega; transformá-los em filtro de índice custaria enfileirar as duas
leituras e o orçamento de 30 ms passaria de folgado a apertado. O corte acontece na
`Peneira`, sobre vinte candidatos. Todos falham fechados.

- **`exige_elegibilidade_b`** — sem elegibilidade concedida, some da lista. Quando o
  item exige elegibilidade e não empurra produto nenhum (portabilidade de salário é
  um deles), a concessão é chaveada pelo termo normalizado — senão o item sumiria
  para todo mundo, para sempre, com a falha fechada certa na direção e errada no
  alvo.
- **`veta_inadimplencia_b`** — crédito novo não é oferecido a quem está em atraso.
  O caminho para esse cliente não é esconder a busca: é a jornada de renegociação,
  que entra pelo gatilho `conta_negativa_30d` e ocupa a linha de evento.
- **`exige_conta_ativa_b`** — operação que só faz sentido com a conta operante.
  Conta em estado desconhecido cai aqui junto com a bloqueada, que é o lado seguro:
  sem saber como está a conta, a operação condicionada não aparece.

## O que ainda não está aqui

O módulo de reordenação (camada 4 do desenho) sobe com o Solr — `SOLR_MODULES=ltr`
no `compose.yaml` — mas o `solrconfig.xml` ainda não declara o `LTRQParserPlugin`,
e não há modelo nem `features`. É de propósito: as camadas 1 a 3 entregam sem modelo
nenhum, e a 4 só faz sentido quando houver impressão registrada com posição.
