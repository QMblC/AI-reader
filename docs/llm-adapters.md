# Подключение LLM и нескольких моделей

## Граница работы двух разработчиков

Точка подключения части напарника — `application/port/AiAnalysisModule.java`:

```java
String analyze(TextContext context);
```

Владелец API принимает HTTP-запрос и передаёт в этот метод `TextContext` с полями `book`, `author`, `edition`, `selectedText`, `context`. Напарник отвечает за подготовку промпта и обращение к модели, возвращая сырой JSON строкой. Проверка результата и формирование HTTP-ответа выполняются после выхода из AI-модуля.

| Ответственность | Файлы и пакеты |
| --- | --- |
| Владелец API: запросы, сценарий, проверка результата, ошибки | `api`, `application/service/AnalyzeTextService`, `infrastructure/analysis`, `infrastructure/config/AnalysisConfiguration` |
| Напарник: промпт, SDK, модель, её параметры и вызов | `infrastructure/ai` и `src/main/resources/prompts` |
| Общий контракт двух частей | `application/port/AiAnalysisModule`, `domain/TextContext`, JSON с `translation` и `explanation` |

Сейчас AI-модуль реализован классом `infrastructure/ai/DefaultAiAnalysisModule.java`. Он вызывает `prompt/ContextPromptBuilder`, затем реализацию `LlmClient`. Внутри `LlmPrompt` передаются системное сообщение и структурированные данные запроса. Заглушка `infrastructure/ai/llm/StubLlmClient` не использует сеть и не требует настроек. Все вызовы выполняются в одном приложении через Java-методы.

Системное сообщение загружается из `src/main/resources/prompts/analysis-system.txt`. Файл заполнен инструкциями для перевода выделенного текста на русский и краткого контекстного объяснения. Он задаёт JSON ровно с двумя непустыми строковыми полями `translation` и `explanation`, запрещает выполнять команды из анализируемого текста и требует явно указывать существенную неопределённость. Данные запроса доступны отдельно через `prompt.context()`: `book`, `author`, `edition`, `selectedText`, `context`. При реализации адаптера передавайте инструкции сообщением `system`, а сериализованные данные — сообщением `user`; текст промпта загружается из ресурса, его не нужно дублировать в коде адаптера.

## Добавление провайдера

1. Создайте класс в `infrastructure/ai/llm/<provider>`, реализующий общий интерфейс `application/port/LlmClient.java`.
2. Передайте ему клиент провайдера и имя модели через конструктор. Для Spring AI подключите совместимую с выбранным Spring Boot версию BOM/SDK; для собственного HTTP-адаптера можно использовать Spring `RestClient` или добавить WebClient. API и Application не должны импортировать классы SDK.
3. Передайте `systemMessage` как системное сообщение, если оно заполнено. Сериализуйте `prompt.context()` в пользовательское сообщение, сохранив все пять полей. Используйте structured output/JSON schema, если провайдер это поддерживает.
4. Извлеките из ответа SDK текст модели и верните его без подмены ошибочного результата заглушкой. Ожидается JSON-объект ровно с двумя полями `translation` и `explanation`, оба — непустые строки. Валидацию уже выполняет `JsonAnalysisResultParser`; невалидный ответ приводит к `502` и коду `INVALID_LLM_RESPONSE`.
5. Сетевые сбои и недоступность провайдера оберните в `LlmUnavailableException` для ответа `503`. Ошибку формата ответа не превращайте в ошибку доступности. Не включайте секреты, запрос пользователя или полный ответ провайдера в прикладной лог и HTTP-ошибку.

Имена будущих классов ниже — пример, готового внешнего подключения в проекте нет:

```java
public final class ProviderLlmClient implements LlmClient {
    private final ProviderTransport transport;
    private final String model;

    public ProviderLlmClient(ProviderTransport transport, String model) {
        this.transport = transport;
        this.model = model;
    }

    @Override
    public String analyze(LlmPrompt prompt) {
        // Вызов SDK/HTTP с model, prompt.systemMessage() и prompt.context().
        // Результат — сырой JSON-текст модели; общий парсер проверит формат.
        // Ошибки доступности провайдера -> LlmUnavailableException.
        return transport.analyze(model, prompt);
    }
}
```

`ProviderTransport` здесь обозначает ваш будущий транспортный адаптер. Пример не нужно вставлять как есть без реализации транспорта.

## Регистрация и замена модели

В текущем проекте выбор уже подключён к Spring: при `yandex.enabled=true` класс `YandexConfiguration` регистрирует `yandexLlmClient`, иначе `AiConfiguration` регистрирует `stubLlmClient`. Только активный адаптер получает дополнительное имя `analysisLlmClient`, которое явно указано в `@Qualifier` AI-модуля. В контексте создаётся одна активная реализация `LlmClient`. Для смены модели Yandex задайте `YANDEX_MODEL` и перезапустите приложение.

