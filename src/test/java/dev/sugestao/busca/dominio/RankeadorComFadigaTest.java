package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Os três sinais novos no ranking: afinidade por produto, tendência e fadiga.
 *
 * <p>O caso que mais importa aqui é {@link #tetoAntesDaFadiga()}: a ordem das
 * operações não é intercambiável, e a forma errada falha em silêncio — a fadiga
 * simplesmente para de funcionar para quem tem mais afinidade, que é quem mais
 * precisa dela.
 */
class RankeadorComFadigaTest {

    private final Rankeador rankeador = new Rankeador();
    private static final PesosDeRanking PESOS = PesosDeRanking.padrao();

    // -----------------------------------------------------------------------
    // B2 · afinidade por produto distingue o que a jornada não distinguia
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dois produtos da mesma jornada deixam de empatar")
    void produtoDesempataDentroDaJornada() {
        // É o defeito que B2 existe para corrigir: consignado e empréstimo são
        // ambos `credito`, e com afinidade só por jornada os dois recebiam
        // exatamente o mesmo impulso. O sistema sabia que a pessoa queria
        // crédito e não tinha como saber qual.
        var emprestimo = candidato("emprestimo pessoal", "credito", "EMPRESTIMO", 1.0);
        var consignado = candidato("credito consignado", "credito", "CONSIGNADO", 1.0);

        var perfil = perfil(
                Map.of("credito", 1.0),
                Map.of("CONSIGNADO", 1.0),
                Map.of(),
                Map.of());

        var ranqueado = rankeador.ranquear(List.of(emprestimo, consignado), perfil, PESOS);

        var porTermo = porTermo(ranqueado);
        assertThat(porTermo.get("credito consignado"))
                .as("o produto que a pessoa demonstrou querer vem na frente")
                .isGreaterThan(porTermo.get("emprestimo pessoal"));

        // Os números, porque foram eles que apontaram o defeito do teto.
        //
        // Empréstimo: 1,6 × 1,25 × 1,5 = 3,0. Consignado: × 1,2 = 3,6.
        //
        // Com o teto em 3,0 — a primeira escolha, "conservadora" — os DOIS saíam
        // 3,0 e esta asserção falhava. Não por erro de conta: porque a afinidade
        // por produto era inerte para quem já tinha afinidade de jornada no
        // máximo. Ver PesosDeRanking.padrao().
        assertThat(porTermo.get("emprestimo pessoal")).isCloseTo(3.0, within(0.0001));
        assertThat(porTermo.get("credito consignado")).isCloseTo(3.6, within(0.0001));
    }

    @Test
    @DisplayName("sem afinidade de produto os dois continuam empatados")
    void semAfinidadeDeProdutoEmpatam() {
        // O recurso novo não muda nada para quem não tem evidência por produto —
        // que é o caso comum e tem de continuar sendo barato.
        var emprestimo = candidato("emprestimo pessoal", "credito", "EMPRESTIMO", 1.0);
        var consignado = candidato("credito consignado", "credito", "CONSIGNADO", 1.0);

        var perfil = perfil(Map.of("credito", 1.0), Map.of(), Map.of(), Map.of());

        var porTermo = porTermo(rankeador.ranquear(List.of(emprestimo, consignado), perfil, PESOS));

        assertThat(porTermo.get("credito consignado"))
                .isCloseTo(porTermo.get("emprestimo pessoal"), within(0.0001));
    }

    // -----------------------------------------------------------------------
    // B3 · tendência
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tendência de alta promove; de baixa não rebaixa")
    void tendenciaSoPromove() {
        var subindo = perfil(Map.of(), Map.of(), Map.of("credito", 4.3), Map.of());
        var esfriando = perfil(Map.of(), Map.of(), Map.of("credito", 0.0), Map.of());
        var neutro = perfil(Map.of(), Map.of(), Map.of(), Map.of());

        double comAlta = soScore(rankeador.ranquear(
                List.of(candidato("emprestimo", "credito", "EMPRESTIMO", 1.0)), subindo, PESOS));
        double comBaixa = soScore(rankeador.ranquear(
                List.of(candidato("emprestimo", "credito", "EMPRESTIMO", 1.0)), esfriando, PESOS));
        double semNada = soScore(rankeador.ranquear(
                List.of(candidato("emprestimo", "credito", "EMPRESTIMO", 1.0)), neutro, PESOS));

        assertThat(comAlta).as("alta promove").isGreaterThan(semNada);

        // Assimetria deliberada: rebaixar por tendência fria puniria quem
        // demonstrou interesse há três semanas, e o decaimento da afinidade já
        // cuida do esquecimento com uma curva que tem física por trás.
        assertThat(comBaixa).as("baixa NÃO rebaixa").isEqualTo(semNada);
    }

    // -----------------------------------------------------------------------
    // B1 · fadiga
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("linha ignorada perde posição para linha de relevância parecida")
    void fadigaReordena() {
        var ignorada = candidato("antecipar limite", "credito", "ANTECIPACAO", 1.0);
        var nova = candidato("emprestimo pessoal", "credito", "EMPRESTIMO", 0.95);

        var perfil = perfil(Map.of(), Map.of(), Map.of(), Map.of("antecipar limite", 0.4));

        var porTermo = porTermo(rankeador.ranquear(List.of(ignorada, nova), perfil, PESOS));

        assertThat(porTermo.get("emprestimo pessoal"))
                .as("a linha nova passa a cansada")
                .isGreaterThan(porTermo.get("antecipar limite"));
    }

    @Test
    @DisplayName("o rebaixamento é observável: fadigaDe devolve o fator à parte da promoção")
    void rebaixamentoEhObservavel() {
        // A JUSTIFICATIVA DA INVARIANTE QUEBRADA, agora verificada.
        //
        // Quebrar o "os sinais só promovem" do Rankeador foi justificado com três
        // partes: rebaixar é permitido, apagar não, E TODO REBAIXAMENTO VAI PARA O
        // RASTRO. A terceira ficou sem implementação por uma rodada — o método
        // existia, era público com javadoc dizendo "para o rastro", e o rastro
        // nunca o chamou.
        //
        // Este teste trava o contrato de que o fator é obtível separado, porque é
        // disso que a explicação precisa: quem olha uma linha baixa tem de
        // distinguir "não foi promovida" de "foi rebaixada" — ausência de sinal
        // contra sinal contrário, que pedem ações opostas.
        var candidato = candidato("antecipar limite", "credito", "ANTECIPACAO", 1.0);

        var semFadiga = perfil(Map.of(), Map.of(), Map.of(), Map.of());
        var comFadiga = perfil(Map.of(), Map.of(), Map.of(), Map.of("antecipar limite", 1.0));

        assertThat(rankeador.fadigaDe(candidato, semFadiga, PESOS))
                .as("sem fadiga, o fator é neutro e a linha não foi rebaixada")
                .isEqualTo(1.0);

        assertThat(rankeador.fadigaDe(candidato, comFadiga, PESOS))
                .as("com fadiga no teto, o fator é o piso configurado")
                .isCloseTo(PESOS.fadigaMinima(), within(0.0001));
    }

    @Test
    @DisplayName("a fadiga casa termo com acento: a chave é normalizada na leitura")
    void fadigaCasaTermoComAcento() {
        // REGRESSÃO. A agregação guarda a chave normalizada — "antecipacao de
        // limite" — e o Rankeador consulta com o termo CRU do índice,
        // "antecipação de limite". A chave não casava para nenhum termo com
        // acento ou maiúscula, ou seja, para a maior parte do catálogo.
        //
        // A fadiga era calculada, gravada no perfil e lida a cada tecla sem
        // rebaixar nada. E o pior: a verificação de ponta a ponta passava verde,
        // porque a linha subia por outro motivo (afinidade de produto) e o
        // script media só "subiu ou não subiu".
        var comAcento = candidato("antecipação de limite", "credito", "ANTECIPACAO", 1.0);

        var perfil = perfil(Map.of(), Map.of(), Map.of(),
                Map.of("antecipacao de limite", 0.4));

        double comFadiga = soScore(rankeador.ranquear(List.of(comAcento), perfil, PESOS));
        double semFadiga = soScore(rankeador.ranquear(
                List.of(comAcento), perfil(Map.of(), Map.of(), Map.of(), Map.of()), PESOS));

        assertThat(comFadiga)
                .as("o termo acentuado do índice casa com a chave normalizada do perfil")
                .isLessThan(semFadiga);
    }

    @Test
    @DisplayName("a fadiga rebaixa mas nunca zera: 0,6 é o piso")
    void fadigaNuncaZera() {
        var candidato = candidato("antecipar limite", "credito", "ANTECIPACAO", 1.0);
        var perfil = perfil(Map.of(), Map.of(), Map.of(), Map.of("antecipar limite", 1.0));

        double comFadigaTotal = soScore(rankeador.ranquear(List.of(candidato), perfil, PESOS));

        // 1,6 (jornada líder, única presente) × 1,25 (produto) × 0,6 (fadiga no
        // piso) = 1,2.
        //
        // O número vale mais que a asserção: mesmo com fadiga MÁXIMA a linha
        // continua acima de 1,0, porque as promoções estruturais pesam mais que
        // a penalidade. É o "reordena sem enterrar" declarado em PesosDeFadiga,
        // em número — e é a razão de o piso ser 0,6 e não 0,1.
        assertThat(comFadigaTotal).isCloseTo(1.2, within(0.0001));
        assertThat(comFadigaTotal).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("linha ignorada de uma jornada clicada ainda perde para a sem fadiga")
    void fadigaSobreviveAAfinidade() {
        // Caso que junta B1 e B2: a pessoa gosta de crédito E ignora esta linha
        // específica de crédito. A afinidade promove as duas; a fadiga separa.
        var ignorada = candidato("antecipar limite", "credito", "ANTECIPACAO", 1.0);
        var outra = candidato("emprestimo pessoal", "credito", "EMPRESTIMO", 1.0);

        var perfil = perfil(
                Map.of("credito", 1.0),
                Map.of(),
                Map.of(),
                Map.of("antecipar limite", 0.4));

        var porTermo = porTermo(rankeador.ranquear(List.of(ignorada, outra), perfil, PESOS));

        assertThat(porTermo.get("emprestimo pessoal"))
                .isGreaterThan(porTermo.get("antecipar limite"));
    }

    // -----------------------------------------------------------------------
    // A ordem das operações — o caso que falha em silêncio
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("o teto é aplicado ANTES da fadiga, senão a fadiga para de funcionar")
    void tetoAntesDaFadiga() {
        // Uma linha com TODAS as promoções no máximo estoura o teto: o produto
        // dos fatores chegaria a 4,14 e e cortado em 3,6.
        //
        // Se a fadiga fosse aplicada antes do teto, 4,14 × 0,6 = 2,48 continuaria
        // abaixo de 3,6 e o teto cortaria menos — e o resultado seria
        // 2,48 em vez de 3,6 x 0,6 = 2,16. A fadiga teria sido DILUIDA pelas
        // promoções que o teto ia descartar de qualquer forma.
        //
        // Pior: para uma linha com promoções ainda maiores, a fadiga
        // desapareceria por completo dentro do teto. Ela pararia de funcionar
        // exatamente para quem tem mais afinidade — quem mais precisa dela.
        var candidato = candidato("emprestimo pessoal", "credito", "EMPRESTIMO", 1.0);

        var perfil = perfil(
                Map.of("credito", 1.0),
                Map.of("EMPRESTIMO", 1.0),
                Map.of("credito", 4.3),
                Map.of("emprestimo pessoal", 1.0));

        // Jornada líder = "credito", única jornada presente.
        double score = soScore(rankeador.ranquear(List.of(candidato), perfil, PESOS));

        assertThat(score)
                .as("3,6 (teto) x 0,6 (fadiga no piso) = 2,16")
                .isCloseTo(2.16, within(0.0001));
    }

    @Test
    @DisplayName("sem fadiga, o teto declarado corta em 3,6")
    void tetoDeclarado() {
        var candidato = candidato("emprestimo pessoal", "credito", "EMPRESTIMO", 1.0);

        var perfil = perfil(
                Map.of("credito", 1.0),
                Map.of("EMPRESTIMO", 1.0),
                Map.of("credito", 4.3),
                Map.of());

        // 1,6 x 1,25 x 1,5 x 1,2 x 1,15 = 4,14, cortado em 3,6. O teto e
        // declarado em PesosDeRanking.fatorMaximo e não emergente do produto dos
        // pesos — era emergente antes, e acrescentar um fator mudava o máximo do
        // sistema em silêncio.
        assertThat(soScore(rankeador.ranquear(List.of(candidato), perfil, PESOS)))
                .isCloseTo(3.6, within(0.0001));
    }

    // -----------------------------------------------------------------------

    private static Candidato candidato(String termo, String jornada, String produto, double score) {
        return new Candidato(
                "OFR-" + produto, termo, "", "/app/" + produto, jornada, produto,
                Restricoes.NENHUMA, List.of(), 0, score, Origem.CATALOGO);
    }

    private static Perfil perfil(
            Map<String, Double> afinidade,
            Map<String, Double> porProduto,
            Map<String, Double> tendencia,
            Map<String, Double> fadiga) {

        return new Perfil(List.of(), Set.of(), Set.of(), Set.of(),
                SituacaoDaConta.ATIVA, afinidade, porProduto, tendencia, fadiga, true);
    }

    private static Map<String, Double> porTermo(Ranqueamento r) {
        return r.candidatos().stream().collect(
                java.util.stream.Collectors.toMap(Candidato::termo, Candidato::relevancia));
    }

    private static double soScore(Ranqueamento r) {
        return r.candidatos().getFirst().relevancia();
    }
}
