# Record Idea Hub

Однокнопочный Android-inbox для голосовых идей и review-сессий с надёжной публикацией в [`onedayonemasterpiece/idea-hub`](https://github.com/onedayonemasterpiece/idea-hub).

## Текущий рабочий контур

```text
Android 1.1
  ├─ локальная запись AAC-LC/M4A, mono 16 kHz, 32 kbit/s
  ├─ ручная пауза и автоматический пропуск длительной тишины
  ├─ SQLite + WorkManager + локальные durable-сегменты
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

Для типичного review продолжительностью до примерно 20 минут количество технических аудиосегментов и пауз не увеличивает число вызовов Gemini: штатный успешный путь использует ровно два `generateContent` запроса.

## Пользовательский сценарий

1. Нажать большую кнопку и начать запись.
2. При необходимости использовать ручную паузу; автоматический режим сам не сохраняет длительную тишину и неречевой шум.
3. Нажать `Завершить и отправить`.
4. Дождаться стадий `Передача → Расшифровка → Выжимка → IdeaHub readback`.
5. Локальное аудио удаляется только после `published_verified`, подтверждённого GitHub readback и серверного purge.

Одна логическая сессия всегда создаёт одну открытую pending-запись IdeaHub. Паузы и транспортные сегменты не создают отдельные Markdown-файлы.

## Граница компонентов

- `android/` — AudioRecord, лёгкий WebRTC VAD, AAC/M4A, foreground service, SQLite, WorkManager, UI и локальная надёжность.
- `my-data-hub` — существующий control-plane: приём сегментов, общий limiter, Gemini Lite, временный spool, IdeaHub transaction и readback.
- `idea-hub` — каноническая необработанная запись и authoritative intake registry.

В APK нет Google, Supabase или GitHub credentials. Телефон получает только публичный HTTPS origin devstand и один device bearer token.

## Совместимость

- Android 1.1 использует `/voice-intake/v2`.
- Незавершённые локальные сессии Android 1.0 могут быть допубликованы через сохранённый `/voice-intake/v1` compatibility path.
- Текущий физически проверенный side-by-side package: `com.onedayonemasterpiece.recordideahub.v11`.

## Сборка

GitHub Actions выполняет:

```text
lintDebug
testDebugUnitTest
assembleDebug
```

Текущий artifact workflow:

```text
record-idea-hub-1.1-rc3-apk
```

Локальный эквивалент при Gradle 8.13 и Android SDK 36:

```bash
gradle -p android --no-daemon lintDebug testDebugUnitTest assembleDebug
```

## Статус

Рабочий цикл Android 1.1 принят на Samsung S21 Ultra и слит в `main`. После
инцидента 2026-09-03 клиент допускает только ограниченный 50 ms wall-clock
jitter между соседними сегментами, сохраняя строго непрерывный audio timeline.
Зафиксированные неблокирующие наблюдения — частичное обрезание оранжевого
маркера одной OEM-маской launcher и нефатальное сообщение Samsung
`MPEG4Writer` при валидном M4A — отложены до отдельной будущей доработки, если
она понадобится.

## Документация

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — текущая архитектура и инварианты.
- [`docs/MY_DATA_HUB_INTEGRATION.md`](docs/MY_DATA_HUB_INTEGRATION.md) — API v2 и серверная граница.
- [`docs/IDEA_HUB_CONTRACT.md`](docs/IDEA_HUB_CONTRACT.md) — атомарная регистрация записи.
- [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) — принятые gates и текущий статус.
- [`docs/ADB_HANDOFF.md`](docs/ADB_HANDOFF.md) — установка и диагностика следующей сборки.
