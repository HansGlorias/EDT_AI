# CLAUDE.md

Плагин для 1C:EDT (Eclipse 4.30, Java 17). Что делаем и в каком порядке —
в [SPEC.md](SPEC.md). Перед работой прочитай его.

## Сборка

Нужны JDK 17 и Maven 3.9+.

```bash
mvn -B verify
```

На Linux без дисплея UI-тесты запускаются под xvfb:

```bash
xvfb-run -a mvn -B verify
```

Быстро, без тестов:

```bash
mvn -B verify -DskipTests
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
- Перед пушем `mvn -B verify` должен быть зелёным.

## Облачная сессия

- Окружению нужен доступ к `download.eclipse.org`, `repo.maven.apache.org`,
  `repo1.maven.org`. `archive.eclipse.org` может быть закрыт, поэтому
  target ссылается на `releases/2023-12/<timestamp>`, а не на
  `eclipse/updates/4.30`.
- Если нет JDK 17 — поставь (`apt-get install openjdk-17-jdk-headless`) и
  собирай с `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
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
