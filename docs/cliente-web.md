# Consumindo a API de um cliente React ou Angular

Escrito para quem vai integrar a caixa de busca no app. Cobre o que a API exige,
o que o navegador exige, e os quatro problemas que aparecem em toda integração de
busca-ao-digitar e não aparecem na documentação de nenhum endpoint.

---

## 1. Sem CORS, não funciona — e o erro aponta para o lugar errado

A API responde normalmente a `curl` e é **bloqueada pelo navegador** se as
origens não estiverem autorizadas. O servidor não recusa nada: o `200` chega, e o
JavaScript nunca vê a resposta, porque faltam os cabeçalhos
`Access-Control-*`. No DevTools isso aparece como erro de rede, o que manda
investigar a API quando o problema é configuração.

Autorize a origem do seu app em `sugestao.cors.origens`:

```properties
sugestao.cors.origens=http://localhost:3000,http://127.0.0.1:3000
```

**Vazio significa nenhuma origem, não todas.** E `localhost` e `127.0.0.1` são
origens **diferentes** para o CORS — a comparação é textual sobre
(esquema, host, porta), não por resolução de nome. Quem abre o app por
`127.0.0.1` toma bloqueio se só `localhost` estiver na lista.

O perfil `local` já lista as portas de 3000 (CRA/Next), 4200 (Angular CLI) e
5173 (Vite), nas duas formas.

### O preflight custa, e tem um jeito de não custar

Os cabeçalhos `X-Cliente-*` não estão na lista segura do CORS, então o navegador
manda um `OPTIONS` antes de cada `GET`. Numa caixa que consulta a cada tecla,
isso **dobraria** o número de requisições.

Trocar os cabeçalhos por query string não resolve: em produção a identidade vem
no `Authorization`, que também exige preflight. O que resolve é
`Access-Control-Max-Age`, que já vem em 1800 s — o navegador guarda a
autorização por (origem, método, cabeçalhos) e deixa de perguntar. Não há nada a
fazer no cliente além de **não variar o conjunto de cabeçalhos entre
requisições**: mudar o conjunto invalida o cache do preflight.

---

## 2. O contrato

```
GET /v1/sugestoes?q=<prefixo>&limite=<n>&explicar=<bool>

X-Cliente-Id              quem e o cliente
X-Cliente-Segmento        PF | PJ
X-Cliente-Relacionamento  VAREJO | UNICLASS | PERSONNALITE | PRIVATE
```

Em produção os três vêm do token e o cliente web não os monta — o gateway os
injeta. Durante o desenvolvimento eles são cabeçalhos para dar para trocar de
perfil sem emitir token.

```jsonc
{
  "prefixo": "consignad",
  "degradada": false,

  // A oferta acionavel de maior ranking ENTRE AS ENTREGUES.
  // Ausente quando nao ha oferta elegivel para esta pessoa e este prefixo.
  "melhorOferta": {
    "ofertaId": "OFR-CREDITO-CONSIGNADO",
    "produtoAlvo": "CONSIGNADO",
    "jornada": "credito",
    "destino": "/app/credito/credito-consignado",
    "posicao": 0,
    "origem": "CATALOGO",
    "relevancia": 2.0
  },

  "itens": [
    {
      "posicao": 0,
      "ofertaId": "OFR-CREDITO-CONSIGNADO",  // "" quando a linha nao e oferta
      "termo": "crédito consignado",
      "rotulo": "",                           // texto de apoio, quando existe
      "destino": "/app/credito/credito-consignado",
      "jornada": "credito",
      "produtoAlvo": "CONSIGNADO",
      "origem": "CATALOGO",                   // EVENTO | HISTORICO | CATALOGO | POPULAR
      "relevancia": 2.0
    }
  ]
}
```

**Esta rota nunca devolve erro.** Prefixo de um caractere, `q` ausente, `limite`
ilegível, `limite` absurdo, `explicar` com texto sem sentido, faixa inexistente,
nenhum cabeçalho — tudo isso é `200` com lista vazia, limitada ou degradada. Não
escreva tratamento de `4xx` para ela; escreva tratamento de **lista vazia**.

