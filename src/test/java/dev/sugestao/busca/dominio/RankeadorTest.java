package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RankeadorTest {

    private final Rankeador rankeador = new Rankeador();
    private final PesosDeRanking pesos = PesosDeRanking.padrao();

    @Test
    @DisplayName("a jornada que domina os candidatos lidera, e o produto dela vem antes do artigo de ajuda")
    void jornadaDominanteLideraEOProdutoVemPrimeiro() {
        var produto = daJornada("empréstimo pessoal", "credito", "EMPRESTIMO", 1.0);
        var ajuda = daJornada("como funciona o consignado", "credito", "", 0.95);
        var outra = daJornada("seguro do celular", "seguros", "SEGURO_CELULAR", 0.9);

        var r = rankeador.ranquear(List.of(ajuda, outra, produto), Perfil.NEUTRO, pesos);

        assertThat(r.jornadaLider()).isEqualTo("credito");

        var ordenados = r.candidatos().stream()
                .sorted(Rankeador.maisRelevantePrimeiro())
                .map(Candidato::termo)
                .toList();

        // Quem digita "emprestim" quer contratar, não entender. O artigo
        // continua na lista, uma linha abaixo.
        assertThat(ordenados).containsExactly(
                "empréstimo pessoal", "como funciona o consignado", "seguro do celular");
    }

    @Test
    @DisplayName("empate entre jornadas não elege ninguém")
    void empateNaoElegeJornada() {
        var cartao = daJornada("limite do cartão", "cartao", "LIMITE_CARTAO", 1.0);
        var pix = daJornada("limite do Pix", "pix", "", 0.98);

        var r = rankeador.ranquear(List.of(cartao, pix), Perfil.NEUTRO, pesos);

        // "limite" serve aos dois. Escolher um por diferença de terceira casa
        // decimal seria inventar uma intenção que ninguém demonstrou.
        assertThat(r.temLider()).isFalse();
        assertThat(r.candidatos()).extracting(Candidato::relevancia)
                .containsExactly(1.0, 0.98);
    }

    @Test
    @DisplayName("'busca' nunca lidera: é o balde do log, não uma jornada")
    void buscaNuncaLidera() {
        var muitos = List.of(
                daJornada("pagar boleto", "busca", "", 1.0),
                daJornada("pagar fatura", "busca", "", 0.9),
                daJornada("pagar conta de luz", "busca", "", 0.8),
                daJornada("seguro de vida", "seguros", "SEGURO_VIDA", 0.3));

        var r = rankeador.ranquear(muitos, Perfil.NEUTRO, pesos);

        // Por contagem, "busca" venceria sempre — e o efeito seria inclinar a
        // lista para mais do mesmo texto, em vez de para uma intenção.
        assertThat(r.jornadaLider()).isEqualTo("seguros");
    }

    @Test
    @DisplayName("o comportamento do próprio cliente muda a ordem, e dois clientes veem listas diferentes")
    void afinidadeDoClienteMudaAOrdem() {
        var credito = daJornada("empréstimo pessoal", "credito", "EMPRESTIMO", 1.0);
        var investimento = daJornada("CDB", "investimento", "CDB", 1.0);

        var investidor = comAfinidade(Map.of("investimento", 1.0));
        var tomador = comAfinidade(Map.of("credito", 1.0));

        var paraInvestidor = primeiroTermo(List.of(credito, investimento), investidor);
        var paraTomador = primeiroTermo(List.of(credito, investimento), tomador);

        // Mesmos candidatos, mesmo score do índice, ordens diferentes. É o que
        // o ctr e a taxa_adesao do índice não conseguem fazer: aqueles são
        // agregados da população e dariam a mesma ordem para os dois.
        assertThat(paraInvestidor).isEqualTo("CDB");
        assertThat(paraTomador).isEqualTo("empréstimo pessoal");
    }

    @Test
    @DisplayName("sem afinidade registrada a ordem é a do índice")
    void semAfinidadeNadaMuda() {
        var a = daJornada("CDB", "investimento", "CDB", 1.0);
        var b = daJornada("Tesouro Direto", "investimento", "TESOURO", 0.5);

        var r = rankeador.ranquear(List.of(a, b), Perfil.NEUTRO, pesos);

        // As duas são da mesma jornada, então o peso de jornada multiplica as
        // duas igualmente e a proporção original sobrevive.
        assertThat(r.candidatos().get(0).relevancia())
                .isEqualTo(r.candidatos().get(1).relevancia() * 2);
    }

    @Test
    @DisplayName("o histórico não é reescalado: ele já nasce entre 0 e 1")
    void historicoNaoEhNormalizado() {
        var doIndice = new Candidato("pagar boleto", "", "/app", "conta", "",
                Restricoes.NENHUMA, List.of(), 1000, 87.4, Origem.POPULAR);
        var doHistorico = new Candidato("pagar fatura", "", "/busca", "busca", "",
                Restricoes.NENHUMA, List.of(), 0, 0.9, Origem.HISTORICO);

        var r = rankeador.ranquear(List.of(doIndice, doHistorico), Perfil.NEUTRO, pesos);

        assertThat(r.candidatos())
                .filteredOn(c -> c.origem() == Origem.HISTORICO)
                .singleElement()
                .extracting(Candidato::relevancia)
                .isEqualTo(0.9);
    }

    @Test
    @DisplayName("nenhum peso rebaixa: todo candidato sai com relevância igual ou maior")
    void pesosSoPromovem() {
        var candidatos = List.of(
                daJornada("empréstimo pessoal", "credito", "EMPRESTIMO", 1.0),
                daJornada("seguro de vida", "seguros", "SEGURO_VIDA", 0.4));

        var r = rankeador.ranquear(candidatos, comAfinidade(Map.of("credito", 1.0)), pesos);

        // Normalizado pelo maior, o de 0.4 vira 0.4 e não pode cair disso.
        // Rebaixar sem dizer por quê é como remover sem registrar — para
        // remover existe a Peneira, que devolve o motivo.
        assertThat(r.candidatos())
                .allSatisfy(c -> assertThat(c.relevancia()).isGreaterThanOrEqualTo(0.4));
    }

    // -----------------------------------------------------------------------

    private String primeiroTermo(List<Candidato> candidatos, Perfil perfil) {
        return rankeador.ranquear(candidatos, perfil, pesos).candidatos().stream()
                .sorted(Rankeador.maisRelevantePrimeiro())
                .map(Candidato::termo)
                .findFirst()
                .orElseThrow();
    }

    private static Perfil comAfinidade(Map<String, Double> afinidade) {
        return new Perfil(List.of(), Set.of(), Set.of(), Set.of(),
                SituacaoDaConta.ATIVA, afinidade, true);
    }

    private static Candidato daJornada(String termo, String jornada, String produto, double score) {
        return new Candidato(termo, "", "/app/" + termo, jornada, produto,
                Restricoes.NENHUMA, List.of(), 1000, score, Origem.CATALOGO);
    }
}
