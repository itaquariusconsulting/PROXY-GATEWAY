package com.aquarius.proxy.controller;

import com.aquarius.proxy.config.ProxyProperties;
import com.aquarius.proxy.dto.ProxyRequest;
import com.aquarius.proxy.dto.ProxyResponse;
import com.aquarius.proxy.exception.ProxyException;
import com.aquarius.proxy.service.ProxyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Endpoints del gateway.
 *
 * <ul>
 *   <li>GET  /proxy/ping      -> health simple.</li>
 *   <li>POST /proxy/forward   -> reenvia la peticion; devuelve {@link ProxyResponse} envuelto.</li>
 *   <li>POST /proxy/raw       -> igual que forward, pero propaga status y body tal cual.</li>
 *   <li>GET  /proxy/stream    -> streaming binario transparente (PDF, imagenes, descargas).</li>
 *   <li>POST /proxy/upload    -> reenvio multipart/form-data (subida de archivos).</li>
 * </ul>
 *
 * <p>Los endpoints binarios ({@code /stream}, {@code /upload}) reciben la URL
 * destino y el metodo como query params ({@code url=..&method=..}). El resto
 * de query params del cliente (salvo {@code url}, {@code method} y
 * {@code token}) se propagan al destino.</p>
 */
@RestController
@RequestMapping("/proxy")
public class ProxyController {

