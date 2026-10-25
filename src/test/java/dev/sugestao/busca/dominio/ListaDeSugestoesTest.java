package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListaDeSugestoesTest {

    private static Sugestao oferta(String termo, String ofertaId, String produto, double relevancia) {
        return new Sugestao(ofertaId, termo, "", "/app/" + termo, "credito", produto,
                relevancia, Origem.CATALOGO);
    }

    private static Sugestao naoOferta(String termo, double relevancia, Origem origem) {
        return new Sugestao("", termo, "", "/busca", "busca", "", relevancia, origem);
    }

    @Test
    @DisplayName("a melhor oferta é a primeira linha da lista que identifica uma oferta")
    void primeiraLinhaQueEhOferta() {
        var lista = new ListaDeSugestoes(List.of(
                naoOferta("contratar emprestimo", 1.0, Origem.POPULAR),
                oferta("empréstimo pessoal", "OFR-CREDITO-EMPRESTIMO", "EMPRESTIMO", 0.9),
                oferta("crédito consignado", "OFR-CREDITO-CONSIGNADO", "CONSIGNADO", 0.8)),
                false);

        assertThat(lista.melhorOferta()).get()
                .extracting(Sugestao::ofertaId).isEqualTo("OFR-CREDITO-EMPRESTIMO");
        assertThat(lista.posicaoDaMelhorOferta()).isEqualTo(1);
    }

    @Test
    @DisplayName("é posicional, não um segundo ranking: não reordena por relevância")
    void naoReordena() {
        // A segunda oferta tem relevância maior. Ela não vence: quem decidiu a
        // ordem foi o Rankeador, com jornada líder e afinidade do cliente, e
        // reordenar aqui criaria duas respostas à mesma pergunta — a resposta
        // exibida divergindo da resposta acionada.
        var lista = new ListaDeSugestoes(List.of(
                oferta("empréstimo pessoal", "OFR-A", "EMPRESTIMO", 0.5),
                oferta("crédito consignado", "OFR-B", "CONSIGNADO", 9.9)),
                false);

        assertThat(lista.melhorOferta()).get().extracting(Sugestao::ofertaId).isEqualTo("OFR-A");
        assertThat(lista.posicaoDaMelhorOferta()).isZero();
    }

    @Test
    @DisplayName("lista sem oferta nenhuma não tem melhor oferta, e isso não é erro")
    void semOfertaNaLista() {
        // Prefixo que só casa com artigo de ajuda, ou cliente com todas as
        // ofertas vetadas. A ausência é informação: para esta pessoa e este
        // prefixo não há oferta elegível. Devolver a primeira linha qualquer só
        // para o campo não vir vazio seria inventar oferta.
        var lista = new ListaDeSugestoes(List.of(
                naoOferta("como funciona o consignado", 1.0, Origem.CATALOGO),
                naoOferta("simular consignado", 0.6, Origem.POPULAR)),
                false);

        assertThat(lista.melhorOferta()).isEmpty();
        assertThat(lista.posicaoDaMelhorOferta()).isEqualTo(-1);
    }

    @Test
    @DisplayName("lista vazia não tem melhor oferta")
    void listaVazia() {
        assertThat(ListaDeSugestoes.vazia().melhorOferta()).isEmpty();
        assertThat(ListaDeSugestoes.vazia().posicaoDaMelhorOferta()).isEqualTo(-1);
    }

    @Test
    @DisplayName("linha com identificador mas sem produto não conta como oferta")
    void identificadorSemProdutoNaoEhOferta() {
        // Mesma regra de Candidato.ehOferta(), e precisa ser a mesma: se as
        // duas divergirem, a resposta HTTP anuncia como oferta algo que a
        // peneira e o ranking não trataram como oferta.
        var lista = new ListaDeSugestoes(List.of(
                oferta("portabilidade de salário", "OFR-SEM-PRODUTO", "", 1.0)),
                false);

        assertThat(lista.melhorOferta()).isEmpty();
    }
}
