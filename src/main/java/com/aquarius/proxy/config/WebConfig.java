package com.aquarius.proxy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS permisivo para que el gateway pueda ser consumido desde cualquier
 * aplicacion (Angular, SPA alojada en otro dominio, herramientas internas...).
 *
 * <p>Por diseno el gateway es un punto de entrada compartido, asi que aceptar
 * cualquier origen en CORS no rompe el modelo de seguridad: la defensa real
 * esta en el shared-secret ({@code X-Proxy-Token} o {@code ?token=...}) y
 * en la allowlist de hosts destino ({@code proxy.allowed-hosts}).</p>
 *
 * <p>Se usa {@code setAllowedOriginPatterns("*")} en lugar de
 * {@code setAllowedOrigins("*")} para poder activar tambien
 * {@code setAllowCredentials(true)} (la spec de CORS prohibe el comodin
 * literal cuando se permiten credenciales).</p>
 */
@Configuration
public class WebConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        // Exponemos los headers tipicos que un cliente (iframe, visor PDF,
        // pdf.js, etc.) necesita leer para renderizar correctamente una
        // descarga binaria.
        cfg.setExposedHeaders(Arrays.asList(
                "Content-Type", "Content-Length", "Content-Disposition",
                "Content-Range", "Accept-Ranges", "ETag", "Last-Modified",
                "X-Proxy-Target"
        ));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(src);
    }
}
