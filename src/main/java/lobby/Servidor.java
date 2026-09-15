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

        // Carpeta donde se guardan las imagenes subidas (por el admin y por los participantes)
        carpetaImagenes = Paths.get(System.getProperty("user.dir"), "uploads");
        Files.createDirectories(carpetaImagenes);

        // Carpeta donde estan los archivos estaticos (html/css/js)
        carpetaWeb = Paths.get(System.getProperty("user.dir"), "web");

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);

        // --- Rutas de la API ---
        server.createContext("/api/admin/login", new AdminLoginHandler());
        server.createContext("/api/admin/crear-lobby", new CrearLobbyHandler());
        server.createContext("/api/admin/subir-imagen", new SubirImagenAdminHandler());
        server.createContext("/api/admin/lobby", new AdminLobbyInfoHandler()); // /api/admin/lobby?codigo=xxx
        server.createContext("/api/admin/iniciar-votacion", new IniciarVotacionHandler());
        server.createContext("/api/lobby/unirse", new UnirseLobbyHandler());
        server.createContext("/api/lobby/info", new LobbyInfoHandler());       // /api/lobby/info?codigo=xxx
        server.createContext("/api/lobby/subir-imagen", new SubirImagenParticipanteHandler());
        server.createContext("/api/lobby/estado", new EstadoHandler());       // /api/lobby/estado?codigo=xxx&usuario=xxx
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

    static String jsonONull(String s) {
        return s == null ? "null" : "\"" + escaparJson(s) + "\"";
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
            String valor = json.substring(j, k).trim();
            if ("null".equals(valor)) return null;
            return valor;
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
    // Handler: subir imagen (ADMIN) -> POST multipart/form-data
    // Campos esperados: clave, codigo, descripcion, archivo
    // ---------------------------------------------------------------
    static class SubirImagenAdminHandler implements HttpHandler {
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
            if (!"carga".equals(lobby.fase)) {
                responderError(ex, 409, "La votacion ya fue iniciada, no se pueden agregar mas imagenes");
                return;
            }
            if (archivoDatos[0] == null || archivoDatos[0].length == 0) {
                responderError(ex, 400, "No se recibio ninguna imagen");
                return;
            }

            String nombreArchivo = guardarArchivo(archivoDatos[0], archivoNombre[0]);
            String idImagen = nombreArchivo.contains(".") ? nombreArchivo.substring(0, nombreArchivo.indexOf('.')) : nombreArchivo;

            Imagen img = new Imagen(idImagen, nombreArchivo, descripcion, null);
            synchronized (lobby) {
                lobby.imagenes.add(img);
            }

            responderJson(ex, 200, "{\"ok\":true,\"id\":\"" + idImagen + "\"}");
        }
    }

    // ---------------------------------------------------------------
    // Handler: subir imagen (PARTICIPANTE) -> POST multipart/form-data
    // Campos esperados: codigo, nombre, descripcion, archivo
    // Sin clave de admin. Maximo Modelos.MAX_IMAGENES_POR_PARTICIPANTE por persona.
    // Solo permitido mientras el lobby esta en fase "carga".
    // ---------------------------------------------------------------
    static class SubirImagenParticipanteHandler implements HttpHandler {
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

            String codigo = camposTexto.get("codigo");
            String nombre = camposTexto.get("nombre");
            String descripcion = camposTexto.getOrDefault("descripcion", "");

            if (nombre == null || nombre.isBlank()) {
                responderError(ex, 400, "Falta tu nombre");
                return;
            }
            nombre = nombre.trim();

            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }
            if (!"carga".equals(lobby.fase)) {
                responderError(ex, 409, "La votacion ya fue iniciada, ya no se pueden agregar imagenes");
                return;
            }
            if (archivoDatos[0] == null || archivoDatos[0].length == 0) {
                responderError(ex, 400, "No se recibio ninguna imagen");
                return;
            }

            int subidasPrevias;
            synchronized (lobby) {
                lobby.usuariosConectados.add(nombre);
                subidasPrevias = lobby.totalSubidasDe(nombre);
            }
            if (subidasPrevias >= Modelos.MAX_IMAGENES_POR_PARTICIPANTE) {
                responderError(ex, 400, "Ya subiste el maximo de " + Modelos.MAX_IMAGENES_POR_PARTICIPANTE + " imagenes");
                return;
            }

            String nombreArchivo = guardarArchivo(archivoDatos[0], archivoNombre[0]);
            String idImagen = nombreArchivo.contains(".") ? nombreArchivo.substring(0, nombreArchivo.indexOf('.')) : nombreArchivo;

            Imagen img = new Imagen(idImagen, nombreArchivo, descripcion, nombre);
            int totalAhora;
            synchronized (lobby) {
                lobby.imagenes.add(img);
                totalAhora = lobby.totalSubidasDe(nombre);
            }

            responderJson(ex, 200, "{\"ok\":true,\"id\":\"" + idImagen + "\",\"misImagenes\":" + totalAhora
                    + ",\"maxImagenes\":" + Modelos.MAX_IMAGENES_POR_PARTICIPANTE + "}");
        }
    }

    private static String guardarArchivo(byte[] datos, String nombreOriginal) throws IOException {
        String ext = "";
        if (nombreOriginal != null && nombreOriginal.contains(".")) {
            ext = nombreOriginal.substring(nombreOriginal.lastIndexOf('.'));
        }
        String idImagen = UUID.randomUUID().toString().substring(0, 12);
        String nombreArchivo = idImagen + ext;
        Path destino = carpetaImagenes.resolve(nombreArchivo);
        Files.write(destino, datos);
        return nombreArchivo;
    }

    private static String extraerBoundary(String contentType) {
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

    // ---------------------------------------------------------------
    // Handler: info de lobby para el admin (incluye descripciones, autor e imagenes)
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
    // Handler: iniciar votacion -> POST { "clave": "...", "codigo": "..." }
    // Fija el orden aleatorio (igual para todos) y pasa el lobby a fase "votando".
    // ---------------------------------------------------------------
    static class IniciarVotacionHandler implements HttpHandler {
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
            String codigo = extraerCampo(body, "codigo");
            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }
            synchronized (lobby) {
                if (!"carga".equals(lobby.fase)) {
                    responderError(ex, 409, "Esta votacion ya fue iniciada");
                    return;
                }
                if (lobby.imagenes.isEmpty()) {
                    responderError(ex, 400, "Agrega al menos una imagen antes de iniciar la votacion");
                    return;
                }
                List<String> ids = new ArrayList<>();
                for (Imagen img : lobby.imagenes) ids.add(img.id);
                Collections.shuffle(ids);
                lobby.ordenIds = ids;
                lobby.indiceActual = 0;
                lobby.revelada = false;
                lobby.reveladaEnMillis = 0L;
                lobby.fase = "votando";
            }
            responderJson(ex, 200, "{\"ok\":true}");
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
            responderJson(ex, 200, "{\"ok\":true,\"codigo\":\"" + lobby.codigo + "\",\"nombre\":\"" + escaparJson(lobby.nombre) + "\",\"fase\":\"" + lobby.fase + "\"}");
        }
    }

    // ---------------------------------------------------------------
    // Handler: info publica del lobby (lista completa, no sincronizada)
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
    // Handler: estado sincronizado de la votacion (polling desde votar.html)
    // GET /api/lobby/estado?codigo=xxx&usuario=xxx
    // ---------------------------------------------------------------
    static class EstadoHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
            String codigo = q.get("codigo");
            String usuario = q.get("usuario");
            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }

            synchronized (lobby) {
                lobby.avanzarSiCorresponde();

                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"fase\":\"").append(lobby.fase).append("\",");
                sb.append("\"nombreLobby\":\"").append(escaparJson(lobby.nombre)).append("\"");

                if ("carga".equals(lobby.fase)) {
                    sb.append(",\"totalImagenes\":").append(lobby.imagenes.size()).append(",");
                    sb.append("\"misImagenes\":").append(lobby.totalSubidasDe(usuario)).append(",");
                    sb.append("\"maxImagenes\":").append(Modelos.MAX_IMAGENES_POR_PARTICIPANTE).append(",");
                    sb.append("\"imagenes\":[");
                    boolean primero = true;
                    for (Imagen img : lobby.imagenes) {
                        if (!primero) sb.append(",");
                        primero = false;
                        sb.append("{\"id\":\"").append(img.id).append("\",");
                        sb.append("\"url\":\"/uploads/").append(img.nombreArchivo).append("\",");
                        sb.append("\"descripcion\":\"").append(escaparJson(img.descripcion)).append("\",");
                        sb.append("\"subidoPor\":").append(jsonONull(img.subidoPor)).append("}");
                    }
                    sb.append("]");
                } else if ("votando".equals(lobby.fase)) {
                    String idActual = lobby.imagenActualId();
                    Imagen img = idActual == null ? null : lobby.buscarImagen(idActual);
                    sb.append(",\"indiceActual\":").append(lobby.indiceActual).append(",");
                    sb.append("\"totalImagenes\":").append(lobby.ordenIds.size()).append(",");

                    if (img == null) {
                        sb.append("\"imagen\":null,\"revelada\":false");
                    } else {
                        boolean requiere = img.requiereAdivinanza(usuario);
                        boolean yoVote = usuario != null && img.votos.containsKey(usuario);
                        boolean yoAdivine = usuario != null && img.adivinanzas.containsKey(usuario);

                        sb.append("\"imagen\":{");
                        sb.append("\"id\":\"").append(img.id).append("\",");
                        sb.append("\"descripcion\":\"").append(escaparJson(img.descripcion)).append("\",");
                        sb.append("\"url\":\"/uploads/").append(img.nombreArchivo).append("\",");
                        sb.append("\"requiereAdivinanza\":").append(requiere);
                        sb.append("},");
                        sb.append("\"yoVote\":").append(yoVote).append(",");
                        sb.append("\"yoAdivine\":").append(yoAdivine).append(",");

                        sb.append("\"participantesParaAdivinar\":[");
                        boolean p1 = true;
                        for (String u : lobby.usuariosConectados) {
                            if (usuario != null && usuario.equals(u)) continue;
                            if (!p1) sb.append(",");
                            p1 = false;
                            sb.append("\"").append(escaparJson(u)).append("\"");
                        }
                        sb.append("],");

                        sb.append("\"revelada\":").append(lobby.revelada);
                        if (lobby.revelada) {
                            Map<String, Integer> stats = img.estadisticasAdivinanzas();
                            sb.append(",\"revelacion\":{");
                            sb.append("\"subidoPor\":").append(jsonONull(img.subidoPor)).append(",");
                            sb.append("\"totalSi\":").append(img.totalSi()).append(",");
                            sb.append("\"totalNo\":").append(img.totalNo()).append(",");
                            sb.append("\"totalVotos\":").append(img.totalVotos()).append(",");
                            sb.append("\"totalAdivinanzas\":").append(img.totalAdivinanzas()).append(",");
                            sb.append("\"estadisticasAdivinanza\":[");
                            boolean p2 = true;
                            for (Map.Entry<String, Integer> e : stats.entrySet()) {
                                if (!p2) sb.append(",");
                                p2 = false;
                                sb.append("{\"nombre\":\"").append(escaparJson(e.getKey())).append("\",");
                                sb.append("\"votos\":").append(e.getValue()).append("}");
                            }
                            sb.append("]}");
                        }
                    }
                }
                // fase "terminado" no necesita datos extra
                sb.append("}");

                responderJson(ex, 200, sb.toString());
            }
        }
    }

    // ---------------------------------------------------------------
    // Handler: votar -> POST { "codigo":"...", "imagenId":"...", "usuario":"...",
    //                          "voto": true/false, "adivinanza": "Nombre" (opcional) }
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
            String adivinanza = extraerCampo(body, "adivinanza");

            if (usuario == null || usuario.isBlank()) {
                responderError(ex, 400, "Falta el usuario");
                return;
            }

            Lobby lobby = almacen.obtener(codigo);
            if (lobby == null) {
                responderError(ex, 404, "Lobby no encontrado");
                return;
            }

            boolean revelo;
            synchronized (lobby) {
                lobby.avanzarSiCorresponde();

                if (!"votando".equals(lobby.fase)) {
                    responderError(ex, 409, "La votacion no esta activa en este momento");
                    return;
                }
                String idActual = lobby.imagenActualId();
                if (idActual == null || !idActual.equals(imagenId)) {
                    responderError(ex, 409, "Esta imagen ya no esta activa, actualiza la pantalla");
                    return;
                }
                Imagen img = lobby.buscarImagen(imagenId);
                if (img == null) {
                    responderError(ex, 404, "Imagen no encontrada");
                    return;
                }
                if (!lobby.usuariosConectados.contains(usuario)) {
                    lobby.usuariosConectados.add(usuario);
                }

                boolean voto = "true".equalsIgnoreCase(votoStr);
                img.votar(usuario, voto);

                if (img.requiereAdivinanza(usuario)) {
                    if (adivinanza == null || adivinanza.isBlank() || adivinanza.equals(usuario)
                            || !lobby.usuariosConectados.contains(adivinanza)) {
                        responderError(ex, 400, "Elige a un participante valido para adivinar quien subio la imagen");
                        return;
                    }
                    img.votarAdivinanza(usuario, adivinanza);
                }

                if (!lobby.revelada && lobby.todosCompletaron(img)) {
                    lobby.revelada = true;
                    lobby.reveladaEnMillis = System.currentTimeMillis();
                }
                revelo = lobby.revelada;
            }

            responderJson(ex, 200, "{\"ok\":true,\"revelada\":" + revelo + "}");
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
                sb.append("\"subidoPor\":").append(jsonONull(img.subidoPor)).append(",");
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
                sb.append("],");
                sb.append("\"estadisticasAdivinanza\":[");
                boolean p3 = true;
                for (Map.Entry<String, Integer> e : img.estadisticasAdivinanzas().entrySet()) {
                    if (!p3) sb.append(",");
                    p3 = false;
                    sb.append("{\"nombre\":\"").append(escaparJson(e.getKey())).append("\",");
                    sb.append("\"votos\":").append(e.getValue()).append("}");
                }
                sb.append("]");
                sb.append("}");
            }
            sb.append("]}");

            responderJson(ex, 200, sb.toString());
        }
    }

    // ---------------------------------------------------------------
    // Convierte un Lobby a JSON. vistaAdmin incluye Si/No y autor de cada imagen.
    // ---------------------------------------------------------------
    static String lobbyAJson(Lobby lobby, boolean vistaAdmin) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"codigo\":\"").append(lobby.codigo).append("\",");
        sb.append("\"nombre\":\"").append(escaparJson(lobby.nombre)).append("\",");
        sb.append("\"fase\":\"").append(lobby.fase).append("\",");
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
                sb.append(",\"subidoPor\":").append(jsonONull(img.subidoPor));
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
