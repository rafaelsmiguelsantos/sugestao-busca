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
            @RequestParam(name = "explicar", required = false, defaultValue = "false") boolean explicar,
            Cliente cliente) {

        var resultado = sugerir.executar(new ConsultaDeSugestao(prefixo, cliente));

        if (log.isDebugEnabled()) {
            log.debug("sugestao {}", resultado.rastro().resumo());
        }

        return RespostaDeSugestoes.de(prefixo, resultado, explicar && explicacaoHabilitada);
    }
}
