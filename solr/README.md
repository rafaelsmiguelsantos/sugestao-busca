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
- **`gatilho_ss`** — eventos que empurram o termo (`pix_recusado_72h`). O BFF manda o
  que o perfil tem; o `bq` faz o resto.
- **`exige_elegibilidade_b`** — quando o serviço de elegibilidade cai, some da lista.
  Falha fechada, ao contrário do perfil, que falha aberto.
- **`visto_em_dt`** — alimenta o `recip(ms(NOW,visto_em_dt),...)` da recência.

## O que ainda não está aqui

O módulo de reordenação (camada 4 do desenho) sobe com o Solr — `SOLR_MODULES=ltr`
no `compose.yaml` — mas o `solrconfig.xml` ainda não declara o `LTRQParserPlugin`,
e não há modelo nem `features`. É de propósito: as camadas 1 a 3 entregam sem modelo
nenhum, e a 4 só faz sentido quando houver impressão registrada com posição.
