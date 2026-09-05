# Record Idea Hub

Однокнопочный Android-inbox для голосовых идей и review-сессий с надёжной публикацией в [`onedayonemasterpiece/idea-hub`](https://github.com/onedayonemasterpiece/idea-hub).

> RC4 — кандидат с рефакторингом доставки, а не заявление о внедрении на телефон.
> Изменения, порядок обновления и обязательные физические проверки:
> [`docs/RELIABILITY_20260905.md`](docs/RELIABILITY_20260905.md).
> Сначала требуется серверная часть `my-data-hub#39`. Перед установкой необходимо
> сверить подпись APK; удалять приложение с незавершёнными записями нельзя.

## Рабочий контур

```text
Android 1.1
  ├─ локальная запись AAC-LC/M4A, mono 16 kHz, 32 kbit/s
  ├─ ручная пауза и автоматический пропуск длительной тишины
  ├─ SQLite + отдельная очередь каждой сессии + durable-сегменты
  ├─ пользовательская передача: UIDT (API 34+) / foreground WorkManager
  └─ HTTPS /voice-intake/v2
          │
          ▼
my-data-hub на devstand
  ├─ durable receipts и временный spool
  ├─ shared Google AI limiter и quota-aware key selection
  ├─ 1 Gemini Flash-Lite запрос на полную транскрипцию
  ├─ 1 Gemini Flash-Lite запрос на подробную выжимку
  └─ atomic IdeaHub commit + exact/current-main readback
          │
          ▼
idea-hub/main
```

Для типичного review продолжительностью до примерно 20 минут количество технических аудиосегментов и пауз не увеличивает число вызовов Gemini: штатный успешный путь использует ровно два `generateContent` запроса. Отказы по квоте и другие ошибки учитываются отдельно.

## Пользовательский сценарий

1. Нажать большую кнопку и начать запись.
2. При необходимости использовать ручную паузу; автоматический режим сам не сохраняет длительную тишину и неречевой шум.
3. Нажать `Завершить и отправить`.
4. Доставка выполняется отдельно от микрофона. После полного приёма обработкой управляет сервер; телефон узнаёт результат при доступной связи.
5. Локальное аудио удаляется только после `published_verified`, подтверждённого GitHub readback и серверного purge.

Одна логическая сессия всегда создаёт одну открытую pending-запись IdeaHub. Паузы и транспортные сегменты не создают отдельные Markdown-файлы. Системная остановка приложения и отсутствие сети могут отложить доставку; устойчивое возобновление не означает непрерываемый процесс.

## Граница компонентов

- `android/` — AudioRecord, лёгкий WebRTC VAD, AAC/M4A, foreground service, SQLite, UIDT/WorkManager, UI и локальная надёжность.
- `my-data-hub` — существующий control-plane: приём сегментов, общий limiter, Gemini Lite, временный spool, IdeaHub transaction и readback.
- `idea-hub` — каноническая необработанная запись и authoritative intake registry.

В APK нет Google, Supabase или GitHub credentials. Телефон получает только публичный HTTPS origin devstand и один device bearer token.

## Совместимость

- Android 1.1 использует `/voice-intake/v2`.
- Незавершённые локальные сессии Android 1.0 могут быть допубликованы через сохранённый `/voice-intake/v1` compatibility path.
- Сохраняется package `com.onedayonemasterpiece.recordideahub.v11`.
- RC4 мигрирует SQLite v3 в v4 с сохранением очереди. Совместимость подписи конкретного установленного APK проверяется отдельно: CI debug certificate не является постоянной подписью выпуска.

## Сборка

GitHub Actions выполняет:

```text
lintDebug
testDebugUnitTest
assembleDebug
```

Текущий artifact workflow:

```text
record-idea-hub-1.1-rc4-apk
record-idea-hub-verification
```

Артефакты содержат SHA исходников, SHA256 APK, публичный fingerprint сертификата и отчёты проверок. Приватный ключ не публикуется.

Локальный эквивалент при Gradle 8.13 и Android SDK 36:

```bash
gradle -p android --no-daemon lintDebug testDebugUnitTest assembleDebug
```

## Статус

Рабочий цикл Android 1.1 ранее принят на Samsung S21 Ultra. После инцидента
2026-09-03 в RC3 добавлен ограниченный 50 ms wall-clock jitter между соседними
сегментами при строго непрерывном audio timeline. RC4 сохраняет это исправление
и добавляет рефакторинг доставки. Приёмка прошлой версии не доказывает поведение
RC4 при погасшем экране: эта проверка, установка APK и серверное внедрение
должны быть выполнены отдельно.

Наблюдения по маске иконки и нефатальному сообщению Samsung `MPEG4Writer` при
валидном M4A не исправлялись в пакете доставки. Восстановление незакрытого
M4A-хвоста и постоянная подпись распространения тоже не входят в этот пакет.

## Документация

- [`docs/RELIABILITY_20260905.md`](docs/RELIABILITY_20260905.md) — RC4, серверная зависимость и границы приёмки.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — базовая архитектура и инварианты.
- [`docs/MY_DATA_HUB_INTEGRATION.md`](docs/MY_DATA_HUB_INTEGRATION.md) — API v2 и серверная граница.
- [`docs/IDEA_HUB_CONTRACT.md`](docs/IDEA_HUB_CONTRACT.md) — атомарная регистрация записи.
- [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) — историческая приёмка Android 1.1/RC3.
- [`docs/ADB_HANDOFF.md`](docs/ADB_HANDOFF.md) — базовая установка и диагностика; дополнительные требования RC4 см. выше.
