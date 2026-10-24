package dev.sugestao.busca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SugestaoBuscaApplication {

	public static void main(String[] args) {
		SpringApplication.run(SugestaoBuscaApplication.class, args);
	}

}