Com uma fronteira, medida: acima de **~7,9 KB de URL** o Tomcat devolve `400`
antes de a aplicação ver a requisição — é o teto de cabeçalho do container, não
uma regra nossa. Nenhum teclado produz isso. Um `useEffect` em laço produz, e é
o único caso em que esta rota responde `4xx`.

### Os campos que mudam a tela

| campo | para que serve |
|---|---|
| `melhorOferta` | a única pergunta que a jornada tem: qual oferta eu aciono |
| `itens[].ofertaId` | vazio significa "não é oferta", nunca "oferta sem id" |
| `itens[].origem` | `EVENTO` merece destaque visual; `HISTORICO` merece ícone de relógio |
| `itens[].rotulo` | quando preenchido, é o motivo ("pela sua última tentativa de Pix") |
| `degradada` | `true` = o perfil não carregou e a lista é a popular do segmento |

`degradada` existe para o app **não registrar a impressão como personalizada**.
Se ela for registrada como se fosse, a camada de aprendizado aprende com ruído.

---

## 3. Os quatro problemas de busca-ao-digitar

Nenhum deles é desta API em particular; todos aparecem em toda caixa de busca, e
três passam desapercebidos em desenvolvimento porque a rede local é rápida.

### 3.1 Debounce — não consulte a cada tecla

Digitar "consignado" são dez teclas. Sem debounce, são dez requisições para
mostrar uma lista. 150 ms é o número usual: curto o bastante para parecer
instantâneo, longo o bastante para engolir a digitação contínua.

### 3.2 Cancelamento — aborte o que ficou obsoleto

A resposta da tecla anterior não interessa mais. `AbortController` no `fetch`, ou
`switchMap` no RxJS, livram a rede e o servidor de trabalho que ninguém vai usar.

### 3.3 Resposta fora de ordem — o problema que ninguém vê em localhost

**É o mais importante dos quatro.** Duas requisições em voo podem responder na
ordem inversa: a de `cons` chega depois da de `consignado`, sobrescreve a lista
correta, e a tela mostra sugestões de um prefixo que a pessoa já deixou para
trás. Em rede local isso quase nunca acontece; em 4G acontece sempre.

Cancelar ajuda e **não resolve**: o cancelamento é uma corrida, e uma resposta já
em trânsito chega mesmo depois do `abort()`. A solução é um número de sequência
ou comparar o `prefixo` que volta na resposta com o que está no campo agora. A
API devolve `prefixo` exatamente para isso.

### 3.4 Lista vazia não é erro

Prefixo curto, nada elegível para a pessoa, ou índice fora do ar — os três dão
lista vazia com `200`. Trate como "nada a mostrar", nunca como falha.

---

## 4. React: um hook completo

```tsx
import { useEffect, useRef, useState } from "react";

const BASE = "http://localhost:8080";
const MINIMO = 2;        // igual a sugestao.prefixo-minimo
const DEBOUNCE_MS = 150;

type Item = {
  posicao: number;
  ofertaId: string;
  termo: string;
  rotulo: string;
  destino: string;
  jornada: string;
  produtoAlvo: string;
  origem: "EVENTO" | "HISTORICO" | "CATALOGO" | "POPULAR";
  relevancia: number;
};

type Resposta = {
  prefixo: string;
  degradada: boolean;
  melhorOferta?: Omit<Item, "rotulo"> & { posicao: number };
  itens: Item[];
};

const cabecalhosDoCliente = {
  "X-Cliente-Id": "pf-pix-recusado",
  "X-Cliente-Segmento": "PF",
  "X-Cliente-Relacionamento": "VAREJO",
};

export function useSugestoes(digitado: string) {
  const [resposta, setResposta] = useState<Resposta | null>(null);

  // O guarda contra resposta fora de ordem. Cada requisicao leva um numero;
  // so a resposta do numero mais alto ja visto pode escrever no estado.
  const emitido = useRef(0);
  const aplicado = useRef(0);

  useEffect(() => {
    const prefixo = digitado.trim();

    if (prefixo.length < MINIMO) {
      setResposta(null);
      return;
    }

    const controlador = new AbortController();
    const meuNumero = ++emitido.current;

    const temporizador = setTimeout(async () => {
      try {
        const url = `${BASE}/v1/sugestoes?q=${encodeURIComponent(prefixo)}`;
        const r = await fetch(url, {
          headers: cabecalhosDoCliente,
          signal: controlador.signal,
        });

        // Esta rota nao devolve erro de negocio, mas a rede ainda pode falhar.
        if (!r.ok) return;

        const corpo: Resposta = await r.json();

        // Chegou atrasada: uma requisicao posterior ja escreveu. Descarta.
        // Sem isto, a resposta de "cons" sobrescreve a de "consignado" e a
        // tela mostra sugestoes de um prefixo que a pessoa ja abandonou.
        if (meuNumero <= aplicado.current) return;

        aplicado.current = meuNumero;
        setResposta(corpo);
      } catch (e) {
        // AbortError e esperado: foi a gente que cancelou.
        if ((e as Error).name !== "AbortError") {
          setResposta(null);
        }
      }
    }, DEBOUNCE_MS);

    return () => {
      clearTimeout(temporizador);
      controlador.abort();
    };
  }, [digitado]);

  return resposta;
}
```

