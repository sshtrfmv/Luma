# Third-party components and marks

## Gradle Wrapper

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`: Gradle project, Apache License 2.0, see `LICENSES/Apache-2.0.txt`. Wrapper JAR SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`; Gradle distribution8.9 is separately pinned in wrapper properties. Source: https://github.com/gradle/gradle/tree/v8.9.0 . The wrapper's own copyright notices remain intact.

## TMDB

The bundled TMDB logo is for attribution and is outside the project's MIT grant. Official branding and attribution: https://www.themoviedb.org/about/logos-attribution ; API attribution requirements: https://developer.themoviedb.org/docs/faq . It is displayed without recoloring, flipping or changing aspect ratio in the catalog's About section.

This product uses the TMDB API but is not endorsed or certified by TMDB.

Catalog titles, descriptions and remote images remain the content of their respective rights holders. They are downloaded at runtime through a user's configured TMDB access; no movie stills, posters or API credentials are bundled in the publication tree.

## Weather

Weather data is requested from https://open-meteo.com/ . The weather tile retains Open-Meteo credit. No user GPS coordinates are collected. Terms/data attribution: https://open-meteo.com/en/terms .

## App names and symbols

YouTube, VK Video, Lampa, Zona, Amnezia, VLC, LocalSend and device/product names identify third-party applications/devices. Their names and recognizable marks are outside the MIT grant; no endorsement or official association is implied. Luma contains independently drawn compatibility UI symbols, not third-party APKs. Utility symbols and the Luma landscape are source-native drawings created for this project. When distributing a fork, retain these notices and review the relevant brand policies for your use.

The old reference_screen/hero_desert raster images and personal TV screenshots are not included in this tree.

## Build-time dependencies

Android SDK and AGP are fetched from Google; android-all13 is fetched from Robolectric's Maven artifact only for the standalone regression task, not packaged in the APK. No Android SDK, test-runtime JAR, or third-party UI library is vendored here.

City search uses Open-Meteo Geocoding API and GeoNames location data: https://open-meteo.com/en/docs/geocoding-api and https://www.geonames.org/ . Only the manually entered city query is sent; device GPS is not used.

## User-provided TV screenshots

Five screenshots prepared for docs/screenshots (publication pending owner confirmation) were provided by the project owner for the public README. They contain third-party movie artwork obtained at runtime from TMDB and application brand marks. These materials remain with their respective rightsholders and are excluded from the project's MIT grant. Screenshots are not additional bundled runtime movie assets.
