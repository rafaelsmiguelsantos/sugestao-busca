package dev.sugestao.busca.dominio;

import java.text.Normalizer;
import java.util.Locale;

/**
 * A mesma normalização em três lugares: o que o cliente digita, o termo que o
 * índice guarda e a chave de deduplicação. Se as três divergirem, "Antecipação"
 * e "antecipacao" ocupam duas linhas da lista com a mesma intenção.
 *
 * <p>É a cópia fiel do {@code ConverterParaChave} de {@code semear-vocabulario.ps1}:
 * o semeador e o serviço precisam concordar, senão a dedup não encontra nada.
 */
public final class Normalizacao {

    private Normalizacao() {
    }

    public static String aplicar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return semAcento.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
