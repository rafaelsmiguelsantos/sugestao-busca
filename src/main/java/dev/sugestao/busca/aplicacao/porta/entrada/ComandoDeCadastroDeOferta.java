package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.oferta.Oferta;

import java.time.Instant;
import java.util.List;

/**
 * O pedido de cadastro, em texto cru, como chegou de fora.
 *
 * <p>Campos de texto e não de domínio de propósito. Quem recebe o pedido é a
 * borda HTTP, que sabe desempacotar JSON e não deve saber o que é um
 * {@code Relacionamento} válido. Quem decide o que é válido é o domínio, em
 * {@link Oferta#de}. Entre os dois fica este comando, que não valida nada e
 * também não perde nada: o valor errado chega até a regra que o recusa, com o
 * texto original, para a mensagem de erro poder citá-lo.
 *
 * <p>É por isso que não há {@code ctr}, {@code taxaAdesao} nem
 * {@code clientesDistintos} aqui. Não é campo esquecido: é campo que o cadastro
 * não aceita, e a ausência no comando garante que nenhuma borda consiga
 * oferecê-lo sem mexer na aplicação.
 */
public record ComandoDeCadastroDeOferta(
        String ofertaId,
        String termo,
        List<String> sinonimos,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        boolean exigeElegibilidade,
        boolean vetaInadimplencia,
        boolean exigeContaAtiva,
        List<String> gatilhos,
        List<String> segmentos,
        List<String> relacionamentos,
        Instant vigenciaInicio,
        Instant vigenciaFim) {

    /** @throws dev.sugestao.busca.dominio.oferta.OfertaInvalida com todas as violações */
    public Oferta paraOferta() {
        return Oferta.de(
                ofertaId, termo, sinonimos, rotulo, destino, jornada, produtoAlvo,
                exigeElegibilidade, vetaInadimplencia, exigeContaAtiva,
                gatilhos, segmentos, relacionamentos, vigenciaInicio, vigenciaFim);
    }
}
