package com.aquarius.proxy.config;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Construye un {@link CloseableHttpClient} reutilizable con pool de conexiones
 * y timeouts parametrizados via {@link ProxyProperties}.
 */
@Configuration
public class HttpClientConfig {

    @Bean(destroyMethod = "close")
    public CloseableHttpClient httpClient(ProxyProperties props) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(200);
        cm.setDefaultMaxPerRoute(40);

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(props.getConnectTimeoutMs())
                .setConnectionRequestTimeout(props.getConnectTimeoutMs())
                .setSocketTimeout(props.getReadTimeoutMs())
                .setRedirectsEnabled(false)
                .build();

        return HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(rc)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }
}
