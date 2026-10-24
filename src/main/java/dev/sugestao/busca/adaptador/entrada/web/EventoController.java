package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.aplicacao.porta.entrada.AprenderComComportamento;
import dev.sugestao.busca.aplicacao.porta.entrada.RegistrarEventos;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDaIngestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDoAprendizado;
import dev.sugestao.busca.configuracao.SugestaoProperties;
import dev.sugestao.busca.dominio.Cliente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A porta de escrita vista de fora.
 *
 * <p>Responde 202, não 200. O app não precisa — e não deve — esperar a
 * agregação: o que ele manda é registro, não pedido. Qualquer trabalho feito
 * com alguém esperando neste caminho é tempo cobrado de uma pessoa que já
 * terminou de buscar.
 */
@RestController
public class EventoController {

    private static final Logger log = LoggerFactory.getLogger(EventoController.class);

    private final RegistrarEventos registrar;
    private final AprenderComComportamento aprender;
    private final boolean gatilhoManualHabilitado;

    public EventoController(
            RegistrarEventos registrar,
            AprenderComComportamento aprender,
            SugestaoProperties props) {
        this.registrar = registrar;
        this.aprender = aprender;
        this.gatilhoManualHabilitado = props.aprendizado().gatilhoManual();
    }

    @PostMapping("/v1/eventos")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResultadoDaIngestao registrar(
            @RequestBody List<EventoRecebido> recebidos, Cliente cliente) {

        var eventos = recebidos.stream().map(e -> e.paraDominio(cliente)).toList();
        var resultado = registrar.executar(eventos);

        if (resultado.descartados() > 0) {
            // Contagem por motivo, nunca o termo. Registrar o que foi barrado
            // recriaria no log exatamente o vazamento que o portão impediu.
            log.info("ingestao barrou {} de {} eventos: {}",
                    resultado.descartados(), resultado.recebidos(), resultado.motivos());
        }
        return resultado;
    }

    /**
     * Roda a agregação agora, em vez de esperar o relógio.
     *
     * <p>Existe para a demonstração: sem isto, mostrar que o sistema aprende
     * exigiria esperar o intervalo do agendador. Fica desligado por padrão fora
     * do ambiente local — é um gatilho que dá para disparar em laço.
     */
    @PostMapping("/v1/aprendizado/rodar")
    public ResponseEntity<ResultadoDoAprendizado> rodarAgora() {
        if (!gatilhoManualHabilitado) {
            return ResponseEntity.notFound().build();
        }
        var resultado = aprender.executar();
        log.info("aprendizado sob demanda: {}", resultado.resumo());
        return ResponseEntity.ok(resultado);
    }
}
