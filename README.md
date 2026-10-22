# Sonata para Android

Reproductor de música local para Android, hermano del
[Sonata de escritorio](https://github.com/julssvanderhaack/sonata). Usa los temas de
[Omarchy](https://omarchy.org) como paletas de colores, que se pueden editar,
importar y exportar en el mismo formato `colors.toml` del escritorio.

> Estado: **0.2**. Hay biblioteca, filtros, temas, reproducción en segundo plano,
> letras sincronizadas y ecualizador. Faltan espectro y listas (ver *Pendiente*).

| Canciones | Sonando | Letra sincronizada | Ecualizador |
|---|---|---|---|
| ![Lista de canciones con carátulas y barra de letras](docs/capturas/02-canciones.png) | ![Pantalla de reproducción](docs/capturas/07-sonando.png) | ![Letra de lrclib.net siguiendo la canción](docs/capturas/19-letra-lrclib.png) | ![Ecualizador de 10 bandas con la curva](docs/capturas/20-ecualizador.png) |

| Temas | Editor de temas |
|---|---|
| ![Cuadrícula de temas de Omarchy](docs/capturas/09-temas.png) | ![Editor con vista previa](docs/capturas/13-editor-preview.png) |

*Las capturas son del emulador, con canciones inventadas generadas con ffmpeg.*

## Qué hace

- **Encuentra la música sola** a través de MediaStore, el índice que Android ya
  mantiene del almacenamiento interno y de la tarjeta SD. No pide "acceso a todos los
  archivos": basta con el permiso de audio. Vuelve a buscar sola cuando cambian los
  archivos.
- **Descarta lo que no es música**:
  - tonos, notificaciones, alarmas y grabaciones (MediaStore los marca);
  - lo que dure menos que la *duración mínima* (10 s por defecto) o pese menos que el
    *tamaño mínimo* (100 KB);
  - notas de voz y audios de WhatsApp y Telegram, carpetas `Android/…` de las apps,
    grabadoras, etc., mediante patrones editables (`*WhatsApp*`, `*PTT-*-WA*`…), que
    también cazan una nota de voz guardada en `Download/`;
  - las carpetas que desmarques en *Ajustes → Carpetas*.

  Los filtros se aplican al leer y no al escanear, así que cambiarlos no obliga a
  volver a buscar.
- **Explorar por** canciones, álbumes, artistas, carpetas y géneros. La pestaña de
  géneros solo aparece si al menos un 10 % de las canciones tiene género.
  - Orden por título, artista, álbum, año, duración o fecha de añadido, con orden
    alfabético español (Á junto a A, Ñ después de N) y barra de letras para saltar.
  - Los álbumes se agrupan por carpeta y se unen por *artista del álbum*, así que un
    álbum con etiquetas a medias no se parte en dos y CD1/CD2 quedan juntos.
    Sin etiquetas, el álbum es el nombre de la carpeta.
  - Los artistas usan el artista del álbum, para que «X feat. Y» no cree artistas sueltos.
  - Las carpetas encadenadas se compactan (`Music/Rock/Metallica` en una fila).
  - Búsqueda sin tildes ni mayúsculas por título, artista, álbum y nombre de archivo.
- **Reproducción** con Media3/ExoPlayer en un servicio: sigue sonando con la pantalla
  apagada, con notificación, controles en la pantalla de bloqueo, Bluetooth y
  auriculares, y se pausa al desenchufarlos.
- **Letras**, en este orden, como en el escritorio:
  1. un `.lrc` (o `.txt`) con el mismo nombre que la canción, si le das permiso a la
     carpeta de tu música en *Ajustes → Letras* (el permiso de audio no cubre esos
     archivos);
  2. la letra incrustada en el archivo: USLT de ID3, `LYRICS` de FLAC/Ogg/Opus o
     `©lyr` de M4A;
  3. la caché;
  4. [lrclib.net](https://lrclib.net), que viene **desactivado** hasta que lo actives
     (en Ajustes o con el botón que aparece cuando no hay letra). Solo envía título,
     artista, álbum y duración. Si la letra incrustada no está sincronizada, se muestra
     mientras busca en lrclib una versión con tiempos.

  Las sincronizadas resaltan la línea que suena y la mantienen a un tercio de la
  altura. Pulsar una línea salta a ella, y ±0,5 s corrige el desfase de esa canción.
  Si mueves la letra con el dedo, deja de seguir la canción durante 4 s.
- **Ecualizador** de 10 bandas, con los mismos filtros, preajustes y "evitar
  saturación" que el escritorio (portados de `eq.cpp`):
  - curva de respuesta real;
  - preajustes propios;
  - preamplificador;
  - doble toque en una banda para devolverla a 0.

  Funciona como un procesador dentro de ExoPlayer, así que no depende del ecualizador
  de cada fabricante. Apagado o plano, deja el audio intacto bit a bit (hay un test
  que lo comprueba).
- **Temas**: los 27 de Omarchy que hay en `assets/themes`, más los tuyos.
  - *Duplicar y editar* abre un editor que se dibuja con el tema que estás editando,
    así que hace de vista previa.
  - El selector de color tiene tono, saturación y brillo, campo hex y los colores de
    la propia paleta como atajos.
  - Importar y exportar `colors.toml`, para llevar un tema del escritorio al móvil o
    al revés.
  - Opción de Material You (los colores del fondo de pantalla) en Android 12 o superior.

## Colores: de Omarchy a Material 3

| Omarchy (`colors.toml`) | Material 3 |
|---|---|
| `accent` | `primary` |
| `background` | `background`, `surface` |
| `dark_background` / `darker_background` | `surfaceContainerLow` / `Lowest` |
| `lighter_background` | `surfaceContainerHigh`, `surfaceVariant` |
| `foreground` | `onBackground`, `onSurface` |
| `dark_foreground` | `onSurfaceVariant` |
| `selection` (+ `selection_foreground`) | `secondaryContainer` (+ `on…`) |
| `muted` | `outline` |
| `red` | `error` |
| `cyan` / `magenta` | `tertiary` |
| `mode` | tema claro u oscuro |

Si falta alguna clave se calcula con las mismas reglas que el escritorio
(`theme.cpp`). Los temas antiguos que solo traen `color0…color15` y
`selection_background` también funcionan. El color del texto sobre el acento o la
selección se elige por contraste (WCAG), y un test comprueba que todos los temas
incluidos se leen bien.

## Compilar

Requisitos: Android SDK con la plataforma 37 (`~/Android/Sdk`, o `sdk.dir` en
`local.properties`) y un JDK 17 o superior (vale el de Android Studio).

```sh
export JAVA_HOME=/opt/android-studio/jbr
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest       # paletas, filtros, agrupación, letras y ecualizador
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Para refrescar los temas desde el Omarchy instalado: `tools/import-omarchy-themes.sh`.

- Android 8.0 o superior (API 26).
- Kotlin, Jetpack Compose (Material 3), Room, DataStore y Media3.

## Organización

```
app/src/main/kotlin/io/github/julssvanderhaack/sonata/
  theme/     Palette (colors.toml → colores), ThemeRepository, SonataTheme (→ Material 3)
  lyrics/    parseLyrics (LRC), LyricsRepository (.lrc, etiquetas, caché, lrclib.net)
  eq/        filtros y preajustes (port de eq.cpp), EqProcessor dentro de ExoPlayer
  library/   Track + Room, Scanner (MediaStore), LibraryFilter, Grouping (álbumes, artistas…)
  player/    PlaybackService (MediaSessionService), PlayerConnection (MediaController)
  settings/  Settings (DataStore)
  ui/        pantallas Compose y navegación
```

## Pendiente

- Espectro en tiempo real.
- FFmpeg para formatos que Android no decodifica (WMA, APE…), con etiquetas leídas por
  la propia app en lugar de MediaStore.
- Listas de reproducción, cola editable y `MediaLibraryService` para Android Auto y
  para reanudar desde los controles del sistema.
- Traducción al inglés (`values-en`) antes de publicar.
- Revisar la licencia de cada tema de terceros antes de publicar (los de serie de
  Omarchy, Aetheria y Vulkanite son MIT).

## Licencia

GPL-3.0, como el Sonata de escritorio.
