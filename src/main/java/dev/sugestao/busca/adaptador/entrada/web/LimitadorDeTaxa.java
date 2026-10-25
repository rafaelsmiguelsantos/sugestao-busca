package dev.sugestao.busca.adaptador.entrada.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quantas requisições por minuto cada chamador pode fazer na rota de digitação.
 *
 * <p><strong>Por que existe.</strong> Uma rota de typeahead sem limite é duas
 * coisas ao mesmo tempo: amplificador de carga — um cliente em laço consome a
 * capacidade medida do serviço — e via de raspagem do catálogo, porque qualquer
 * portador de token de cliente enumera as ofertas varrendo prefixos. É o único
 * item desta rodada que é <em>risco</em> e não qualidade de busca.
 *
 * <p><strong>E o que ele honestamente não resolve.</strong> O contador é por
 * processo e em memória: não sobrevive a restart e não é compartilhado entre
 * instâncias. Com três instâncias atrás de um balanceador, o limite efetivo é o
 * triplo do configurado. A camada que resolve isso é o gateway, que vê todo o
 * tráfego; esta aqui é defesa em profundidade, e escrever isso evita o pior
 * resultado possível — alguém marcar "limite de taxa" como resolvido.
 *
 * <p>Também não separa quem digita rápido de quem raspa devagar. Os dois limites
 * que isso exigiria são incompatíveis: quem digita "financiamento imobiliário"
 * gasta ~25 requisições em segundos, e quem raspa com paciência fica abaixo de
 * qualquer teto que não quebre a digitação. O número escolhido protege a
 * digitação; o resto é trabalho de detecção de comportamento, não de contador.
 *
 * <p><strong>A chave é o {@code sub} do token, com o IP como alternativa.</strong>
 * O token é a identidade provada; o IP é o que sobra quando a requisição nem
 * chegou a ser autenticada. Limitar só por IP puniria um prédio inteiro atrás de
 * NAT; limitar só por token deixaria passar quem nem manda token.
 *
 * <p><strong>Janela fixa, não deslizante.</strong> A janela fixa admite o dobro
 * do limite na virada — 600 no fim de um minuto e 600 no começo do seguinte. Para
 * proteger de laço, isso basta, e o custo é um contador e um relógio por chave em
 * vez de uma estrutura por chave. A diferença importaria num limitador de
 * cobrança, onde o número é contrato; aqui o número é teto de segurança.
 */
