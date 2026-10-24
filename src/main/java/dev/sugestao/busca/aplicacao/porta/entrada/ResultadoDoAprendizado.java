package dev.sugestao.busca.aplicacao.porta.entrada;

import java.util.List;

/**
 * O que uma rodada de agregação e publicação fez.
 *
 * <p>{@code retidosPeloPiso} é o número que importa ler. Ele não é desperdício:
 * é a medida de quanta coisa foi digitada por gente de menos para virar sugestão
 * de alguém. Se ele vai a zero numa base grande, o piso provavelmente foi
 * afrouxado — e esse é o único parâmetro do projeto cujo afrouxamento vira
 * incidente de privacidade em vez de queda de relevância.
 */
public record ResultadoDoAprendizado(
        int eventosConsumidos,
        int termosAvaliados,
        int termosPublicados,
        int retidosPeloPiso,
        int perfisAtualizados,
        List<String> exemplosRetidos) {

    public ResultadoDoAprendizado {
        exemplosRetidos = List.copyOf(exemplosRetidos);
    }

    public String resumo() {
        return "eventos=%d termos=%d publicados=%d retidos=%d perfis=%d"
                .formatted(eventosConsumidos, termosAvaliados, termosPublicados,
                        retidosPeloPiso, perfisAtualizados);
    }
}
