package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.oferta.Oferta;

import java.util.Optional;

/**
 * Onde oferta cadastrada é guardada e de onde é lida de volta.
 *
 * <p>Hoje é o mesmo Solr que atende a busca, e isso é escolha de implementação,
 * não da porta. Num sistema de verdade o cadastro de ofertas é um registro
 * próprio, com histórico e aprovação, e o índice é só uma projeção dele. Esta
 * interface é desenhada para suportar essa separação sem mudar o caso de uso:
 * {@link #gravar} e {@link #porId} são as duas únicas coisas que a aplicação
 * precisa, e nenhuma delas fala de documento, de coleção ou de commit.
 *
 * <p>Não tem prazo nem caminho alternativo, ao contrário de
 * {@link CatalogoDeSugestoes}. O cadastro não roda no orçamento de 30 ms: tem
 * uma jornada do outro lado esperando confirmação, e "gravei, talvez" é pior
 * para ela do que esperar um segundo. Falha aqui é erro, e sobe.
 */
public interface RegistroDeOfertas {

    /**
     * Grava a oferta, substituindo a versão anterior do mesmo identificador.
     *
     * <p>Idempotente por {@code ofertaId}: a mesma oferta enviada duas vezes
     * resulta numa oferta, não em duas. Isso não é conveniência — é o que
     * permite à jornada reenviar depois de um timeout sem precisar saber se a
     * primeira tentativa chegou.
     */
    void gravar(Oferta oferta);

    Optional<Oferta> porId(String ofertaId);
}
