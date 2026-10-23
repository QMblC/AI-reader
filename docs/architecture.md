# Архитектура заготовки

Приложение следует разделению из `MVP_Backend_Architecture.docx` и приложенной схемы. Это один процесс Spring Boot и один Maven-модуль; границы логических модулей заданы пакетами. Выделять отдельные сервисы или подключать БД для текущего сценария не требуется. Автоматические тесты и тестовые зависимости в проект не включены.

Текущая версия проекта и версия API в Swagger — **1.0**. Префикс `/api/v1` обозначает основную версию API и сохраняется для версии 1.0.

```text
POST /api/v1/analyses
    -> API: DTO + Bean Validation
    -> AnalyzeTextUseCase / AnalyzeTextService
    -> AiAnalysisModule.analyze(TextContext) — граница части напарника
    -> DefaultAiAnalysisModule
    -> ContextPromptBuilder (системный промпт + TextContext)
    -> LlmClient (порт Application, bean analysisLlmClient)
    <- YandexLlmClient + YandexCompletionResponseParser (yandex.enabled=true)
       либо StubLlmClient (yandex.enabled=false); результат — сырой JSON анализа
    -> AnalysisResultParser (проверка формата ответа)
    <- AnalysisResult -> AnalysisResponse

GET /actuator/health -> Spring Boot Actuator
```

| Пакет | Ответственность |
| --- | --- |
| `api` | HTTP, входные/выходные DTO, валидация, Problem Details |
| `application` | Сценарий анализа, контракт `AiAnalysisModule` и общие интерфейсы взаимодействия |
| `domain` | `TextContext`, `AnalysisResult` и инварианты моделей |
| `infrastructure.ai` | Часть напарника: `DefaultAiAnalysisModule`, сборщик промпта, LLM-адаптеры и `AiConfiguration` |
| `infrastructure.analysis` | Проверка результата модели в `JsonAnalysisResultParser` |
| `infrastructure.config` | Сборка объектов и выбор явно указанного bean через `@Qualifier` |
| `infrastructure.security` | Stateless SecurityFilterChain и ограничение частоты запросов |

Domain не зависит от Spring, HTTP, провайдеров или других слоёв. Application зависит от Domain и собственных интерфейсов. API знает входной сценарий и доменные значения. Infrastructure реализует исходящий порт и собирает приложение. Контроллер не создаёт адаптер и не формирует промпт.

API использует отдельные DTO: изменения транспорта или ответа внешнего провайдера не должны менять внутренние модели. Входные ограничения находятся на границе HTTP; доменные модели дополнительно запрещают `null` и пустые значения обязательных полей. `AnalyzeTextService` передаёт данные контроллера в `AiAnalysisModule.analyze(TextContext)` и получает сырой JSON, который затем проверяет через `AnalysisResultParser`.

Логический AI-модуль расположен в `infrastructure/ai` и относится к работе напарника. Его реализация `DefaultAiAnalysisModule` вызывает `ContextPromptBuilder`, затем `LlmClient`. Сборщик формирует `LlmPrompt` с двумя независимыми частями: `systemMessage` и `context` типа `TextContext`, который содержит все пять полей запроса. `AiConfiguration` внутри модуля отвечает за загрузку промпта и подключение выбранного LLM-адаптера. Это пакет внутри одного приложения, а не отдельный сервис или Maven-модуль.

Владелец API отвечает за контроллер, DTO, валидацию, `AnalyzeTextService`, проверку ответа и ошибки. Напарник отвечает за содержимое `infrastructure/ai` и `resources/prompts`: сборку промпта, настройку модели и её вызов. Общая договорённость — `TextContext` на входе модуля и JSON с `translation` и `explanation` на выходе.

Системный промпт хранится в `src/main/resources/prompts/analysis-system.txt` и загружается `AiConfiguration` в UTF-8. Он задаёт перевод только выделенного фрагмента на русский, краткое контекстное объяснение, правила работы с неоднозначностью и формат JSON с двумя строковыми полями. Инструкции также требуют воспринимать содержимое всех пяти полей запроса как данные, включая встречающиеся в тексте команды. Книга, автор, издание, фраза и абзац передаются адаптеру отдельно как `TextContext`. `YandexLlmClient` передаёт эти данные в пользовательском сообщении, а содержимое файла — в системном. В демонстрационном режиме `StubLlmClient` возвращает фиксированный результат. После изменения файла требуется перезапуск приложения с обновлёнными ресурсами или пересборка JAR.

Запрос анализа содержит необязательные строки `book` (до 500 символов), `author` и `edition` (до 300 символов), а также обязательные непустые `selectedText` (до 2000 символов) и `context` (до 12000 символов). Сведения о книге можно не передавать, передать как `null` или оставить пустыми. В доменной модели отсутствующие сведения нормализуются в пустые строки. Полей выбора языка в запросе нет.

```json
{
  "book": "",
  "author": "",
  "edition": "",
  "selectedText": "He kicked the bucket",
  "context": "After a long illness, he finally kicked the bucket."
}
```

Ответ анализа содержит только `translation` и `explanation`. При `yandex.enabled=true` эти значения формирует модель Yandex. При выключенном флаге любой корректный запрос возвращает один и тот же демонстрационный ответ:

