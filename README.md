# Record Idea Hub

Однокнопочный Android-inbox для голосовых идей и review-сессий с надёжной доставкой в [`onedayonemasterpiece/idea-hub`](https://github.com/onedayonemasterpiece/idea-hub).

## Продуктовый сценарий

1. Нажать большую кнопку и начать запись.
2. Поставить запись на паузу и продолжить ту же логическую сессию любое число раз.
3. Закрытые WAV-чанки остаются на телефоне и последовательно отправляются в `my-data-hub` на devstand.
4. `my-data-hub` проводит каждый запрос Gemini Flash-Lite через общий онлайн-limiter и возвращает структурированную расшифровку.
5. Телефон сохраняет расшифровку локально. После явного завершения `my-data-hub` делает одну подробную выжимку и один атомарный commit в `idea-hub/main`.
6. Успех показывается только после GitHub readback. Затем приложение удаляет локальные WAV-чанки.

Технические чанки и паузы не создают отдельные Markdown-файлы: одна завершённая пользовательская сессия всегда даёт одну новую pending-запись IdeaHub.

## Граница компонентов

- `android/` — запись, foreground service, пауза/продолжение, SQLite, WorkManager, локальные транскрипции, retry и лаконичный прогресс.
- `my-data-hub` — существующий devstand control-plane: прокси Gemini Lite, общий limiter, итоговый synthesis, GitHub transaction и readback.
- `idea-hub` — каноническое хранилище необработанной записи и authoritative intake registry.

В этом репозитории нет отдельного backend, Fly-приложения, серверной БД или очереди. Ключи Google, Supabase limiter и GitHub никогда не попадают в APK.

## Сборка Android

GitHub Actions выполняет lint, unit tests и сборку installable debug APK. Artifact называется:

```text
record-idea-hub-debug-apk
```

Локальный эквивалент при установленном Gradle 8.13 и Android SDK 36:

```bash
gradle -p android --no-daemon lintDebug testDebugUnitTest assembleDebug
```

## Эксплуатационные документы

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — продуктовая и техническая граница.
- [`docs/MY_DATA_HUB_INTEGRATION.md`](docs/MY_DATA_HUB_INTEGRATION.md) — серверный контракт devstand.
- [`docs/IDEA_HUB_CONTRACT.md`](docs/IDEA_HUB_CONTRACT.md) — атомарная регистрация новой записи.
- [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) — критерии готовности.
- [`docs/ADB_HANDOFF.md`](docs/ADB_HANDOFF.md) — установка и физическая проверка Samsung S21 Ultra через OpenCode.
