package dev.sugestao.busca.configuracao;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.ObjectMapper;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Quem pode chamar o quê.
 *
 * <p>É um <strong>servidor de recurso</strong>: valida o token que chega e não
 * emite nenhum. Emitir é papel do provedor de identidade, e um BFF que emite o
 * próprio token é um BFF que decide quem ele mesmo é. No POC não há provedor, e
 * por isso a assinatura é HMAC com segredo compartilhado e os tokens de
 * desenvolvimento saem de {@code scripts/gerar-token.ps1} — um script, fora da
 * aplicação, para não existir caminho de emissão no serviço nem por acidente.
 *
 * <p><strong>Dois tipos de chamador, e a diferença importa.</strong>
 * <ul>
 *   <li><em>O app, em nome de uma pessoa.</em> O token tem {@code sub} com o
 *       identificador do cliente, mais {@code segmento} e
 *       {@code relacionamento}. Escopos: {@code sugestao:ler} e
 *       {@code eventos:escrever}.</li>
 *   <li><em>Uma jornada de produto.</em> O token tem {@code jornada}, e é essa
 *       reivindicação que diz de qual jornada ela é dona. Escopos:
 *       {@code ofertas:ler} e {@code ofertas:escrever}.</li>
 * </ul>
 *
 * <p>É por isso que o segmento e a faixa saem do token e não do perfil: eles são
 * atributos de coorte, conhecidos antes de qualquer leitura, e é isso que
 * permite à consulta ao Solr arrancar junto da leitura do perfil em vez de atrás
 * dela. Agora eles vêm <em>provados</em>, e não ditos por cabeçalho — a diferença
 * entre uma coorte e uma coorte escolhida por quem chama.
 *
 * <p><strong>O que esta classe deliberadamente não faz.</strong> Não decide se a
 * oferta pertence à jornada de quem cadastra. Isso é regra de negócio e mora no
 * caso de uso, que recebe {@link dev.sugestao.busca.aplicacao.porta.entrada.Autorizacao}.
 * Aqui só se decide se o chamador tem o <em>escopo</em> de escrita; qual oferta
 * ele pode escrever é outra pergunta, feita onde as regras de oferta moram.
 */
