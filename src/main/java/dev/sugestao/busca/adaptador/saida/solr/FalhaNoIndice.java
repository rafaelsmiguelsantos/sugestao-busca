package dev.sugestao.busca.adaptador.saida.solr;

/**
 * O índice não respondeu. Sobe como falha porque é isso que ela é — quem decide
 * que o cliente não vai ver erro nenhum é o caso de uso, que tem o caminho
 * alternativo declarado. O adaptador não engole exceção por conta própria: se
 * engolisse, devolveria lista vazia e a aplicação não teria como distinguir
 * "não achei nada" de "não consegui perguntar".
 */
public class FalhaNoIndice extends RuntimeException {

    public FalhaNoIndice(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
