#!/bin/bash
set -euo pipefail
source_dir="$(cd "$(dirname "$0")" && pwd)"
target="${1:-$HOME/Downloads/TVLauncher 2}"
if [ ! -f "$target/app/src/main/java/ru/sasha/tvlauncher/MainActivity.java" ]; then
  echo "Не найден существующий проект: $target" >&2
  echo 'Для нового проекта открой папку Luma-1.0.0 в Android Studio, install.sh не нужен.' >&2
  exit 1
fi
target="$(cd "$target" && pwd)"
[ "$source_dir" != "$target" ] || { echo 'Это уже полный проект. Открой его в Android Studio.' >&2; exit 1; }
while IFS= read -r file; do
  case "$file" in ''|/*|../*|*/../*|*/..) echo 'Недопустимый путь в списке обновления.' >&2; exit 1;; esac
  [ -f "$source_dir/$file" ] || { echo "В архиве отсутствует $file" >&2; exit 1; }
done < "$source_dir/update-files.txt"
while IFS= read -r file; do
  case "$file" in ''|/*|../*|*/../*|*/..) echo 'Недопустимый путь удаления.' >&2; exit 1;; esac
  if grep -Fxq -- "$file" "$source_dir/update-files.txt"; then echo 'Файл одновременно обновляется и удаляется.' >&2; exit 1; fi
done < "$source_dir/remove-files.txt"

backup="$target/.sashatv-backups/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$backup/original"
printf '%s\n' "$target" > "$backup/target.txt"
cat "$source_dir/update-files.txt" "$source_dir/remove-files.txt" > "$backup/update-files.txt"
cp "$source_dir/restore.sh" "$backup/restore.sh"
while IFS= read -r file; do
  if [ -f "$target/$file" ]; then
    mkdir -p "$backup/original/$(dirname "$file")"
    cp -p "$target/$file" "$backup/original/$file"
    printf '%s\n' "$file" >> "$backup/existing.txt"
  else
    printf '%s\n' "$file" >> "$backup/absent.txt"
  fi
done < "$backup/update-files.txt"
while IFS= read -r file; do
  mkdir -p "$target/$(dirname "$file")"
  cp "$source_dir/$file" "$target/$file"
done < "$source_dir/update-files.txt"
while IFS= read -r file; do rm -f -- "$target/$file"; done < "$source_dir/remove-files.txt"
chmod +x "$target/gradlew"
echo 'Luma 0.9.0: исходники установлены. В Android Studio дождись Gradle Sync и нажми Run ▶.'
echo "Резервная копия: $backup"
printf 'Откат исходников: bash %q\n' "$backup/restore.sh"
