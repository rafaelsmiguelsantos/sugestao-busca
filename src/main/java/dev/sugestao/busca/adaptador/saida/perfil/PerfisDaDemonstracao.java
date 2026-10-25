package dev.sugestao.busca.adaptador.saida.perfil;

import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O estado inicial do registro de perfil: perfis falsos, inventados para mostrar
 * o mecanismo. Nenhum cliente real por trás de nenhum destes números.
 *
 * <p>Eles existem para a demonstração do desenho — digitar as mesmas letras com
 * perfis diferentes e ver a lista mudar — e por isso são poucos e bem separados:
 * cada um exercita uma parte distinta da peneira e da cota.
 *
 * <p>A partir daqui eles evoluem. O caminho de aprendizado atualiza o histórico
 * de quem buscar, preservando o que a busca não sabe: gatilho, elegibilidade,
 * produto contratado e situação da conta vêm de outros produtores.
 */
public final class PerfisDaDemonstracao {

    private PerfisDaDemonstracao() {
    }

    public static Map<String, Perfil> iniciais() {
        return Map.of(

                // Chega sem história. Linha de base: é a lista popular do
                // segmento, igual à que um cliente novo veria.
                "pf-novo", new Perfil(
                        List.of(), Set.of(), Set.of(), Set.of(),
                        SituacaoDaConta.ATIVA, true),

                // Pix recusado por saldo há duas horas. Mostra a linha de
                // evento abrindo a lista com rótulo próprio.
                //
                // CONSIGNADO está aqui porque sem ele o produto
                // OFR-CREDITO-CONSIGNADO era inalcançável para os oito perfis:
                // o único que tinha a elegibilidade era o pf-negativado, e
                // crédito consignado veta inadimplência. A oferta existia no
                // índice, com identificador, e ninguém nunca a veria — buraco
                // no dado de demonstração que só apareceu quando alguém digitou
                // "consignado" e leu a lista de descartes.
                "pf-pix-recusado", new Perfil(
                        List.of("pagar boleto", "limite do pix", "pix agendado"),
                        Set.of("pix_recusado_72h", "saldo_insuficiente_24h"),
                        Set.of(),
                        Set.of("ANTECIPACAO", "EMPRESTIMO", "LIMITE_CARTAO", "CONSIGNADO"),
                        SituacaoDaConta.ATIVA, true),

                // Já tem CDB. Digita "cdb" e não recebe de volta o que já
                // comprou — corte de produto contratado, que vem do perfil.
                "pf-investidor", new Perfil(
                        List.of("tesouro direto", "rendimento da poupanca", "cdb"),
                        Set.of(), Set.of("CDB"), Set.of("TESOURO", "LCI"),
                        SituacaoDaConta.ATIVA, true),

                // Elegibilidade de nada. Falha fechada funcionando: as ofertas
                // que exigem elegibilidade somem, e só elas.
                "pf-sem-elegibilidade", new Perfil(
                        List.of("aumentar limite do cartao"),
                        Set.of(), Set.of(), Set.of(),
                        SituacaoDaConta.ATIVA, true),

                // Conta negativa. Tem elegibilidade de crédito novo, mas a
                // situação da conta veta — e o gatilho abre a renegociação na
                // primeira linha. É a diferença entre esconder a busca de quem
                // está em atraso e levar essa pessoa ao caminho que resolve.
                "pf-negativado", new Perfil(
                        List.of("pagar fatura", "parcelar fatura do cartao"),
                        Set.of("conta_negativa_30d", "fatura_alta_7d"),
                        Set.of(),
                        Set.of("EMPRESTIMO", "CONSIGNADO", "RENEGOCIACAO", "PARCELAMENTO_FATURA"),
                        SituacaoDaConta.INADIMPLENTE, true),

                // Conta bloqueada por suspeita de fraude. Operação que exige
                // conta operante some; desbloquear e contestar continuam.
                "pf-bloqueado", new Perfil(
                        List.of("bloquear conta por roubo"),
                        Set.of("acesso_suspeito_24h"), Set.of(), Set.of(),
                        SituacaoDaConta.BLOQUEADA, true),

                // Lojista com recebível disponível. O mesmo mecanismo no outro
                // segmento.
                "pj-lojista", new Perfil(
                        List.of("emitir boleto", "taxa da maquininha"),
                        Set.of("recebivel_disponivel_24h"), Set.of(),
                        Set.of("ANTECIPACAO_PJ", "CAPITAL_GIRO"),
                        SituacaoDaConta.ATIVA, true),

                // Uniclass. Mesma letra, outra faixa: o fq de relacionamento
                // muda a lista no índice, antes de o perfil ser lido.
                "pf-uniclass", new Perfil(
                        List.of("cdb", "tesouro direto"),
                        Set.of(), Set.of(),
                        Set.of("TESOURO", "LCI", "FUNDO_EXCLUSIVO", "CONSULTORIA"),
                        SituacaoDaConta.ATIVA, true));
    }
}