```json
{
  "translation": "Сыграл в ящик",
  "explanation": "Выражение 'kick the bucket' — английская идиома, означающая смерть. В данном контексте речь идет о человеке, который умер после продолжительной болезни."
}
```

`LlmClient` возвращает сырой JSON строкой. Общий `AnalysisResultParser` проверяет ответ до создания `AnalysisResponse`: корнем должен быть объект ровно с двумя полями `translation` и `explanation`, оба значения — непустые строки. Пустой ответ, `null`, неправильные типы, отсутствующие или лишние поля, повторяющиеся ключи, Markdown-обёртки и текст после JSON отклоняются. Проверка едина для всех будущих LLM-адаптеров.

Невалидный ответ модели преобразуется в `502 Bad Gateway` с `application/problem+json` и полем `code: INVALID_LLM_RESPONSE`. Ошибка в запросе самого клиента по-прежнему возвращает `400`; недоступность провайдера — `503`. Исходный ответ модели не включается в текст ошибки и прикладной лог. Пример ошибки:

```json
{
  "type": "about:blank",
  "title": "Bad Gateway",
  "status": 502,
  "detail": "Модель вернула некорректный ответ. Ожидается JSON с непустыми строками translation и explanation.",
  "code": "INVALID_LLM_RESPONSE"
}
```

Состав зависимостей не включает SDK конкретной LLM. `YandexLlmClient` использует Spring `RestClient` из существующей web-зависимости, собирает запрос с промптом и JSON-схемой и принимает результат при HTTP `200`. `YandexCompletionResponseParser` проверяет структуру транспортного ответа, завершённость генерации, роль сообщения, отсутствие отказа и извлекает неизменённый текст модели. Общий `JsonAnalysisResultParser` проверяет JSON анализа внутри этого текста. При `yandex.enabled=true` конфигурация `YandexConfiguration` создаёт HTTP-клиент и адаптер с именами `yandexLlmClient` и `analysisLlmClient`; иначе `AiConfiguration` создаёт `stubLlmClient` с тем же общим именем. AI-модуль использует `@Qualifier("analysisLlmClient")`, поэтому выбирается ровно один адаптер. Поддержка нескольких адаптеров обеспечена именованными bean и явной инъекцией; маршрутизация по запросу, fallback, A/B-тесты и динамический выбор модели отсутствуют.

В заготовке реализованы Security, Bean Validation, единый формат HTTP-ошибок, глобальный лимит анализов, health и сбор стандартных метрик. JWT/OIDC, распределённые квоты, история, PostgreSQL, иллюстрации, пользовательские модули и публикация метрик остаются следующими этапами. Локальный HTTP подходит для разработки; HTTPS при развёртывании можно завершать на reverse proxy.

Извлечение текста модели и проверка транспортного ответа Yandex реализованы в адаптере и его парсере. HTTP-клиент применяет таймауты соединения и ожидания ответа из `YandexProperties`, запрещает автоматические перенаправления и закрывается при остановке контекста. Общий срок выполнения, ограничение размера ответа и политика повторов остаются следующим этапом. `LlmUnavailableException` задаёт независимый контракт недоступности и преобразуется API в безопасный ответ `503`. Невалидный транспортный ответ и неправильный JSON анализа приводят к `InvalidLlmResponseException` и безопасному ответу `502`.

## Swagger UI и OpenAPI

Swagger UI доступен после запуска приложения по адресу `http://localhost:8080/swagger-ui.html`, спецификация OpenAPI — `http://localhost:8080/v3/api-docs`. Интеграция использует `springdoc-openapi-starter-webmvc-ui` версии 2.9.1 для Spring Boot 3. Документация и её статические ресурсы доступны без токена через Spring Security; остальные служебные пути остаются закрыты.

В Swagger отображаются `POST /api/v1/analyses` и `GET /actuator/health`. Для анализа описаны обязательные поля, ограничения длины, примеры и ответы с ошибками. Чтобы отправить запрос, раскройте POST, нажмите **Try it out**, проверьте пример JSON и нажмите **Execute**. Ответ содержит только `translation` и `explanation`. Вызовы из Swagger учитываются в общем лимите анализов.

После изменения зависимостей в IDEA выполните **Reload All Maven Projects**, затем перезапустите `AiReaderApplication`. Отдельный Postman для Swagger не требуется. Описание интеграции: [официальная документация springdoc v2](https://springdoc.org/v2/).

В текущей сети соединение с `repo.maven.apache.org` завершается ошибкой TLS `handshake_failure`. Для загрузки зависимостей и Maven-плагинов в `.mvn/settings.xml` задано HTTPS-зеркало Maven Central от Huawei Cloud. `.mvn/maven.config` подключает эти настройки только для проекта и задаёт TLS 1.2. Maven Wrapper загружает Maven с того же зеркала с проверкой закреплённой SHA-256 суммы. Глобальный файл настроек Maven не изменяется.

В IDEA откройте **Settings → Build, Execution, Deployment → Build Tools → Maven**, включите **Use settings from .mvn/maven.config** и выполните **Reload All Maven Projects**. Если импорт продолжает обращаться к старому адресу, задайте **User settings file** явно: `D:\AI-reader\AI-reader\.mvn\settings.xml`. После неудачной загрузки может потребоваться повторный импорт с `-U`, чтобы обновить кеш ошибок. Инструкция: [настройки Maven в IDEA](https://www.jetbrains.com/help/idea/maven.html).
