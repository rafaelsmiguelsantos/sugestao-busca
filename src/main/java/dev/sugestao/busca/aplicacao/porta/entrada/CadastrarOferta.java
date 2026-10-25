package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.oferta.Oferta;

import java.util.Optional;

/**
 * O lado de escrita do catálogo: uma jornada cadastra a oferta que ela possui.
 *
 * <p>Separado de {@link SugerirSugestoes} porque são dois casos de uso com
 * exigências opostas. A sugestão tem 30 ms, nunca falha, e degrada em silêncio.
 * O cadastro tem segundos, falha alto, e precisa dizer exatamente o que recusou.
 * Uma interface só para os dois forçaria um deles a aceitar as garantias do
 * outro.
 */
public interface CadastrarOferta {

    ResultadoDoCadastro executar(ComandoDeCadastroDeOferta comando);

    /**
     * A oferta como o registro a guardou — para a jornada conferir que o que
     * ficou gravado é o que ela mandou.
     *
     * <p>Existe porque o cadastro normaliza: o termo passa pela mesma
     * normalização do prefixo digitado, o identificador vai para maiúscula, os
     * sinônimos são deduplicados. Sem uma leitura de volta, quem integra não tem
     * como saber qual das suas duas grafias sobreviveu.
     */
    Optional<Oferta> porId(String ofertaId);
}
