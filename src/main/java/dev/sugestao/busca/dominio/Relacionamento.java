package dev.sugestao.busca.dominio;

/**
 * A faixa de relacionamento do cliente.
 *
 * <p>Fica junto do segmento, no token, e pelo mesmo motivo: é atributo estável,
 * conhecido antes de qualquer leitura de perfil. Isso permite que ele entre como
 * {@code fq} na consulta ao índice em vez de virar corte depois — e um {@code fq}
 * cacheável por (prefixo, segmento, relacionamento) é exatamente a parte desta
 * camada que vale cachear.
 *
 * <p>Se um dia a faixa passar a ser calculada em vez de declarada, ela migra
 * para o {@link Perfil} e vira regra da {@link Peneira}. A diferença não é de
 * importância, é de quando o valor fica conhecido.
 */
public enum Relacionamento {

    VAREJO,
    UNICLASS,
    PERSONNALITE,
    PRIVATE;

    public static Relacionamento de(String texto) {
        if (texto == null || texto.isBlank()) {
            return VAREJO;
        }
        try {
            return valueOf(texto.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Faixa desconhecida cai no varejo: a lista fica mais pobre, nunca
            // mais rica. Errar para cima ofereceria produto de faixa alta a
            // quem não tem direito a ele.
            return VAREJO;
        }
    }
}
