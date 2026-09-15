package lobby;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Parser simple de multipart/form-data escrito a mano, sin dependencias externas.
 * Soporta campos de texto normales y UN archivo (campo "archivo").
 */
public class MultipartParser {

    public static void parse(byte[] cuerpo, String boundary,
                              Map<String, String> camposTexto,
                              byte[][] archivoDatosOut,
                              String[] archivoNombreOut) {

        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] delimFinal = ("--" + boundary + "--").getBytes(StandardCharsets.US_ASCII);

        int pos = indexOf(cuerpo, delim, 0);
        while (pos != -1) {
            int inicioParte = pos + delim.length;
            // saltar CRLF despues del boundary
            if (inicioParte + 1 < cuerpo.length && cuerpo[inicioParte] == '\r' && cuerpo[inicioParte + 1] == '\n') {
                inicioParte += 2;
            }
            int siguientePos = indexOf(cuerpo, delim, inicioParte);
            if (siguientePos == -1) break;

            // el final de esta parte es justo antes del siguiente boundary, menos el CRLF final
            int finParte = siguientePos;
            if (finParte >= 2 && cuerpo[finParte - 2] == '\r' && cuerpo[finParte - 1] == '\n') {
                finParte -= 2;
            }

            if (finParte > inicioParte) {
                procesarParte(cuerpo, inicioParte, finParte, camposTexto, archivoDatosOut, archivoNombreOut);
            }

            pos = siguientePos;
            // si lo que sigue es el delimitador final, terminamos
            if (matchAt(cuerpo, pos, delimFinal)) break;
        }
    }

    private static void procesarParte(byte[] cuerpo, int inicio, int fin,
                                       Map<String, String> camposTexto,
                                       byte[][] archivoDatosOut,
                                       String[] archivoNombreOut) {
        // Buscar fin de cabeceras (\r\n\r\n)
        byte[] sepCabecera = {'\r', '\n', '\r', '\n'};
        int finCabeceras = indexOf(cuerpo, sepCabecera, inicio);
        if (finCabeceras == -1 || finCabeceras > fin) return;

        String cabeceras = new String(cuerpo, inicio, finCabeceras - inicio, StandardCharsets.UTF_8);
        int inicioDatos = finCabeceras + 4;

        String nombreCampo = extraerAtributo(cabeceras, "name");
        String nombreArchivo = extraerAtributo(cabeceras, "filename");

        if (nombreArchivo != null && !nombreArchivo.isEmpty()) {
            byte[] datos = new byte[fin - inicioDatos];
            System.arraycopy(cuerpo, inicioDatos, datos, 0, fin - inicioDatos);
            archivoDatosOut[0] = datos;
            archivoNombreOut[0] = nombreArchivo;
        } else if (nombreCampo != null) {
            String valor = new String(cuerpo, inicioDatos, fin - inicioDatos, StandardCharsets.UTF_8);
            camposTexto.put(nombreCampo, valor);
        }
    }

    private static String extraerAtributo(String cabeceras, String atributo) {
        String buscar = atributo + "=\"";
        int i = cabeceras.indexOf(buscar);
        if (i == -1) return null;
        int inicio = i + buscar.length();
        int fin = cabeceras.indexOf("\"", inicio);
        if (fin == -1) return null;
        return cabeceras.substring(inicio, fin);
    }

    private static int indexOf(byte[] datos, byte[] patron, int desde) {
        outer:
        for (int i = desde; i <= datos.length - patron.length; i++) {
            for (int j = 0; j < patron.length; j++) {
                if (datos[i + j] != patron[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean matchAt(byte[] datos, int pos, byte[] patron) {
        if (pos + patron.length > datos.length) return false;
        for (int j = 0; j < patron.length; j++) {
            if (datos[pos + j] != patron[j]) return false;
        }
        return true;
    }
}
