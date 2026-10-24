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

    @Scheduled(
            fixedDelayString = "${sugestao.aprendizado.intervalo-ms}",
            initialDelayString = "${sugestao.aprendizado.intervalo-ms}")
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
