package com.aquarius.proxy.service;

import com.aquarius.proxy.config.ProxyProperties;
import com.aquarius.proxy.dto.ProxyRequest;
import com.aquarius.proxy.dto.ProxyResponse;
import com.aquarius.proxy.exception.ProxyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reenvia peticiones al servicio indicado en {@code request.url}.
 *
 * Tres modos de operacion:
 * <ul>
 *   <li>{@link #forward(ProxyRequest)}: envoltorio JSON texto (legacy).</li>
 *   <li>{@link #forwardStream}: streaming binario transparente (p. ej. PDF
 *       para un iframe, imagenes, descargas).</li>
 *   <li>{@link #forwardMultipart}: subida multipart/form-data transparente.</li>
 * </ul>
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    // Headers hop-by-hop (RFC 7230) que NO se deben reenviar tal cual.
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host"
    );

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final CloseableHttpClient httpClient;
    private final ProxyProperties props;
    private final ObjectMapper mapper;

    public ProxyService(CloseableHttpClient httpClient, ProxyProperties props, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.props = props;
        this.mapper = mapper;
    }

    // ========================================================================
    // 1) Envoltorio JSON (comportamiento historico, sin cambios)
    // ========================================================================

    public ProxyResponse forward(ProxyRequest req) {
        URI target = buildTargetUri(req.getUrl(), req.getParams());
        checkHostAllowed(target);
        String method = normalizeMethod(req.getMethod());

        HttpRequestBase apacheReq = newRequest(method, target);
        copyHeaders(apacheReq, req.getHeaders());
        attachJsonBody(apacheReq, req);
        applyTimeout(apacheReq, req.getTimeoutMs());

        long t0 = System.currentTimeMillis();
        try (CloseableHttpResponse resp = httpClient.execute(apacheReq)) {
            long elapsed = System.currentTimeMillis() - t0;
            return readResponseAsJson(resp, target, elapsed);
        } catch (IOException ex) {
            log.error("[proxy] fallo conexion a {}: {}", target, ex.getMessage());
            throw new ProxyException(HttpStatus.BAD_GATEWAY,
                    "No se pudo contactar el servicio destino: " + ex.getMessage(), ex);
        }
    }

    // ========================================================================
    // 2) Streaming binario (PDF, imagen, video, etc.)
    // ========================================================================

    /**
     * Hace una peticion HTTP al destino y reenvia la respuesta tal cual al
     * cliente (status, headers relevantes y body binario en streaming).
     *
     * No carga el body en memoria: copia bloque a bloque desde el destino al
     * {@link HttpServletResponse}. Propaga {@code Range} si el cliente lo
     * envio (imprescindible para que el visor PDF del navegador pueda hacer
     * saltos dentro de archivos grandes).
     */
    public void forwardStream(String targetUrl,
                              String method,
                              Map<String, String> headers,
                              Map<String, List<String>> params,
                              HttpServletResponse clientOut) {
        URI target = buildTargetUri(targetUrl, params);
        checkHostAllowed(target);
        String m = normalizeMethod(method);
        if (!Arrays.asList("GET", "HEAD").contains(m)) {
            throw new ProxyException(HttpStatus.BAD_REQUEST,
                    "/proxy/stream solo acepta GET o HEAD (recibido: " + m + ")");
        }

        HttpRequestBase apacheReq = newRequest(m, target);
        copyHeaders(apacheReq, headers);

        long t0 = System.currentTimeMillis();
        try (CloseableHttpResponse resp = httpClient.execute(apacheReq)) {
            int status = resp.getStatusLine().getStatusCode();
            clientOut.setStatus(status);

            // Propagar headers del destino (excepto hop-by-hop). Content-Length
            // lo reseteamos si el stream se corta por limite; Content-Type y
            // Content-Disposition son los importantes para que el navegador
            // renderice correctamente.
            for (Header h : resp.getAllHeaders()) {
                if (HOP_BY_HOP.contains(h.getName().toLowerCase(Locale.ROOT))) continue;
                clientOut.setHeader(h.getName(), h.getValue());
            }

            HttpEntity entity = resp.getEntity();
            if (entity == null) {
                clientOut.flushBuffer();
                log.debug("[proxy-stream] {} {} -> {} (sin body) en {} ms",
                        m, target, status, System.currentTimeMillis() - t0);
                return;
            }

            long max = props.getMaxStreamBytes();
            try (InputStream in = entity.getContent();
                 OutputStream out = clientOut.getOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > max) {
                        log.warn("[proxy-stream] respuesta excede max-stream-bytes ({}), cortando", max);
                        // Cortamos en silencio: ya hemos enviado status y parte
                        // del body; el cliente vera una respuesta truncada, que
                        // es preferible a un 500 tardio.
                        break;
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            log.debug("[proxy-stream] {} {} -> {} en {} ms",
                    m, target, status, System.currentTimeMillis() - t0);

        } catch (IOException ex) {
            log.error("[proxy-stream] fallo conexion a {}: {}", target, ex.getMessage());
            throw new ProxyException(HttpStatus.BAD_GATEWAY,
                    "No se pudo contactar el servicio destino: " + ex.getMessage(), ex);
        }
    }

    // ========================================================================
    // 3) Multipart (subida de archivos)
    // ========================================================================

    /**
     * Reenvia una peticion multipart/form-data al destino construyendo una
     * nueva multipart con los mismos files + fields y stream-ea la respuesta
     * al cliente tal cual (binaria o texto, da igual).
     */
    public void forwardMultipart(String targetUrl,
                                 String method,
                                 Map<String, String> headers,
                                 Map<String, List<String>> params,
                                 List<MultipartFile> files,
                                 Map<String, String> formFields,
                                 HttpServletResponse clientOut) {
        URI target = buildTargetUri(targetUrl, params);
        checkHostAllowed(target);
        String m = normalizeMethod(method);
        if (!METHODS_WITH_BODY.contains(m)) {
            throw new ProxyException(HttpStatus.BAD_REQUEST,
                    "/proxy/upload requiere POST/PUT/PATCH/DELETE (recibido: " + m + ")");
        }

        HttpRequestBase apacheReq = newRequest(m, target);
        // Copiamos headers pero EXCLUIMOS Content-Type: lo fija MultipartEntityBuilder
        // (con el boundary correcto). Tambien excluimos Content-Length.
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null) continue;
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(k)) continue;
                if ("content-type".equals(k)) continue;
                apacheReq.setHeader(e.getKey(), e.getValue());
            }
        }

        MultipartEntityBuilder mp = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .setCharset(StandardCharsets.UTF_8);

        // Campos de formulario (texto)
        if (formFields != null) {
            for (Map.Entry<String, String> e : formFields.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                mp.addPart(e.getKey(),
                        new StringBody(e.getValue(), ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)));
            }
        }

        // Archivos
        long max = props.getMaxUploadBytes();
        long totalBytes = 0;
        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                totalBytes += f.getSize();
                if (totalBytes > max) {
                    throw new ProxyException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Upload excede max-upload-bytes (" + max + " bytes)");
                }
                try {
                    ContentType ct = f.getContentType() != null
                            ? ContentType.parse(f.getContentType())
                            : ContentType.APPLICATION_OCTET_STREAM;
                    mp.addPart(f.getName(),
                            new InputStreamBody(f.getInputStream(), ct, f.getOriginalFilename()));
                } catch (IOException ex) {
                    throw new ProxyException(HttpStatus.BAD_REQUEST,
                            "No pude leer el archivo '" + f.getName() + "': " + ex.getMessage(), ex);
                }
            }
        }

        if (apacheReq instanceof HttpEntityEnclosingRequest) {
            ((HttpEntityEnclosingRequest) apacheReq).setEntity(mp.build());
        }

        long t0 = System.currentTimeMillis();
        try (CloseableHttpResponse resp = httpClient.execute(apacheReq)) {
            int status = resp.getStatusLine().getStatusCode();
            clientOut.setStatus(status);
            for (Header h : resp.getAllHeaders()) {
                if (HOP_BY_HOP.contains(h.getName().toLowerCase(Locale.ROOT))) continue;
                clientOut.setHeader(h.getName(), h.getValue());
            }
            HttpEntity entity = resp.getEntity();
            if (entity == null) {
                clientOut.flushBuffer();
                return;
            }
            long maxResp = props.getMaxStreamBytes();
            try (InputStream in = entity.getContent();
                 OutputStream out = clientOut.getOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxResp) {
                        log.warn("[proxy-upload] respuesta excede max-stream-bytes ({}), cortando", maxResp);
                        break;
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            log.debug("[proxy-upload] {} {} -> {} en {} ms ({} files, {} bytes)",
                    m, target, status, System.currentTimeMillis() - t0,
                    files != null ? files.size() : 0, totalBytes);

        } catch (IOException ex) {
            log.error("[proxy-upload] fallo conexion a {}: {}", target, ex.getMessage());
            throw new ProxyException(HttpStatus.BAD_GATEWAY,
                    "No se pudo contactar el servicio destino: " + ex.getMessage(), ex);
        }
    }

    // ========================================================================
    // Helpers compartidos
    // ========================================================================

    private URI buildTargetUri(String url, Map<String, ?> params) {
        if (url == null || url.isBlank()) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "url destino es obligatorio");
        }
        try {
            URIBuilder b = new URIBuilder(url);
            if (params != null) {
                for (Map.Entry<String, ?> e : params.entrySet()) {
                    String key = e.getKey();
                    Object val = e.getValue();
                    if (key == null || val == null) continue;
                    // El valor puede venir como string simple (frontend: query={search:"x"})
                    // o como lista multi-valor (params={k:["a","b"]}).
                    if (val instanceof Iterable) {
                        for (Object v : (Iterable<?>) val) {
                            if (v != null) b.addParameter(key, String.valueOf(v));
                        }
                    } else {
                        b.addParameter(key, String.valueOf(val));
                    }
                }
            }
            return b.build();
        } catch (URISyntaxException ex) {
            throw new ProxyException(HttpStatus.BAD_REQUEST,
                    "URL destino invalida: " + url, ex);
        }
    }

    private void checkHostAllowed(URI uri) {
        List<String> allow = props.getAllowedHosts();
        if (allow == null || allow.isEmpty()) {
            throw new ProxyException(HttpStatus.FORBIDDEN,
                    "Gateway sin allowedHosts configurados; rechazo por seguridad.");
        }
        if (allow.contains("*")) return;

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "URL destino sin host.");
        }
        String hostPort = port > 0 ? (host + ":" + port) : host;

        for (String rule : allow) {
            if (rule.equalsIgnoreCase(host) || rule.equalsIgnoreCase(hostPort)) return;
        }
        throw new ProxyException(HttpStatus.FORBIDDEN,
                "Host destino no permitido: " + hostPort);
    }

    private String normalizeMethod(String m) {
        if (m == null || m.isBlank()) return "GET";
        String up = m.trim().toUpperCase(Locale.ROOT);
        if (!Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(up)) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "Metodo HTTP no soportado: " + m);
        }
        return up;
    }

    private HttpRequestBase newRequest(String method, URI uri) {
        HttpRequestBase r;
        if (METHODS_WITH_BODY.contains(method)) {
            r = new HttpEntityEnclosingRequestBase() {
                @Override public String getMethod() { return method; }
            };
        } else {
            r = new HttpRequestBase() {
                @Override public String getMethod() { return method; }
            };
        }
        r.setURI(uri);
        return r;
    }

    private void copyHeaders(HttpRequestBase req, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if (HOP_BY_HOP.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
            req.setHeader(e.getKey(), e.getValue());
        }
    }

    private void attachJsonBody(HttpRequestBase req, ProxyRequest src) {
        if (src.getBody() == null || src.getBody().isNull()) return;
        if (!METHODS_WITH_BODY.contains(req.getMethod())) return;
        if (!(req instanceof HttpEntityEnclosingRequest)) return;

        String payload;
        try {
            payload = src.getBody().isTextual() ? src.getBody().asText()
                    : mapper.writeValueAsString(src.getBody());
        } catch (JsonProcessingException ex) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "Body JSON invalido", ex);
        }

        ContentType ct = resolveContentType(src);
        StringEntity entity = new StringEntity(payload, ct);
        ((HttpEntityEnclosingRequest) req).setEntity(entity);

        if (req.getFirstHeader("Content-Type") == null) {
            req.setHeader("Content-Type", ct.toString());
        }
    }

    private ContentType resolveContentType(ProxyRequest src) {
        if (src.getHeaders() != null) {
            for (Map.Entry<String, String> e : src.getHeaders().entrySet()) {
                if ("content-type".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                    try {
                        ContentType parsed = ContentType.parse(e.getValue());
                        // Si el cliente no declaro charset, StringEntity caeria a
                        // ISO-8859-1 y el backend (que lee UTF-8) fallaria al parsear
                        // cualquier body con acentos. Forzamos UTF-8 en ese caso.
                        return parsed.getCharset() != null
                                ? parsed
                                : parsed.withCharset(StandardCharsets.UTF_8);
                    } catch (Exception ignore) { /* fall-through */ }
                }
            }
        }
        return ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8);
    }

    private void applyTimeout(HttpRequestBase req, Integer override) {
        if (override == null || override <= 0) return;
        RequestConfig base = req.getConfig();
        RequestConfig.Builder b = base != null ? RequestConfig.copy(base) : RequestConfig.custom();
        req.setConfig(b.setSocketTimeout(override).build());
    }

    private ProxyResponse readResponseAsJson(CloseableHttpResponse resp, URI target, long elapsed) throws IOException {
        ProxyResponse out = new ProxyResponse();
        out.setStatus(resp.getStatusLine().getStatusCode());
        out.setElapsedMs(elapsed);
        out.setTarget(target.toString());

        Map<String, String> headers = new LinkedHashMap<>();
        for (Header h : resp.getAllHeaders()) {
            if (HOP_BY_HOP.contains(h.getName().toLowerCase(Locale.ROOT))) continue;
            headers.put(h.getName(), h.getValue());
        }
        out.setHeaders(headers);

        HttpEntity entity = resp.getEntity();
        if (entity != null) {
            String text = readBounded(entity.getContent(), props.getMaxResponseBytes());
            ContentType ct = entity.getContentType() != null
                    ? ContentType.parse(entity.getContentType().getValue())
                    : null;
            if (ct != null && ct.getMimeType() != null
                    && ct.getMimeType().toLowerCase(Locale.ROOT).contains("json")
                    && !text.isBlank()) {
                try {
                    out.setBody(mapper.readTree(text));
                } catch (Exception ex) {
                    out.setRawBody(text);
                }
            } else {
                out.setRawBody(text);
            }
        }
        return out;
    }

    private String readBounded(InputStream in, long max) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8 * 1024];
            long total = 0;
            int n;
            while ((n = r.read(buf)) != -1) {
                total += n;
                if (total > max) {
                    throw new ProxyException(HttpStatus.BAD_GATEWAY,
                            "Respuesta del servicio excede el limite (" + max + " bytes)");
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
