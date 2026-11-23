# Гостевой доступ к AI-reader для мобильного клиента

Пользователь может читать и пользоваться ИИ без аккаунта. При первом обращении к ИИ приложение подтверждает свою подлинность через Firebase App Check и открывает гостевую сессию. Сервер привязывает к сессии квоты, кэш анализа и доступ к иллюстрациям.

## Настройка приложения

1. Получите у команды бэкенда URL окружения и конфигурацию Firebase для вашей сборки. Адрес API в проде должен использовать HTTPS.
2. Подключите Firebase App Check. Для Android настройте Play Integrity и проверьте релизную сборку из каждого поддерживаемого канала распространения. Для iOS подключите App Attest. Бэкенд разрешает только явно перечисленные Firebase App ID. [Android](https://firebase.google.com/docs/app-check/android/play-integrity-provider) · [iOS](https://firebase.google.com/docs/app-check/ios/app-attest-provider)
3. Получайте свежий токен через SDK App Check и передавайте его в заголовке `X-Firebase-AppCheck` с каждым запросом к гостевому API, анализу и иллюстрациям. SDK управляет кэшем этого токена. [Android](https://firebase.google.com/docs/app-check/android/custom-resource) · [iOS](https://firebase.google.com/docs/app-check/ios/custom-resource)

Для разработки используйте debug provider в отдельном проекте Firebase для тестового окружения. Зарегистрируйте отладочный секрет в этом проекте, затем получайте токен App Check через SDK. В запросах передавайте выданный SDK токен App Check. Храните отладочные секреты в настройках разработчика или CI. [Android](https://firebase.google.com/docs/app-check/android/debug-provider) · [iOS](https://firebase.google.com/docs/app-check/ios/debug-provider)

## Сессия и заголовки запросов

| Операция | Метод и путь | `Authorization` | Ответ |
| --- | --- | --- | --- |
| Открыть сессию | `POST /api/v1/guest-sessions` | — | `201`, пара токенов и сроки действия |
| Обновить токен доступа | `POST /api/v1/guest-sessions/refresh` | `Bearer <refreshToken>` | `200`, новый `accessToken` и `accessExpiresAt` |
| Анализ текста | `POST /api/v1/analyses` | `Bearer <accessToken>` | `200`, `translation` и `explanation` |
| Создать иллюстрацию | `POST /api/v1/illustrations` | `Bearer <accessToken>` | `202` для задания в работе, `200` для завершённого задания |
| Проверить статус | `GET /api/v1/illustrations/{id}` | `Bearer <accessToken>` | `202` в работе, `200` со статусом `READY` или `FAILED` |
| Скачать иллюстрацию | `GET /api/v1/illustrations/{id}/image` | `Bearer <accessToken>` | `200`, PNG или JPEG |

Создание и обновление сессии идут без тела запроса. Для открытия сессии достаточно заголовка App Check.

```http
POST /api/v1/guest-sessions HTTP/1.1
Host: api.example.com
X-Firebase-AppCheck: <app-check-token>
```

```json
{
  "accessToken": "<access-token>",
  "accessExpiresAt": "2026-11-23T08:30:00Z",
  "refreshToken": "<refresh-token>",
  "sessionExpiresAt": "2026-12-23T08:00:00Z"
}
```

Сохраняйте `refreshToken` и `sessionExpiresAt` в защищённом хранилище приложения. Токен доступа можно держать в памяти. При первом запуске после перезапуска приложения получите новый токен доступа с сохранённым `refreshToken`. По умолчанию токен доступа действует 30 минут, а сессия — 30 дней с момента создания. Ориентируйтесь на даты в ответе. Обновление токена сохраняет гостевую сессию и не продлевает её срок. Храните текущий `refreshToken` после обновления доступа.

Используйте один общий запрос на создание или обновление сессии для всех одновременно ожидающих запросов. Обновляйте токен незадолго до `accessExpiresAt`. При истечении срока сессии создайте новую. Доступ к иллюстрациям предыдущей сессии при этом теряется. Храните токены вне логов, аналитики и URL.

При анализе текста отправьте токен доступа и токен App Check вместе с JSON.

```http
POST /api/v1/analyses HTTP/1.1
Host: api.example.com
X-Firebase-AppCheck: <app-check-token>
Authorization: Bearer <access-token>
Content-Type: application/json

{"selectedText":"old castle","context":"They saw the old castle near the river."}
```

## Иллюстрации и повторы

После `202` опрашивайте относительный адрес из `Location` с паузой из `Retry-After` в секундах. Когда `status` станет `READY`, скачайте относительный `imageUrl` через тот же HTTP-клиент с обоими заголовками. При `FAILED` остановите опрос и покажите ошибку по `errorCode`. Структура запроса и варианты `style` и `size` описаны отдельно. [Контракт иллюстраций](illustrations.md#api-и-сессия)

После сетевого сбоя повторяйте исходный JSON с той же гостевой сессией. Пока результат есть в кэше, сервер возвращает тот же анализ или то же задание без новой генерации. Кэш хранится до 48 часов. Короткое хранение ошибочного задания даёт приложению возможность показать ошибку и избежать немедленного нового вызова. Обычный минутный лимит на POST учитывает и повторы.

## Ошибки доступа и лимиты

Читайте поле `code` в ответах `application/problem+json`.

| HTTP и код | Действие клиента |
| --- | --- |
| `401 APP_CHECK_REQUIRED` или `APP_CHECK_INVALID` | Получите новый App Check токен через SDK и повторите запрос один раз. При повторном отказе покажите ошибку проверки приложения. |
| `401 GUEST_ACCESS_INVALID` | Обновите доступ через `/guest-sessions/refresh`, затем повторите исходный запрос один раз. |
| `401 GUEST_REFRESH_INVALID` | Очистите данные старой сессии и откройте новую для следующих операций. Для старых иллюстраций покажите, что результат недоступен. |
| `404 ILLUSTRATION_NOT_FOUND` | Прекратите опрос. Задание истекло или относится к другой сессии. |
| `429 GUEST_QUOTA_EXCEEDED` или `GENERATION_BUDGET_EXHAUSTED` | Покажите, когда можно снова запустить новую генерацию. `Retry-After` содержит число секунд до сброса квоты в полночь UTC. |
| `429 REQUEST_RATE_LIMIT`, `GUEST_SESSION_RATE_LIMIT` или `GUEST_REFRESH_RATE_LIMIT` | Примените паузу из `Retry-After` до следующей попытки. |
| `429 ILLUSTRATION_BUSY` | Предложите повторить позднее. При автоматическом повторе используйте увеличивающуюся задержку. |
| `503 APP_CHECK_UNAVAILABLE` | Покажите временную недоступность. Используйте увеличивающуюся задержку перед повтором. |

## Настройка тестового бэкенда

Заполните `APP_CHECK_PROJECT_NUMBER` числовым номером проекта Firebase и `APP_CHECK_ALLOWED_APP_IDS` списком разрешённых Android и iOS App ID через запятую. Например, `1:123456789012:android:abc123,1:123456789012:ios:def456`. Для запуска нужна база PostgreSQL. Flyway создаёт таблицы гостевых сессий и квот. Сервер получает публичные ключи проверки по HTTPS с `firebaseappcheck.googleapis.com`. [Переменные окружения](../.env.example) · [Проверка токенов](https://firebase.google.com/docs/app-check/custom-resource-backend)

Для проверки анализа без вызовов модели задайте `YANDEX_ENABLED=false`. По умолчанию гость может начать до 20 анализов и 3 иллюстраций в сутки, а общие потолки для всех гостей составляют 200 анализов и 30 иллюстраций. Для продакшена подберите `GUEST_*_PER_DAY` и `TOTAL_*_PER_DAY` под бюджет проекта. Квоты резервируются перед запуском новой генерации, поэтому принятая попытка с ошибкой может расходовать квоту. Настройте HTTPS перед Spring Boot и закройте прямой доступ к его HTTP-порту снаружи.
