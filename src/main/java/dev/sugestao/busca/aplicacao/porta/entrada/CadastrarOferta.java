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

    /**
     * @param autorizacao de qual jornada o chamador é dono. A regra "a oferta
     *                    tem de ser da jornada de quem cadastra" é aplicada
     *                    aqui, na aplicação, e não no controller: ela é regra de
     *                    negócio, e a borda só sabe traduzir token em
     *                    {@link Autorizacao}. O efeito prático é que ela é
     *                    testável sem HTTP, e continuaria valendo se o cadastro
     *                    passasse a entrar por uma fila ou por um console.
     * @throws JornadaNaoAutorizada quando a oferta é de outra jornada
     * @throws dev.sugestao.busca.dominio.oferta.OfertaInvalida com todas as violações
     */
    ResultadoDoCadastro executar(ComandoDeCadastroDeOferta comando, Autorizacao autorizacao);

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
