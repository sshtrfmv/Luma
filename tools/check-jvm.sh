#!/bin/bash
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
android_jar="${1:-}"
[ -f "$android_jar" ] || { echo 'Передай путь к android-all.jar (Android13, Robolectric). Обычный SDK android.jar содержит заглушки и не годится для выполнения этих проверок.' >&2; exit 1; }
classes="$project/.jvm-check/classes"
mkdir -p "$classes"
java com.sun.tools.javac.Main --release 8 -cp "$android_jar" -d "$classes" \
 "$project/tools/jvm-stubs/ru/sasha/tvlauncher/R.java" \
 "$project/app/src/main/java/ru/sasha/tvlauncher/"*.java "$project/tests/"*.java
for source in "$project/tests/"*Test.java; do
  name="$(basename "$source" .java)"
  java -cp "$classes:$android_jar" "ru.sasha.tvlauncher.$name"
done
