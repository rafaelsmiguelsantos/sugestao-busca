package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.adaptador.saida.solr.FalhaNoIndice;
import dev.sugestao.busca.aplicacao.porta.entrada.JornadaNaoAutorizada;
import dev.sugestao.busca.dominio.oferta.OfertaInvalida;
import dev.sugestao.busca.dominio.oferta.Violacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Traduz exceção em resposta, no formato do RFC 9457
 * ({@code application/problem+json}).
 *
 * <p>Formato padronizado e não um JSON de erro próprio porque erro é contrato
 * igual ao sucesso. Quem integra escreve o tratamento de falha uma vez, contra
 * {@code type} e {@code status}, e não precisa aprender um esquema novo por
 * serviço. {@code type} é URI e funciona como código estável: a redação de
 * {@code detail} pode melhorar sem quebrar ninguém.
 *
 * <p><strong>A regra de ouro desta classe: nada do que sai aqui conta como o
 * sistema é feito por dentro.</strong> Nome de classe, caminho de arquivo,
 * consulta ao Solr e pilha de chamadas ficam no log, que é nosso; a resposta
 * leva o que o chamador pode usar para corrigir a chamada dele. Mensagem de
 * exceção de infraestrutura é a forma mais comum de um serviço contar o nome da
 * sua coleção, a porta do seu banco e a versão da sua biblioteca a quem
 * perguntar errado de propósito.
 *
 * <p><strong>Estende {@link ResponseEntityExceptionHandler} de propósito.</strong>
 * O apanhador de {@link Exception} aqui embaixo é rede de segurança, e sem a
 * classe-mãe ele seria uma armadilha: caminho inexistente, método HTTP errado e
 * {@code Content-Type} não suportado são exceções do próprio Spring MVC, e todas
 * cairiam nele e sairiam como 500. Um 404 reportado como 500 manda quem integra
 * investigar o nosso serviço quando o erro é um caminho mal digitado do lado
 * dele. A classe-mãe trata cada uma com o status certo, e por especificidade ela
 * ganha do apanhador genérico.
 *
 * <p>Note que a busca não aparece aqui. {@code GET /v1/sugestoes} não tem
 * caminho de erro: índice fora do ar vira lista degradada e prefixo inválido
 * vira lista vazia, dentro do próprio caso de uso. Esta classe existe para o
 * lado de escrita.
 */
