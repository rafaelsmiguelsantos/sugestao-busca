package dev.sugestao.busca.aplicacao;

import java.time.Duration;

/**
 * Prazos por dependência, não timeouts de biblioteca. A diferença é que cada um
 * destes tem caminho alternativo declarado e nenhum tem nova tentativa — a
 * próxima tecla já vem a caminho, e repetir a chamada só atrasaria a resposta
 * de um prefixo que o cliente já abandonou.
 *
 * <p>O {@code total} não é a soma: Solr e perfil correm em paralelo. Ele é o
 * teto do leque inteiro, e serve para que uma dependência lenta não consuma o
 * prazo da outra.
 */
public record Prazos(Duration solr, Duration perfil, Duration total) {

    public static Prazos de(long solrMs, long perfilMs, long totalMs) {
        return new Prazos(
                Duration.ofMillis(solrMs),
                Duration.ofMillis(perfilMs),
                Duration.ofMillis(totalMs));
    }
}
