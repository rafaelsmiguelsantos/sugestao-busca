package dev.sugestao.busca.configuracao;

import dev.sugestao.busca.adaptador.entrada.web.ResolvedorDeCliente;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registra o resolvedor que entrega um {@code Cliente} pronto ao controller.
 * Quando houver token de verdade, este arquivo some e o Spring Security ocupa
 * o lugar — o controller não muda.
 */
@Configuration
public class ConfiguracaoWeb implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvedores) {
        resolvedores.add(new ResolvedorDeCliente());
    }
}
