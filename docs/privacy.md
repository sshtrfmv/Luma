# Данные и сеть

Luma хранит настройки, избранное и порядок приложений локально. TMDB Read Access Token шифруется через Android Keystore; токен не включён в Git или экспорт настроек. Автоматической отправки аналитики/отчётов нет.

Запросы каталога идут к api.themoviedb.org, обложки — к image.tmdb.org; погода — к api.open-meteo.com с предустановленными координатами города, без GPS-разрешения. Внешним приложениям передаются выбранные идентификатор/тип фильма либо команда управления настроенным туннелем AmneziaWG.

Экспорт настроек включает избранные фильмы, порядок приложений, фильтры, оформление и ручные названия HDMI. В нём нет токена TMDB или конфигурации VPN, но названия устройств/избранное могут быть личными — не публикуй копию.

ADB-диагностика запускается вручную на компьютере. Она содержит ограниченные сведения о приложении, памяти, кадрах, фокусе и HDMI. Системные дампы могут включать названия других приложений и устройств. Не отправляй архив в публичный issue без просмотра.

tools/check-publication.py проверяет текущее дерево по ограниченному набору путей и паттернов. Это не универсальный сканер секретов и не проверка всей истории Git.

Weather city search sends the entered name to geocoding-api.open-meteo.com (Open-Meteo / GeoNames). The selected city and coordinates are stored locally in weather preferences. Forecast requests use those coordinates. No device geolocation permission is requested. City preferences are not part of the existing settings export.
