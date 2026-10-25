package dev.sugestao.busca.aplicacao.porta.entrada;

/**
 * Quem está pedindo, do ponto de vista de permissão.
 *
 * <p>É deliberadamente pobre: não tem nome, não tem identificador de usuário,
 * não tem lista de papéis. Tem a única coisa que uma regra desta aplicação
 * precisa saber — <strong>de qual jornada o chamador é dono</strong> — e a
 * exceção para quem é dono de todas.
 *
 * <p>Enriquecê-la seria tentador e errado. Toda informação que entrasse aqui
 * passaria a ser uma informação que a camada de aplicação sabe extrair de um
 * token, e token é assunto da borda. O que existe aqui é o resultado da decisão
 * de autenticação, não o material dela.
 *
 * <p>Separada do {@link ComandoDeCadastroDeOferta} de propósito, e não é
 * cerimônia: o comando é o que o chamador <em>pediu</em>, e isto é o que ele
 * <em>pode</em>. Juntar os dois num objeto só permitiria a quem monta o pedido
 * declarar a própria permissão, que é exatamente o furo que esta classe fecha.
 */
public record Autorizacao(String jornada, boolean podeTodasAsJornadas) {

    public Autorizacao {
        jornada = jornada == null ? "" : jornada.strip().toLowerCase();
    }

    /**
     * O chamador dono de uma jornada só.
     */
    public static Autorizacao daJornada(String jornada) {
        return new Autorizacao(jornada, false);
    }

    /**
     * O chamador que pode cadastrar em qualquer jornada.
     *
     * <p>Existe para operação e migração em lote — alguém precisa poder carregar
     * o catálogo inicial. É privilégio alto e por isso tem nome próprio: numa
     * auditoria, procurar por quem recebe {@code ofertas:todas} é uma busca de
     * uma linha.
     */
    public static Autorizacao irrestrita() {
        return new Autorizacao("", true);
    }

    /**
     * Se este chamador pode cadastrar oferta na jornada pedida.
     *
     * <p>Comparação exata sobre o texto normalizado. Nada de prefixo, nada de
     * curinga: "credito" não autoriza "credito-imobiliario", porque a jornada é
     * um identificador e não um namespace. Se um dia precisar ser hierárquico, a
     * hierarquia tem de ser declarada, não inferida de um casamento de texto.
     */
    public boolean podeCadastrarEm(String jornadaDaOferta) {
        if (podeTodasAsJornadas) {
            return true;
        }
        if (jornada.isEmpty() || jornadaDaOferta == null) {
            return false;
        }
        return jornada.equals(jornadaDaOferta.strip().toLowerCase());
    }
}
