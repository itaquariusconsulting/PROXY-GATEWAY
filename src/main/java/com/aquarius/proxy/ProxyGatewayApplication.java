package com.aquarius.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point del WAR. Aun cuando se despliega en Tomcat externo,
 * Spring Boot necesita una clase con {@code @SpringBootApplication}.
 * La clase {@link ServletInitializer} se encarga del bootstrap bajo
 * un contenedor servlet.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProxyGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyGatewayApplication.class, args);
    }
}
