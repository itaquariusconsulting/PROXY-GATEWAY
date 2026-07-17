package com.aquarius.proxy.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

import javax.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cuerpo que recibe el gateway por cada invocacion.
 *
 * Ejemplo minimo:
 * <pre>
 * {
 *   "url": "http://192.168.10.48:6708/lista-usuarios",
 *   "method": "POST",
 *   "headers": { "Authorization": "Bearer xxx" },
 *   "params":  { "page": "1", "size": "20" },
 *   "body":    { "filtro": "activos" }
 * }
 * </pre>
 *
 * Si solo tienes IP + puerto + path (como en el ejemplo del cliente)
 * basta con el campo {@code url}; el resto son opcionales.
 */
public class ProxyRequest {

    /** URL completa destino: http(s)://host:port/path */
    @NotBlank(message = "url es obligatorio")
    private String url;

    /** Metodo HTTP. Default: GET. */
    private String method = "GET";

    /** Headers a reenviar. Host/Content-Length se calculan automaticamente. */
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * Query params. Se aceptan valores simples (string) o listas (multi-valor),
     * y el campo llega tanto como "params" como "query" (el frontend usa "query").
     * Se codifican con URL-encoding al armar la peticion.
     */
    @JsonAlias({"query"})
    private Map<String, Object> params = new LinkedHashMap<>();

    /**
     * Body de la peticion. Se acepta cualquier JSON arbitrario (objeto,
     * arreglo, string, numero...). Si es null no se envia cuerpo.
     */
    private JsonNode body;

    /** Override del timeout de lectura en milisegundos (opcional). */
    private Integer timeoutMs;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public JsonNode getBody() { return body; }
    public void setBody(JsonNode body) { this.body = body; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
}
