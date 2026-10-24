package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.aplicacao.porta.entrada.ConsultaDeSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.SugerirSugestoes;
import dev.sugestao.busca.dominio.Cliente;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A borda HTTP, e nada além disso: recebe, delega, formata.
 *
 * <p>Não há tratamento de erro aqui porque não há erro a tratar. Perfil fora do
 * ar, índice fora do ar, prefixo curto demais — tudo isso já virou lista vazia
 * ou lista degradada antes de chegar neste ponto. Uma caixa de busca que devolve
 * 500 enquanto a pessoa digita é pior do que uma que devolve nada.
 */
@RestController
public class SugestaoController {

    private final SugerirSugestoes sugerir;

    public SugestaoController(SugerirSugestoes sugerir) {
        this.sugerir = sugerir;
    }

    @GetMapping("/v1/sugestoes")
    public RespostaDeSugestoes sugestoes(
            @RequestParam(name = "q", required = false, defaultValue = "") String prefixo,
            Cliente cliente) {

        var lista = sugerir.executar(new ConsultaDeSugestao(prefixo, cliente));
        return RespostaDeSugestoes.de(prefixo, lista);
    }
}
