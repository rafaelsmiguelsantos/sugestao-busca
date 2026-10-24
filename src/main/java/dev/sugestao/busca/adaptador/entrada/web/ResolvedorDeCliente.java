package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.dominio.Cliente;
import dev.sugestao.busca.dominio.Relacionamento;
import dev.sugestao.busca.dominio.Segmento;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Monta o {@link Cliente} antes de o controller existir.
 *
 * <p>No desenho isto é {@code @AuthenticationPrincipal}: identidade e segmento
 * saem do token, já validados. Aqui saem de cabeçalhos, porque o POC não tem
 * emissor de token — e é só isto que muda. O controller recebe um Cliente nos
 * dois mundos e nunca souber de onde ele veio é o ponto.
 *
 * <p><strong>Não use em produção como está.</strong> Cabeçalho de requisição é
 * dito pelo cliente, não provado: quem escolhe o próprio {@code X-Cliente-Id}
 * escolhe o histórico de quem quiser.
 */
public class ResolvedorDeCliente implements HandlerMethodArgumentResolver {

    static final String CABECALHO_ID = "X-Cliente-Id";
    static final String CABECALHO_SEGMENTO = "X-Cliente-Segmento";
    static final String CABECALHO_RELACIONAMENTO = "X-Cliente-Relacionamento";

    @Override
    public boolean supportsParameter(MethodParameter parametro) {
        return Cliente.class.equals(parametro.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parametro,
            ModelAndViewContainer mav,
            NativeWebRequest requisicao,
            WebDataBinderFactory binder) {

        String id = requisicao.getHeader(CABECALHO_ID);
        String segmento = requisicao.getHeader(CABECALHO_SEGMENTO);
        String relacionamento = requisicao.getHeader(CABECALHO_RELACIONAMENTO);

        // Sem identificação não há erro: há cliente anônimo. Ele recebe a lista
        // popular do segmento, que é exatamente o que o desenho prevê para
        // perfil indisponível.
        return new Cliente(
                id == null || id.isBlank() ? "anonimo" : id,
                Segmento.de(segmento),
                Relacionamento.de(relacionamento));
    }
}
