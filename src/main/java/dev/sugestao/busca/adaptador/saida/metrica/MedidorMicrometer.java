package dev.sugestao.busca.adaptador.saida.metrica;

import dev.sugestao.busca.aplicacao.RastroDaSugestao;
import dev.sugestao.busca.aplicacao.porta.saida.MedidorDeBusca;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * A medição da busca em Micrometer, exposta pelo Actuator.
 *
 * <p><strong>A regra que organiza esta classe inteira: nada que uma pessoa
 * digitou vira rótulo.</strong> O {@link RastroDaSugestao} traz o prefixo e o
 * identificador do cliente, porque ele também alimenta o log de depuração. Aqui
 * os dois são lidos e nenhum é propagado. Dois motivos, e os dois bastam
 * sozinhos:
 *
 * <ul>
 *   <li><em>Privacidade.</em> Prefixo é texto de busca de uma pessoa. O piso de
 *       k-anonimato existe para que termo raro não saia do serviço; um contador
 *       {@code busca{prefixo="adiantamento para maria"}} desfaria isso pela porta
 *       da observabilidade, onde ninguém procura vazamento — e painel de métrica
 *       costuma ser mais acessível que índice de busca.</li>
 *   <li><em>Custo.</em> Rótulo por prefixo é cardinalidade ilimitada: cada tecla
 *       de cada pessoa cria uma série temporal nova. Isso derruba o coletor, e
 *       derruba junto as métricas que iam ser úteis.</li>
 * </ul>
 *
 * <p>O que entra como rótulo é, sem exceção, de <strong>conjunto pequeno e
 * conhecido</strong>: faixa de comprimento do prefixo, desfecho, estado do
 * perfil, motivo de descarte, resultado da tolerância. A única fronteira que
 * merece explicação é a jornada: ela não vem do texto digitado, vem do catálogo,
 * e quem escreve no catálogo é uma jornada autenticada. O limite de cardinalidade
 * aí é "quantos produtos o banco tem", que é um número de duas casas.
 *
 * <p><strong>Medir não pode quebrar a resposta.</strong> Todo método engole
 * exceção e registra em {@code debug}. Uma caixa de busca que devolve 500 porque
 * o coletor de métrica engasgou seria o pior tipo de instrumentação: a que cria o
 * incidente que devia observar.
 */
public class MedidorMicrometer implements MedidorDeBusca {

    private static final Logger log = LoggerFactory.getLogger(MedidorMicrometer.class);

    private static final String BUSCAS = "sugestao.buscas";
    private static final String DESCARTES = "sugestao.descartes";
    private static final String SUBSTITUTAS = "sugestao.substitutas";
    private static final String REBAIXADAS = "sugestao.linhas.rebaixadas";
    private static final String TOLERANCIA = "sugestao.tolerancia";
    private static final String LATENCIA = "sugestao.latencia";
    private static final String CLIQUES = "sugestao.cliques";
    private static final String IMPRESSOES = "sugestao.impressoes";
    private static final String POSICAO_CLICADA = "sugestao.posicao.clicada";
    private static final String LINHAS_EXIBIDAS = "sugestao.linhas.exibidas";

    private final MeterRegistry registro;

    public MedidorMicrometer(MeterRegistry registro) {
        this.registro = registro;
    }

    @Override
    public void busca(RastroDaSugestao rastro) {
        try {
            registrar(rastro);
        } catch (RuntimeException e) {
            log.debug("medicao da busca falhou e foi ignorada", e);
        }
    }

    private void registrar(RastroDaSugestao rastro) {
        registro.counter(BUSCAS,
                        "desfecho", desfecho(rastro),
                        "prefixo", faixaDeComprimento(rastro.prefixoNormalizado()),
                        "perfil", rastro.perfil().name(),
                        "jornada", rastro.jornadaLider().isBlank() ? "nenhuma" : rastro.jornadaLider(),
                        "degradada", Boolean.toString(rastro.perfil() != RastroDaSugestao.EstadoDoPerfil.ENCONTRADO))
                .increment();

        // Prefixo recusado por tamanho não gastou rede e não diz nada sobre
        // relevância: deixá-lo nos percentis faria a latência parecer melhor a
        // cada pessoa que começou a digitar e desistiu.
        if (!rastro.prefixoAceito()) {
            return;
        }

        registro.counter(TOLERANCIA, "resultado", rastro.tolerancia().name()).increment();

        Timer.builder(LATENCIA)
                .tag("etapa", "total")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registro)
                .record(rastro.msTotal(), TimeUnit.MILLISECONDS);

        Timer.builder(LATENCIA).tag("etapa", "indice").register(registro)
                .record(rastro.msAteIndice(), TimeUnit.MILLISECONDS);

        Timer.builder(LATENCIA).tag("etapa", "perfil").register(registro)
                .record(rastro.msAtePerfil(), TimeUnit.MILLISECONDS);

