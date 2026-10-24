package dev.sugestao.busca.dominio;

import java.util.ArrayList;
import java.util.List;

/**
 * O que o perfil tira da lista depois que o índice já respondeu.
 *
 * <p>Por que aqui e não como {@code fq}: segmento e faixa de relacionamento vêm
 * do token e já são {@code fq}. Produto contratado, elegibilidade e situação da
 * conta só são conhecidos quando o perfil chega — transformá-los em {@code fq}
 * custaria enfileirar as duas chamadas, e o orçamento de 30 ms passaria de
 * folgado a apertado. É trabalho de CPU sobre vinte candidatos: 2 ms no desenho,
 * e não é onde o tempo mora.
 *
 * <p>O piso de k-anonimato é a exceção e continua sendo {@code fq} no índice.
 * O que está aqui é rede de segurança, não o controle: se um termo abaixo do
 * piso chegou até este ponto, a coleção foi publicada com o invariante violado
 * e isso é alarme, não ajuste de relevância.
 *
 * <p>Todas as regras daqui falham fechadas. Com o perfil ausente a situação da
 * conta é {@code DESCONHECIDA} e nenhuma elegibilidade está concedida, então
 * toda oferta condicionada some e sobra a lista neutra — que é informação útil
 * e inofensiva. O erro oposto, oferecer crédito novo a quem está inadimplente
 * porque o perfil não carregou, não é recuperável por uma tecla a mais.
 */
public final class Peneira {

    private final int pisoKAnonimato;

    public Peneira(int pisoKAnonimato) {
        this.pisoKAnonimato = pisoKAnonimato;
    }

    public Peneirada aplicar(List<Candidato> candidatos, Perfil perfil) {
        var sobreviventes = new ArrayList<Candidato>(candidatos.size());
        var descartes = new ArrayList<Descarte>();

        for (Candidato candidato : candidatos) {
            MotivoDeDescarte motivo = julgar(candidato, perfil);
            if (motivo == null) {
                sobreviventes.add(candidato);
            } else {
                descartes.add(Descarte.de(candidato, motivo));
            }
        }
        return new Peneirada(sobreviventes, descartes);
    }

    /** {@code null} quando o candidato passa. */
    private MotivoDeDescarte julgar(Candidato candidato, Perfil perfil) {
        if (candidato.origem().veioDoIndice()
                && candidato.clientesDistintos() < pisoKAnonimato) {
            return MotivoDeDescarte.ABAIXO_DO_PISO;
        }
        if (perfil.jaContratou(candidato.produtoAlvo())) {
            return MotivoDeDescarte.PRODUTO_JA_CONTRATADO;
        }

        Restricoes restricoes = candidato.restricoes();

        if (restricoes.exigeElegibilidade()
                && !perfil.elegivelPara(candidato.chaveDeElegibilidade())) {
            return MotivoDeDescarte.SEM_ELEGIBILIDADE;
        }

        // Crédito novo para quem está em atraso. O caminho certo para esse
        // cliente não é esconder a busca, é a jornada de renegociação — que
        // entra por gatilho e ocupa a linha de evento.
        if (restricoes.vetaInadimplencia() && perfil.situacaoDaConta().inadimplente()) {
            return MotivoDeDescarte.CONTA_INADIMPLENTE;
        }

        // Operação que só faz sentido com a conta operante. Conta desconhecida
        // cai aqui junto com a bloqueada: falha fechada.
        if (restricoes.exigeContaAtiva() && !perfil.situacaoDaConta().operante()) {
            return MotivoDeDescarte.CONTA_NAO_OPERANTE;
        }

        return null;
    }
}