Usando, com a impressão registrada:

```tsx
function CaixaDeBusca() {
  const [texto, setTexto] = useState("");
  const resposta = useSugestoes(texto);

  // Registra o que foi EXIBIDO, nao o que foi buscado. E a impressao com
  // posicao que torna o aprendizado possivel: sem ela, o modelo aprende que a
  // primeira linha e a melhor, quando ela e apenas onde a linha estava.
  useEffect(() => {
    if (!resposta?.itens.length) return;

    fetch(`${BASE}/v1/eventos`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...cabecalhosDoCliente },
      body: JSON.stringify([
        {
          tipo: "SUGESTAO_EXIBIDA",
          termosExibidos: resposta.itens.map((i) => i.termo),
          listaDegradada: resposta.degradada,
        },
      ]),
      keepalive: true,
    }).catch(() => {});
  }, [resposta]);

  function aoClicar(item: Item) {
    fetch(`${BASE}/v1/eventos`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...cabecalhosDoCliente },
      body: JSON.stringify([
        { tipo: "SUGESTAO_CLICADA", termo: item.termo, posicao: item.posicao },
      ]),
      keepalive: true,
    }).catch(() => {});

    navegarPara(item.destino);
  }

  return (
    <>
      <input value={texto} onChange={(e) => setTexto(e.target.value)} />

      {resposta?.melhorOferta && (
        <BotaoDeOferta
          ofertaId={resposta.melhorOferta.ofertaId}
          produto={resposta.melhorOferta.produtoAlvo}
          jornada={resposta.melhorOferta.jornada}
        />
      )}

      <ul>
        {resposta?.itens.map((i) => (
          <li key={i.termo} onClick={() => aoClicar(i)}>
            {i.origem === "EVENTO" && <Destaque />}
            {i.origem === "HISTORICO" && <IconeDeRelogio />}
            {i.termo}
            {i.rotulo && <small>{i.rotulo}</small>}
          </li>
        ))}
      </ul>
    </>
  );
}
```

**`keepalive: true` nos eventos** porque o clique navega: sem ele o navegador
cancela a requisição ao trocar de página, e justamente o clique — o sinal mais
valioso do conjunto — é o que se perde.

---

## 5. Angular: o mesmo com RxJS

Aqui os quatro problemas viram quatro operadores, e o de ordem sai de graça:

