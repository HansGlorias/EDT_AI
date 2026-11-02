# Проверки локально и в CI

Правило согласовано 02.11.2026: стандартный полный регресс выполняет CI.
Локально проверяем изменение и связанные с ним сценарии.

| Изменение | Локальные проверки |
|---|---|
| Только документация | `git diff --check`, просмотр diff и проверка ссылок; без запуска Eclipse |
| Core | Новые/изменённые тесты и затронутые соседние сценарии core; без UI-набора |
| UI | Затронутые UI-тесты и соседние сценарии, нужные core-тесты; просмотр снимков только изменённых состояний |
| Сборка или диагностика конкретного сбоя | Проверки по причине изменения/сбоя; при необходимости полный локальный `verify` |

Полный локальный прогон нужен только для диагностики конкретного сбоя,
при изменении сборки или по запросу владельца. Не повторяй уже зелёные
проверки того же SHA без новых изменений или конкретной причины; после
правок проверяй затронутые сценарии заново. В PR укажи, что проверено
локально, а что проверяет CI. Не обещай точную экономию времени или лимита.

## Выбор тестов

Запускать из корня репозитория, Java 17 (`JAVA_HOME`). Только Maven wrapper:
`./mvnw` на Linux, `.\mvnw.cmd` на Windows (Maven 3.9.11).
В примерах перечислены существующие классы; замени список на тесты своей
правки и соседние сценарии. `-Dtest` выбирает классы, `-pl` — модули,
`-am` добавляет их зависимости. `releng/edtai.target` нужен Tycho для
target platform. Команды ниже выбирают один тестовый модуль; в отчёте
проверь, что нужные классы действительно выполнены. Не подавляй ошибку
отсутствия выбранных тестов.

### Core: Linux / Astra

```bash
export LANG=C.UTF-8
./mvnw -B -ntp verify -pl releng/edtai.target,bundles/io.github.hansglorias.edtai.core,tests/io.github.hansglorias.edtai.core.tests -am -Dtest=RequestBudgetTest,ModelConnectionTest > core-tests.log 2>&1
```

Для core-набора `xvfb` не нужен: в его POM задан `useUIHarness=false`.
На Windows та же команда с `.\mvnw.cmd`, аргументом
`"-Dtest=RequestBudgetTest,ModelConnectionTest"` и выводом `*> core-tests.log`.

### UI: Linux без дисплея

```bash
export LANG=C.UTF-8
xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -B -ntp verify -pl releng/edtai.target,bundles/io.github.hansglorias.edtai.core,bundles/io.github.hansglorias.edtai.ui,tests/io.github.hansglorias.edtai.ui.tests -am -Dtest=AiPreferencePageTest,ChatModelTest > ui-tests.log 2>&1
```

В графической сессии Astra `xvfb-run` можно убрать. UI-набор использует
живой workbench (`useUIHarness=true`, `useUIThread=true`).

### UI: Windows / PowerShell

```powershell
$env:LANG = 'C.UTF-8'
$uiTestHome = Join-Path ([System.IO.Path]::GetTempPath()) ('edtai-ui-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $uiTestHome | Out-Null
.\mvnw.cmd -B -ntp verify -pl releng/edtai.target,bundles/io.github.hansglorias.edtai.core,bundles/io.github.hansglorias.edtai.ui,tests/io.github.hansglorias.edtai.ui.tests -am "-Dtest=AiPreferencePageTest,ChatModelTest" "-Dui.test.vmargs=-Duser.home=$uiTestHome" *> ui-tests.log
```

Отдельный временный `user.home` изолирует тестовый Eclipse от личного
Secure Storage. UI POM дополнительно задаёт отдельный `-eclipse.keyring`
в `target/secure-storage`. Не используй домашний каталог личной EDT.

Если все зависимости уже скачаны, можно добавить `-o`; это не причина
повторять успешную проверку. Первая сборка загружает target platform
Eclipse 2023-12 и зависимости, поэтому требует доступа к репозиториям.

## Итоги и сбои

Сохраняй полный лог в файл; проверяй код завершения Maven и итог
`BUILD SUCCESS` / `BUILD FAILURE`. Не выводи весь успешный лог в чат.
Для краткого просмотра: `tail -n 40 ui-tests.log` (Linux) или
`Get-Content -Tail 40 ui-tests.log` (PowerShell). При сбое читай сообщение
и отчёт конкретного теста, затем проверяй исправление. Не перезапускай
вслепую и не отключай тесты или платформы ради зелёного статуса.

- Отчёты: `tests/*/target/surefire-reports/`.
- Лог Eclipse: `tests/*/target/work/data/.metadata/.log`.
- Снимки: `tests/io.github.hansglorias.edtai.ui.tests/target/screenshots/`.

В CI доступны `test-reports-<ОС>` и `screenshots-<ОС>`. Смотри снимки
затронутых состояний из нужного запуска; не требуется пересматривать
весь каталог для каждой правки. Для одной документации снимки не нужны.

## Обязательный полный регресс в CI

[build.yml](../.github/workflows/build.yml) выполняет полный wrapper
`verify` без выбора `-Dtest`/`-pl` на `ubuntu-latest` (под xvfb) и
`windows-latest`: core + UI, затем сборку update-site. Обе проверки
актуальной версии PR должны быть зелёными до merge. Выборочные локальные
проверки этого требования не заменяют. После новых коммитов нужны
результаты для обновлённой версии PR, а не зелёный статус старого SHA.

## Update-site zip из CI

После merge не пересобирай локально только ради zip:

1. Открой [Actions → build](https://github.com/HansGlorias/EDT_AI/actions/workflows/build.yml).
2. Выбери успешный запуск для **нужного полного SHA ветки `main`**;
   сверь ветку и коммит, убедись, что Windows и Ubuntu зелёные. Артефакт
   PR или другого коммита не заменяет сборку этого SHA.
3. Скачай артефакт `edtai-update-site` и передай пользователю ссылку на
   запуск/артефакт с указанием SHA. GitHub упаковывает содержимое
   `target/repository/` в zip — это готовый архив для установки в EDT.

Если артефакт истёк или недоступен, сообщи об этом владельцу; повторный
запуск для восстановления артефакта — отдельная причина, а не обязательная
повторная проверка каждой фичи.
