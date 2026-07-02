# proxy-gateway

WAR generico escrito en Spring Boot 2.7.18 / Java 11 que recibe en el body la
definicion completa de la peticion (URL destino, metodo, headers, params y
body) y la reenvia al servicio correspondiente en la red interna. Se despliega
en un Tomcat 9 existente (no trae servidor embebido).

## Endpoints

| Metodo   | Path            | Descripcion                                                                                   |
|----------|-----------------|-----------------------------------------------------------------------------------------------|
| GET      | /proxy/ping     | Health rapido; responde `{status:"ok"}`.                                                      |
| GET      | /actuator/health| Health de Spring Actuator.                                                                    |
| POST     | /proxy/forward  | Reenvia la peticion JSON y devuelve HTTP 200 envolviendo la respuesta del destino.            |
| POST     | /proxy/raw      | Reenvia la peticion JSON y replica el status/body del destino tal cual.                       |
| GET/HEAD | /proxy/stream   | **Streaming binario** (PDF, imagenes, descargas). Se pasa la URL destino como query param.    |
| POST     | /proxy/upload   | **multipart/form-data** -> reenvia archivos + campos de formulario al destino.                |

Los dos ultimos son los que permiten **enviar y recibir archivos (PDF u otros)**
desde cualquier aplicacion sin obligar al cliente a serializarlos en JSON.

## Body de entrada (`POST /proxy/forward`)

```json
{
  "url":     "http://192.168.10.48:6708/lista-usuarios",
  "method":  "POST",
  "headers": {
    "Authorization": "Bearer eyJ...",
    "X-Correlation-Id": "abc-123"
  },
  "params":  {
    "page": ["1"],
    "size": ["20"]
  },
  "body": {
    "filtro": "activos"
  },
  "timeoutMs": 15000
}
```

- `url` **obligatorio** (http o https con host y puerto).
- `method` opcional (default `GET`). Soporta GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS.
- `headers` opcional; se ignoran los hop-by-hop (`Host`, `Content-Length`, etc.).
- `params` opcional; se codifican como query string.
- `body` opcional; cualquier JSON valido (objeto, array, string...).
- `timeoutMs` opcional; override del read-timeout (el default esta en `application.yml`).

## Respuesta de `/proxy/forward`

```json
{
  "status": 200,
  "elapsedMs": 142,
  "target": "http://192.168.10.48:6708/lista-usuarios?page=1&size=20",
  "headers": {
    "Content-Type": "application/json",
    "X-Server": "uvicorn"
  },
  "body": [
    { "id": 1, "login": "admin" },
    { "id": 2, "login": "operador" }
  ],
  "rawBody": null
}
```

Si el destino devuelve algo distinto a JSON, `body` viene `null` y el texto
queda en `rawBody`.

## Respuesta de `/proxy/raw`

El gateway devuelve el mismo status (p.ej. 201, 404) y el body tal cual llego
(con su Content-Type original). Util cuando el cliente no quiere desenvolver
nada.

## `/proxy/stream` (descargas, preview PDF)

Reenvia la respuesta del destino tal cual (headers + body binario) en
streaming, sin materializarla en memoria. Util para:

- Abrir PDFs en un `<iframe>` sin descargar.
- Mostrar imagenes servidas por un backend interno.
- Descargas grandes (tope configurable con `proxy.max-stream-bytes`).

```
GET /proxy/stream?url=http://127.0.0.1:8096/api/documents/42/file
GET /proxy/stream?url=http://127.0.0.1:8096/api/documents/42/file&token=abc123
```

- `url` **obligatorio**.
- `method` opcional (solo se aceptan `GET` o `HEAD`).
- `token` opcional; sirve para pasar el `shared-secret` cuando la peticion la
  lanza un iframe, que no puede anadir headers. Si se define shared-secret,
  debe venir o por header `X-Proxy-Token` o por este query param.
- Cualquier otro query param del cliente se propaga al destino.
- Los headers `Range`, `If-Modified-Since`, `Authorization`, etc. del cliente
  se propagan automaticamente al destino.
- Los headers `Content-Type`, `Content-Disposition`, `Content-Range`,
  `Accept-Ranges` y `ETag` del destino llegan intactos al navegador.

