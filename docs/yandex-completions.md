# Контракт Yandex Completions API

Адаптер отправляет `POST /v1/chat/completions` на `YANDEX_BASE_URL` (по умолчанию `https://ai.api.cloud.yandex.net`).

Заголовки: `Content-Type: application/json`, `Authorization: Api-Key <YANDEX_API_KEY>`, `OpenAI-Project: <YANDEX_FOLDER_ID>`.

Поле `model` формируется как `gpt://<YANDEX_FOLDER_ID>/<YANDEX_MODEL>`. Системный промпт передаётся отдельным сообщением `system`, если он непустой. Сообщение `user` содержит JSON всех пяти полей `TextContext`: `book`, `author`, `edition`, `selectedText`, `context`.

Запрос синхронный (`stream: false`), с `temperature` и `max_tokens` из настроек. `response_format.type` — `json_schema`, `response_format.json_schema.name` — `reading_analysis`, схема берётся из `src/main/resources/schemas/analysis-result.schema.json`.

Адаптер принимает только HTTP 200, непустой массив `choices`, первое сообщение с ролью `assistant`, `finish_reason: stop`, непустой строковый `content` и отсутствие непустого `refusal`. Пустой ответ, дублирующиеся ключи и текст после JSON отклоняются. Затем общий `JsonAnalysisResultParser` проверяет JSON анализа внутри `content`: ровно два поля `translation` и `explanation`, оба — непустые строки.

HTTP-ошибки и сбои соединения преобразуются в `LlmUnavailableException`. Статус провайдера сохраняется отдельно; его тело и исходное исключение с потенциально чувствительными данными не сохраняются. Приложение возвращает 503 с категорией ошибки. Невалидный ответ модели приводит к 502.

Соединение и чтение ограничены таймаутами. Перенаправления запрещены, автоматических повторов нет. API-ключ не отправляется при запуске приложения: платный запрос выполняется только при обращении к анализу.

Протокол основан на [официальной инструкции структурированного вывода](https://aistudio.yandex.ru/ru/docs/ai-studio/operations/generation/completions-structured).
