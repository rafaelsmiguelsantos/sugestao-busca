package dev.sugestao.busca.configuracao;

import dev.sugestao.busca.adaptador.entrada.web.ResolvedorDeCliente;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
}
