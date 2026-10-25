package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.aplicacao.porta.entrada.ConsultaDeSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.SugerirSugestoes;
import dev.sugestao.busca.configuracao.SugestaoProperties;
import dev.sugestao.busca.dominio.Cliente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A borda HTTP, e nada além disso: recebe, delega, formata, registra.
 *
 * <p>Não há tratamento de erro aqui porque não há erro a tratar. Perfil fora do
 * ar, índice fora do ar, prefixo curto demais — tudo isso já virou lista vazia
 * ou lista degradada antes de chegar neste ponto. Uma caixa de busca que devolve
 * 500 enquanto a pessoa digita é pior do que uma que devolve nada.
 *
 * <p>Os dois parâmetros opcionais chegam como texto, e não como {@code boolean}
 * e {@code int}, pelo mesmo motivo. A conversão do Spring falha alto: um
 * {@code ?explicar=sim} ou {@code ?limite=tres} vira 400 antes de qualquer
 * código nosso rodar, e seria o único jeito de esta rota responder com erro.
 * Lidos como texto e interpretados aqui, valor estranho cai no padrão e a lista
 * sai. É tolerância na entrada, que é o lugar certo para ela: ninguém depura uma
 * caixa de busca pelo código de status.
 *
 * <p>O log do rastro mora aqui, e não no caso de uso, para que a aplicação
 * continue sem dependência nenhuma de framework — nem de fachada de log.
 */
@RestController
public class SugestaoController {

    private static final Logger log = LoggerFactory.getLogger(SugestaoController.class);

    private final SugerirSugestoes sugerir;
    private final boolean explicacaoHabilitada;

    public SugestaoController(SugerirSugestoes sugerir, SugestaoProperties props) {
        this.sugerir = sugerir;
        this.explicacaoHabilitada = props.explicacaoHabilitada();
    }

    @GetMapping("/v1/sugestoes")
    public RespostaDeSugestoes sugestoes(
            @RequestParam(name = "q", required = false, defaultValue = "") String prefixo,
            @RequestParam(name = "limite", required = false) String limite,
            @RequestParam(name = "explicar", required = false) String explicar,
            Cliente cliente) {

        var resultado = sugerir.executar(
                new ConsultaDeSugestao(prefixo, cliente, inteiro(limite)));

        if (log.isDebugEnabled()) {
            log.debug("sugestao {}", resultado.rastro().resumo());
        }

        return RespostaDeSugestoes.de(
                prefixo, resultado, verdadeiro(explicar) && explicacaoHabilitada);
    }

    /**
     * Zero para ausente ou ilegível — e zero significa "usa o configurado", em
     * {@code ParametrosDeSugestao.limiteEfetivo}. Valor acima do teto não é
     * recusado aqui nem lá: é limitado.
     */
    private static int inteiro(String valor) {
        if (valor == null || valor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(valor.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Só {@code true} e {@code 1} ligam a explicação. Qualquer outra coisa,
     * inclusive texto sem sentido, desliga.
     *
     * <p>Falha fechada, e aqui isso tem peso: a explicação lista os descartes, e
     * os descartes dizem quais produtos aquele cliente não pode contratar.
     * Interpretar generosamente um {@code ?explicar=qualquercoisa} como "sim"
     * seria vazar informação sobre a pessoa por causa de uma consulta mal
     * escrita. A chave {@code sugestao.explicacao-habilitada} é o segundo
     * freio, e em produção ela fica desligada.
     */
    private static boolean verdadeiro(String valor) {
        if (valor == null) {
            return false;
        }
        String limpo = valor.strip().toLowerCase();
        return "true".equals(limpo) || "1".equals(limpo);
    }
}
