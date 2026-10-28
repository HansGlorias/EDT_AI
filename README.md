# EDT AI

Плагин для 1C:EDT: чат с языковой моделью прямо в IDE. Рассчитан в том числе
на закрытый контур: адрес модели задаётся в настройках, корпоративный CA
подключается PEM-файлом, токен хранится в Eclipse Secure Storage.

**Сейчас — этап 1, каркас.** Вид «AI Chat» отвечает эхом, модель ещё не
подключается. Настройки сохраняются и пригодятся на следующих этапах. План —
в [SPEC.md](SPEC.md).

Поддерживаемые версии: 1C:EDT 2025.2 и 2026.1 (Eclipse 4.30, Java 17),
Windows и Linux (в том числе Astra Linux).

## Что есть

- **Window → Show View → Other… → AI → AI Chat.** История диалога, поле
  ввода, «Отправить» или Ctrl+Enter. На панели вида: «Очистить историю» и
  «Настройки». Внизу строка состояния: режим, протокол, модель.
- **Window → Preferences → EDT AI:**
  - протокол: OpenAI-совместимый (`/v1/chat/completions`) или Anthropic
    Messages (`/v1/messages`);
  - адрес сервера и модель;
  - токен: сохраняется в Secure Storage, в обычные настройки не попадает;
  - сертификаты: доверять корневым сертификатам ОС (на Windows —
    хранилище `Windows-ROOT`, на Linux — `/etc/ssl/certs/ca-certificates.crt`)
    и дополнительный PEM-файл с корпоративным CA. Под полем видно, сколько
    сертификатов прочитано из файла;
  - MCP-серверы: имя, транспорт (stdio / http), команда или URL, флажок
    «включён».

## Установка в EDT

1. Скачайте update-site:
   - из GitHub Actions: откройте последний успешный запуск **build** и
     скачайте артефакт `edtai-update-site` (это zip);
   - или соберите сами (см. ниже) и возьмите
     `releng/io.github.hansglorias.edtai.repository/target/io.github.hansglorias.edtai.repository-<версия>.zip`.
2. В EDT: **Help → Install New Software…** → **Add…** → **Archive…**, выберите
   zip, нажмите **Add**.
3. Отметьте **EDT AI**. Если список пуст, снимите флажок **Group items by
   category**.
4. Снимите флажок **Contact all update sites during install to find required
   software**: всё нужное уже есть в EDT, а в закрытом контуре внешние сайты
   недоступны.
5. **Next → Next**, примите лицензию, **Finish**.
6. На вопрос о неподписанном содержимом ответьте **Install anyway**: плагин
   пока не подписан.
7. Перезапустите EDT.

Если **Install New Software** недоступен (например, его отключили
политикой), можно поставить через dropins: распакуйте zip, скопируйте jar из
`plugins/` в каталог `dropins/` рядом с исполняемым файлом EDT и
перезапустите EDT.

### Закрытый контур

- Перенесите zip любым разрешённым способом. Плагин ничего не скачивает при
  установке и не требует доступа в интернет.
- Корпоративный CA: Preferences → EDT AI → **Файл CA (PEM)**. Файл может
  содержать несколько сертификатов. Если CA уже установлен в систему,
  достаточно флажка **Доверять корневым сертификатам операционной системы**.
- Токен хранится в Eclipse Secure Storage. На Windows он защищён учётной
  записью пользователя. На Linux, если нет связки ключей (libsecret), EDT
  при первом сохранении попросит задать мастер-пароль Secure Storage.

### Удаление

**Help → About → Installation Details → Installed Software → EDT AI →
Uninstall.**

## Сборка

Нужны JDK 17 и Maven 3.9+, а также доступ к `download.eclipse.org` и Maven
Central.

```bash
mvn -B verify
```

На Linux без дисплея UI-тесты запускаются так:

```bash
xvfb-run -a mvn -B verify
```

Сборка без тестов:

```bash
mvn -B verify -DskipTests
```

Структура проекта:

| Каталог | Что внутри |
|---|---|
| `releng/edtai.target` | target platform: Eclipse 2023-12 (4.30) |
| `bundles/…core` | настройки, Secure Storage, TLS, модель чата, эхо-провайдер; без UI |
| `bundles/…ui` | вид «AI Chat», страница настроек |
| `tests/…core.tests` | тесты ядра (JUnit 5, OSGi без UI) |
| `tests/…ui.tests` | smoke-тест в живом workbench |
| `features/…feature` | фича для установки |
| `releng/…repository` | update-site (p2) и его zip |

CI (GitHub Actions) собирает и тестирует на Ubuntu (GTK под xvfb) и Windows.

## Лицензия

[MIT](LICENSE)
