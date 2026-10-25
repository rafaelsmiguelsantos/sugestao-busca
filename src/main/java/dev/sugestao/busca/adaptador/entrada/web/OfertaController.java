package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.aplicacao.porta.entrada.CadastrarOferta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * O lado de escrita do catálogo: a jornada dona cadastra a própria oferta.
 *
 * <p>Fica separado do {@link SugestaoController} porque os dois têm perfis de
 * falha opostos. A busca nunca devolve erro — índice fora do ar vira lista
 * degradada, prefixo curto vira lista vazia — porque há alguém digitando e uma
 * caixa de busca com 500 é pior que uma caixa vazia. O cadastro é o contrário:
 * cada recusa é informação que a jornada precisa receber inteira, e um cadastro
 * que "quase funcionou" é oferta que não vai aparecer e ninguém vai saber por
 * quê.
 *
 * <p><strong>O que falta aqui para produção, declarado:</strong> não há
 * autorização. Qualquer chamador pode cadastrar oferta em nome de qualquer
 * jornada. Num sistema de verdade o token carrega a jornada e o serviço recusa
 * cadastro fora dela — o campo {@code jornada} do corpo seria conferido contra o
 * token, não aceito. Isto está fora do escopo da prova de conceito, e está
 * escrito aqui porque um endpoint de escrita sem dono é o tipo de coisa que, não
 * dita, vira surpresa na revisão de segurança.
 */
@RestController
public class OfertaController {

    private static final Logger log = LoggerFactory.getLogger(OfertaController.class);

    private final CadastrarOferta cadastro;

    public OfertaController(CadastrarOferta cadastro) {
        this.cadastro = cadastro;
    }

    /**
     * 201 quando criou, 200 quando substituiu — e as duas com o corpo gravado.
     *
     * <p>O {@code Location} sai nos dois casos: quem integra quer o caminho de
     * conferência tanto depois de criar quanto depois de atualizar.
     */
    @PostMapping(path = "/v1/ofertas", consumes = "application/json")
    public ResponseEntity<RespostaDeOferta> cadastrar(@RequestBody OfertaRecebida corpo) {
        var resultado = cadastro.executar(corpo.paraComando());
        var resposta = RespostaDeOferta.de(resultado.oferta());
        var local = URI.create("/v1/ofertas/" + resultado.ofertaId());

        log.info("oferta {} {} jornada={} produto={}",
                resultado.ofertaId(),
                resultado.criada() ? "criada" : "substituida",
                resultado.oferta().jornada(),
                resultado.oferta().produtoAlvo());

        return resultado.criada()
                ? ResponseEntity.created(local).body(resposta)
                : ResponseEntity.ok().location(local).body(resposta);
    }

    @GetMapping("/v1/ofertas/{ofertaId}")
    public RespostaDeOferta porId(@PathVariable String ofertaId) {
        return cadastro.porId(ofertaId)
                .map(RespostaDeOferta::de)
                .orElseThrow(() -> new OfertaNaoEncontrada(ofertaId));
    }
}
