# Как установить пробную сборку PR или ветки

Общий [update site](https://hansglorias.github.io/EDT_AI/) публикуется
только при push в `main`. В обычном workflow `build` для PR и ручного
запуска job Pages имеет статус `skipped`, а zip создаётся шагом Linux-сборки.
В отдельной быстрой сборке job Pages вообще нет. Пробы до merge не
публикуются на общем сайте.

## Быстрый zip выбранной ветки без тестов

При наличии прав записи:

1. Откройте **Actions → Быстрая сборка ZIP (без тестов) → Run workflow**.
2. В **Use workflow from** выберите нужную ветку, затем **Run workflow**.
   Произвольные команды или дополнительные параметры вводить не нужно.
3. Дождитесь успешного job `zip` и сверьте ref/полный SHA в его кратком итоге.
4. В **Artifacts** скачайте `edtai-preview-<shortSHA>`; прямая ссылка есть
   также в кратком итоге. Нужен вход в GitHub. Архив хранится 7 дней,
   уже содержит установочный p2-site и не требует распаковки.
5. В EDT: **Help → Install New Software… → Add… → Archive…** — выберите zip.
   Остальные шаги — в разделе «Установить zip в EDT» ниже.

Этот workflow делает один запуск `./mvnw -B -ntp verify -DskipTests` на
Ubuntu с Java 17. Тесты, UI harness и публикация Pages не запускаются.
Получается пробная сборка для ручной проверки; обязательные полные проверки
`build` на Windows и Ubuntu перед merge сохраняются.

Чтобы workflow появился в интерфейсе Actions, файл
`.github/workflows/quick-build.yml` должен сначала попасть в `main`.
Выбранная ветка также должна содержать этот файл с `workflow_dispatch`:
в старых ветках его может ещё не быть. В таком случае обновите свою ветку
из `main` или используйте уже доступный артефакт PR/локальную сборку ниже.

## Скачать сборку PR

1. Войдите в GitHub и откройте нужный PR.
2. В проверках актуальной версии PR выберите **build / ubuntu-latest →
   Details**, затем **Summary** запуска. Можно также открыть
   [Actions → build](https://github.com/HansGlorias/EDT_AI/actions/workflows/build.yml)
   и выбрать запуск нужного PR.
3. Сверьте исходную ветку и SHA коммита. При событии `pull_request`
   собранный SHA может отличаться от SHA ветки: проверяется результат
   объединения PR с `main` (`refs/pull/<номер>/merge`). В новых запусках оба значения
   указаны в кратком итоге Linux-job.
4. Дождитесь зелёных **ubuntu-latest** и **windows-latest** этого запуска.
   Внизу страницы запуска, в **Artifacts**, нажмите **edtai-update-site**.
   В новых запусках прямая ссылка есть также в кратком итоге Linux-job. GitHub скачает zip,
   уже готовый для установки: распаковывать его не нужно.

Если артефакта нет, проверьте запуск/SHA и результат шага **Update site**
в Linux-job. Для скачивания нужен вход в GitHub; архивы могут истечь по
сроку хранения. **Pages: skipped** само по себе не означает, что zip нет.
Порядок скачивания описан также в
[документации GitHub](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts).

## Запустить полную сборку выбранной ветки вручную

При наличии прав записи: **Actions → build → Run workflow → Branch**,
выберите нужную ветку, затем **Run workflow**. `workflow_dispatch` уже
включён в workflow. Дождитесь обеих зелёных проверок и скачайте
**edtai-update-site** из **Artifacts** этого запуска. Сверьте ветку/SHA:
ручной запуск проверяет выбранную ветку, а PR-прогон — объединение с `main`.
Ручной запуск не публикует Pages, в том числе при выборе `main`.
[Документация GitHub](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

## Личная пробная сборка на Windows

В своей копии репозитория откройте корень нужной ветки. Нужны JDK 17
(`JAVA_HOME` указывает на него) и доступ к Maven Central и
`download.eclipse.org` для первой загрузки зависимостей. Maven отдельно
не нужен: wrapper скачает версию 3.9.11.

```powershell
java -version
.\mvnw.cmd -B -ntp verify -DskipTests *> trial-build.log
```

Проверьте код завершения Maven и `BUILD SUCCESS` в `trial-build.log`
(`Get-Content -Tail 40 trial-build.log`). Эта команда создаёт архив без
запуска тестов/Eclipse; она предназначена для личной пробы и **не заменяет
полный CI Windows + Ubuntu перед merge**. Правила регресса — в
[TESTING.md](TESTING.md).

Готовый локальный архив:
`releng/io.github.hansglorias.edtai.repository/target/io.github.hansglorias.edtai.repository-0.1.0-SNAPSHOT.zip`.
Для уже успешного CI-прогона скачайте его артефакт вместо повторной
локальной сборки ради zip.

## Установить zip в EDT

1. **Help → Install New Software… → Add… → Archive…** — выберите скачанный
   `edtai-preview-<shortSHA>.zip`, `edtai-update-site.zip` или локальный zip из `target`.
2. Нажмите **Add**, отметьте **EDT AI**. Если список пуст, снимите
   **Group items by category**.
3. Снимите **Contact all update sites during install to find required software**.
4. **Next → Next**, примите лицензию, **Finish**. Для неподписанного
   содержимого — **Install anyway**. Перезапустите EDT.

В закрытом контуре сначала перенесите zip разрешённым способом.
Если установка через p2 недоступна, используйте существующую
[инструкцию Astra](install-astra.md).