@Configuration
public class ConfiguracaoDeSeguranca {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDeSeguranca.class);

    /** Escopos. Nomes com dois-pontos, no formato recurso:acao. */
    public static final String LER_SUGESTAO = "SCOPE_sugestao:ler";
    public static final String ESCREVER_EVENTOS = "SCOPE_eventos:escrever";
    public static final String LER_OFERTAS = "SCOPE_ofertas:ler";
    public static final String ESCREVER_OFERTAS = "SCOPE_ofertas:escrever";
    public static final String EXECUTAR_APRENDIZADO = "SCOPE_aprendizado:executar";

    private static final String BASE_DE_PROBLEMAS = "https://sugestao-busca.dev/problemas/";

    /**
     * Jackson 3, em {@code tools.jackson.databind} — não
     * {@code com.fasterxml.jackson.databind}. O Spring Boot 4 trocou de linha
     * principal, e só o pacote das <em>anotações</em> ficou onde estava, o que
     * faz um import errado compilar na metade do arquivo e falhar na outra.
     */
    private final SugestaoProperties.Seguranca seguranca;
    private final ObjectMapper json;

    public ConfiguracaoDeSeguranca(SugestaoProperties props, ObjectMapper json) {
        this.seguranca = props.seguranca() == null
                ? SugestaoProperties.Seguranca.padrao()
                : props.seguranca();
        this.json = json;
    }

    /**
     * O decodificador. HMAC-SHA256 com segredo compartilhado.
     *
     * <p>Trocar por RSA com JWKS remoto é trocar este método por
     * {@code NimbusJwtDecoder.withJwkSetUri(...)}, e nada mais do projeto
     * muda — nem o resolvedor de cliente, nem as regras de rota, nem o caso de
     * uso. É a mesma propriedade que o disjuntor demonstrou do outro lado: a
     * tecnologia fica num canto.
     *
     * <p>HS256 exige 256 bits de segredo. Um segredo curto faz a biblioteca
     * recusar na inicialização, e isso é bom: a falha aparece ao subir, não ao
     * validar o primeiro token.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] bytes = seguranca.segredo().getBytes(StandardCharsets.UTF_8);

        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "sugestao.seguranca.segredo precisa de ao menos 32 bytes para HS256; "
                            + "tem " + bytes.length);
        }

        var chave = new SecretKeySpec(bytes, "HmacSHA256");
        var decodificador = NimbusJwtDecoder.withSecretKey(chave)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // Validador padrão (expiração e not-before) mais o emissor. Sem conferir
        // o emissor, um token assinado com o mesmo segredo por outro serviço do
        // mesmo ecossistema valeria aqui.
        decodificador.setJwtValidator(
                org.springframework.security.oauth2.jwt.JwtValidators
                        .createDefaultWithIssuer(seguranca.emissor()));

        return decodificador;
    }

    @Bean
    public SecurityFilterChain cadeia(HttpSecurity http) throws Exception {

        if (!seguranca.habilitada()) {
            // Modo aberto, para demonstração sem emitir token. Grita no log na
            // subida de propósito: uma API que ficou aberta por configuração é
            // o tipo de coisa que ninguém nota até alguém de fora notar.
            log.warn("=".repeat(70));
            log.warn("SEGURANCA DESLIGADA (sugestao.seguranca.habilitada=false).");
            log.warn("Todas as rotas estao abertas e a identidade vem de cabecalho,");
            log.warn("que e dito pelo cliente e nao provado. Nunca em producao.");
            log.warn("=".repeat(70));

            return http
                    .csrf(csrf -> csrf.disable())
                    .cors(Customizer.withDefaults())
                    .authorizeHttpRequests(rotas -> rotas.anyRequest().permitAll())
                    .build();
        }

        return http
                // Sem CSRF: não há sessão nem cookie. A credencial é um token no
                // cabeçalho Authorization, que o navegador não anexa sozinho —
                // e é a anexação automática que o CSRF existe para conter.
                .csrf(csrf -> csrf.disable())

                // CORS antes da autenticação, senão o preflight do navegador
                // (que não leva Authorization, por especificação) tomaria 401 e
                // o cliente web nunca passaria do primeiro OPTIONS.
                .cors(Customizer.withDefaults())

                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(rotas -> rotas
                        // O despacho de ERRO passa pela cadeia de novo, e sem
                        // isto ele é NEGADO.
                        //
                        // O efeito é pior do que parece e foi achado sondando: o
                        // container rejeita a requisição — firewall de caminho,
                        // cabeçalho grande demais, método não suportado —, faz
                        // forward para /error, e /error não casa com nenhuma
                        // regra. Cai no denyAll, e como o despacho de erro não
                        // carrega autenticação, o cliente recebe
                        // <strong>401 "autentique-se"</strong> no lugar do 400
                        // que era a resposta certa.
                        //
                        // Mandar quem errou a requisição trocar de credencial é
                        // o conselho errado, e ele some o erro de verdade. Vale
                        // para todo erro despachado pelo container, não só para
                        // o caso que revelou isto.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                        // Saúde é pública: é o que o balanceador consulta, e ele
                        // não tem token. 'info' e 'metrics' NÃO são — métrica de
                        // latência por rota conta o desenho do serviço a quem
                        // perguntar.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/sugestoes")
                            .hasAuthority(LER_SUGESTAO)

                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/eventos")
                            .hasAuthority(ESCREVER_EVENTOS)

                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/ofertas")
                            .hasAuthority(ESCREVER_OFERTAS)

                        // Ler a oferta de volta é escopo próprio: quem opera um
                        // painel de conferência não precisa poder cadastrar.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/ofertas/**")
                            .hasAnyAuthority(LER_OFERTAS, ESCREVER_OFERTAS)

                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/aprendizado/rodar")
                            .hasAuthority(EXECUTAR_APRENDIZADO)

                        // Nega por padrão. É a diferença entre uma rota nova
                        // nascer fechada e nascer aberta, e a segunda só se
                        // descobre em revisão de segurança.
                        .anyRequest().denyAll())

                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(this::semCredencial)
                        .accessDeniedHandler(this::semPermissao))

                .exceptionHandling(e -> e
                        .authenticationEntryPoint(this::semCredencial)
                        .accessDeniedHandler(this::semPermissao))

                .build();
    }

    /**
     * 401 em {@code problem+json}, como o resto dos erros deste serviço.
     *
     * <p>O padrão do Spring Security é um 401 de corpo vazio. Funciona e obriga
     * quem integra a adivinhar entre "não mandei token", "mandei expirado" e
     * "mandei assinado com a chave errada".
     *
     * <p><strong>O {@code WWW-Authenticate} é escrito à mão aqui, e tem de
     * ser.</strong> Substituir o ponto de entrada substitui o
     * {@code BearerTokenAuthenticationEntryPoint}, que era quem o emitia — então
     * trocar o corpo do 401 derruba o cabeçalho junto, em silêncio. O RFC 6750
     * exige esse cabeçalho num 401 de recurso protegido por Bearer, e sem ele o
     * cliente não tem como saber qual esquema usar. Foi o teste 13 da collection
     * que apontou, depois de o comentário aqui ter afirmado por escrito que o
     * cabeçalho continuava saindo.
     *
     * <p>Sem o parâmetro {@code error=}, que o RFC permite: ele é exatamente o
     * lugar onde se escreveria "invalid_token" ou "expired", e é o que esta
     * resposta decidiu não contar.
     */
    private void semCredencial(HttpServletRequest requisicao, HttpServletResponse resposta,
                               org.springframework.security.core.AuthenticationException e)
            throws IOException {

        resposta.setHeader("WWW-Authenticate", "Bearer realm=\"sugestao-busca\"");

        var problema = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problema.setType(URI.create(BASE_DE_PROBLEMAS + "sem-credencial"));
        problema.setTitle("Credencial ausente ou invalida");
        problema.setDetail("envie um token Bearer valido no cabecalho Authorization");

        // O motivo exato fica no log. "Assinatura invalida" e "expirado em tal
        // hora" ajudam quem integra e ajudam igualmente quem está sondando.
        log.info("401 em {} {}: {}", requisicao.getMethod(), requisicao.getRequestURI(),
                e.getMessage());

        escrever(resposta, HttpStatus.UNAUTHORIZED, problema);
    }

    private void semPermissao(HttpServletRequest requisicao, HttpServletResponse resposta,
                              org.springframework.security.access.AccessDeniedException e)
            throws IOException {

        var problema = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problema.setType(URI.create(BASE_DE_PROBLEMAS + "sem-permissao"));
        problema.setTitle("Sem permissao");
        problema.setDetail("o token e valido mas nao tem o escopo necessario para esta rota");

        log.info("403 em {} {}: {}", requisicao.getMethod(), requisicao.getRequestURI(),
                e.getMessage());

        escrever(resposta, HttpStatus.FORBIDDEN, problema);
    }

    private void escrever(HttpServletResponse resposta, HttpStatus status, ProblemDetail problema)
            throws IOException {

        resposta.setStatus(status.value());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(resposta.getOutputStream(), problema);
    }
}
