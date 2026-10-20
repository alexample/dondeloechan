# Dónde lo Echan

App de Android que dice el día, la hora y el canal exacto (con su dial) de cada partido de Primera, Segunda, Copa del Rey y Champions League.

## Instalar en el móvil

1. En el móvil, abre la sección **Releases** de este repositorio y descarga `DondeLoEchan.apk` de la última versión.
2. Ábrela. Android pedirá permiso para instalar apps de esta fuente (Chrome o Archivos): acéptalo.
3. Las versiones nuevas se instalan encima de la anterior.

## Cómo se actualiza la guía

- `data/partidos.json` es la guía de partidos. Una tarea programada de Claude la revisa cada mañana (horarios, señal exacta en la guía de Movistar Plus+ y resultados de los partidos jugados) y la sube aquí.
- La app descarga ese archivo al abrirse y lo guarda para funcionar sin conexión. No hace falta reinstalar nada.
- El repositorio debe ser **público** para que la app pueda leer el archivo.

## Compilar

Cada cambio en `app/` hace que GitHub Actions compile una APK firmada y la publique en Releases (workflow `Compilar APK`). También se puede lanzar a mano desde la pestaña Actions.

## Escudos

La app usa monogramas. Para poner escudos con permiso de uso, mira `app/src/main/assets/escudos/LEEME.txt`.

## Google Play

Antes de publicar en Play, sustituye la clave de `keystore/` por una privada guardada como secreto de GitHub y genera un `.aab` (`gradle :app:bundleRelease`).

## Web

La misma guía se publica como web en **https://alexample.github.io/dondeloechan/** (workflow `Publicar web`).
Se vuelve a publicar sola cada vez que cambia `data/partidos.json`. Desde el móvil se puede añadir a la pantalla de inicio.
Para activarla la primera vez: Settings → Pages → Source: **GitHub Actions**.