## `/proxy/upload` (subida de archivos)

Reenvia una peticion `multipart/form-data` al destino. El cliente arma la
multipart como haria contra el servicio final, y el gateway la reconstruye
hacia el destino preservando archivos y campos de texto.

```
POST /proxy/upload?url=http://127.0.0.1:8096/api/documents/manual
Content-Type: multipart/form-data; boundary=----xyz

------xyz
Content-Disposition: form-data; name="file"; filename="factura.pdf"
Content-Type: application/pdf

...bytes...
------xyz
Content-Disposition: form-data; name="document_type"

INVOICE
------xyz--
```

- `url` **obligatorio**.
- `method` opcional (POST/PUT/PATCH/DELETE). Default `POST`.
- `token` opcional (igual que en `/stream`).
- Todos los parts tipo archivo se reenvian; los campos de texto tambien.
- Tope por defecto: 100 MB por peticion (`proxy.max-upload-bytes`).

## Seguridad

1. **Allowlist de hosts** obligatoria (`proxy.allowed-hosts`). Cualquier URL
   que apunte a un host fuera de la lista se rechaza con 403.
   - Acepta `host` (todos los puertos) o `host:puerto` exactos.
   - `*` permite todo; usar solo en desarrollo.
2. **Shared secret opcional** (`proxy.shared-secret`). Si se define, toda
   peticion al gateway debe enviar el header `X-Proxy-Token: <secret>`.
   Los endpoints binarios (`/stream`, `/upload`) tambien aceptan `?token=...`.
3. Redirects y cookies automaticas estan deshabilitados (el cliente decide).
4. Tres topes de tamanio independientes para evitar exhausto de memoria:
   - `proxy.max-response-bytes` -> body JSON en `/forward` y `/raw`.
   - `proxy.max-stream-bytes`   -> tope de `/proxy/stream` (streaming).
   - `proxy.max-upload-bytes`   -> tope total de `/proxy/upload`.
5. **CORS permisivo** por defecto (cualquier origen, con credenciales). La
   defensa real es el shared-secret + allowlist, no CORS. Si quieres
   endurecerlo edita `config/WebConfig.java`.

## Configuracion (`application.yml`)

```yaml
proxy:
  allowed-hosts:
    - 127.0.0.1:14005
    - 192.168.10.48:6708
  connect-timeout-ms: 5000
  read-timeout-ms: 30000
  max-response-bytes: 10485760      # 10 MB   (JSON)
  max-stream-bytes:   209715200     # 200 MB  (/proxy/stream)
  max-upload-bytes:   104857600     # 100 MB  (/proxy/upload)
  shared-secret: ""
```

Todas las claves se pueden sobreescribir con variables de entorno (relaxed
binding de Spring Boot):

```
PROXY_SHAREDSECRET=abc123
PROXY_ALLOWEDHOSTS_0=127.0.0.1:14005
PROXY_ALLOWEDHOSTS_1=192.168.10.48:6708
PROXY_READTIMEOUTMS=45000
PROXY_MAXSTREAMBYTES=209715200
PROXY_MAXUPLOADBYTES=104857600
```

## Build

```bash
cd proxy-gateway
mvn clean package -DskipTests
# salida: target/proxy-gateway.war
```

El WAR pesa ~18 MB (incluye Spring Boot). Copiarlo a `C:\tomcat\webapps\`:

- `proxy-gateway.war` -> contexto `/proxy-gateway` (POST `/proxy-gateway/proxy/forward`).
- Renombrarlo a `ROOT.war` para exponerlo en `/` (POST `/proxy/forward`).

Tomcat lo despliega automaticamente (hot-deploy).

## Ejemplos de uso

### curl - JSON

```bash
curl -X POST https://seguridad.tu-dominio.com/proxy/forward \
  -H "Content-Type: application/json" \
  -H "X-Proxy-Token: abc123" \
  -d '{
        "url": "http://127.0.0.1:14005/api/v1/users",
        "method": "GET",
        "headers": { "Authorization": "Bearer eyJ..." }
      }'
