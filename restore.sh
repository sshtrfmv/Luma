#!/bin/bash
set -euo pipefail
backup="$(cd "$(dirname "$0")" && pwd)"
[ -f "$backup/target.txt" ] && [ -f "$backup/update-files.txt" ] || { echo 'Запусти restore.sh из папки резервной копии.' >&2; exit 1; }
IFS= read -r target < "$backup/target.txt"
[ -d "$target/app/src/main" ] || { echo "Проект не найден: $target" >&2; exit 1; }
for list in existing absent; do
  if [ -f "$backup/$list.txt" ]; then
    while IFS= read -r file; do
      case "$file" in ''|/*|../*|*/../*|*/..) echo 'Недопустимый путь в копии.' >&2; exit 1;; esac
      grep -Fxq -- "$file" "$backup/update-files.txt" || { echo 'Неизвестный файл в копии.' >&2; exit 1; }
      if [ "$list" = existing ]; then
        [ -f "$backup/original/$file" ] || { echo "Неполная копия: $file" >&2; exit 1; }
      fi
    done < "$backup/$list.txt"
  fi
done
if [ -f "$backup/existing.txt" ]; then
  while IFS= read -r file; do
    mkdir -p "$target/$(dirname "$file")"
    cp -p "$backup/original/$file" "$target/$file"
  done < "$backup/existing.txt"
fi
if [ -f "$backup/absent.txt" ]; then
  while IFS= read -r file; do rm -f -- "$target/$file"; done < "$backup/absent.txt"
fi
echo 'Исходники восстановлены. Для возврата приложения на телевизор нужен Run ▶ в Android Studio.'
echo 'Если Android отклонит понижение версии, повысь versionCode в восстановленном app/build.gradle выше установленного. Не удаляй приложение, чтобы сохранить данные.'
