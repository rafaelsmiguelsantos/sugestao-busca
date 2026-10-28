package dev.sugestao.busca.configuracao;

import dev.sugestao.busca.adaptador.entrada.web.ResolvedorDeCliente;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Registra o resolvedor que entrega um {@code Cliente} pronto ao controller.
 *
 * <p>O comentário antigo aqui dizia que este arquivo sumiria quando houvesse
 * token de verdade. Errado, e a razão é interessante: o token chegou e o
 * resolvedor continuou — o que mudou foi só de <em>onde</em> ele lê. O Spring
 * Security valida e popula o contexto; traduzir contexto em {@code Cliente}
 * continua sendo trabalho da borda, porque {@code Cliente} é tipo de domínio e
 * o domínio não conhece {@code Jwt}.
 */
@Configuration
public class ConfiguracaoWeb implements WebMvcConfigurer {

    private final boolean segurancaHabilitada;

    public ConfiguracaoWeb(SugestaoProperties props) {
        var seguranca = props.seguranca() == null
                ? SugestaoProperties.Seguranca.padrao()
                : props.seguranca();
        this.segurancaHabilitada = seguranca.habilitada();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvedores) {
        resolvedores.add(new ResolvedorDeCliente(segurancaHabilitada));
    }

    /**
     * Toda resposta JSON passa a declarar {@code charset=UTF-8}.
     *
     * <p>Era uma inconsistência anotada nas limitações — o caminho de erro
     * declarava, o de sucesso não — e ela <strong>deixou de ser cosmética no dia
     * em que um cliente real apareceu</strong>. O avaliador de ranking em
     * {@code scripts\avaliar-ranking.ps1} recebia {@code emprÃ©stimo pessoal} em
     * vez de {@code empréstimo pessoal}, e com isso toda consulta cujo melhor
     * resultado tem acento media zero.
     *
     * <p>A causa não é um defeito do cliente. Sem {@code charset} declarado, um
     * cliente HTTP tem de adivinhar, e adivinhar ISO-8859-1 é a escolha
     * historicamente correta para {@code text/*} — é o que o
     * {@code Invoke-RestMethod} do Windows PowerShell 5.1 faz. A RFC 8259 diz
     * que JSON é UTF-8 e que o parâmetro é dispensável; "dispensável" não é o
     * mesmo que "inofensivo", e metade dos termos deste catálogo tem acento.
     *
     * <p><strong>Por que aqui e não em {@code produces} nas rotas.</strong> São
     * seis rotas hoje, e {@code produces} teria de ser lembrado na sétima. O
     * defeito original foi exatamente este: alguém declarou no caminho de erro e
     * não no de sucesso. Um lugar só, e a próxima rota nasce certa.
     *
     * <p>Só subtipo JSON recebe o parâmetro. Converter que anuncia
     * {@code *&#47;*} — e há — ficaria errado ganhando charset, e {@code text/*}
     * já tem o seu resolvido pelo Spring.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> conversores) {
        for (HttpMessageConverter<?> conversor : conversores) {
            if (conversor instanceof AbstractHttpMessageConverter<?> abstrato) {
                abstrato.setSupportedMediaTypes(
                        abstrato.getSupportedMediaTypes().stream()
                                .map(ConfiguracaoWeb::comUtf8)
                                .toList());
            }
        }
    }

    private static MediaType comUtf8(MediaType tipo) {
        if (tipo.getCharset() != null || !ehJson(tipo)) {
            return tipo;
        }
        return new MediaType(tipo.getType(), tipo.getSubtype(), StandardCharsets.UTF_8);
    }

    /** {@code application/json} e os sufixados, como {@code application/problem+json}. */
    private static boolean ehJson(MediaType tipo) {
        String subtipo = tipo.getSubtype();
        return "json".equals(subtipo) || subtipo.endsWith("+json");
    }
}
