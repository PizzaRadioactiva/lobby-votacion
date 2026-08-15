package lobby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import lobby.Modelos.*;

public class Servidor {

    // Cambia esta clave si quieres otra contrasena de administrador.
    static final String CLAVE_ADMIN = "admin123";

    static final Almacen almacen = new Almacen();
    static Path carpetaImagenes;
    static Path carpetaWeb;

    public static void main(String[] args) throws Exception {
        int puerto = 8080;
        if (args.length > 0) {
            puerto = Integer.parseInt(args[0]);
        }

        // Carpeta donde se guardan las imagenes subidas por el admin
        carpetaImagenes = Paths.get(System.getProperty("user.dir"), "uploads");
        Files.createDirectories(carpetaImagenes);

        // Carpeta donde estan los archivos estaticos (html/css/js)
        carpetaWeb = Paths.get(System.getProperty("user.dir"), "web");

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);

        // --- Rutas de la API ---
        server.createContext("/api/admin/login", new AdminLoginHandler());
        server.createContext("/api/admin/crear-lobby", new CrearLobbyHandler());
        server.createContext("/api/admin/subir-imagen", new SubirImagenHandler());
        server.createContext("/api/admin/lobby", new AdminLobbyInfoHandler()); // /api/admin/lobby?codigo=xxx
        server.createContext("/api/lobby/unirse", new UnirseLobbyHandler());
        server.createContext("/api/lobby/info", new LobbyInfoHandler());       // /api/lobby/info?codigo=xxx
        server.createContext("/api/lobby/votar", new VotarHandler());
        server.createContext("/api/lobby/resultados", new ResultadosHandler());// /api/lobby/resultados?codigo=xxx

        // --- Imagenes subidas ---
        server.createContext("/uploads/", new ArchivosEstaticosHandler(carpetaImagenes, "/uploads/"));

