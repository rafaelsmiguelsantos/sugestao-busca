package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidatoTest {

    @Test
    @DisplayName("é oferta só com identificador E produto: um sem o outro é cadastro pela metade")
    void ehOfertaExigeOsDois() {
        assertThat(Candidatos.oferta("empréstimo pessoal", "OFR-X", "EMPRESTIMO").ehOferta()).isTrue();

        // Identificador sem produto: a Peneira não tem o que julgar pela chave
        // de elegibilidade e o Rankeador não tem o que promover dentro da
        // jornada. Anunciar isso como oferta prometeria ao consumidor uma coisa
        // que o resto do fluxo não trata como tal.
        assertThat(Candidatos.oferta("artigo de ajuda", "OFR-Y", "").ehOferta()).isFalse();

        // Produto sem identificador: é o catálogo antigo, de antes do cadastro.
        // Aparece na busca, mas não há como uma jornada acioná-lo por id.
        assertThat(Candidatos.produto("CDB", "CDB", false).ehOferta()).isFalse();

        // Termo do log não é oferta de jeito nenhum.
        assertThat(Candidatos.doLog("contratar emprestimo", 500, null).ehOferta()).isFalse();
    }

    @Test
    @DisplayName("trocar a origem preserva a procedência")
    void comOrigemPreservaProcedencia() {
        var doLog = Candidatos.doLog("contratar emprestimo", 500, null);
        assertThat(doLog.com(Origem.EVENTO).procedencia()).isEqualTo(Procedencia.LOG);

        var doCatalogo = Candidatos.oferta("empréstimo pessoal", "OFR-X", "EMPRESTIMO");
        assertThat(doCatalogo.com(Origem.EVENTO).procedencia()).isEqualTo(Procedencia.CATALOGO);
    }

    @Test
    @DisplayName("trocar a relevância preserva a procedência")
    void comRelevanciaPreservaProcedencia() {
        var doLog = Candidatos.doLog("contratar emprestimo", 500, null);
        assertThat(doLog.com(0.5).procedencia()).isEqualTo(Procedencia.LOG);
    }

    @Test
    @DisplayName("sem procedência declarada, ela é deduzida da origem")
    void procedenciaDeduzidaDaOrigem() {
        assertThat(Candidatos.de("x", Origem.HISTORICO, 1.0).procedencia()).isEqualTo(Procedencia.CLIENTE);
        assertThat(Candidatos.de("x", Origem.CATALOGO, 1.0).procedencia()).isEqualTo(Procedencia.CATALOGO);
        assertThat(Candidatos.de("x", Origem.POPULAR, 1.0).procedencia()).isEqualTo(Procedencia.LOG);

        // EVENTO cai em LOG, a opção restritiva. Evento não se cria: ele é
        // resultado de com(Origem) sobre um candidato que já existia, e aquele
        // caminho preserva a procedência verdadeira. Se alguém construir um
        // evento direto, é melhor que ele responda por um piso que não
        // precisava do que escape de um que precisava.
        assertThat(Candidatos.de("x", Origem.EVENTO, 1.0).procedencia()).isEqualTo(Procedencia.LOG);
    }

    @Test
    @DisplayName("completarCom herda a identidade da oferta do candidato descartado")
    void completarComHerdaOfertaId() {
        // O caso que importa: o cliente já buscou "emprestimo pessoal" antes, e
        // a linha do histórico vence a deduplicação contra o item de catálogo.
        // Sem herdar o identificador, a jornada consumidora perderia justamente
        // a oferta que ela tem mais motivo para acionar — a que a pessoa já
        // procurou — e receberia uma linha de texto sem identidade.
        var doHistorico = new Candidato("empréstimo pessoal", "", "", "busca", "",
                Restricoes.NENHUMA, List.of(), 0, 1.0, Origem.HISTORICO);
        var doCatalogo = Candidatos.oferta("empréstimo pessoal", "OFR-CREDITO-EMPRESTIMO", "EMPRESTIMO");

        var mesclado = doHistorico.completarCom(doCatalogo);

        assertThat(mesclado.ofertaId()).isEqualTo("OFR-CREDITO-EMPRESTIMO");
        assertThat(mesclado.produtoAlvo()).isEqualTo("EMPRESTIMO");
        assertThat(mesclado.destino()).isEqualTo("/app/empréstimo pessoal");

        // Regressão, e a parte sutil. A linha do histórico chega com
        // jornada="busca", que não é vazio — então a herança por "vazio?"
        // mantinha o sentinela. O candidato saía identificado e acionável mas
        // sem jornada para o ranking: nem impulso de jornada líder, nem
        // afinidade do cliente, exatamente na linha de sinal mais forte do
        // conjunto. Jornada.ausente() trata nulo, vazio e sentinela como a
        // mesma coisa, que é o que eles são.
        assertThat(mesclado.jornada()).isEqualTo("credito");

        // A origem continua sendo a do vencedor.
        assertThat(mesclado.origem()).isEqualTo(Origem.HISTORICO);
    }

    @Test
    @DisplayName("jornada de verdade no vencedor não é sobrescrita pela do descartado")
    void completarComNaoSobrescreveJornadaDeVerdade() {
        var doCatalogo = Candidatos.oferta("empréstimo pessoal", "OFR-A", "EMPRESTIMO");
        var outro = new Candidato("OFR-B", "empréstimo pessoal", "", "/x", "renegociacao",
                "RENEGOCIACAO", Restricoes.NENHUMA, List.of(), 0, 1.0,
                Origem.CATALOGO, Procedencia.CATALOGO);

        // 'credito' é jornada de verdade: herdar seria trocar a intenção da
        // linha pela do candidato que perdeu a deduplicação.
        assertThat(doCatalogo.completarCom(outro).jornada()).isEqualTo("credito");
        assertThat(doCatalogo.completarCom(outro).ofertaId()).isEqualTo("OFR-A");
    }

    @Test
    @DisplayName("completarCom NÃO herda o contador de clientes distintos")
    void completarComNaoHerdaOContadorDoPiso() {
        // Regressão de privacidade. Este campo era Math.max das duas pontas, o
        // que parecia conservador e era o contrário: um termo do log com nove
        // clientes distintos, colidindo com um item de catálogo de contador
        // alto, saía da mistura com o contador do catálogo e passava pelo piso
        // de k-anonimato sem nunca ter tido trinta pessoas por trás.
        var doLog = Candidatos.doLog("adiantamento para maria silva", 9, null);
        var doCatalogo = Candidatos.produto("adiantamento para maria silva", "ANTECIPACAO", false);

        assertThat(doCatalogo.clientesDistintos()).isEqualTo(1000);

        var mesclado = doLog.completarCom(doCatalogo);

        assertThat(mesclado.clientesDistintos()).isEqualTo(9);
        assertThat(mesclado.procedencia()).isEqualTo(Procedencia.LOG);

        // E o piso de verdade continua barrando.
        assertThat(new Peneira(30).aplicar(List.of(mesclado), Perfil.NEUTRO).sobreviventes()).isEmpty();
    }

    @Test
    @DisplayName("identificador de oferta nulo ou com espaços vira vazio")
    void ofertaIdNormalizado() {
        var sem = new Candidato(null, "x", "", "", "busca", "",
                Restricoes.NENHUMA, List.of(), 0, 1.0, Origem.POPULAR, Procedencia.LOG);
        assertThat(sem.ofertaId()).isEmpty();
        assertThat(sem.ehOferta()).isFalse();

        var comEspaco = new Candidato("  OFR-X  ", "x", "", "", "credito", "PRODUTO",
                Restricoes.NENHUMA, List.of(), 0, 1.0, Origem.CATALOGO, Procedencia.CATALOGO);
        assertThat(comEspaco.ofertaId()).isEqualTo("OFR-X");
        assertThat(comEspaco.ehOferta()).isTrue();
    }
}
