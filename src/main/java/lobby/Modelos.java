package lobby;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contiene todas las clases de modelo usadas por la aplicacion.
 * Todo se guarda en memoria (se pierde al reiniciar el servidor).
 */
public class Modelos {

    /** Una imagen dentro de un lobby, con su descripcion y sus votos. */
    public static class Imagen {
        public String id;
        public String nombreArchivo;   // nombre del archivo guardado en disco
        public String descripcion;
        // votante -> true (si) / false (no)
        public Map<String, Boolean> votos = new LinkedHashMap<>();

        public Imagen(String id, String nombreArchivo, String descripcion) {
            this.id = id;
            this.nombreArchivo = nombreArchivo;
            this.descripcion = descripcion;
        }

        public synchronized void votar(String usuario, boolean valor) {
            votos.put(usuario, valor);
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
    }

    /** Un lobby de votacion creado por el admin. */
    public static class Lobby {
        public String codigo;              // identificador unico usado en el link /lobby/{codigo}
        public String nombre;
        public List<Imagen> imagenes = new ArrayList<>();
        public Set<String> usuariosConectados = new LinkedHashSet<>();
        public boolean activo = true;

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
