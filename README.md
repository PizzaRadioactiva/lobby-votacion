# Sala de Votacion — Proyecto Java + HTML + CSS

Sistema de votacion de imagenes con lobby. El **administrador** crea una sala,
sube imagenes con descripcion y comparte un link. Los **usuarios** entran con
ese link y votan **Si / No** en cada imagen. Al final se puede ver una tabla
de resultados con el promedio de votos y quien voto que.

No usa frameworks ni librerias externas: el backend es Java puro (con la
clase `com.sun.net.httpserver.HttpServer` que trae el propio JDK) y el
frontend es HTML + CSS + JavaScript planos. Los datos se guardan **en
memoria**, es decir, se borran cada vez que reinicias el servidor.

---

## 1. Estructura del proyecto

```
lobby-votacion/
├── src/main/java/lobby/
│   ├── Servidor.java        <- servidor HTTP y todas las rutas de la API
│   ├── Modelos.java         <- clases Lobby, Imagen, Almacen (memoria)
│   └── MultipartParser.java <- lee las imagenes subidas por el admin
├── web/
│   ├── index.html   <- pagina de inicio (elegir admin o usuario)
│   ├── admin.html   <- panel de administrador
│   ├── votar.html   <- pantalla de votacion para el usuario
│   └── estilo.css   <- estilos compartidos
├── uploads/          <- aqui se guardan las imagenes que sube el admin
└── README.md
```

## 2. Requisitos

Solo necesitas tener instalado el **JDK 17 o superior** (no hace falta Maven,
Gradle, ni Spring Boot). Puedes revisar tu version con:

```bash
java -version
javac -version
```

Si no lo tienes, descarga el JDK gratis desde [Adoptium](https://adoptium.net/).

## 3. Como correrlo en tu computadora

Desde la carpeta `lobby-votacion`:

```bash
# 1. Compilar
javac -d out src/main/java/lobby/*.java

# 2. Ejecutar (por defecto usa el puerto 8080)
java -cp out lobby.Servidor 8080
```

Veras un mensaje asi en la consola:

```
=================================================
 Servidor iniciado en http://localhost:8080
 Clave de administrador: admin123
=================================================
```

Abre tu navegador en **http://localhost:8080**

- **Usuarios**: entran desde la pagina de inicio pegando el link/codigo del lobby.
- **Administrador**: entra a **http://localhost:8080/admin** y usa la clave
  `admin123` (puedes cambiarla, ver seccion 4).

> El servidor debe quedarse corriendo (no cierres la terminal) mientras la
> gente esta votando. Si lo detienes, se pierden los lobbies, las imagenes
> y los votos, porque todo vive en memoria.

## 4. Cambiar la clave de administrador

Abre `src/main/java/lobby/Servidor.java` y busca esta linea cerca del inicio:

```java
static final String CLAVE_ADMIN = "admin123";
```

Cambia `"admin123"` por la clave que quieras, guarda el archivo y vuelve a
compilar (paso 1 de la seccion anterior).

## 5. Flujo de uso

1. El administrador entra a `/admin`, pone la clave, y crea una nueva
   votacion escribiendo un nombre (ej. "Diseños para el logo").
2. El sistema genera un **link unico**, por ejemplo:
   `http://localhost:8080/lobby/a1b2c3d4`
3. El administrador sube imagenes una por una, cada una con su **descripcion**.
4. El administrador comparte el link con los votantes (por WhatsApp, correo, etc).
5. Cada usuario abre el link, escribe su nombre, y va votando **Si / No** en
   cada imagen, una por una.
6. Cuando terminan, pueden ver los **resultados** (boton "Ver resultados").
7. El administrador tambien puede ver los resultados desde su panel, con el
   detalle de quien voto que en cada imagen.

## 6. Opciones gratuitas para publicarlo en internet

Como el proyecto es Java puro sin dependencias, cualquier servicio que
permita correr un JAR o una app Java sirve. Estas son alternativas con
capa gratuita real (sin tarjeta de credito obligatoria en la mayoria):

### Opcion recomendada: Render (Free Web Service)
- Gratis, soporta Java, se conecta directo a un repositorio de GitHub.
- Sube tu proyecto a GitHub, en Render eliges "New Web Service", seleccionas
  el repo, y como comando de build usas:
  `javac -d out src/main/java/lobby/*.java`
  y como comando de arranque:
  `java -cp out lobby.Servidor $PORT`
  (Render asigna el puerto automaticamente en la variable de entorno `PORT`,
  por eso el programa acepta el puerto como argumento).
- Limite: el servicio "duerme" tras un rato sin uso en el plan gratuito, y
  al dormir se reinicia (se pierden los datos, ya que estan en memoria).

### Alternativa: Railway (plan gratuito con creditos mensuales)
- Tambien se conecta a GitHub, deploy automatico.
- Da unas horas/creditos gratis al mes, suficiente para un evento puntual
  de votacion.

### Alternativa: Fly.io (free allowance)
- Requiere Docker, un poco mas tecnico, pero tiene capa gratuita generosa.
- Necesitarias un `Dockerfile` simple con Java 21 y copiar el proyecto.

### Para pruebas rapidas sin publicar nada
- Puedes correrlo en tu propia computadora (seccion 3) y compartir tu
  direccion local usando **ngrok** (gratis) o **Cloudflare Tunnel** (gratis),
  que te dan una URL publica temporal que apunta a tu `localhost:8080`.
  Es la opcion mas rapida si solo necesitas votar con un grupo por un rato.

> Importante: como los datos se guardan en memoria (elegiste esta opcion
> para mantenerlo simple), cualquier reinicio del servidor -- ya sea manual,
> por el plan gratuito "dormido", o por una actualizacion -- borra los
> lobbies, imagenes y votos. Si mas adelante quieres que los resultados
> sobrevivan a reinicios, se puede agregar una base de datos simple como
> H2 o SQLite sin mucho esfuerzo adicional.

## 7. Notas de seguridad

- La clave de administrador viaja en texto plano; esta bien para un proyecto
  simple/interno, pero no seria apropiado para un caso con datos sensibles.
- No hay limite de tamaño de imagen ni validacion de tipo de archivo mas
  alla de la extension. Para un uso publico masivo convendria agregar
  limites de tamaño.
- Todo el codigo esta comentado en español para que sea facil de leer y
  modificar.
