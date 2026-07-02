package com.aquarius.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Propiedades de configuracion del gateway. Se cargan desde
 * {@code application.yml} bajo el prefijo {@code proxy}.
 */
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

    /**
     * Lista de hosts permitidos. Se acepta "host:puerto" o "host" (cualquier puerto).
     * Se soporta el comodin "*" para permitir todos (no recomendado en produccion).
     */
    private List<String> allowedHosts = new ArrayList<>();

    /**
     * Timeout de conexion al servicio destino (ms).
     */
    private int connectTimeoutMs = 5_000;

    /**
     * Timeout de lectura (ms).
     */
    private int readTimeoutMs = 30_000;

    /**
     * Tamanio maximo del body de respuesta permitido en bytes cuando se
     * materializa en memoria (envoltorio JSON /proxy/forward y /proxy/raw).
     * No aplica a /proxy/stream ni /proxy/upload, que trabajan en streaming.
     */
    private long maxResponseBytes = 10L * 1024 * 1024; // 10 MB

    /**
     * Cota de seguridad para /proxy/stream: si la respuesta del destino
     * supera este tamanio (en bytes), se corta el stream y se cierra la
     * conexion. Evita que un destino malicioso exhausta el servidor.
     */
    private long maxStreamBytes = 200L * 1024 * 1024; // 200 MB

    /**
     * Cota para uploads entrantes en /proxy/upload. Al llegar a este limite
     * la peticion se rechaza con 413. Tambien se usa como limite de cada
     * part individual (archivo) de la multipart.
     */
    private long maxUploadBytes = 100L * 1024 * 1024; // 100 MB

    /**
     * Shared secret. Si se define, cada peticion al gateway debe enviar el
     * header {@code X-Proxy-Token} con este valor; en caso contrario -> 401.
     * Para /proxy/stream se acepta ademas como query param ?token=... dado
     * que un iframe no puede anadir headers.
     */
    private String sharedSecret = "";

    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public long getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(long maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }

    public long getMaxStreamBytes() { return maxStreamBytes; }
    public void setMaxStreamBytes(long maxStreamBytes) { this.maxStreamBytes = maxStreamBytes; }

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }

    public String getSharedSecret() { return sharedSecret; }
    public void setSharedSecret(String sharedSecret) { this.sharedSecret = sharedSecret; }
}
