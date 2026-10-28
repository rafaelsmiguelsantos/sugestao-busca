package dev.sugestao.busca.dominio;

import java.util.List;
import java.util.Optional;

/**
 * A última etapa, e a única que só age quando todas as outras não entregaram
 * nada.
 *
 * <p><strong>O defeito que ela corrige.</strong> Um cliente novo digita
 * {@code veiculos}; a busca encontra "financiamento de veículo"; a peneira o
 * remove por {@code SEM_ELEGIBILIDADE}, com razão — oferecer o que a pessoa não
 * pode contratar é problema de conduta, não de relevância. E a resposta é lista
 * vazia. A remoção está certa; <em>a lista vazia é o defeito.</em>
 *
 * <p>O que torna isso corrigível sem dado novo é que o sistema <strong>já
 * calcula a informação necessária e a joga fora</strong>: o {@link Descarte}
 * carrega a jornada e o motivo, e em produção o rastro inteiro é descartado,
 * porque a explicação fica desligada. Esta classe lê o que já estava ali.
 *
 * <h2>As quatro regras, e o que cada uma impede</h2>
 *
 * <p><strong>1. Só age com a lista vazia.</strong> Não completa lista curta.
 * Duas linhas boas mais uma de ajuda é uma lista pior que duas linhas boas — a
 * terceira rouba atenção de quem já tinha o que queria. O recurso é resgate, não
 * enchimento.
 *
 * <p><strong>2. Só age sobre motivo condicional do cliente.</strong> Lista vazia
 * porque nada casou com o prefixo continua vazia, e tem de continuar: não há
 * jornada a oferecer, e inventar uma devolveria algo que ninguém procurou. Ver
 * {@link MotivoDeDescarte#condicionalDoCliente()}, e em especial por que
 * {@link MotivoDeDescarte#ABAIXO_DO_PISO} está de fora.
 *
 * <p><strong>3. A linha é função da jornada, nunca do motivo.</strong> Esta é a
 * regra de privacidade do recurso, e está garantida pela assinatura: o que chega
 * a {@link Substitutos#para(String)} é a jornada, e nada mais. Uma substituta
 * escolhida pelo motivo informaria a decisão da peneira a quem recebe a
 * resposta — "você não tem elegibilidade" dito por omissão —, e a peneira é
 * exatamente o lugar onde o sistema sabe coisas sobre a pessoa que ela não pediu
 * para contar a ninguém. Com a linha dependendo só da jornada, as quatro razões
 * de remoção produzem resposta idêntica e nenhuma delas é distinguível de fora.
 *
 * <p><strong>4. Uma linha, e sem restrição.</strong> O teto é um por construção
 * — devolve {@link Optional} — e a ausência de restrição é verificada no
 * construtor de {@link Substituto}, não aqui. Por isso esta classe não precisa
 * chamar a peneira de novo: a substituta não tem como ser removida por ela.
 *
 * <h2>Por que depois da mistura, e não antes do ranking</h2>
 *
 * <p>Porque a condição é "a lista final está vazia", e antes da mistura isso
 * ainda não é um fato. Entrar antes significaria disputar cota e ser
 * deduplicada — e a substituta não deve competir com nada: ela existe só na
 * ausência de competidores. Entrar depois também a mantém fora do ranking, que
 * é o certo, porque não há o que ranquear numa lista de um elemento que não
 * concorreu.
 *
 * <p><strong>E essa posição é estrutural, não estética.</strong> A substituta
 * carrega {@link Procedencia#CATALOGO}, que é a resposta certa para a pergunta
 * de privacidade — não há comportamento de ninguém aqui — e a resposta
 * <em>errada</em> para {@link Procedencia#temScoreDoIndice()}: ela nunca foi
 * pontuada pelo índice, e a relevância dela nasce zero justamente para dizer
 * isso. Se alguém mover esta etapa para antes do {@link Rankeador}, a
 * normalização de escala vai tratar aquele zero como score de Solr.
 *
 * <p>É a mesma conflação que {@link Procedencia} existe para desfazer — duas
 * perguntas diferentes com a mesma resposta enquanto ninguém olha. Um valor novo
 * de procedência resolveria o tipo e reintroduziria a conflação pelo outro lado,
 * misturando <em>de onde o dado veio</em> com <em>se ele foi pontuado</em>. Está
 * declarado nos limites conhecidos em vez de contornado, e hoje não tem efeito:
 * a substituta não passa por aqui, e, mesmo que passasse, ela nasce sem gatilho
 * nenhum e por isso também não seria promovida a evento.
 */
public final class Recuperador {

    private final Substitutos substitutos;

    public Recuperador(Substitutos substitutos) {
        this.substitutos = substitutos == null ? Substitutos.NENHUM : substitutos;
    }

    /**
     * A linha substituta, quando é o caso.
     *
     * @param escolhidos a lista final, já misturada e cortada
     * @param descartes  tudo que saiu, na ordem em que saiu
     */
    public Optional<Candidato> recuperar(List<Candidato> escolhidos, List<Descarte> descartes) {
        if (substitutos.vazio() || escolhidos == null || !escolhidos.isEmpty()) {
            return Optional.empty();
        }

        // A ORDEM DOS DESCARTES É O CRITÉRIO, e isso é o que dispensa um campo de
        // relevância no Descarte.
        //
        // A peneira percorre os candidatos na ordem em que os recebeu, e essa
        // ordem é a do índice: melhor score do Solr primeiro, com o histórico
        // depois. Então o primeiro descarte condicional é a coisa mais forte que
        // casou com o prefixo e teve de sair — que é a melhor leitura disponível
        // de "o que esta pessoa estava procurando".
        //
        // Guardar a relevância no Descarte para ordenar aqui seria pior, não
        // melhor: ela é score cru do Solr para o que veio do índice e um valor de
        // 0,5 a 1,0 para o que veio do histórico, e comparar os dois é comparar
        // escalas diferentes. A ordem já carrega a resposta sem precisar do
        // número.
        for (Descarte descarte : descartes == null ? List.<Descarte>of() : descartes) {
            if (!descarte.motivo().condicionalDoCliente()) {
                continue;
            }
            // Jornada sem substituta declarada não interrompe a busca pela
            // próxima. A alternativa — desistir no primeiro descarte condicional
            // — trocaria uma linha útil por nenhuma sempre que a tabela tivesse
            // um buraco, e buraco em tabela de conteúdo é o estado normal dela
            // enquanto o catálogo cresce.
            //
            // O custo é declarado: com duas jornadas removidas, a linha pode vir
            // da segunda intenção em vez da primeira. A comparação entre
            // sugestao.descartes e sugestao.substitutas é o que mostra o tamanho
            // desse buraco.
            var substituta = substitutos.para(descarte.jornada());
            if (substituta.isPresent()) {
                return substituta;
            }
        }
        return Optional.empty();
    }
}