    // Headers del cliente que NO reenviamos al destino (son del gateway o ruido).
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "connection", "keep-alive", "content-length",
            "transfer-encoding", "x-proxy-token", "origin", "referer",
            "cookie"
    );

    // Query params reservados por el propio gateway.
    private static final Set<String> GATEWAY_PARAMS = Set.of("url", "method", "token");

    private final ProxyService proxyService;
    private final ProxyProperties props;

    public ProxyController(ProxyService proxyService, ProxyProperties props) {
        this.proxyService = proxyService;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // Health
    // ------------------------------------------------------------------

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "service", "proxy-gateway");
    }

    // ------------------------------------------------------------------
    // 1) Envoltorio JSON (legacy)
    // ------------------------------------------------------------------

    @PostMapping(value = "/forward",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProxyResponse> forward(@Valid @RequestBody ProxyRequest req,
                                                 HttpServletRequest http) {
        authorize(http, null);
        ProxyResponse r = proxyService.forward(req);
        // Devolvemos HTTP 200 siempre; el status real del destino va en el body.
        return ResponseEntity.ok(r);
    }

    @PostMapping(value = "/raw",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> raw(@Valid @RequestBody ProxyRequest req,
                                      HttpServletRequest http) {
        authorize(http, null);
        ProxyResponse r = proxyService.forward(req);
        Object body = r.getBody() != null ? r.getBody() : r.getRawBody();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(r.getStatus());
        // Propaga Content-Type si el destino lo envio
        String ct = r.getHeaders() != null ? r.getHeaders().getOrDefault("Content-Type",
                r.getHeaders().get("content-type")) : null;
        if (ct != null) builder.header("Content-Type", ct);
        return builder.body(body);
    }

    // ------------------------------------------------------------------
    // 2) Streaming binario (PDF, imagen, video...)
    // ------------------------------------------------------------------

    /**
     * Reenvio de descargas / previews. La URL destino se pasa como
     * query param {@code url}. Ejemplos:
     * <pre>
     *   GET /proxy/stream?url=http://127.0.0.1:8096/api/documents/42/file
     *   GET /proxy/stream?url=http://...&amp;token=abc123   (para iframes)
     * </pre>
     *
     * <p>Como un iframe no puede anadir headers, se acepta el token tanto
     * por header {@code X-Proxy-Token} como por query param {@code token}.</p>
     */
    @RequestMapping(value = "/stream", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void stream(@RequestParam("url") String url,
                       @RequestParam(value = "method", required = false, defaultValue = "GET") String method,
                       @RequestParam(value = "token", required = false) String token,
                       HttpServletRequest http,
                       HttpServletResponse out) {
        authorize(http, token);
        Map<String, String> headers = copyClientHeaders(http);
        Map<String, List<String>> params = copyClientParams(http);
        proxyService.forwardStream(url, method, headers, params, out);
    }

    // ------------------------------------------------------------------
    // 3) Multipart (subida de archivos)
    // ------------------------------------------------------------------

    /**
     * Reenvio de subidas multipart/form-data. La URL destino va en el
     * query param {@code url}. Todos los parts tipo archivo del cliente se
     * reagrupan en una nueva multipart hacia el destino; los campos de texto
     * (parametros del formulario) tambien se propagan.
     *
     * <pre>
     *   POST /proxy/upload?url=http://127.0.0.1:8096/api/documents/manual
     *   Content-Type: multipart/form-data; boundary=...
     *   - file: (pdf)
     *   - document_type: "INVOICE"
     *   - issue_date: "2026-04-01"
     * </pre>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void upload(@RequestParam("url") String url,
                       @RequestParam(value = "method", required = false, defaultValue = "POST") String method,
                       @RequestParam(value = "token", required = false) String token,
                       @RequestParam Map<String, String> allParams,
                       HttpServletRequest http,
                       HttpServletResponse out) {
        authorize(http, token);

        // Files: todos los parts que llegan como MultipartFile.
        List<MultipartFile> files = new ArrayList<>();
        try {
            if (http instanceof org.springframework.web.multipart.MultipartHttpServletRequest) {
                org.springframework.web.multipart.MultipartHttpServletRequest mreq =
                        (org.springframework.web.multipart.MultipartHttpServletRequest) http;
                for (Map.Entry<String, List<MultipartFile>> e : mreq.getMultiFileMap().entrySet()) {
                    if (e.getValue() != null) files.addAll(e.getValue());
                }
            }
        } catch (Exception ignore) { /* sin archivos */ }

        // Campos de formulario (texto). Excluimos los query params reservados.
        Map<String, String> formFields = new LinkedHashMap<>();
        if (allParams != null) {
            for (Map.Entry<String, String> e : allParams.entrySet()) {
                if (GATEWAY_PARAMS.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
                formFields.put(e.getKey(), e.getValue());
            }
        }

        Map<String, String> headers = copyClientHeaders(http);
        Map<String, List<String>> params = Collections.emptyMap(); // los campos van en el body

        proxyService.forwardMultipart(url, method, headers, params, files, formFields, out);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Valida el shared-secret. Permite pasarlo por header
     * {@code X-Proxy-Token} o (para iframes / descargas directas) por query
     * param {@code token}.
     */
    private void authorize(HttpServletRequest http, String queryToken) {
        String secret = props.getSharedSecret();
        if (secret == null || secret.isBlank()) return; // sin seguridad => permitir
        String headerToken = http.getHeader("X-Proxy-Token");
        String got = headerToken != null ? headerToken : queryToken;
        if (!secret.equals(got)) {
            throw new ProxyException(HttpStatus.UNAUTHORIZED, "Token de proxy invalido o ausente.");
        }
    }

    /** Copia todos los headers del cliente (excepto gateway/ruido) a un mapa. */
    private Map<String, String> copyClientHeaders(HttpServletRequest http) {
        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = http.getHeaderNames();
        if (names == null) return out;
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            if (n == null) continue;
            if (SKIP_REQUEST_HEADERS.contains(n.toLowerCase(Locale.ROOT))) continue;
            out.put(n, http.getHeader(n));
        }
        return out;
    }

    /**
     * Copia los query params del cliente al destino, excluyendo los que son
     * del propio gateway ({@code url}, {@code method}, {@code token}).
     */
    private Map<String, List<String>> copyClientParams(HttpServletRequest http) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, String[]> in = http.getParameterMap();
        if (in == null) return out;
        for (Map.Entry<String, String[]> e : in.entrySet()) {
            String k = e.getKey();
            if (k == null || GATEWAY_PARAMS.contains(k.toLowerCase(Locale.ROOT))) continue;
            if (e.getValue() == null) continue;
            out.put(k, java.util.Arrays.asList(e.getValue()));
        }
        return out;
    }
}
