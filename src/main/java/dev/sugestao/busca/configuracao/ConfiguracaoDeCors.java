package dev.sugestao.busca.configuracao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * O que um cliente React ou Angular precisa para conseguir chamar este serviço
 * de dentro do navegador.
 *
 * <p>Sem isto a API responde normalmente a {@code curl} e é <strong>bloqueada
 * pelo navegador</strong>. A distinção confunde muita gente na primeira
 * integração: o servidor não recusa nada, o 200 chega, e o JavaScript nunca vê
 * a resposta — o navegador a descarta por falta dos cabeçalhos
 * {@code Access-Control-*}. Em DevTools aparece como erro de rede, o que manda
 * investigar o lugar errado.
 *
 * <p><strong>Preflight é inevitável aqui, e isso tem custo.</strong> Os
 * cabeçalhos {@code X-Cliente-*} não estão na lista segura do CORS, então o
 * navegador manda um {@code OPTIONS} antes de cada {@code GET}. Numa caixa de
 * busca que consulta a cada tecla, isso dobraria o número de requisições — e
 * trocar os cabeçalhos por parâmetros de query não resolveria nada, porque em
 * produção a identidade vem no {@code Authorization}, que também exige
 * preflight. O que resolve é {@code maxAge}: o navegador guarda a autorização
 * por (origem, método, cabeçalhos) e deixa de perguntar. Os 1800 s padrão
 * cobrem uma sessão de uso; o Chrome limita o próprio teto em 2 h, então pedir
 * mais não ajuda.
 *
 * <p><strong>Desligado por padrão.</strong> {@code sugestao.cors.origens} vem
 * vazio, e vazio significa nenhuma origem autorizada — não todas. É a escolha
 * oposta à conveniente: um {@code *} no padrão funcionaria em todo lugar,
 * inclusive em produção, e é assim que uma API interna passa a aceitar chamada
 * de qualquer página da internet sem ninguém ter decidido isso. O perfil
 * {@code local} lista as portas de desenvolvimento.
 *
 * <p>Não há {@code allowCredentials}. Este serviço não usa cookie de sessão: a
 * identidade vem em cabeçalho, que o cliente envia explicitamente. Ligar
 * credenciais permitiria ao navegador anexar cookie de outra origem a estas
 * chamadas, que é o contrário do que se quer — e o CORS proíbe combinar
 * credenciais com origem curinga, de propósito.
 */
@Configuration
public class ConfiguracaoDeCors implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDeCors.class);

    private final SugestaoProperties.Cors cors;

    public ConfiguracaoDeCors(SugestaoProperties props) {
        // Bloco ausente no arquivo de propriedades chega nulo. Cair em
        // 'desligado' e nao em 'tudo liberado' e a mesma escolha do padrao
        // vazio, aplicada ao caso de a chave nem existir.
        this.cors = props.cors() == null
                ? SugestaoProperties.Cors.desligado()
                : props.cors();
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        List<String> origens = cors.origens();

        if (origens.isEmpty()) {
            log.info("CORS desligado: nenhuma origem em sugestao.cors.origens. "
                    + "Cliente de navegador sera bloqueado; curl e servidor-a-servidor funcionam.");
            return;
        }

        registro.addMapping("/v1/**")
                // Origens por extenso, nunca padrao com curinga. allowedOrigins
                // recusa "*" junto com credenciais, mas aqui a razao e outra:
                // uma lista explicita e auditavel, e um curinga em arquivo de
                // configuracao sobrevive ao caminho para producao.
                .allowedOrigins(origens.toArray(String[]::new))

                // GET para a busca, POST para eventos e cadastro de oferta,
                // OPTIONS porque e o preflight. Nada mais: nao ha DELETE nem
                // PUT nesta API, e listar o que nao existe seria prometer.
                .allowedMethods("GET", "POST", "OPTIONS")

                // Os cabecalhos que o cliente precisa poder enviar. Content-Type
                // entra porque application/json nao esta na lista segura.
                .allowedHeaders(
                        "Authorization",
                        "Content-Type",
                        // Os tres de identidade so valem com a seguranca
                        // desligada, mas continuam autorizados aqui: autorizar
                        // um cabecalho que o servico ignora nao concede nada, e
                        // retira-los faria o preflight falhar para um cliente de
                        // demonstracao que ainda os envie.
                        "X-Cliente-Id",
                        "X-Cliente-Segmento",
                        "X-Cliente-Relacionamento")

                // Location sai no 201 do cadastro de oferta, e sem expo-lo o
                // JavaScript nao consegue le-lo: o navegador so entrega ao
                // script os cabecalhos de resposta da lista segura.
                .exposedHeaders("Location")

                .allowCredentials(false)
                .maxAge(cors.maxAgeSegundos());

        log.info("CORS ligado para {} origem(ns), preflight cacheado por {} s",
                origens.size(), cors.maxAgeSegundos());
    }
}
