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

    public record Linha(int posicao, String termo, Origem origem, double relevancia, String destino) {
    }

    public RastroDaSugestao {
        descartes = List.copyOf(descartes);
        linhas = List.copyOf(linhas);
    }

    /** Uma linha de log legível, para quando o rastro vai para o console. */
    public String resumo() {
        return "q='%s' -> '%s' | cliente=%s/%s perfil=%s | indice=%d hist=%d evento=%d | descartes=%d | linhas=%d | %dms (indice %dms, perfil %dms)"
                .formatted(prefixoDigitado, prefixoNormalizado, clienteId, segmento, perfil,
                        candidatosDoIndice, candidatosDoHistorico, promovidosAEvento,
                        descartes.size(), linhas.size(), msTotal, msAteIndice, msAtePerfil);
    }
}
