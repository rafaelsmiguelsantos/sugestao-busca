package dev.sugestao.busca.adaptador.entrada.agendador;

import dev.sugestao.busca.aplicacao.porta.entrada.AprenderComComportamento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * O relógio que aciona o caminho de aprendizado.
 *
 * <p>É um adaptador de entrada como o controller, só que quem chama é um
 * agendador em vez de uma pessoa. Essa simetria é o ponto: a regra não sabe
 * quem a acionou, e por isso o mesmo caso de uso serve ao relógio, ao gatilho
 * manual da demonstração e ao teste.
 *
 * <p>Erro aqui é registrado e engolido de propósito. Uma rodada que falha não
 * pode derrubar o agendador — a próxima pega os mesmos eventos pendentes, e uma
 * janela de agregação atrasada degrada a relevância sem machucar ninguém.
 */
public class AgendadorDeAprendizado {

    private static final Logger log = LoggerFactory.getLogger(AgendadorDeAprendizado.class);

    private final AprenderComComportamento aprender;

    public AgendadorDeAprendizado(AprenderComComportamento aprender) {
        this.aprender = aprender;
    }

    /**
     * O atraso inicial é separado do intervalo, e por um motivo que custou uma
     * depuração.
     *
     * <p>Os dois eram o mesmo valor, o que dava uma <strong>janela cega de 15
     * segundos</strong> depois da subida: nesse intervalo, nenhum perfil tinha
     * afinidade calculada, e quem subisse a aplicação e consultasse na hora via
     * a lista sem nenhum sinal de comportamento. Numa demonstração isso parece
     * defeito; em produção é o primeiro usuário do dia recebendo lista pior sem
     * razão.
     *
     * <p>O atraso existia para não disparar uma rodada antes de as dependências
     * estarem de pé. Deixou de ser necessário quando a rodada passou a atualizar
     * perfil <em>antes</em> de tocar o índice: com o Solr fora, a parte de
     * vocabulário falha e o agendador a retoma, e a parte de perfil — que não
     * precisa de Solr — já terá acontecido. Por isso o padrão agora é zero.
     */
    @Scheduled(
            fixedDelayString = "${sugestao.aprendizado.intervalo-ms}",
            initialDelayString = "${sugestao.aprendizado.atraso-inicial-ms:0}")
    public void rodar() {
        try {
            var resultado = aprender.executar();
            if (resultado.eventosConsumidos() > 0) {
                log.info("aprendizado: {}", resultado.resumo());
            }
        } catch (Exception e) {
            log.warn("rodada de aprendizado falhou; a proxima retoma os pendentes", e);
        }
    }
}
