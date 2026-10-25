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

        /**
         * Quantos perfis tiveram o mapa de fadiga cortado pelo teto.
         *
         * <p>Existe para o teto não cortar em silêncio. Com o número à vista, a
         * calibragem deixa de ser chute: <strong>zero numa base grande</strong>
         * significa teto folgado e nada a fazer; <strong>a maioria dos
         * perfis</strong> significa que ele está descartando sinal, e aí a
         * conversa é sobre quanto o perfil pode pesar — não sobre se o número
         * está bom.
         *
         * <p>É a mesma lógica de {@code retidosPeloPiso}: um limite que ninguém
         * mede é um limite que alguém vai afrouxar por palpite.
         */
        int perfisNoTetoDeFadiga,

        List<String> exemplosRetidos) {

    public ResultadoDoAprendizado {
        exemplosRetidos = List.copyOf(exemplosRetidos);
    }

    public String resumo() {
        return "eventos=%d termos=%d publicados=%d retidos=%d perfis=%d fadigaNoTeto=%d"
                .formatted(eventosConsumidos, termosAvaliados, termosPublicados,
                        retidosPeloPiso, perfisAtualizados, perfisNoTetoDeFadiga);
    }
}
