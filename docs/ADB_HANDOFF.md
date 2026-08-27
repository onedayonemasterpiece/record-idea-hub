# OpenCode ADB handoff

Цель: установить APK на Samsung S21 Ultra, привязать его к backend и подтвердить полный путь `голос → Gemini Flash-Lite через shared limiter → pending intake в idea-hub`.

## Входы

OpenCode должен получить вне GitHub:

```text
BACKEND_URL=https://...
DEVICE_TOKEN=...
```

Секреты Gemini, Supabase limiter и GitHub на телефон не передаются.

## Получение APK

Возьми artifact `record-idea-hub-debug-apk` из зелёного workflow `CI and APK` для merge commit в `main`. Внутри находится `app-debug.apk`.

## Установка и привязка

```bash
adb devices -l
adb install -r app-debug.apk
adb shell pm grant com.onedayonemasterpiece.recordideahub android.permission.RECORD_AUDIO
adb shell pm grant com.onedayonemasterpiece.recordideahub android.permission.POST_NOTIFICATIONS
adb shell am start \
  -n com.onedayonemasterpiece.recordideahub/.MainActivity \
  --es backend_url "$BACKEND_URL" \
  --es device_token "$DEVICE_TOKEN"
```

Не печатай `DEVICE_TOKEN` в итоговой диагностике. Приложение сохраняет его через Android Keystore.

Для backend на компьютере вместо публичного HTTPS можно использовать только debug APK:

```bash
adb reverse tcp:8080 tcp:8080
BACKEND_URL=http://127.0.0.1:8080
```

## Аппаратный acceptance

Выполни на реальном S21 Ultra:

1. Запись 20–30 секунд → пауза → продолжение → завершение. Должен появиться ровно один Markdown source packet.
2. Выключи экран на 10 минут во время записи. Таймлайн и WAV должны продолжаться.
3. Отключи сеть, запиши и заверши сессию, затем верни сеть. Доставка должна возобновиться без повторной записи.
4. Переключи Wi‑Fi ↔ мобильная сеть во время загрузки.
5. Принудительно закрой UI во время записи. Foreground service и уведомление должны сохранять управление записью.
6. Перезапусти приложение после закрытого чанка. Незавершённый `.wav.part` должен восстановиться, сессия — открыться на паузе.
7. Дождись зелёного статуса GitHub. Проверь один neutral commit в `idea-hub/main`, detail Markdown и запись `pending` в `registry/intake-sessions.yaml`.
8. После GitHub readback проверь очистку:

```bash
adb shell run-as com.onedayonemasterpiece.recordideahub \
  sh -c 'find files/audio -type f 2>/dev/null | wc -l'
```

Ожидается `0` для подтверждённых сессий.

## Диагностика

```bash
adb logcat -c
adb logcat --pid="$(adb shell pidof -s com.onedayonemasterpiece.recordideahub)"
adb shell dumpsys activity services com.onedayonemasterpiece.recordideahub
adb shell dumpsys jobscheduler | grep -A20 recordideahub
adb shell run-as com.onedayonemasterpiece.recordideahub ls -l databases files/audio
```

Фиксируй: действие, точное время, состояние UI, HTTP/Android error, наличие локального WAV, backend session ID, GitHub commit SHA. Не включай токены и ключи.

## Samsung-specific проверка

Сначала тестируй без специальных исключений батареи. Только если One UI фактически останавливает длительную запись, установи для приложения режим батареи «Без ограничений» и повтори тот же сценарий; это должно быть зафиксировано как требование эксплуатации, а не скрытая ручная правка.
