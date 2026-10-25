package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.dominio.Cliente;
import dev.sugestao.busca.dominio.Relacionamento;
import dev.sugestao.busca.dominio.Segmento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Monta o {@link Cliente} antes de o controller existir.
 *
 * <p>Com segurança ligada, identidade, segmento e faixa saem das reivindicações
 * do token já validado. O controller recebe um {@code Cliente} e nunca soube de
 * onde ele veio — é esse o ponto, e é o que permitiu trocar cabeçalho por token
 * sem alterar uma linha de controller, caso de uso ou domínio.
 *
 * <p><strong>Segmento e faixa vindo do token não é detalhe de autenticação, é
 * decisão de arquitetura.</strong> Eles são atributos de coorte, conhecidos antes
 * de qualquer leitura de perfil, e é isso que permite à consulta ao Solr arrancar
 * junto da leitura do perfil em vez de atrás dela — o que faz o orçamento de
 * 30 ms fechar. Agora eles vêm <em>provados</em> em vez de ditos, o que é a
 * diferença entre uma coorte e uma coorte escolhida por quem chama.
 *
 * <p><strong>Não há queda para cabeçalho quando a segurança está ligada.</strong>
 * A tentação é óbvia: ler o token quando houver, e o cabeçalho quando não. O
 * problema é que isso transforma qualquer rota que acidentalmente fique fora da
 * cadeia de autorização numa rota onde o chamador escolhe o próprio
 * {@code X-Cliente-Id} — e portanto o histórico de quem quiser. O modo de
 * cabeçalho existe só quando a segurança está explicitamente desligada, e nesse
 * caso o log já gritou na subida.
 */
public class ResolvedorDeCliente implements HandlerMethodArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(ResolvedorDeCliente.class);

    static final String CABECALHO_ID = "X-Cliente-Id";
    static final String CABECALHO_SEGMENTO = "X-Cliente-Segmento";
    static final String CABECALHO_RELACIONAMENTO = "X-Cliente-Relacionamento";

    static final String CLAIM_SEGMENTO = "segmento";
    static final String CLAIM_RELACIONAMENTO = "relacionamento";

    private final boolean segurancaHabilitada;

    public ResolvedorDeCliente(boolean segurancaHabilitada) {
        this.segurancaHabilitada = segurancaHabilitada;
    }

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

        Jwt token = tokenAtual();

        if (token != null) {
            return doToken(token);
        }

        if (segurancaHabilitada) {
            // Rota protegida sem token é impossível pela cadeia de autorização.
            // Se acontecer, é configuração furada — e a resposta certa é o
            // cliente mais pobre que existe, nunca o cabeçalho.
            log.warn("rota protegida sem token no contexto: entregando cliente anonimo. "
                    + "Confira a cadeia de autorizacao.");
            return new Cliente("anonimo", Segmento.PF, Relacionamento.VAREJO);
        }

        return doCabecalho(requisicao);
    }

    private static Jwt tokenAtual() {
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null) {
            return null;
        }
        return autenticacao.getPrincipal() instanceof Jwt token ? token : null;
    }

    /**
     * Do token. As leituras de segmento e faixa continuam lenientes — e aqui a
     * leniência é inofensiva, porque o valor foi assinado: ele pode estar
     * ausente ou escrito de forma estranha, mas não foi escolhido pelo chamador.
     * Faixa desconhecida cai em varejo, e a lista fica mais pobre e nunca mais
     * rica.
     */
    private static Cliente doToken(Jwt token) {
        String id = token.getSubject();

        return new Cliente(
                id == null || id.isBlank() ? "anonimo" : id,
                Segmento.de(token.getClaimAsString(CLAIM_SEGMENTO)),
                Relacionamento.de(token.getClaimAsString(CLAIM_RELACIONAMENTO)));
    }

    /**
     * De cabeçalho. Só com {@code sugestao.seguranca.habilitada=false}.
     *
     * <p>Cabeçalho é dito pelo cliente, não provado: quem escolhe o próprio
     * {@code X-Cliente-Id} escolhe o histórico de quem quiser, e quem escolhe o
     * próprio {@code X-Cliente-Relacionamento} vê oferta de faixa que não é a
     * dele.
     */
    private static Cliente doCabecalho(NativeWebRequest requisicao) {
        String id = requisicao.getHeader(CABECALHO_ID);

        // Sem identificação não há erro: há cliente anônimo. Ele recebe a lista
        // popular do segmento, que é exatamente o que o desenho prevê para
        // perfil indisponível.
        return new Cliente(
                id == null || id.isBlank() ? "anonimo" : id,
                Segmento.de(requisicao.getHeader(CABECALHO_SEGMENTO)),
                Relacionamento.de(requisicao.getHeader(CABECALHO_RELACIONAMENTO)));
    }
}
