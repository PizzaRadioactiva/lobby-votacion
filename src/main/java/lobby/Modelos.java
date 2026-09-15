package lobby;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contiene todas las clases de modelo usadas por la aplicacion.
 * Todo se guarda en memoria (se pierde al reiniciar el servidor).
 */
public class Modelos {

    /** Maximo de imagenes que puede subir cada participante (no el admin). */
    public static final int MAX_IMAGENES_POR_PARTICIPANTE = 5;

    /** Cuanto tiempo se muestra la revelacion (autor + estadisticas) antes de pasar a la siguiente imagen. */
    public static final long DURACION_REVELACION_MS = 7000L;

    /** Una imagen dentro de un lobby, con su descripcion y sus votos. */
    public static class Imagen {
        public String id;
        public String nombreArchivo;   // nombre del archivo guardado en disco
        public String descripcion;
        // null/blank = la subio el administrador. Si no, es el nombre del participante que la subio.
        public String subidoPor;
        // votante -> true (si) / false (no)
        public Map<String, Boolean> votos = new LinkedHashMap<>();
        // votante -> nombre del participante que cree que subio la imagen
        public Map<String, String> adivinanzas = new LinkedHashMap<>();

        public Imagen(String id, String nombreArchivo, String descripcion, String subidoPor) {
            this.id = id;
            this.nombreArchivo = nombreArchivo;
            this.descripcion = descripcion;
            this.subidoPor = (subidoPor == null || subidoPor.isBlank()) ? null : subidoPor;
        }

        public synchronized void votar(String usuario, boolean valor) {
            votos.put(usuario, valor);
        }

        public synchronized void votarAdivinanza(String usuario, String elegido) {
            adivinanzas.put(usuario, elegido);
        }

        /** Si esta imagen requiere que "usuario" adivine el autor (nadie adivina su propia imagen ni las del admin). */
        public boolean requiereAdivinanza(String usuario) {
            if (subidoPor == null) return false;
            if (usuario == null) return false;
            return !subidoPor.equals(usuario);
        }

        public synchronized int totalSi() {
            int c = 0;
            for (boolean v : votos.values()) if (v) c++;
            return c;
        }

        public synchronized int totalNo() {
            int c = 0;
            for (boolean v : votos.values()) if (!v) c++;
            return c;
        }

        public synchronized int totalVotos() {
            return votos.size();
        }

        public synchronized double porcentajeSi() {
            if (votos.isEmpty()) return 0.0;
            return (totalSi() * 100.0) / votos.size();
        }

        public synchronized int totalAdivinanzas() {
            return adivinanzas.size();
        }

        /** nombre elegido -> cuantos votantes lo eligieron. */
        public synchronized Map<String, Integer> estadisticasAdivinanzas() {
            Map<String, Integer> stats = new LinkedHashMap<>();
            for (String elegido : adivinanzas.values()) {
                stats.merge(elegido, 1, Integer::sum);
            }
            return stats;
        }
    }

    /** Un lobby de votacion creado por el admin. */
    public static class Lobby {
        public String codigo;              // identificador unico usado en el link /lobby/{codigo}
        public String nombre;
        public List<Imagen> imagenes = new ArrayList<>();
        public Set<String> usuariosConectados = new LinkedHashSet<>();
        public boolean activo = true;

        // --- Estado de la votacion sincronizada ---
        // "carga"    -> se pueden subir imagenes (admin y participantes)
        // "votando"  -> orden fijo y aleatorio, una imagen a la vez para todos
        // "terminado"-> ya se paso por todas las imagenes
        public String fase = "carga";
        public List<String> ordenIds = new ArrayList<>();
        public int indiceActual = 0;
        public boolean revelada = false;
        public long reveladaEnMillis = 0L;

        public Lobby(String codigo, String nombre) {
            this.codigo = codigo;
            this.nombre = nombre;
        }

        public synchronized Imagen buscarImagen(String id) {
            for (Imagen img : imagenes) {
                if (img.id.equals(id)) return img;
            }
            return null;
        }

        public synchronized int totalSubidasDe(String usuario) {
            if (usuario == null) return 0;
            int c = 0;
            for (Imagen img : imagenes) {
                if (usuario.equals(img.subidoPor)) c++;
            }
            return c;
        }

        /** Id de la imagen actual segun el orden sincronizado, o null si no aplica. */
        public synchronized String imagenActualId() {
            if (!"votando".equals(fase)) return null;
            if (indiceActual < 0 || indiceActual >= ordenIds.size()) return null;
            return ordenIds.get(indiceActual);
        }

        /**
         * Si ya paso el tiempo de revelacion, avanza a la siguiente imagen (o termina la votacion).
         * Debe llamarse con el lock del lobby tomado (metodo synchronized).
         */
        public synchronized void avanzarSiCorresponde() {
            if (!"votando".equals(fase)) return;
            if (!revelada) return;
            if (System.currentTimeMillis() - reveladaEnMillis < DURACION_REVELACION_MS) return;

            indiceActual++;
            revelada = false;
            reveladaEnMillis = 0L;
            if (indiceActual >= ordenIds.size()) {
                fase = "terminado";
            }
        }

        /** Todos los usuarios conectados ya completaron lo que se les pedia en esta imagen. */
        public synchronized boolean todosCompletaron(Imagen img) {
            if (usuariosConectados.isEmpty()) return false;
            for (String u : usuariosConectados) {
                if (!img.votos.containsKey(u)) return false;
                if (img.requiereAdivinanza(u) && !img.adivinanzas.containsKey(u)) return false;
            }
            return true;
        }
    }

    /** Almacen central en memoria de toda la app. */
    public static class Almacen {
        public Map<String, Lobby> lobbies = new ConcurrentHashMap<>();

        public Lobby crearLobby(String nombre) {
            String codigo = generarCodigo();
            Lobby l = new Lobby(codigo, nombre);
            lobbies.put(codigo, l);
            return l;
        }

        public Lobby obtener(String codigo) {
            return lobbies.get(codigo);
        }

        private String generarCodigo() {
            String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
            Random r = new Random();
            String codigo;
            do {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    sb.append(chars.charAt(r.nextInt(chars.length())));
                }
                codigo = sb.toString();
            } while (lobbies.containsKey(codigo));
            return codigo;
        }
    }
}
