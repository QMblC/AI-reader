# Запуск AI-reader и проверка через Swagger

AI-reader — backend на Java 21 и Spring Boot. Для первого запуска используйте локальную заглушку анализа, которая работает без ключа Yandex и обращения к модели. Мобильное API требует токен App Check и гостевую сессию. Для перевода произвольного текста включите Yandex.

## Требования

- JDK 21; переменная `JAVA_HOME` должна указывать на каталог JDK, а `PATH` — содержать его `bin`.
- Доступ в интернет при первой сборке для загрузки Maven и зависимостей.
- Свободный порт 8080 (или другой порт через `SERVER_PORT`).
- Docker нужен только для варианта запуска в контейнере.
- Настроенные `APP_CHECK_PROJECT_NUMBER` и `APP_CHECK_ALLOWED_APP_IDS`. Для ручного вызова мобильных маршрутов получите App Check токен от тестовой сборки. [Настройка тестового окружения](mobile-guest-integration.md#настройка-тестового-бэкенда)

Отдельно устанавливать Maven не требуется: в проекте есть Maven Wrapper (`mvnw` и `mvnw.cmd`). Для кэша требуется PostgreSQL: `docker compose up -d postgres` или собственная локальная БД. Настройки подключения и иллюстраций — в [illustrations.md](illustrations.md).

Все команды ниже выполняются из корня репозитория, где лежат `pom.xml`, `mvnw.cmd` и `src`. В текущей рабочей копии это `D:\AI-reader\AI-reader`, а не родительский каталог `D:\AI-reader`.

Проверьте Java и Maven:

```powershell
java -version
.\mvnw.cmd -version
```

В Linux/macOS используйте `./mvnw` вместо `.\mvnw.cmd`. Если скрипт не исполняемый, выполните `chmod +x mvnw`.

## Быстрый запуск в Windows PowerShell

```powershell
Set-Location D:\AI-reader\AI-reader
$env:YANDEX_ENABLED = 'false'
.\mvnw.cmd spring-boot:run
```

Дождитесь строки `Started AiReaderApplication` в журнале. Терминал останется занят работающим сервером. Для остановки нажмите `Ctrl+C`.

В Linux/macOS:

```bash
YANDEX_ENABLED=false ./mvnw spring-boot:run
```

Заглушка всегда возвращает один и тот же пример про идиому `kick the bucket`, независимо от входного текста. Такой запуск проверяет работу API, но не качество перевода.

## Проверка состояния

Откройте [health](http://localhost:8080/actuator/health) или выполните во втором окне PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Ожидается HTTP `200` и JSON:

```json
{"status":"UP"}
```

Health подтверждает работу приложения. Он не проверяет доступность Yandex и корректность ключа.

## Пример запроса

1. Откройте [Swagger UI](http://localhost:8080/swagger-ui/index.html).
2. Нажмите **Authorize** и введите токен в `appCheck`. Через **Try it out** выполните `POST /api/v1/guest-sessions` и получите `accessToken`.
3. Введите выданный `accessToken` в `guestToken` через **Authorize**.
4. Разверните `POST /api/v1/analyses` и нажмите **Try it out**. Вставьте JSON ниже, нажмите **Execute** и проверьте HTTP-код и **Response body**.

```json
{
  "book": "",
  "author": "",
  "edition": "",
  "selectedText": "He kicked the bucket",
  "context": "After a long illness, he finally kicked the bucket."
}
```

В режиме заглушки ожидается HTTP `200` и ответ:

```json
{
  "translation": "Сыграл в ящик",
  "explanation": "Выражение 'kick the bucket' — английская идиома, означающая смерть. В данном контексте речь идет о человеке, который умер после продолжительной болезни."
}
```

Тот же запрос из PowerShell:

```powershell
$body = @{
    book = ''
    author = ''
    edition = ''
    selectedText = 'He kicked the bucket'
    context = 'After a long illness, he finally kicked the bucket.'
} | ConvertTo-Json
$headers = @{ 'X-Firebase-AppCheck' = '<токен App Check из тестовой сборки>' }
$guest = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/guest-sessions -Headers $headers
$headers.Authorization = "Bearer $($guest.accessToken)"
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/analyses -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

Обязательные поля: `selectedText` (непустая строка, до 2000 символов) и `context` (непустая строка, до 12000 символов). `book`, `author`, `edition` необязательны; максимальная длина — 500, 300 и 300 символов соответственно. Неизвестные поля JSON отклоняются.

Для проверки валидации передайте пустой `selectedText`: ожидается HTTP `400`. Лимит по умолчанию — 60 запросов анализа в минуту на весь экземпляр приложения, общий для всех клиентов; превышение даёт `429` с заголовком `Retry-After`.

## Запуск с Yandex

Остановите приложение. В том же терминале задайте окружение процесса и запустите его заново:

```powershell
$env:YANDEX_ENABLED = 'true'
$env:YANDEX_API_KEY = '<API-ключ>'
$env:YANDEX_FOLDER_ID = '<идентификатор каталога>'
$env:YANDEX_MODEL = 'yandexgpt-5.1'
.\mvnw.cmd spring-boot:run
```

Замените значения в угловых скобках своими. Ключ должен иметь доступ к выбранной модели и каталогу. Не сохраняйте настоящий ключ в документации или Git. В `YANDEX_FOLDER_ID` укажите только ID каталога, без `gpt://` и имени модели. Модель задаётся отдельно в `YANDEX_MODEL`.

В Linux/macOS задайте эти же переменные через `export`, затем выполните `./mvnw spring-boot:run`.

Повторите запрос через Swagger. Ожидаются HTTP `200` и непустые строки `translation` и `explanation`; точный текст ответа модели может различаться. Запуск сервера сам по себе не вызывает модель: обращение выполняется при запросе анализа и может расходовать средства Yandex Cloud.

Полный список переменных, значения по умолчанию и ограничения приведены в [README](../README.md#настройки-yandex), контракт адаптера — в [yandex-completions.md](yandex-completions.md).

В проекте настроена автоматическая загрузка локального `.env` (пример — `.env.example`). Также можно использовать окружение терминала, **Environment variables** в IDEA или Docker `--env-file`. После изменения настроек перезапускайте приложение.

Чтобы вернуться к заглушке:

```powershell
$env:YANDEX_ENABLED = 'false'
Remove-Item Env:YANDEX_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:YANDEX_FOLDER_ID -ErrorAction SilentlyContinue
.\mvnw.cmd spring-boot:run
```

## Запуск через IntelliJ IDEA

1. Откройте каталог с `pom.xml` как Maven-проект и дождитесь загрузки зависимостей.
2. В **Project SDK** и настройках Maven выберите JDK 21.
3. Создайте конфигурацию запуска для `com.aireader.AiReaderApplication`.
4. В **Environment variables** задайте `YANDEX_ENABLED=false` для заглушки либо переменные Yandex из предыдущего раздела.
5. Запустите конфигурацию и откройте Swagger по адресу выше.

Для остановки используйте **Stop**. Переменные из другого терминала уже запущенная IDEA автоматически не получает.

## Сборка JAR и тесты

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target/ai-reader.jar
```

`package` также выполняет тесты; отдельный `test` нужен, если требуется только проверка. Тесты Yandex-адаптера используют локальные HTTP-заглушки и не обращаются к платной модели. Перед запуском JAR остановите другой экземпляр сервера, занимающий тот же порт. Режим JAR определяется теми же переменными окружения.

## Запуск через Docker

Из корня репозитория:

```powershell
docker build -t ai-reader:local .
docker run --rm --name ai-reader -p 127.0.0.1:8080:8080 --env-file .env -e YANDEX_ENABLED=false ai-reader:local
```

Сборка образа включает Maven и тесты; Java на компьютере для этого варианта не нужна. Для запуска с моделью подготовьте локальный `.env` с `YANDEX_ENABLED=true`, `YANDEX_API_KEY`, `YANDEX_FOLDER_ID` и при необходимости `YANDEX_MODEL`, по одной записи `ИМЯ=значение` на строку. Затем используйте вместо предыдущей команды запуска:

```powershell
docker run --rm --name ai-reader -p 127.0.0.1:8080:8080 --env-file .env ai-reader:local
```

`.env` исключён из Git правилами проекта. Команда публикует сервер на localhost. Swagger и health доступны по тем же адресам. Для остановки выполните `docker stop ai-reader` в другом терминале.

## Другой порт и типичные ошибки

Для запуска на порту 8081 в PowerShell:

```powershell
$env:SERVER_PORT = '8081'
.\mvnw.cmd spring-boot:run
```

Замените порт в адресах Swagger, health и API. Для Docker при неизменном внутреннем порте используйте `-p 127.0.0.1:8081:8080`.

| Симптом | Что проверить |
| --- | --- |
| `java` не найдена, неверный `JAVA_HOME`, ошибка версии Java | Установите JDK 21, проверьте `java -version` и `.\mvnw.cmd -version`; откройте новый терминал после изменения окружения. |
| Maven не скачивается или зависимости не разрешаются | Проверьте сеть и настройки proxy/Maven. Адрес загрузки Wrapper задан в `.mvn/wrapper/maven-wrapper.properties`. |
| Порт уже занят | Остановите предыдущий экземпляр или задайте другой `SERVER_PORT`. |
| Браузер не подключается | Дождитесь `Started AiReaderApplication`, проверьте терминал и фактический порт. |
| `403` на `/` | Используйте `/swagger-ui/index.html` или `/actuator/health`: корневой маршрут не открыт. |
| `400` при анализе | Проверьте обязательные поля, длину строк, синтаксис JSON и отсутствие лишних полей. |
| `415` | Передавайте `Content-Type: application/json`. |
| `429` | Подождите число секунд из `Retry-After`; лимит общий. При необходимости настройте `RATE_LIMIT_REQUESTS_PER_MINUTE` положительным целым числом и перезапустите сервер. |
| При включении Yandex приложение не стартует | Проверьте обязательные переменные, формат ID каталога и сообщения валидации в журнале. |
| `502`, `code=INVALID_LLM_RESPONSE` | Модель вернула ответ, не соответствующий ожидаемому формату. |
| `503` | Проверьте `code` и `providerStatus`, если он присутствует: `401` — ключ, `403` — доступ, `404` — модель/адрес, `429` — лимит провайдера; без статуса возможна сетевая ошибка. Подробности кодов есть в README. |

## Запуск на тестовой ВМ

Адреса, управление службой systemd и размещение JAR описаны отдельно в [deployment.md](deployment.md). Для локального запуска доступ к ВМ не требуется.
