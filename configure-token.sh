#!/bin/bash
set +x
set -euo pipefail
command -v adb >/dev/null || { echo 'Не найден adb.' >&2; exit 1; }
serial="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$serial" ]; then serial="$(adb get-serialno)"; fi
[ -n "$serial" ] && [ "$serial" != unknown ] || { echo 'Укажи адрес/серийный номер ТВ первым аргументом.' >&2; exit 1; }
command -v pbpaste >/dev/null || { echo 'Этот скрипт предназначен для macOS.' >&2; exit 1; }
catalog_token="$(pbpaste | tr -d '\r\n')"
catalog_token="${catalog_token#Bearer }"
if [[ ${#catalog_token} -lt 20 || ! "$catalog_token" =~ ^[A-Za-z0-9._-]+$ ]]; then
  unset catalog_token
  echo 'Сначала скопируй API Read Access Token TMDB в буфер обмена.' >&2
  exit 1
fi
trap 'unset catalog_token' EXIT
adb -s "$serial" shell run-as ru.sasha.tvlauncher mkdir -p files
printf '%s' "$catalog_token" | adb -s "$serial" shell "run-as ru.sasha.tvlauncher sh -c 'cat > files/tmdb-token-import.txt'"
unset catalog_token
adb -s "$serial" shell am start -n ru.sasha.tvlauncher/.MainActivity >/dev/null
echo 'Токен передан в приложение. Открой главную лаунчера и дождись загрузки каталога.'
