# CLAUDE.md

Плагин для 1C:EDT (Eclipse 4.30, Java 17). Что делаем и в каком порядке —
в [SPEC.md](SPEC.md). Перед работой прочитай его.

## Сборка

Нужен JDK 17. Maven — через wrapper (`./mvnw`, версия 3.9.11): на более
новом Maven из образа GitHub Actions Tycho 4.0.13 не стартовал, поэтому
системный `mvn` не используй.

```bash
./mvnw -B verify
```

На Linux без дисплея UI-тесты запускаются под xvfb:

```bash
xvfb-run -a ./mvnw -B verify
```

Быстро, без тестов:

```bash
./mvnw -B verify -DskipTests
```

Update-site после сборки:
`releng/io.github.hansglorias.edtai.repository/target/io.github.hansglorias.edtai.repository-*.zip`.

Отчёты тестов: `tests/*/target/surefire-reports/`.

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
- Перед пушем `./mvnw -B verify` должен быть зелёным.

## Локальная работа (Windows, Astra)

- JDK 17 (`JAVA_HOME`), Maven не нужен — `./mvnw` (на Windows
  `mvnw.cmd`) скачает 3.9.11 сам. Первая сборка качает target platform
  Eclipse 2023-12 (~1 ГБ) — нужен доступ к `download.eclipse.org`.
- Сборка с тестами: `./mvnw -B verify`. UI-тесты открывают окно Eclipse:
  на Windows и в графической сессии Astra — просто запуск; по SSH без
  дисплея — `xvfb-run -a`.
- Быстро только ядро: `./mvnw -B verify -pl releng/edtai.target,bundles/io.github.hansglorias.edtai.core,tests/io.github.hansglorias.edtai.core.tests -am`.
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
- Вся сборка с тестами одной строкой:

  ```bash
  export LANG=C.UTF-8 JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 MVNW_REPOURL=https://repo1.maven.org/maven2
  xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -B -ntp verify
  ```

  Повторно, когда всё уже скачано, быстрее с `-o` (offline). Модуль
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