public class LimitadorDeTaxa extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LimitadorDeTaxa.class);

    private static final String BASE_DE_PROBLEMAS = "https://sugestao-busca.dev/problemas/";

    private final int porMinuto;
    private final ObjectMapper json;

    /**
     * Expira por escrita, e tem teto.
     *
     * <p>O teto é o que impede o limitador de ser o próprio vetor: sem ele, um
     * atacante com tokens descartáveis — ou com IPs forjados num cabeçalho —
     * faria o mapa crescer até o processo morrer, usando a defesa como ataque.
     * O Caffeine despeja a chave menos usada quando chega no teto, e no pior caso
     * isso significa um chamador ganhar a contagem zerada: degradação do limite,
     * não do processo.
     */
    private final Cache<String, Janela> janelas;

    public LimitadorDeTaxa(int porMinuto, int tetoDeChaves, ObjectMapper json) {
        this.porMinuto = porMinuto;
        this.json = json;
        this.janelas = Caffeine.newBuilder()
                .maximumSize(tetoDeChaves)
                .expireAfterWrite(Duration.ofMinutes(2))
                .build();
    }

    /**
     * Só a rota de sugestões.
     *
     * <p>O cadastro de oferta e a ingestão de eventos não entram: o primeiro é de
     * volume baixo e tem uma jornada esperando, e o segundo é o caminho por onde
     * o app devolve o que já aconteceu — recusá-lo com 429 jogaria fora sinal de
     * aprendizado para proteger um recurso que ninguém está consumindo. O volume
     * de teclas digitadas é o que justifica o limite, e ele existe num lugar só.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !"/v1/sugestoes".equals(requisicao.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao,
                                    HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {

        String chave = chaveDe(requisicao);
        Janela janela = janelas.get(chave, c -> new Janela());

        int usadas = janela.contar(porMinuto);

        // RateLimit-Limit e RateLimit-Remaining saem em TODA resposta, não só no
        // 429. Um cliente que só descobre o limite ao ser recusado não tem como
        // se comportar antes; com os cabeçalhos, um front-end pode aumentar o
        // debounce ao ver a sobra encolher.
        resposta.setHeader("RateLimit-Limit", Integer.toString(porMinuto));
        resposta.setHeader("RateLimit-Remaining", Integer.toString(Math.max(0, porMinuto - usadas)));
        resposta.setHeader("RateLimit-Reset", Long.toString(janela.segundosAteVirar()));

        if (usadas > porMinuto) {
            recusar(requisicao, resposta, janela.segundosAteVirar());
            return;
        }

        cadeia.doFilter(requisicao, resposta);
    }

    /**
     * O {@code sub} do token quando há token; o endereço remoto quando não há.
     *
     * <p>Lê o {@code SecurityContext}, e por isso este filtro tem de rodar
     * <em>depois</em> da cadeia de segurança — ver
     * {@code ConfiguracaoWeb}. A ordem inversa seria mais protetora (recusaria
     * antes de validar assinatura) e limitaria todo mundo por IP, perdendo a
     * única chave que não é falsificável.
     *
     * <p>{@code getRemoteAddr} e não {@code X-Forwarded-For}: o cabeçalho é dito
     * pelo cliente, e aceitá-lo sem um proxy confiável configurado transforma o
     * limite em opcional — basta variar o cabeçalho a cada requisição.
     */
    private static String chaveDe(HttpServletRequest requisicao) {
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();

        if (autenticacao instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getClaimAsString(JwtClaimNames.SUB);
            if (sub != null && !sub.isBlank()) {
                return "sub:" + sub;
            }
        }
        return "ip:" + requisicao.getRemoteAddr();
    }

    /**
     * 429 em {@code problem+json}, com {@code Retry-After}.
     *
     * <p>O {@code Retry-After} é o que diferencia um limite de uma recusa
     * arbitrária: ele diz ao cliente quando voltar, e um cliente bem escrito
     * obedece em vez de tentar em laço — que é o comportamento que piora
     * exatamente a situação que o limite está contendo.
     */
    private void recusar(HttpServletRequest requisicao, HttpServletResponse resposta,
                         long segundos) throws IOException {

        log.info("429 em {} {}", requisicao.getMethod(), requisicao.getRequestURI());

        resposta.setHeader("Retry-After", Long.toString(segundos));

        var problema = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problema.setType(URI.create(BASE_DE_PROBLEMAS + "limite-de-taxa"));
        problema.setTitle("Limite de requisicoes excedido");
        problema.setDetail("aguarde " + segundos + "s antes de tentar de novo");

        resposta.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(resposta.getOutputStream(), problema);
    }

    /**
     * Um contador e o instante em que ele nasceu.
     *
     * <p>Mutável e compartilhada entre threads: a rota é servida por threads
     * virtuais e o mesmo cliente pode ter várias em voo. O {@code AtomicInteger}
     * e a troca sincronizada do instante são o que impede duas requisições
     * simultâneas de zerarem a janela uma da outra — o modo de falha seria um
     * limite que nunca dispara sob concorrência, que é a única condição em que
     * ele importa.
     */
    private static final class Janela {

        private final AtomicInteger usadas = new AtomicInteger();
        private volatile long nascidaEm = System.nanoTime();

        private static final long UM_MINUTO_EM_NANOS = 60_000_000_000L;

        int contar(int limite) {
            long agora = System.nanoTime();

            if (agora - nascidaEm >= UM_MINUTO_EM_NANOS) {
                synchronized (this) {
                    // Confere de novo dentro do bloco: duas threads podem ter
                    // passado pelo if, e sem a segunda conferência a segunda
                    // zeraria uma janela que a primeira acabou de criar.
                    if (agora - nascidaEm >= UM_MINUTO_EM_NANOS) {
                        nascidaEm = agora;
                        usadas.set(0);
                    }
                }
            }

            // Incrementa com teto: sem o teto, um cliente em laço faria o
            // contador crescer sem limite durante o minuto inteiro, e
            // `porMinuto - usadas` estouraria para um número negativo enorme no
            // cabeçalho RateLimit-Remaining.
            return usadas.updateAndGet(n -> n <= limite ? n + 1 : n);
        }

        long segundosAteVirar() {
            long decorrido = System.nanoTime() - nascidaEm;
            long restante = UM_MINUTO_EM_NANOS - decorrido;
            return Math.max(1, restante / 1_000_000_000L);
        }
    }
}
