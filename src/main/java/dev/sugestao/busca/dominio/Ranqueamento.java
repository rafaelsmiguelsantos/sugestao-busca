package dev.sugestao.busca.dominio;

import java.util.List;

/**
 * Os candidatos com a relevância já ajustada, e qual jornada liderou.
 *
 * <p>A jornada sai junto porque sem ela a lista final é inexplicável: duas
 * ordens diferentes para o mesmo prefixo, e nada no resultado dizendo que uma
 * foi inclinada para crédito e a outra não.
 */
public record Ranqueamento(List<Candidato> candidatos, String jornadaLider) {

    public Ranqueamento {
        candidatos = List.copyOf(candidatos);
        jornadaLider = jornadaLider == null ? "" : jornadaLider;
    }

    public boolean temLider() {
        return !jornadaLider.isBlank();
    }
}