```

### curl - Descargar / previsualizar PDF

```bash
curl -o factura.pdf \
  -H "X-Proxy-Token: abc123" \
  "https://seguridad.tu-dominio.com/proxy/stream?url=http://127.0.0.1:8096/api/documents/42/file"
```

### curl - Subir un PDF con metadatos

```bash
curl -X POST \
  -H "X-Proxy-Token: abc123" \
  -F "file=@factura.pdf;type=application/pdf" \
  -F "document_type=INVOICE" \
  -F "issue_date=2026-04-01" \
  "https://seguridad.tu-dominio.com/proxy/upload?url=http://127.0.0.1:8096/api/documents/manual"
```

### Angular - JSON

```ts
this.http.post<ProxyResponse>('/proxy/forward', {
  url: 'http://192.168.10.48:6708/lista-usuarios',
  method: 'POST',
  headers: { Authorization: `Bearer ${token}` },
  body: { filtro: 'activos' }
}).subscribe(r => console.log(r.status, r.body));
```

### Angular - PDF en iframe

```ts
// component.ts
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

pdfSrc(docId: number): SafeResourceUrl {
  const target = encodeURIComponent(`http://127.0.0.1:8096/api/documents/${docId}/file`);
  return this.sanitizer.bypassSecurityTrustResourceUrl(
    `/proxy/stream?url=${target}&token=abc123`
  );
}
```

```html
<iframe [src]="pdfSrc(doc.id)" width="100%" height="800"></iframe>
```

### Angular - Subir un PDF

```ts
const fd = new FormData();
fd.append('file', file);                       // File del <input type="file">
fd.append('document_type', 'INVOICE');
fd.append('issue_date', '2026-04-01');

const target = encodeURIComponent('http://127.0.0.1:8096/api/documents/manual');
this.http.post(`/proxy/upload?url=${target}`, fd, {
  headers: { 'X-Proxy-Token': 'abc123' }
}).subscribe(resp => console.log(resp));
```

### Python - subir/descargar

```python
import requests

GW = "https://seguridad.tu-dominio.com"
TOKEN = {"X-Proxy-Token": "abc123"}

# Descargar PDF
r = requests.get(
    f"{GW}/proxy/stream",
    params={"url": "http://127.0.0.1:8096/api/documents/42/file"},
    headers=TOKEN, stream=True, timeout=60,
)
r.raise_for_status()
with open("factura.pdf", "wb") as fh:
    for chunk in r.iter_content(1024 * 16):
        fh.write(chunk)

# Subir PDF
with open("nueva.pdf", "rb") as fh:
    r = requests.post(
        f"{GW}/proxy/upload",
        params={"url": "http://127.0.0.1:8096/api/documents/manual"},
        headers=TOKEN,
        files={"file": ("nueva.pdf", fh, "application/pdf")},
        data={"document_type": "INVOICE", "issue_date": "2026-04-01"},
        timeout=120,
    )
print(r.status_code, r.text[:400])
```

## Diagrama

```
cliente (Angular / otro backend / navegador)
      |
      | POST /proxy/forward   { url, method, headers, params, body }        (JSON)
      | POST /proxy/raw       { url, method, ... }                          (JSON passthrough)
      | GET  /proxy/stream?url=...                                          (binario streaming)
      | POST /proxy/upload?url=...  (multipart/form-data)                   (subida)
      v
+---------------------------+
|   Tomcat 9   ROOT.war     |
|   proxy-gateway           |
|   (Spring Boot 2.7.18)    |
|                           |
|  - CORS permisivo         |
|  - valida allowlist       |
|  - valida token opcional  |
|  - HttpClient (pool)      |
|  - streaming / multipart  |
+------------+--------------+
             |
             v  (red interna)
+---------------------------+   +---------------------------+
| 127.0.0.1:14005/api/...   |   | 192.168.10.48:6708/...    |
| FastAPI Seguridad         |   | Otro servicio Python       |
+---------------------------+   +---------------------------+
```

## Versiones

| Componente   | Version |
|--------------|---------|
| Java         | 11      |
| Spring Boot  | 2.7.18  |
| Tomcat       | 9.x     |
| Apache HttpClient | 4.5.14 |
| Apache httpmime   | 4.5.14 |
| Jackson      | heredado de Spring Boot 2.7.18 |