```ts
import { HttpClient } from "@angular/common/http";
import { Injectable, inject } from "@angular/core";
import { Observable, of } from "rxjs";
import { debounceTime, distinctUntilChanged, map, switchMap, catchError } from "rxjs/operators";

@Injectable({ providedIn: "root" })
export class SugestaoService {
  private http = inject(HttpClient);
  private readonly base = "http://localhost:8080";
  private readonly minimo = 2;

  private cabecalhos = {
    "X-Cliente-Id": "pf-pix-recusado",
    "X-Cliente-Segmento": "PF",
    "X-Cliente-Relacionamento": "VAREJO",
  };

  sugerir(digitado$: Observable<string>): Observable<Resposta | null> {
    return digitado$.pipe(
      map((t) => t.trim()),
      debounceTime(150),            // 3.1 debounce
      distinctUntilChanged(),       // nao repete a mesma consulta
      switchMap((prefixo) => {
        if (prefixo.length < this.minimo) return of(null);

        // switchMap resolve 3.2 e 3.3 de uma vez: ao chegar um valor novo ele
        // cancela a inscricao anterior E descarta o que ela ainda emitir.
        // E a razao pela qual o bug de ordem e raro em Angular e comum em
        // fetch escrito a mao.
        return this.http
          .get<Resposta>(`${this.base}/v1/sugestoes`, {
            params: { q: prefixo },
            headers: this.cabecalhos,
          })
          .pipe(catchError(() => of(null)));   // 3.4 falha de rede = nada a mostrar
      })
    );
  }

  registrarExibicao(r: Resposta) {
    this.http.post(`${this.base}/v1/eventos`, [
      {
        tipo: "SUGESTAO_EXIBIDA",
        termosExibidos: r.itens.map((i) => i.termo),
        listaDegradada: r.degradada,
      },
    ], { headers: this.cabecalhos }).subscribe({ error: () => {} });
  }

  registrarClique(item: Item) {
    this.http.post(`${this.base}/v1/eventos`, [
      { tipo: "SUGESTAO_CLICADA", termo: item.termo, posicao: item.posicao },
    ], { headers: this.cabecalhos }).subscribe({ error: () => {} });
  }
}
```

---

## 6. O que a jornada consumidora faz com `melhorOferta`

A jornada não desenha lista. Ela tem uma pergunta — "qual oferta eu apresento
agora?" — e para ela basta uma linha:

```ts
const r = await fetch(
  `${BASE}/v1/sugestoes?q=${encodeURIComponent(prefixo)}&limite=1`,
  { headers: cabecalhosDoCliente }
).then((x) => x.json());

if (r.melhorOferta) {
  acionar(r.melhorOferta.ofertaId);   // o codigo que a propria jornada cadastrou
}
```

`limite=1` devolve **exatamente** o item da posição 0 de `limite=8`: o teto corta
o fim da lista, nunca reordena o começo.

**`melhorOferta` ausente é informação, não erro.** Significa que para esta pessoa
e este prefixo não há oferta elegível — conta inadimplente, produto já
contratado, sem elegibilidade concedida, ou fora da faixa. Não insista com
outro prefixo: a ausência é a resposta.

Observado com `q=consignado`, mesmas teclas e dois clientes:

| cliente | jornada líder | topo da lista | `melhorOferta` |
|---|---|---|---|
| `pf-pix-recusado` (elegível) | `credito` | **crédito consignado** (o produto) | `OFR-CREDITO-CONSIGNADO` |
| `pf-novo` (sem elegibilidade) | `ajuda` | como funciona o consignado (o artigo) | — |

A segunda linha não é a API falhando. Quem não pode contratar recebe a
explicação, não a oferta.

---

## 7. Checklist de integração

- [ ] origem do app em `sugestao.cors.origens`, com `localhost` **e** `127.0.0.1`
- [ ] conjunto de cabeçalhos estável entre requisições (preserva o cache do preflight)
- [ ] debounce de ~150 ms
- [ ] cancelamento da requisição obsoleta
- [ ] **guarda contra resposta fora de ordem** (número de sequência, ou comparar `prefixo`)
- [ ] mínimo de 2 caracteres no cliente, para não gastar ida e volta
- [ ] lista vazia tratada como "nada a mostrar", não como erro
- [ ] `SUGESTAO_EXIBIDA` com `termosExibidos` e `listaDegradada`
- [ ] `SUGESTAO_CLICADA` com `posicao`, e `keepalive` porque o clique navega
- [ ] `origem: "EVENTO"` com destaque visual; `rotulo` exibido quando vier
- [ ] `melhorOferta` ausente tratada como "não há oferta", não como falta de dado
