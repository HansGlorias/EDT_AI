#!/bin/bash
# Подключает EDT AI к EDT без p2 и без прав на каталог EDT.
#
# Нужен, когда Install New Software не работает: EDT стоит в каталоге root
# (/opt/...), sudo нет, а установка в пользовательский профиль p2 падает на
# чужих компонентах. Скрипт копирует jar плагина в ~/.edt-ai и собирает
# собственный список бандлов: системный bundles.info EDT плюс наши jar.
# EDT нужно запускать с этим списком — см. ~/.edt-ai/edt.sh.
#
# Использование: bash edtai-user-install.sh <update-site.zip> [каталог EDT]
set -euo pipefail

ZIP=${1:?"укажите zip update-site: bash $0 /tmp/edtai.zip [каталог EDT]"}
# По умолчанию — самая новая EDT из /opt/1C/1CE/components (там же лежит 1C:EDT Start, его пропускаем)
EDT=${2:-$( (ls -d /opt/1C/1CE/components/1c-edt-*/1cedt 2>/dev/null || true) | sort -V | tail -n 1 | xargs -r dirname)}
BASE_INFO="$EDT/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
HOME_DIR="$HOME/.edt-ai"

[ -f "$ZIP" ] || { echo "Нет файла $ZIP" >&2; exit 1; }
[ -n "$EDT" ] && { [ -x "$EDT/1cedt" ] || [ -x "$EDT/eclipse" ]; } || { echo "Не найдена EDT в «$EDT», укажите каталог вторым параметром" >&2; exit 1; }
[ -r "$BASE_INFO" ] || { echo "Нет $BASE_INFO" >&2; exit 1; }

rm -rf "$HOME_DIR/plugins"
mkdir -p "$HOME_DIR/plugins" "$HOME_DIR/tmp" "$HOME_DIR/configuration"

# Достаём jar из zip: unzip есть не везде, поэтому через python3, если его нет
if command -v unzip >/dev/null; then
    unzip -q -j -o "$ZIP" 'plugins/*.jar' -d "$HOME_DIR/plugins"
else
    python3 - "$ZIP" "$HOME_DIR/plugins" <<'PY'
import sys, zipfile, os
with zipfile.ZipFile(sys.argv[1]) as z:
    for name in z.namelist():
        if name.startswith('plugins/') and name.endswith('.jar'):
            with open(os.path.join(sys.argv[2], os.path.basename(name)), 'wb') as out:
                out.write(z.read(name))
PY
fi

# Системный список без наших старых строк + наши jar
INFO="$HOME_DIR/bundles.info"
grep -v '^io\.github\.hansglorias\.edtai\.' "$BASE_INFO" > "$INFO"
for jar in "$HOME_DIR"/plugins/*.jar; do
    name=$(basename "$jar" .jar)
    echo "${name%_*},${name##*_},file:$jar,4,false" >> "$INFO"
done

LAUNCHER="$EDT/1cedt"
[ -x "$LAUNCHER" ] || LAUNCHER="$EDT/eclipse"
cat > "$HOME_DIR/edt.sh" <<SH
#!/bin/bash
# Запуск EDT с подключённым EDT AI. Создан edtai-user-install.sh.
export _JAVA_OPTIONS="\${_JAVA_OPTIONS:-} -Djava.io.tmpdir=$HOME_DIR/tmp -Dorg.eclipse.equinox.simpleconfigurator.configUrl=file:$INFO"
exec "$LAUNCHER" -configuration "$HOME_DIR/configuration" "\$@"
SH

echo "Готово. Подключены:"
ls -1 "$HOME_DIR/plugins"
echo
echo "Запуск EDT: bash $HOME_DIR/edt.sh"