        // O motivo do descarte é a métrica mais acionável do conjunto: ela diz
        // POR QUE a lista veio curta. "SEM_ELEGIBILIDADE subiu 300% em
        // financiamento" é um alarme que aponta para o dado de elegibilidade, e
        // não para a busca — que foi exatamente o caso real.
        for (var descarte : rastro.descartes()) {
            registro.counter(DESCARTES, "motivo", descarte.motivo().name()).increment();
        }

        // O rebaixamento por fadiga também é contado, e isto fecha um buraco.
        //
        // Ele já aparece linha por linha na explicação — mas a explicação fica
        // DESLIGADA em produção, porque a lista de descartes conta quais produtos
        // a pessoa não pode contratar. Sem a métrica, o único sinal do projeto
        // que rebaixa seria invisível justamente onde ele age de verdade.
        //
        // E o número responde a uma pergunta de calibragem: se quase nada é
        // rebaixado, o piso de evidência está alto demais para ter efeito; se
        // quase tudo é, está baixo demais e a lista está sendo achatada.
        long rebaixadas = rastro.linhasRebaixadas();
        if (rebaixadas > 0) {
            registro.counter(REBAIXADAS).increment(rebaixadas);
        }

        // A substituta por jornada, e a jornada é o que faz este contador valer.
        //
        // O total responde "o recurso age?"; a jornada responde "o que fazer a
        // respeito?". Substituta concentrada em `credito` significa que há oferta
        // de crédito casando com o que as pessoas digitam e que quase ninguém
        // pode contratar — leitura de catálogo, não de relevância. Sem o rótulo,
        // o número é só uma curiosidade.
        if (rastro.substituida()) {
            String jornada = rastro.jornadaSubstituta();
            registro.counter(SUBSTITUTAS, "jornada", jornada.isBlank() ? "nenhuma" : jornada)
                    .increment();
        }
    }

    /**
     * O desfecho em cinco valores, e não em "vazio/não vazio".
     *
     * <p>{@code SEM_OFERTA} é a categoria que faltava e que escondeu um defeito
     * real: {@code q=financiamento} devolvia uma linha de ação e nenhuma oferta.
     * Não era zero-resultado, então nenhum contador de lista vazia acusaria; e
     * não era sucesso, porque quem buscou financiamento não recebeu proposta
     * nenhuma. Sem este valor, o caso é invisível.
     *
     * <p>{@code SUBSTITUIDA} é o quinto, e existe para não estragar o quarto.
     * A linha substituta nunca é oferta, então sem este valor toda busca
     * resgatada cairia em {@code SEM_OFERTA} — e {@code SEM_OFERTA} passaria a
     * significar duas coisas opostas: "encontrou conteúdo e nenhuma proposta" e
     * "não encontrou nada e recebeu uma linha de consolo". Um valor que significa
     * duas coisas é um valor que não significa nada.
     *
     * <p>E há uma propriedade que vale declarar: <strong>{@code ZERO_RESULTADO +
     * SUBSTITUIDA} é a taxa de zero-resultado de antes do recurso.</strong> A
     * série histórica continua comparável — a soma mede cobertura de busca, que
     * não mudou, e a separação mede quantas dessas falhas de cobertura o resgate
     * converteu em alguma coisa.
     */
    private static String desfecho(RastroDaSugestao rastro) {
        if (!rastro.prefixoAceito()) {
            return "PREFIXO_RECUSADO";
        }
        if (rastro.linhas().isEmpty()) {
            return "ZERO_RESULTADO";
        }
        if (rastro.substituida()) {
            return "SUBSTITUIDA";
        }
        return rastro.temOferta() ? "COM_OFERTA" : "SEM_OFERTA";
    }

    /**
     * Comprimento em faixa, nunca o prefixo.
     *
     * <p>A faixa responde à pergunta que importa — "a partir de quantas letras a
     * busca começa a encontrar?" — sem guardar nenhuma letra. É a troca que torna
     * esta métrica publicável.
     */
    private static String faixaDeComprimento(String prefixo) {
        int n = prefixo == null ? 0 : prefixo.length();
        if (n <= 1) {
            return "0-1";
        }
        if (n <= 3) {
            return "2-3";
        }
        if (n <= 6) {
            return "4-6";
        }
        if (n <= 10) {
            return "7-10";
        }
        return "11+";
    }

    @Override
    public void cliqueEm(int posicao) {
        try {
            registro.counter(CLIQUES).increment();

            if (posicao >= 0) {
                DistributionSummary.builder(POSICAO_CLICADA)
                        .publishPercentiles(0.5, 0.9)
                        .register(registro)
                        .record(posicao);
            }
        } catch (RuntimeException e) {
            log.debug("medicao do clique falhou e foi ignorada", e);
        }
    }

    @Override
    public void impressao(int linhasExibidas, boolean degradada) {
        try {
            registro.counter(IMPRESSOES, "degradada", Boolean.toString(degradada)).increment();
            DistributionSummary.builder(LINHAS_EXIBIDAS).register(registro).record(linhasExibidas);
        } catch (RuntimeException e) {
            log.debug("medicao da impressao falhou e foi ignorada", e);
        }
    }
}