@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler
        implements ResponseBodyAdvice<Object> {

    private static final Logger registro = LoggerFactory.getLogger(TratadorDeErros.class);

    private static final String BASE = "https://sugestao-busca.dev/problemas/";

    /** {@code application/problem+json;charset=UTF-8}. Ver {@link #beforeBodyWrite}. */
    private static final MediaType PROBLEMA_UTF8 = new MediaType(
            MediaType.APPLICATION_PROBLEM_JSON, StandardCharsets.UTF_8);

    /**
     * 422 e não 400.
     *
     * <p>A diferença é útil para quem integra e não é preciosismo: 400 diz "não
     * consegui ler o que você mandou" e a correção é no serializador; 422 diz
     * "li perfeitamente e recusei" e a correção é no valor. Corpo com JSON
     * quebrado dá 400 logo abaixo; oferta com jornada reservada dá 422.
     */
    @ExceptionHandler(OfertaInvalida.class)
    public ProblemDetail ofertaInvalida(OfertaInvalida e) {
        var problema = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problema.setType(URI.create(BASE + "oferta-invalida"));
        problema.setTitle("Oferta invalida");
        problema.setDetail(e.violacoes().size() == 1
                ? "1 campo recusado"
                : e.violacoes().size() + " campos recusados");

        // Todas as violacoes, nao a primeira. Ver OfertaInvalida.
        problema.setProperty("violacoes", e.violacoes().stream()
                .map(TratadorDeErros::comoMapa)
                .toList());

        registro.info("cadastro de oferta recusado: {}", e.violacoes());
        return problema;
    }

    /**
     * 403, e não 422.
     *
     * <p>A distinção é a que mais ajuda quem integra: 422 diz "o que você mandou
     * está errado" e a correção é no corpo; 403 diz "o que você mandou está
     * certo e não é seu", e a correção é no token — ou em não mandar. Devolver
     * as duas como 422 obrigaria a ler a mensagem para saber se o problema é do
     * pedido ou da permissão.
     *
     * <p>A resposta diz qual jornada foi pedida e qual é a do chamador. As duas
     * são informação que ele já tem: uma ele enviou, a outra está no token dele.
     */
    @ExceptionHandler(JornadaNaoAutorizada.class)
    public ProblemDetail jornadaNaoAutorizada(JornadaNaoAutorizada e) {
        var problema = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problema.setType(URI.create(BASE + "jornada-nao-autorizada"));
        problema.setTitle("Jornada nao autorizada");
        problema.setDetail("o token autoriza cadastro apenas na jornada do proprio chamador");
        problema.setProperty("jornadaPedida", e.jornadaPedida());
        problema.setProperty("jornadaDoChamador", e.jornadaDoChamador());

        registro.warn("cadastro negado: pediu '{}', e dono de '{}'",
                e.jornadaPedida(), e.jornadaDoChamador());
        return problema;
    }

    @ExceptionHandler(OfertaNaoEncontrada.class)
    public ProblemDetail naoEncontrada(OfertaNaoEncontrada e) {
        var problema = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problema.setType(URI.create(BASE + "oferta-nao-encontrada"));
        problema.setTitle("Oferta nao encontrada");
        problema.setDetail("nenhuma oferta cadastrada com este identificador");
        problema.setProperty("ofertaId", e.ofertaId());
        return problema;
    }

    /**
     * 503 e não 500: o índice estar fora do ar é condição temporária, e a
     * diferença diz à jornada que reenviar faz sentido. {@code Retry-After} fica
     * de fora de propósito — não temos previsão de volta para prometer, e
     * prometer errado é pior que não prometer.
     */
    @ExceptionHandler(FalhaNoIndice.class)
    public ProblemDetail falhaNoIndice(FalhaNoIndice e) {
        var problema = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problema.setType(URI.create(BASE + "indice-indisponivel"));
        problema.setTitle("Indice indisponivel");
        problema.setDetail("o cadastro nao foi gravado; tente novamente");

        // A causa inteira so no log: ela carrega URL, colecao e porta.
        registro.error("falha no indice no caminho de escrita", e);
        return problema;
    }

    /**
     * A rede de segurança. Qualquer coisa não prevista sai como 500 sem detalhe,
     * e entra inteira no log — que é o único lugar onde ela é segura.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail inesperado(Exception e) {
        var problema = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problema.setType(URI.create(BASE + "erro-interno"));
        problema.setTitle("Erro interno");
        problema.setDetail("a requisicao nao foi concluida");

        registro.error("erro nao tratado", e);
        return problema;
    }

    /**
     * Substitui a mensagem da classe-mãe por uma que diz o que corrigir.
     *
     * <p>A mensagem do Jackson não vai no corpo em nenhum caso: ela cita nome de
     * classe Java, nome de campo e por vezes um trecho do que foi enviado —
     * útil no log, indesejado numa resposta. O que falta a quem integra é o
     * formato de data, que é o erro que de fato acontece aqui.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e,
            HttpHeaders cabecalhos,
            HttpStatusCode status,
            WebRequest requisicao) {

        var problema = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problema.setType(URI.create(BASE + "corpo-ilegivel"));
        problema.setTitle("Corpo da requisicao ilegivel");
        problema.setDetail("JSON malformado, ou data fora do formato ISO-8601 "
                + "(exemplo: 2026-10-24T00:00:00Z)");

        registro.info("corpo ilegivel: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(problema);
    }

    /**
     * Declara {@code charset=UTF-8} em toda resposta de problema.
     *
     * <p><strong>Por que isto não é redundante com
     * {@code ConfiguracaoWeb.extendMessageConverters}.</strong> Aquele método
     * acrescenta o parâmetro aos tipos <em>suportados</em> dos conversores, e
     * resolve o caminho de sucesso. {@link ProblemDetail} não passa por ali: o
     * Spring escolhe o tipo de resposta dele a partir de uma constante
     * — {@code APPLICATION_PROBLEM_JSON}, sem charset — e essa escolha vence a
     * lista de suportados.
     *
     * <p>O resultado, <em>medido</em>, era a mesma inconsistência de antes
     * mudada de lugar: sucesso e os 401/403 da cadeia de segurança declaravam o
     * charset, e o 404 e o <strong>422</strong> não. O 422 é precisamente a
     * resposta mais cheia de texto acentuado deste serviço — "caminho relativo
     * começando com '/'", "valor não reconhecido" — ou seja, a correção tinha
     * deixado de fora o corpo que mais precisava dela.
     *
     * <p>Um {@code ResponseEntity} com {@code contentType} em cada apanhador
     * resolveria e repetiria a decisão seis vezes, com a sétima sendo esquecida.
     * Foi exatamente assim que o defeito original nasceu.
     */
    @Override
    public boolean supports(MethodParameter retorno, Class<? extends HttpMessageConverter<?>> conversor) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object corpo,
            MethodParameter retorno,
            MediaType tipoEscolhido,
            Class<? extends HttpMessageConverter<?>> conversor,
            ServerHttpRequest requisicao,
            ServerHttpResponse resposta) {

        // Só problem+json, e só quando ninguém declarou charset. A resposta de
        // sucesso já vem resolvida pelos conversores, e sobrescrever um charset
        // declarado à mão seria desfazer uma decisão de quem a declarou.
        if (tipoEscolhido != null
                && tipoEscolhido.getCharset() == null
                && MediaType.APPLICATION_PROBLEM_JSON.equalsTypeAndSubtype(tipoEscolhido)) {
            resposta.getHeaders().setContentType(PROBLEMA_UTF8);
        }
        return corpo;
    }

    private static Map<String, String> comoMapa(Violacao violacao) {
        return Map.of(
                "campo", violacao.campo(),
                "codigo", violacao.codigo(),
                "mensagem", violacao.mensagem());
    }
}