        // --- Archivos estaticos del frontend (html/css/js) ---
        server.createContext("/", new ArchivosEstaticosHandler(carpetaWeb, "/"));

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("=================================================");
        System.out.println(" Servidor iniciado en http://localhost:" + puerto);
        System.out.println(" Clave de administrador: " + CLAVE_ADMIN);
        System.out.println("=================================================");
    }

    // ---------------------------------------------------------------
    // Utilidades comunes
    // ---------------------------------------------------------------

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String par : query.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv.length == 2) {
                map.put(urlDecode(kv[0]), urlDecode(kv[1]));
            }
        }
        return map;
    }

    static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    static String leerCuerpo(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    static byte[] leerCuerpoBytes(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static void responderJson(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void responderError(HttpExchange ex, int codigo, String mensaje) throws IOException {
        responderJson(ex, codigo, "{\"error\":\"" + escaparJson(mensaje) + "\"}");
    }

    static String escaparJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    // Extrae un campo string simple de un JSON plano tipo {"clave":"valor", ...}
    static String extraerCampo(String json, String campo) {
        String buscar = "\"" + campo + "\"";
        int i = json.indexOf(buscar);
        if (i == -1) return null;
        int dosPuntos = json.indexOf(":", i + buscar.length());
        if (dosPuntos == -1) return null;
        int j = dosPuntos + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        if (json.charAt(j) == '"') {
            StringBuilder sb = new StringBuilder();
            j++;
            while (j < json.length() && json.charAt(j) != '"') {
                char c = json.charAt(j);
                if (c == '\\' && j + 1 < json.length()) {
                    j++;
                    char next = json.charAt(j);
                    if (next == 'n') sb.append('\n');
                    else sb.append(next);
                } else {
                    sb.append(c);
                }
                j++;
            }
            return sb.toString();
        } else {
            // valor no-string (numero, boolean)
            int k = j;
            while (k < json.length() && ",}".indexOf(json.charAt(k)) == -1) k++;
            return json.substring(j, k).trim();
        }
    }

    // ---------------------------------------------------------------
    // Handler: login admin -> POST { "clave": "..." }
    // ---------------------------------------------------------------
    static class AdminLoginHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responderError(ex, 405, "Metodo no permitido");
                return;
            }
            String body = leerCuerpo(ex);
            String clave = extraerCampo(body, "clave");
            if (CLAVE_ADMIN.equals(clave)) {
                responderJson(ex, 200, "{\"ok\":true}");
            } else {
                responderError(ex, 401, "Clave incorrecta");
            }
        }
    }

    // ---------------------------------------------------------------
    // Handler: crear lobby -> POST { "clave": "...", "nombre": "..." }
    // ---------------------------------------------------------------
    static class CrearLobbyHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responderError(ex, 405, "Metodo no permitido");
                return;
            }
            String body = leerCuerpo(ex);
            String clave = extraerCampo(body, "clave");
            if (!CLAVE_ADMIN.equals(clave)) {
                responderError(ex, 401, "No autorizado");
                return;
            }
            String nombre = extraerCampo(body, "nombre");
            if (nombre == null || nombre.isBlank()) nombre = "Votacion sin nombre";

            Lobby lobby = almacen.crearLobby(nombre);
            String json = "{\"ok\":true,\"codigo\":\"" + lobby.codigo + "\"}";
            responderJson(ex, 200, json);
        }
    }

    // ---------------------------------------------------------------
    // Handler: subir imagen -> POST multipart/form-data
    // Campos esperados: clave, codigo, descripcion, archivo
    // ---------------------------------------------------------------
    static class SubirImagenHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responderError(ex, 405, "Metodo no permitido");
                return;
            }
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                responderError(ex, 400, "Se esperaba multipart/form-data");
                return;
            }
            String boundary = extraerBoundary(contentType);
            if (boundary == null) {
                responderError(ex, 400, "Boundary no encontrado");
                return;
            }
            byte[] cuerpo = leerCuerpoBytes(ex);
            Map<String, String> camposTexto = new HashMap<>();
            byte[][] archivoDatos = new byte[1][];
            String[] archivoNombre = new String[1];

            MultipartParser.parse(cuerpo, boundary, camposTexto, archivoDatos, archivoNombre);

            String clave = camposTexto.get("clave");
            if (!CLAVE_ADMIN.equals(clave)) {
                responderError(ex, 401, "No autorizado");
                return;
            }
            String codigo = camposTexto.get("codigo");
            String descripcion = camposTexto.getOrDefault("descripcion", "");

            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }
            if (archivoDatos[0] == null || archivoDatos[0].length == 0) {
                responderError(ex, 400, "No se recibio ninguna imagen");
                return;
            }

            String ext = "";
            String nombreOriginal = archivoNombre[0];
            if (nombreOriginal != null && nombreOriginal.contains(".")) {
                ext = nombreOriginal.substring(nombreOriginal.lastIndexOf('.'));
            }
            String idImagen = UUID.randomUUID().toString().substring(0, 12);
            String nombreArchivo = idImagen + ext;

            Path destino = carpetaImagenes.resolve(nombreArchivo);
            Files.write(destino, archivoDatos[0]);

            Imagen img = new Imagen(idImagen, nombreArchivo, descripcion);
            synchronized (lobby) {
                lobby.imagenes.add(img);
            }

            responderJson(ex, 200, "{\"ok\":true,\"id\":\"" + idImagen + "\"}");
        }

        private String extraerBoundary(String contentType) {
            for (String parte : contentType.split(";")) {
                parte = parte.trim();
                if (parte.startsWith("boundary=")) {
                    String b = parte.substring("boundary=".length());
                    if (b.startsWith("\"") && b.endsWith("\"")) {
                        b = b.substring(1, b.length() - 1);
                    }
                    return b;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Handler: info de lobby para el admin (incluye descripciones e imagenes)
    // GET /api/admin/lobby?codigo=xxx&clave=xxx
    // ---------------------------------------------------------------
    static class AdminLobbyInfoHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
            String clave = q.get("clave");
            if (!CLAVE_ADMIN.equals(clave)) {
                responderError(ex, 401, "No autorizado");
                return;
            }
            String codigo = q.get("codigo");
            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }
            responderJson(ex, 200, lobbyAJson(lobby, true));
        }
    }

    // ---------------------------------------------------------------
    // Handler: unirse a un lobby como usuario -> POST { "codigo":"...", "nombre":"..." }
    // ---------------------------------------------------------------
    static class UnirseLobbyHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responderError(ex, 405, "Metodo no permitido");
                return;
            }
            String body = leerCuerpo(ex);
            String codigo = extraerCampo(body, "codigo");
            String nombre = extraerCampo(body, "nombre");
            if (nombre == null || nombre.isBlank()) nombre = "Anonimo";

            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado. Revisa el link.");
                return;
            }
            synchronized (lobby) {
                lobby.usuariosConectados.add(nombre);
            }
            responderJson(ex, 200, "{\"ok\":true,\"codigo\":\"" + lobby.codigo + "\",\"nombre\":\"" + escaparJson(lobby.nombre) + "\"}");
        }
    }

    // ---------------------------------------------------------------
    // Handler: info publica del lobby (para pantalla de votacion)
    // GET /api/lobby/info?codigo=xxx
    // ---------------------------------------------------------------
    static class LobbyInfoHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
            String codigo = q.get("codigo");
            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }
            responderJson(ex, 200, lobbyAJson(lobby, false));
        }
    }

    // ---------------------------------------------------------------
    // Handler: votar -> POST { "codigo":"...", "imagenId":"...", "usuario":"...", "voto": true/false }
    // ---------------------------------------------------------------
    static class VotarHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
                responderError(ex, 405, "Metodo no permitido");
                return;
            }
            String body = leerCuerpo(ex);
            String codigo = extraerCampo(body, "codigo");
            String imagenId = extraerCampo(body, "imagenId");
            String usuario = extraerCampo(body, "usuario");
            String votoStr = extraerCampo(body, "voto");

            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }
            Imagen img = lobby.buscarImagen(imagenId);
            if (img == null) {
                responderError(ex, 404, "Imagen no encontrada");
                return;
            }
            boolean voto = "true".equalsIgnoreCase(votoStr);
            img.votar(usuario == null ? "Anonimo" : usuario, voto);

            responderJson(ex, 200, "{\"ok\":true}");
        }
    }

    // ---------------------------------------------------------------
    // Handler: resultados -> GET /api/lobby/resultados?codigo=xxx
    // ---------------------------------------------------------------
    static class ResultadosHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
            String codigo = q.get("codigo");
            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"nombre\":\"").append(escaparJson(lobby.nombre)).append("\",");
            sb.append("\"totalUsuarios\":").append(lobby.usuariosConectados.size()).append(",");
            sb.append("\"imagenes\":[");
            boolean primero = true;
            for (Imagen img : lobby.imagenes) {
                if (!primero) sb.append(",");
                primero = false;
                sb.append("{");
                sb.append("\"id\":\"").append(img.id).append("\",");
                sb.append("\"descripcion\":\"").append(escaparJson(img.descripcion)).append("\",");
                sb.append("\"url\":\"/uploads/").append(img.nombreArchivo).append("\",");
                sb.append("\"totalSi\":").append(img.totalSi()).append(",");
                sb.append("\"totalNo\":").append(img.totalNo()).append(",");
                sb.append("\"totalVotos\":").append(img.totalVotos()).append(",");
                sb.append("\"porcentajeSi\":").append(String.format(Locale.US, "%.1f", img.porcentajeSi())).append(",");
                sb.append("\"votantes\":[");
                boolean p2 = true;
                for (Map.Entry<String, Boolean> e : img.votos.entrySet()) {
                    if (!p2) sb.append(",");
                    p2 = false;
                    sb.append("{\"usuario\":\"").append(escaparJson(e.getKey())).append("\",");
                    sb.append("\"voto\":").append(e.getValue()).append("}");
                }
                sb.append("]");
                sb.append("}");
            }
            sb.append("]}");

            responderJson(ex, 200, sb.toString());
        }
    }

    // ---------------------------------------------------------------
    // Convierte un Lobby a JSON. incluirDescripcion siempre true ahora
    // (las descripciones son publicas para que el usuario sepa que vota)
    // ---------------------------------------------------------------
    static String lobbyAJson(Lobby lobby, boolean vistaAdmin) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"codigo\":\"").append(lobby.codigo).append("\",");
        sb.append("\"nombre\":\"").append(escaparJson(lobby.nombre)).append("\",");
        sb.append("\"totalUsuarios\":").append(lobby.usuariosConectados.size()).append(",");
        sb.append("\"imagenes\":[");
        boolean primero = true;
        for (Imagen img : lobby.imagenes) {
            if (!primero) sb.append(",");
            primero = false;
            sb.append("{");
            sb.append("\"id\":\"").append(img.id).append("\",");
            sb.append("\"descripcion\":\"").append(escaparJson(img.descripcion)).append("\",");
            sb.append("\"url\":\"/uploads/").append(img.nombreArchivo).append("\",");
            sb.append("\"totalVotos\":").append(img.totalVotos());
            if (vistaAdmin) {
                sb.append(",\"totalSi\":").append(img.totalSi());
                sb.append(",\"totalNo\":").append(img.totalNo());
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Handler para servir archivos estaticos (html/css/js e imagenes subidas)
    // ---------------------------------------------------------------
    static class ArchivosEstaticosHandler implements HttpHandler {
        private final Path base;
        private final String prefijo;

        ArchivosEstaticosHandler(Path base, String prefijo) {
            this.base = base;
            this.prefijo = prefijo;
        }

        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String rel = path.substring(prefijo.length());
            if (rel.isEmpty() || rel.equals("/")) rel = "index.html";

            // Rutas amigables para paginas del frontend
            if (prefijo.equals("/")) {
                if (path.startsWith("/lobby/")) {
                    rel = "votar.html";
                } else if (path.equals("/admin")) {
                    rel = "admin.html";
                }
            }

            Path archivo = base.resolve(rel).normalize();
            if (!archivo.startsWith(base) || !Files.exists(archivo) || Files.isDirectory(archivo)) {
                String msg = "404 - No encontrado";
                byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                return;
            }

            String mime = detectarMime(archivo.toString());
            byte[] datos = Files.readAllBytes(archivo);
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.sendResponseHeaders(200, datos.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(datos);
            }
        }

        private String detectarMime(String nombre) {
            String n = nombre.toLowerCase();
            if (n.endsWith(".html")) return "text/html; charset=utf-8";
            if (n.endsWith(".css")) return "text/css; charset=utf-8";
            if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (n.endsWith(".png")) return "image/png";
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
            if (n.endsWith(".gif")) return "image/gif";
            if (n.endsWith(".webp")) return "image/webp";
            if (n.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }
}
