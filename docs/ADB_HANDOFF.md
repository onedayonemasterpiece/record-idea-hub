# OpenCode ADB handoff

Цель: установить уже собранный APK на Samsung S21 Ultra, привязать его к существующему `my-data-hub` devstand и подтвердить путь:

```text
голос -> локальные WAV/SQLite -> my-data-hub -> shared limiter -> Gemini Flash-Lite
      -> idea-hub/main readback -> локальное удаление WAV
```

OpenCode не реализует интерфейс и бизнес-логику. Любой кодовый дефект фиксируется только после воспроизводимого аппаратного наблюдения и отдельного commit в `record-idea-hub`.

## Входы

Получить вне GitHub и не печатать в итоговой диагностике:

```text
SERVER_URL=https://<devstand public host>
DEVICE_TOKEN=<single-device bearer token>
```

На телефон не передаются Google API key, Supabase service key или GitHub token.

## Получение APK

Использовать artifact `record-idea-hub-debug-apk` из последнего зелёного workflow `Android CI and APK` на `main` либо из явно указанного проверяемого feature commit.

Пример через GitHub CLI:

```bash
REPO=onedayonemasterpiece/record-idea-hub
RUN_ID="$(gh run list -R "$REPO" --workflow ci.yml --status success --limit 1 --json databaseId --jq '.[0].databaseId')"
test -n "$RUN_ID"
rm -rf .tmp-record-idea-apk
mkdir -p .tmp-record-idea-apk
gh run download -R "$REPO" "$RUN_ID" -n record-idea-hub-debug-apk -D .tmp-record-idea-apk
APK="$(find .tmp-record-idea-apk -name '*.apk' -type f -print -quit)"
test -f "$APK"
sha256sum "$APK"
```

Зафиксировать run ID, head SHA, artifact ID/name и APK SHA-256.

## Проверка devstand до установки

Не включать shell tracing (`set -x`) и не выводить `DEVICE_TOKEN`:

```bash
set +x
curl --fail --silent --show-error \
  -H "Authorization: Bearer $DEVICE_TOKEN" \
  "$SERVER_URL/voice-intake/v1/health"
```

Ожидается JSON `status=ready`, модель Flash-Lite и `server_audio_persistence=false`.

## Установка

```bash
adb devices -l
adb install -r "$APK"
adb shell pm grant \
  com.onedayonemasterpiece.recordideahub \
  android.permission.RECORD_AUDIO
```

Для Android 13+:

```bash
adb shell pm grant \
  com.onedayonemasterpiece.recordideahub \
  android.permission.POST_NOTIFICATIONS || true
```

## Привязка приложения

Приложение принимает только URL devstand и device token. Перед запуском выключить shell tracing и не копировать команду с подставленным секретом в отчёт:

```bash
set +x
adb shell am start \
  -n com.onedayonemasterpiece.recordideahub/.MainActivity \
  --es server_url "$SERVER_URL" \
  --es device_token "$DEVICE_TOKEN"
unset DEVICE_TOKEN
```

Приложение сохраняет token в encrypted preferences, ключ шифрования создаётся Android Keystore. После provisioning в UI должно появиться короткое подтверждение `my-data-hub настроен через ADB`.

Для локальной debug-проверки без публичного reverse proxy допустим только debug APK:

```bash
adb reverse tcp:8080 tcp:8080
SERVER_URL=http://127.0.0.1:8080
```

Production acceptance проводится только через HTTPS URL devstand.

## Базовый end-to-end сценарий

1. Нажать `Записать` и говорить 20–30 секунд.
2. Нажать `Пауза`.
3. Убедиться, что интерфейс показывает одну и ту же сессию и локально сохранённый чанк.
4. Нажать `Продолжить`, записать ещё 20–30 секунд.
5. Нажать `Завершить и отправить`.
6. Наблюдать стадии без перезапуска записи:
   - `Передача`;
   - `Gemini Lite распознаёт`;
   - `IdeaHub commit и readback`;
   - `IdeaHub проверено · аудио удалено`.
