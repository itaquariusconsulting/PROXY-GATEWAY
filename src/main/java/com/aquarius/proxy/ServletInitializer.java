package com.aquarius.proxy;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Permite que Tomcat (externo) arranque la aplicacion como un servlet
 * estandar. Necesario para el empaquetado WAR.
 */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(ProxyGatewayApplication.class);
    }
}
