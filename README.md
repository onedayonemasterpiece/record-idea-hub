# Record Idea Hub

Однокнопочный Android‑inbox для голосовых идей и review-сессий с надёжной доставкой в [`onedayonemasterpiece/idea-hub`](https://github.com/onedayonemasterpiece/idea-hub).

## Что делает MVP

1. Записывает одну логическую сессию с паузой и продолжением.
2. Локально сохраняет восстанавливаемые WAV-чанки и отправляет закрытые чанки во время записи.
3. Распознаёт каждый чанк и делает подробную итоговую выжимку одной моделью Gemini Flash‑Lite.
4. Все вызовы Gemini проходят через общий онлайн-limiter из `events-bot-new`; обход limiter в production запрещён.
5. Атомарно создаёт один Markdown source packet и открытую `pending`-запись в `idea-hub/registry/intake-sessions.yaml`.
6. Считает доставку успешной только после GitHub readback точного commit.
7. После подтверждения удаляет аудио и на телефоне, и на backend.

## Структура

- `android/` — лаконичное приложение для Samsung S21 Ultra и других Android 10+ устройств.
- `backend/` — приём чанков, Gemini Lite, shared limiter, Markdown и GitHub transaction.
- `docs/` — архитектура, контракт IdeaHub, acceptance и handoff для установки через ADB.

## Быстрая проверка backend

```bash
cd backend
python -m pip install -e '.[dev]'
pytest
```

Сборка APK выполняется GitHub Actions. Ключи Gemini, Supabase limiter и GitHub никогда не попадают в APK или публичный репозиторий.

## Сборка Android

GitHub Actions запускает тесты и публикует artifact `record-idea-hub-debug-apk`. Локальный эквивалент при установленном Gradle 8.13 и Android SDK 36:

```bash
gradle -p android testDebugUnitTest assembleDebug
```

Аппаратная установка и проверка описаны в `docs/ADB_HANDOFF.md`.