7. Открыть ссылку IdeaHub и проверить:
   - один neutral commit;
   - один `inbox/voice/.../<session_id>.md`;
   - один `registry/sessions/.../<session_id>.md`;
   - одну открытую entry в `registry/intake-sessions.yaml`;
   - одна пользовательская сессия, а не отдельный Markdown на каждый чанк.

## Обязательные аппаратные сценарии

### Паузы и одна логическая сессия

Сделать не менее пяти коротких pause/resume. После завершения должен появиться один Markdown и один `session_id`.

### Экран выключен

Записывать не менее десяти минут с выключенным экраном. Foreground notification должна оставаться доступной, таймлайн после возврата — соответствовать фактической записи.

### Потеря сети

1. Начать запись при работающей сети.
2. Отключить Wi-Fi и мобильные данные.
3. Поставить на паузу, продолжить и завершить сессию.
4. Убедиться, что UI прямо сообщает о сохранённых исходных данных.
5. Вернуть сеть.
6. Проверить автоматическое продолжение без новой записи и без потери чанков.

### Переключение сети

Во время обработки переключить Wi-Fi на мобильную сеть и обратно. Не должно появиться два IdeaHub commit для одной сессии.

### UI recreation

Во время записи нажать Home и удалить карточку Activity из Recent Apps, но не выполнять `am force-stop`. Foreground recording service должен сохранить запись. Повторное открытие приложения должно показать текущую сессию.

### Перезапуск после закрытого чанка

После паузы выгрузить UI и открыть приложение снова. Сессия должна восстановиться в паузе, закрытый WAV и локальный ledger — остаться доступными.

### Quota wait

При реальном 429/limiter denial интерфейс должен показать `Лимит Gemini`, конкретное время повтора и кнопку `Повторить сейчас`. До успешного повтора WAV не удаляются. Не создавать искусственный quota incident без согласования с devstand Codex.

### Очистка после readback

После статуса `IdeaHub проверено`:

```bash
adb shell run-as com.onedayonemasterpiece.recordideahub \
  sh -c 'find files/audio -type f 2>/dev/null | wc -l'
```

Для подтверждённых сессий ожидается отсутствие WAV. SQLite и маленькие transcript/receipt записи могут сохраняться как локальная история.

## Диагностика

Очистить старые логи перед воспроизведением:

```bash
adb logcat -c
PID="$(adb shell pidof -s com.onedayonemasterpiece.recordideahub)"
test -n "$PID"
adb logcat --pid="$PID"
```

Дополнительные команды:

```bash
adb shell dumpsys activity services com.onedayonemasterpiece.recordideahub
adb shell dumpsys jobscheduler | grep -A30 recordideahub
adb shell run-as com.onedayonemasterpiece.recordideahub \
  sh -c 'find files/audio -maxdepth 3 -type f -printf "%p %s bytes\n" 2>/dev/null'
adb shell run-as com.onedayonemasterpiece.recordideahub \
  ls -l databases shared_prefs files/audio
```

В отчёте фиксировать:

- точное время и действие;
- состояние четырёх строк UI;
- `session_id`;
- наличие и размер локального WAV;
- HTTP status и безопасный error code;
- GitHub commit SHA и source path;
- был ли WAV удалён только после readback.

Не включать device token, Google/Supabase/GitHub keys, аудиобайты или полный текст личной расшифровки.

## Samsung-specific проверка

Сначала тестировать со штатными настройками батареи. Только если One UI фактически останавливает длительную запись, установить для приложения режим батареи `Без ограничений` и повторить тот же сценарий. Это фиксируется как эксплуатационное требование с доказательством, а не применяется заранее.

## Результат handoff

Вернуть владельцу:

- APK artifact/run/head SHA;
- модель телефона и Android/One UI version;
- PASS/FAIL по каждому сценарию;
- IdeaHub commit/source path тестовой сессии;
- подтверждение post-readback cleanup;
- минимальный logcat-фрагмент только при дефекте;
- точный commit исправления, если аппаратная отладка потребовала кодовой правки.
