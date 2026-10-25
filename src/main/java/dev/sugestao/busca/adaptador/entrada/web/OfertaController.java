package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.aplicacao.porta.entrada.Autorizacao;
import dev.sugestao.busca.aplicacao.porta.entrada.CadastrarOferta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * <p><strong>A jornada vem do token, e o corpo é conferido contra ela.</strong>
 * O escopo {@code ofertas:escrever} diz que o chamador pode cadastrar <em>alguma
 * coisa</em>; a reivindicação {@code jornada} diz o quê. Sem a segunda, a jornada
 * de seguros poderia publicar uma oferta de crédito, com rótulo e destino
 * escolhidos por ela, e ela apareceria na busca como se fosse do time de
 * crédito.
 *
 * <p>Este controller não aplica essa regra: ele traduz o token em
 * {@link Autorizacao} e passa adiante. Quem decide é o caso de uso, porque "a
 * oferta tem de ser da jornada de quem cadastra" é regra de negócio — e ela
 * continuaria valendo se o cadastro passasse a entrar por uma fila, por um lote
 * ou por um console administrativo.
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
    public ResponseEntity<RespostaDeOferta> cadastrar(
            @RequestBody OfertaRecebida corpo,
            @AuthenticationPrincipal Jwt token) {

        var resultado = cadastro.executar(corpo.paraComando(), autorizacaoDe(token));
        var resposta = RespostaDeOferta.de(resultado.oferta());
        var local = URI.create("/v1/ofertas/" + resultado.ofertaId());

        log.info("oferta {} {} jornada={} produto={} por={}",
                resultado.ofertaId(),
                resultado.criada() ? "criada" : "substituida",
                resultado.oferta().jornada(),
                resultado.oferta().produtoAlvo(),
                token == null ? "sem-token" : token.getSubject());

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

    /**
     * Traduz o token em {@link Autorizacao} — e só isso.
     *
     * <p>A reivindicação {@code jornada} diz de qual jornada o chamador é dono.
     * O escopo {@code ofertas:todas} é o privilégio de cadastrar em qualquer
     * uma, e existe para operação e carga inicial de catálogo: alguém precisa
     * poder semear. Tem nome próprio para que, numa auditoria, procurar quem o
     * recebe seja uma busca de uma linha.
     *
     * <p>Token nulo só acontece com a segurança desligada. Nesse modo o cadastro
     * fica irrestrito, o que é coerente: não há de onde tirar a jornada, e a
     * subida já gritou no log que o serviço está aberto.
     */
    private static Autorizacao autorizacaoDe(Jwt token) {
        if (token == null) {
            return Autorizacao.irrestrita();
        }

        // Separa em espaços e compara exato, nunca contains(). A reivindicação
        // 'scope' é uma lista separada por espaço, e um contains() casaria o
        // privilégio mais alto desta API dentro de qualquer escopo que o tenha
        // como pedaço — "relatorio:ofertas:todas-as-regioes" concederia tudo.
        var escopos = token.getClaimAsString("scope");
        if (escopos != null) {
            for (String escopo : escopos.split("\\s+")) {
                if (ESCOPO_TODAS_AS_JORNADAS.equals(escopo)) {
                    return Autorizacao.irrestrita();
                }
            }
        }

        return Autorizacao.daJornada(token.getClaimAsString("jornada"));
    }

    /** Sem o prefixo SCOPE_: aqui se lê a reivindicação crua, não a autoridade. */
    private static final String ESCOPO_TODAS_AS_JORNADAS = "ofertas:todas";
}
