#!/bin/bash
set -euo pipefail
operation="${1:-}"
copy_path="${2:-$HOME/Desktop/sashatv-settings.json}"
command -v adb >/dev/null || { echo 'Не найден adb.' >&2; exit 1; }
serial="${3:-${ANDROID_SERIAL:-}}"
if [ -z "$serial" ]; then serial="$(adb get-serialno)"; fi
[ -n "$serial" ] && [ "$serial" != unknown ] || { echo 'Укажи адрес/серийный номер ТВ третьим аргументом.' >&2; exit 1; }
command -v adb >/dev/null || { echo 'Не найден adb.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Не найден python3.' >&2; exit 1; }
check_copy() {
  python3 - "$1" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1])
if p.stat().st_size > 2 * 1024 * 1024:
    raise SystemExit('Копия больше 2 МБ.')
try:
    data = json.loads(p.read_text(encoding='utf-8'))
except (ValueError, UnicodeError):
    raise SystemExit('Это не корректная JSON-копия. Сначала создай её в настройках Luma.')
if not isinstance(data, dict) or data.get('format') != 'sasha-tv-settings' or data.get('version') != 1:
    raise SystemExit('Неверный формат копии Luma.')
PY
}
case "$operation" in
  save)
    # Same directory permits atomic replacement; invalid adb output never replaces an existing copy.
    temp_copy="$(mktemp "${copy_path}.tmp.XXXXXX")"
    trap 'rm -f "$temp_copy"' EXIT
    adb -s "$serial" exec-out run-as ru.sasha.tvlauncher cat files/sashatv-settings.json > "$temp_copy"
    check_copy "$temp_copy"
    mv -f "$temp_copy" "$copy_path"
    echo "Копия сохранена: $copy_path"
    ;;
  load)
    check_copy "$copy_path"
    adb -s "$serial" shell -T "run-as ru.sasha.tvlauncher sh -c 'cat > files/sashatv-import.json'" < "$copy_path"
    echo 'Копия передана. На ТВ: Настройки → Восстановить копию → Копия, загруженная через ADB.'
    ;;
  *)
    echo 'Использование: bash backup-settings.sh save|load [путь_к_JSON] [адрес_ADB]' >&2
    echo 'Перед save нажми «Сохранить копию» в настройках Luma. Выбор файла на ТВ можно отменить.' >&2
    exit 1
    ;;
esac
