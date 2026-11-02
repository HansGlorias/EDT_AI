# CLAUDE.md

Агентам других систем (Codex и т. п.) — также [AGENTS.md](AGENTS.md):
сборка, процесс, карта кода, состояние и следующая задача.

Плагин для 1C:EDT (Eclipse 4.30, Java 17). Что делаем и в каком порядке —
в [SPEC.md](SPEC.md). Перед работой прочитай его.

## Сборка

Нужен JDK 17. Maven — через wrapper (`./mvnw`, версия 3.9.11): на более
новом Maven из образа GitHub Actions Tycho 4.0.13 не стартовал, поэтому
системный `mvn` не используй.

Локально — новые/изменённые тесты и затронутые соседние сценарии. При
правках UI проверяй затронутые UI-сценарии; при чистой правке core не
запускай UI-набор. Для одной документации проверь diff и ссылки, Eclipse
не запускай. Команды выбора тестов и модулей — в
[docs/TESTING.md](docs/TESTING.md).

Полный wrapper `verify` (core + UI) на Windows и Ubuntu обязателен в CI
перед мержем актуальной версии PR. Полный локальный прогон — только для
диагностики конкретного сбоя, при изменении сборки или по запросу владельца.
Не повторяй зелёные проверки того же SHA без новых изменений или причины.
Логи пиши в файл, читай итог и сведения о сбоях; смотри только затронутые
UI-снимки, в том числе из артефактов CI.

Update-site zip бери из успешного CI нужного SHA ветки `main`, артефакт
`edtai-update-site`; повторная локальная сборка ради архива не нужна.
Отчёты локальных тестов: `tests/*/target/surefire-reports/`.

## Правила

- Собираем только против target platform из `releng/edtai.target`
  (Eclipse 2023-12). Не поднимай его версию и не используй API новее 4.30.
- Не используй SWT `Browser`.
- `core` не зависит от SWT/JFace/`org.eclipse.ui`.
- Никаких сторонних библиотек без явного решения в SPEC.md.
- Токен хранится только в Secure Storage. Не пиши его в preferences, логи,
  исключения, `toString()`.
- Репозиторий публичный: никаких реальных адресов, токенов, хостов и
  сертификатов из КСПД. В тестах — `example.invalid`, `localhost` и
  сгенерированный тестовый сертификат.
- Новый код — с тестами в `tests/`. Логику держи в `core`, чтобы её можно
  было проверить без UI.
- Перед пушем достаточно целевых локальных проверок; перед мержем оба
  полных CI-прогона должны быть зелёными. Не отключай тесты или платформы.

## Локальная работа (Windows, Astra)

- JDK 17 (`JAVA_HOME`), Maven не нужен — `./mvnw` (на Windows
  `mvnw.cmd`) скачает 3.9.11 сам. Первая сборка качает target platform
  Eclipse 2023-12 (~1 ГБ) — нужен доступ к `download.eclipse.org`.
- Целевые проверки: [docs/TESTING.md](docs/TESTING.md). UI-тесты открывают
  окно Eclipse: на Windows и в графической сессии Astra — просто запуск;
  по SSH без дисплея — `xvfb-run -a`. На Windows изолируй тестовый Eclipse
  от личного Secure Storage через отдельный временный `user.home`
  (`-Dui.test.vmargs=-Duser.home=…`).
- Список доработок с приоритетами — `docs/REVIEW.md`, что уже сделано —
  этап 7 в SPEC.md.

## Облачная сессия

- Окружению нужен доступ к `download.eclipse.org`, `repo.maven.apache.org`,
  `repo1.maven.org`. `archive.eclipse.org` может быть закрыт, поэтому
  target ссылается на `releases/2023-12/<timestamp>`, а не на
  `eclipse/updates/4.30`.
- Если нет JDK 17 — поставь (`apt-get install openjdk-17-jdk-headless`) и
  собирай с `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
- `repo.maven.apache.org` через прокси облака может отвечать 429 — тогда
  ни wrapper, ни плагины не скачиваются. Обход только для своей сессии, в
  репозиторий не коммитить:
  - wrapper качает Maven с зеркала, если задан
    `MVNW_REPOURL=https://repo1.maven.org/maven2`;
  - зависимости — через зеркало в `~/.m2/settings.xml`:

    ```xml
    <settings>
      <mirrors>
        <mirror>
          <id>repo1</id>
          <mirrorOf>central</mirrorOf>
          <url>https://repo1.maven.org/maven2</url>
        </mirror>
      </mirrors>
    </settings>
    ```
- Если в контейнере нет UTF-8-локали (`LANG` пуст), JVM не создаёт
  каталоги с кириллицей и UI-тесты с `src/CommonModules/Тест/…` падают с
  `InvalidPathException`. Собирай с `LANG=C.UTF-8` (в CI задано так же).
- Полная локальная сборка только для диагностики, при изменении сборки
  или по запросу владельца:

  ```bash
  export LANG=C.UTF-8 JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 MVNW_REPOURL=https://repo1.maven.org/maven2
  xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -B -ntp verify > verify.log 2>&1
  ```

  Если зависимости уже скачаны, можно добавить `-o` (offline). Модуль
  отдельно — с `-pl releng/edtai.target,<модуль>`: без target platform
  Tycho не собирает.
- Работай в своей ветке, PR в `main`.

## Коммиты

- Сообщение по-русски, с маленькой буквы, без точки в конце, суть
  изменения: `вид чата и эхо-провайдер`.
- Без `Co-Authored-By`.

## PR

В описании:

- что сделано;
- что проверено автотестами;
- что нужно проверить руками в EDT на Windows и на Astra Linux (по
  пунктам, чтобы можно было пройти чек-листом).
