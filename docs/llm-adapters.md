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

Системное сообщение загружается из `src/main/resources/prompts/analysis-system.txt`. Сейчас файл пустой, а данные запроса доступны отдельно через `prompt.context()`: `book`, `author`, `edition`, `selectedText`, `context`. Пустой промпт не препятствует передаче данных адаптеру. Сам текст промпта можно добавить позднее, не меняя контроллер и контракт API.

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

В `infrastructure/ai/AiConfiguration.java` зарегистрируйте адаптер под отдельным именем. Например, после реализации `ProviderLlmClient` и `ProviderTransport`:

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

Явный `@Qualifier` внутри `AiConfiguration` снимает неоднозначность при нескольких bean типа `LlmClient`. Сейчас модуль использует только `stubLlmClient`. Владелец API обращается к одному `AiAnalysisModule` и не выбирает модель; универсальный реестр и runtime-переключатель пока не добавлены.

## Настройки настоящего адаптера

Ключи храните в серверных переменных окружения или менеджере секретов. Не передавайте их мобильному приложению. `.env` исключён из Git, но Spring Boot сам по себе этот файл не загружает: используйте переменные окружения, настройки IDE или Docker `--env-file`.

При подключении транспорта задайте connect/read/overall timeout, ограничьте размер ответа и число повторов. Повторяйте только временные ошибки с ограниченным backoff; учитывайте риск повторной оплаты. В заготовке эти настройки не объявлены, поскольку внешнего клиента пока нет.

После добавления адаптера проверьте его через локальный HTTP mock или подмену SDK: передачу всех полей, пустой системный промпт, корректный ответ, timeout, `429`, `5xx` и неверный JSON. Для проверки формата важны отсутствующее поле, неверный тип, пустая строка, лишнее поле, дублирующийся ключ и текст после JSON. Проверка не должна обращаться к платной модели. Выполните `./mvnw.cmd package` для проверки сборки; автоматических тестов в проекте сейчас нет.
