package dev.sugestao.busca.aplicacao;

import dev.sugestao.busca.dominio.Descarte;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Segmento;

import java.util.List;

/**
 * O que aconteceu dentro de uma requisição, etapa por etapa.
 *
 * <p>É um objeto de aplicação, não de domínio: nenhuma regra depende dele e
 * ninguém decide nada a partir dele. Ele existe para que as afirmações sobre
 * esta camada sejam verificáveis em vez de ditas — qual prefixo chegou ao
 * índice, quantos candidatos voltaram, o que o perfil tirou e por quê, quanto
 * cada dependência custou, e com que score cada linha ganhou a posição.
 *
 * <p>Quem resolve se isso vira log, resposta HTTP ou nada é o adaptador. O caso
 * de uso só monta.
 */
public record RastroDaSugestao(
        String prefixoDigitado,
        String prefixoNormalizado,
        String clienteId,
        Segmento segmento,
        EstadoDoPerfil perfil,
        long msAteIndice,
        long msAtePerfil,
        long msTotal,
        int candidatosDoIndice,
        int candidatosDoHistorico,
        int promovidosAEvento,

        /** A jornada que o prefixo revelou, ou vazio quando nenhuma dominou. */
        String jornadaLider,

        /**
         * Se o prefixo chegou a ser consultado, ou foi recusado por tamanho
         * antes de qualquer rede.
         *
         * <p>Existe para a medição poder separar <em>busca que não encontrou</em>
         * de <em>texto que nem era busca</em>. As duas respondem lista vazia e
         * são coisas opostas: a primeira é falha de cobertura e merece alarme; a
         * segunda é alguém no meio da primeira letra. Misturadas num só
         * contador, a taxa de zero-resultado fica dominada pelo segundo caso e
         * para de significar qualquer coisa — e era justamente a métrica que ia
         * dizer se a tolerância a erro de digitação funcionou.
         */
        boolean prefixoAceito,

        /**
         * Se a consulta tolerante a erro de digitação foi acionada, e o que ela
         * trouxe. Ver {@link dev.sugestao.busca.aplicacao.ToleranciaAplicada}.
         */
        ToleranciaAplicada tolerancia,

        List<Descarte> descartes,
        List<Linha> linhas) {

    /** Como o perfil chegou — ou por que não chegou. */
    public enum EstadoDoPerfil {
        ENCONTRADO,
        /** O cliente não tem perfil montado. Não é incidente: é cliente novo. */
        SEM_PERFIL,
        /** A leitura estourou o prazo. Falha aberta: lista popular do segmento. */
        PRAZO_VENCIDO,
        /** A leitura falhou. Mesma degradação, outro alarme. */
        FALHOU
    }

    /**
     * O {@code ofertaId} entra aqui para a medição poder distinguir
     * <em>encontrou algo</em> de <em>encontrou algo acionável</em>.
     *
     * <p>São perguntas diferentes e a segunda é a que o negócio faz. Uma busca
     * que devolve três artigos de ajuda e nenhuma oferta não é zero-resultado,
     * mas também não é sucesso — e foi exatamente o que apareceu em
     * {@code q=financiamento}: uma linha de ação, nenhuma oferta, nenhum alarme
     * possível com o que estava medido.
     */
    public record Linha(int posicao, String ofertaId, String termo, Origem origem,
                        double relevancia, String destino) {

        public Linha {
            ofertaId = ofertaId == null ? "" : ofertaId;
        }

        public boolean ehOferta() {
            return !ofertaId.isBlank();
        }
    }

    public RastroDaSugestao {
        descartes = List.copyOf(descartes);
        linhas = List.copyOf(linhas);
        tolerancia = tolerancia == null ? ToleranciaAplicada.NAO_PRECISOU : tolerancia;
    }

    /** Uma linha de log legível, para quando o rastro vai para o console. */
    public String resumo() {
        return "q='%s' -> '%s' | cliente=%s/%s perfil=%s | indice=%d hist=%d evento=%d | jornada=%s | tolerancia=%s | descartes=%d | linhas=%d%s | %dms (indice %dms, perfil %dms)"
                .formatted(prefixoDigitado, prefixoNormalizado, clienteId, segmento, perfil,
                        candidatosDoIndice, candidatosDoHistorico, promovidosAEvento,
                        jornadaLider.isBlank() ? "-" : jornadaLider,
                        tolerancia,
                        descartes.size(), linhas.size(), temOferta() ? " (com oferta)" : "",
                        msTotal, msAteIndice, msAtePerfil);
    }

    /**
     * A busca foi atendida e não encontrou nada.
     *
     * <p>Diferente de {@code linhas.isEmpty()}: prefixo recusado por tamanho
     * também não tem linha nenhuma, e não é zero-resultado. Ver
     * {@link #prefixoAceito}.
     */
    public boolean zeroResultado() {
        return prefixoAceito && linhas.isEmpty();
    }

    /** Alguma linha entregue é oferta acionável. */
    public boolean temOferta() {
        return linhas.stream().anyMatch(Linha::ehOferta);
    }
}