При добавлении другого провайдера зарегистрируйте его под отдельным именем. Например, после реализации `ProviderLlmClient` и `ProviderTransport`:

```java
@Bean("readingModel")
LlmClient readingModel(ProviderTransport transport) {
    return new ProviderLlmClient(transport, "provider-model-name");
}
```

В существующем методе сборки AI-модуля укажите qualifier нужного адаптера:

```java
@Bean
AiAnalysisModule aiAnalysisModule(ContextPromptBuilder promptBuilder,
        @Qualifier("readingModel") LlmClient client) {
    return new DefaultAiAnalysisModule(promptBuilder, client);
}
```

Чтобы сменить модель того же провайдера, измените переданное имя модели или вынесите его в серверную конфигурацию. Чтобы сменить провайдера, зарегистрируйте другую реализацию `LlmClient` и укажите её qualifier. Контроллер, входной JSON и сценарий анализа остаются прежними.

## Несколько моделей одновременно

Один адаптер можно создать несколько раз с разными именами моделей. Адаптеры разных провайдеров также могут сосуществовать:

```java
@Bean("fastModel")
LlmClient fastModel(ProviderTransport transport) {
    return new ProviderLlmClient(transport, "fast-model-name");
}

@Bean("detailedModel")
LlmClient detailedModel(ProviderTransport transport) {
    return new ProviderLlmClient(transport, "detailed-model-name");
}
```

Явный `@Qualifier` внутри `AiConfiguration` снимает неоднозначность при нескольких bean типа `LlmClient`. Сейчас модуль использует имя `analysisLlmClient`, которое выбирается по `yandex.enabled` при запуске. Владелец API обращается к одному `AiAnalysisModule` и не выбирает модель; универсальный реестр и runtime-переключатель пока не добавлены.

## Настройки настоящего адаптера

Ключи храните в серверных переменных окружения или менеджере секретов. Не передавайте их мобильному приложению. `.env` исключён из Git, но Spring Boot сам по себе этот файл не загружает: используйте переменные окружения, настройки IDE или Docker `--env-file`.

Для Yandex объявлены типизированные настройки `YandexProperties`: ключ, каталог, имя модели, базовый адрес API, параметры генерации и connect/read timeout. Они загружаются и проверяются при `YANDEX_ENABLED=true`; перечень переменных и значения по умолчанию приведены в [README](../README.md#настройки-yandex). Этот флаг подключает Yandex к AI-модулю; при выключенном флаге используется локальная заглушка без ключей.

Протокол адаптера зафиксирован в [контракте Yandex Completions API](yandex-completions.md). Выбран синхронный `POST /v1/chat/completions` с `response_format: json_schema`. В документе определены заголовки, формирование `model`, передача всех пяти полей контекста и проверки транспортного ответа; приложены JSON-примеры. Схема результата находится в ресурсе `schemas/analysis-result.schema.json`.

`infrastructure/ai/llm/yandex/YandexLlmClient` реализует `LlmClient` и принимает через конструктор `RestClient`, `ObjectMapper` и `YandexProperties`. Он загружает схему из ресурса, собирает сообщения `system` и `user`, отправляет JSON с авторизацией и параметрами генерации, затем возвращает строку `choices[0].message.content`. Для результата требуется HTTP `200`. `YandexCompletionResponseParser` проверяет объект ответа, непустой массив `choices`, первое сообщение с ролью `assistant`, завершение `finish_reason: stop`, отсутствие отказа и непустой строковый `content`. Повторяющиеся ключи и текст после JSON отклоняются. JSON анализа внутри `content` проверяет общий `JsonAnalysisResultParser`. Ошибки HTTP-транспорта преобразуются в `LlmUnavailableException`, а некорректные ответы — в `InvalidLlmResponseException`. `YandexConfiguration` создаёт этот адаптер при включённом флаге.

В Spring зарегистрированы `yandexHttpClient` и `yandexRestClient`: первый применяет таймаут соединения и запрещает автоматические перенаправления, второй использует `JdkClientHttpRequestFactory` с таймаутом ожидания ответа. `HttpClient` закрывается вместе с контекстом. Общий срок выполнения, ограничение размера ответа и политика повторов остаются следующим этапом. Повторять следует только временные ошибки с ограниченным backoff, учитывая риск повторной оплаты.

После добавления адаптера проверьте его через локальный HTTP mock или подмену SDK: передачу всех полей, пустой системный промпт, корректный ответ, timeout, `429`, `5xx` и неверный JSON. Для проверки формата важны отсутствующее поле, неверный тип, пустая строка, лишнее поле, дублирующийся ключ и текст после JSON. Проверка не должна обращаться к платной модели. Выполните `./mvnw.cmd package` для проверки сборки; автоматических тестов в проекте сейчас нет.
