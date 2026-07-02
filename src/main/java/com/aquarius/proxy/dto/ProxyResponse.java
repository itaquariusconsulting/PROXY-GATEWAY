package com.aquarius.proxy.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respuesta que devuelve el gateway al cliente. Encapsula el resultado
 * del servicio destino preservando status, headers y body.
 */
public class ProxyResponse {

    /** Status HTTP devuelto por el servicio destino. */
    private int status;

    /** Duracion en milisegundos de la llamada. */
    private long elapsedMs;

    /** URL efectiva invocada (util para debug). */
    private String target;

    /** Headers que respondio el servicio destino (filtrados los hop-by-hop). */
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * Body de la respuesta. Si el content-type es JSON se entrega parseado;
     * en caso contrario se entrega como texto en el campo {@link #rawBody}.
     */
    private JsonNode body;

    /** Cuerpo crudo (texto) cuando el destino responde algo distinto a JSON. */
    private String rawBody;

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public JsonNode getBody() { return body; }
    public void setBody(JsonNode body) { this.body = body; }

    public String getRawBody() { return rawBody; }
    public void setRawBody(String rawBody) { this.rawBody = rawBody; }
}
