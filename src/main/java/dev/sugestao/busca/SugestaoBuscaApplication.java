package dev.sugestao.busca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * O agendamento está ligado aqui porque o caminho de escrita é acionado por um
 * relógio: a agregação roda fora da requisição, sem ninguém esperando por ela.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SugestaoBuscaApplication {

	public static void main(String[] args) {
		SpringApplication.run(SugestaoBuscaApplication.class, args);
	}

}
