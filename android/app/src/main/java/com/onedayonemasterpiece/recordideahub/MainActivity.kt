package com.onedayonemasterpiece.recordideahub

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class MainActivity : Activity() {
    private val store by lazy { AppGraph.store(this) }
    private val config by lazy { AppGraph.config(this) }
    private val runtime by lazy { RecordingRuntime(this) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var timer: TextView
    private lateinit var captureCaption: TextView
    private lateinit var primary: Button
    private lateinit var finish: Button
    private lateinit var retry: Button
    private lateinit var captureStatus: TextView
    private lateinit var uploadStatus: TextView
    private lateinit var transcriptionStatus: TextView
    private lateinit var githubStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resultLink: TextView
    private var pendingStart = false
    private var captureAnimator: AnimatorSet? = null
    private var timerAnimator: ObjectAnimator? = null
    private var animatedMode: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(RecordingService.EXTRA_MESSAGE)
                ?.takeIf(String::isNotBlank)
                ?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            refresh()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        consumeProvisioningIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let(::consumeProvisioningIntent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(RecordingService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        handler.post(tick)
        SyncScheduler.enqueue(this)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(receiver) }
        stopAnimations()
        super.onPause()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(22))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val top = FrameLayout(this)
        content.addView(top, LinearLayout.LayoutParams(-1, dp(48)))
        top.addView(
            TextView(this).apply {
                text = "Record Idea Hub"
                textSize = 20f
                setTextColor(FOREGROUND)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(-2, -1, Gravity.START),
        )
        top.addView(
            Button(this).apply {
                text = "⚙"
                textSize = 20f
                setTextColor(MUTED)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Настройки my-data-hub"
                setOnClickListener { showSettings() }
            },
            FrameLayout.LayoutParams(dp(56), -1, Gravity.END),
        )

        timer = TextView(this).apply {
            text = "00:00"
            textSize = 42f
            setTextColor(FOREGROUND)
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        content.addView(timer, LinearLayout.LayoutParams(-1, dp(68)))
        captureCaption = TextView(this).apply {
            text = "Готово к записи"
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        content.addView(captureCaption, LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(8) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(CARD, 18f)
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        captureStatus = statusRow(card)
        uploadStatus = statusRow(card)
        transcriptionStatus = statusRow(card)
        githubStatus = statusRow(card)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(8) })

        retry = Button(this).apply {
            text = "Повторить сейчас"
            isAllCaps = false
            textSize = 14f
            setTextColor(FOREGROUND)
            background = rounded(SECONDARY, 16f)
            visibility = View.GONE
            setOnClickListener { retryLatest() }
        }
        content.addView(retry, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })
        content.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        primary = Button(this).apply {
            text = "Записать"
            isAllCaps = false
            textSize = 23f
            setTextColor(Color.BLACK)
            background = rounded(ACTIVE, 120f)
            setOnClickListener { onPrimary() }
        }
        content.addView(primary, LinearLayout.LayoutParams(dp(220), dp(220)))

        finish = Button(this).apply {
            text = "Завершить и отправить"
            isAllCaps = false
            textSize = 16f
            setTextColor(FOREGROUND)
            background = rounded(SECONDARY, 18f)
            visibility = View.GONE
            setOnClickListener { RecordingService.command(this@MainActivity, RecordingService.ACTION_FINISH) }
        }
        content.addView(finish, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })

        resultLink = TextView(this).apply {
            text = "Открыть запись в IdeaHub"
            textSize = 15f
            setTextColor(LINK)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                val url = store.latestSession()?.githubUrl ?: return@setOnClickListener
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        content.addView(resultLink, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        setContentView(root)
    }

    private fun statusRow(parent: LinearLayout): TextView = TextView(this).also { view ->
        view.textSize = 14.5f
        view.setTextColor(MUTED)
        view.setPadding(0, dp(3), 0, dp(3))
        view.maxLines = 2
        parent.addView(view, LinearLayout.LayoutParams(-1, dp(34)))
    }

    private fun refresh() {
        val active = store.activeSession()
        val latest = active ?: store.latestSession()
        if (latest == null) {
            timer.text = "00:00"
            captureCaption.text = "Готово к записи"
            captureStatus.text = "Запись            ○ не идёт"
            uploadStatus.text = "Передача        ○ нет данных"
            transcriptionStatus.text = "Gemini Lite    ○ нет данных"
            githubStatus.text = "IdeaHub           ○ нет данных"
            progress.progress = 0
            primary.text = "Записать"
            finish.visibility = View.GONE
            retry.visibility = View.GONE
            resultLink.visibility = View.GONE
            animateMode(CaptureActivity.IDLE, CaptureState.FINISHED)
            return
        }

        val runtimeSnapshot = runtime.snapshotFor(latest.sessionId)
        val duration = max(latest.durationMs, runtimeSnapshot?.durationMs ?: 0L)
        val activity = if (active?.captureState == CaptureState.RECORDING) {
            runtimeSnapshot?.captureActivity ?: latest.captureActivity
        } else {
            latest.captureActivity
        }
        timer.text = formatDuration(duration)
        captureCaption.text = captureCaption(latest.captureState, activity)
        captureStatus.text = captureLine(latest.captureState, activity)

        val total = latest.chunkCount.coerceAtLeast(1)
        uploadStatus.text = "Передано        ${mark(latest.chunksUploaded >= latest.chunkCount && latest.chunkCount > 0)} " +
            "${latest.chunksUploaded}/${latest.chunkCount} · AAC"
        transcriptionStatus.text = transcriptionLine(latest)
        githubStatus.text = processLabel(latest)
        val capturePart = if (latest.captureState == CaptureState.FINISHED) 20 else 8
        val uploadPart = (20.0 * latest.chunksUploaded / total).toInt().coerceIn(0, 20)
        val geminiPart = when (latest.remoteState) {
            RemoteState.TRANSCRIBING -> 10
            RemoteState.SUMMARIZING -> 26
            RemoteState.PUBLISHING, RemoteState.VERIFYING -> 35
            RemoteState.PUBLISHED_VERIFIED -> 35
            else -> 0
        }
        val githubPart = when (latest.remoteState) {
            RemoteState.PUBLISHING, RemoteState.VERIFYING -> 12
            RemoteState.PUBLISHED_VERIFIED -> 25
            else -> 0
        }
        progress.progress = (capturePart + uploadPart + geminiPart + githubPart).coerceIn(0, 100)

        when (active?.captureState) {
            CaptureState.RECORDING -> {
                primary.text = "Пауза"
                finish.visibility = View.VISIBLE
            }
            CaptureState.PAUSED -> {
                primary.text = "Продолжить"
                finish.visibility = View.VISIBLE
            }
            else -> {
                primary.text = "Новая запись"
                finish.visibility = View.GONE
            }
        }
        retry.visibility = if (latest.remoteState in setOf(
                RemoteState.WAITING_FOR_QUOTA,
                RemoteState.RETRYABLE_ERROR,
                RemoteState.RECONCILIATION_REQUIRED,
            )) View.VISIBLE else View.GONE
        resultLink.visibility = if (
            latest.remoteState == RemoteState.PUBLISHED_VERIFIED && latest.githubUrl != null
        ) View.VISIBLE else View.GONE
        animateMode(activity, latest.captureState)
    }

    private fun captureCaption(captureState: String, activity: String): String = when {
        captureState == CaptureState.PAUSED -> "Ручная пауза · микрофон выключен"
        captureState == CaptureState.FINISHED -> "Сессия сохранена · идёт обработка"
        activity == CaptureActivity.AUTO_SILENCE -> "Слушаю · тишина и шум не записываются"
        activity == CaptureActivity.FALLBACK_CONTINUOUS -> "Автопропуск недоступен · записываю всё"
        activity == CaptureActivity.VOICE -> "Записываю голос"
        else -> "Запись идёт"
    }

    private fun captureLine(captureState: String, activity: String): String = when {
        captureState == CaptureState.PAUSED -> "Запись            Ⅱ ручная пауза"
        captureState == CaptureState.FINISHED -> "Запись            ✓ сохранена локально"
        activity == CaptureActivity.AUTO_SILENCE -> "Запись            ◌ автопропуск тишины"
        activity == CaptureActivity.FALLBACK_CONTINUOUS -> "Запись            ● непрерывный fallback"
        else -> "Запись            ● голос"
    }

    private fun transcriptionLine(session: SessionSnapshot): String = when (session.remoteState) {
        RemoteState.QUEUED, RemoteState.NORMALIZING -> "Gemini Lite    ○ подготовка 0/2"
        RemoteState.TRANSCRIBING -> "Gemini Lite    ● расшифровка 0/2"
        RemoteState.SUMMARIZING -> "Gemini Lite    ● выжимка 1/2"
        RemoteState.PUBLISHING, RemoteState.VERIFYING, RemoteState.PUBLISHED_VERIFIED ->
            "Gemini Lite    ✓ готово 2/2"
        RemoteState.WAITING_FOR_QUOTA -> "Gemini Lite    ◌ ожидание лимита"
        else -> "Gemini Lite    ○ ожидает завершения"
    }

    private fun processLabel(session: SessionSnapshot): String = when (session.remoteState) {
        RemoteState.LOCAL_ONLY -> if (config.isConfigured()) {
            "my-data-hub  ○ ожидает передачи"
        } else {
            "Локально          ✓ настройте my-data-hub"
        }
        RemoteState.RECEIVING -> "my-data-hub  ● принимает AAC-сегменты"
        RemoteState.QUEUED, RemoteState.NORMALIZING -> "my-data-hub  ● готовит единое аудио"
        RemoteState.TRANSCRIBING -> "Gemini Lite    ● распознаёт всю сессию"
        RemoteState.SUMMARIZING -> "Gemini Lite    ● формирует подробную выжимку"
        RemoteState.PROCESSING -> "Gemini Lite    ● распознаёт"
        RemoteState.PUBLISHING -> "IdeaHub           ● commit"
        RemoteState.VERIFYING -> "IdeaHub           ● readback"
        RemoteState.PUBLISHED_VERIFIED -> "IdeaHub           ✓ проверено · аудио удалено"
        RemoteState.WAITING_FOR_QUOTA -> {
            val retryAt = session.retryAtEpochMs
            if (retryAt == null) "Лимит Gemini  ◌ ожидание, аудио сохранено"
            else "Лимит Gemini  ◌ повтор в ${formatClock(retryAt)}"
        }
        RemoteState.RECONCILIATION_REQUIRED -> "Gemini          ! требуется безопасная сверка"
        RemoteState.RETRYABLE_ERROR -> {
            val error = session.lastError?.take(62).orEmpty()
            if (error.isBlank()) "Процесс          ! данные сохранены" else "Процесс          ! $error"
        }
        else -> "my-data-hub  ○ ожидает"
    }

    private fun animateMode(activity: String, captureState: String) {
        val key = "$captureState:$activity"
        if (key == animatedMode && captureAnimator?.isRunning == true) return
        stopAnimations()
        animatedMode = key
        primary.alpha = 1f
        primary.scaleX = 1f
        primary.scaleY = 1f
        timer.alpha = 1f
        when {
            captureState == CaptureState.PAUSED -> {
                primary.background = rounded(PAUSED, 120f)
                primary.alpha = 0.78f
            }
            captureState != CaptureState.RECORDING -> primary.background = rounded(ACTIVE, 120f)
            activity == CaptureActivity.AUTO_SILENCE -> {
                primary.background = rounded(LISTENING, 120f)
                val animations = listOf(
                    ObjectAnimator.ofFloat(primary, View.SCALE_X, 1f, 1.025f),
                    ObjectAnimator.ofFloat(primary, View.SCALE_Y, 1f, 1.025f),
                    ObjectAnimator.ofFloat(primary, View.ALPHA, 0.72f, 1f),
                ).onEach {
                    it.duration = 1_800L
                    it.repeatMode = ValueAnimator.REVERSE
                    it.repeatCount = ValueAnimator.INFINITE
                }
                captureAnimator = AnimatorSet().apply {
                    playTogether(animations)
                    start()
                }
                timerAnimator = ObjectAnimator.ofFloat(timer, View.ALPHA, 1f, 0.42f).apply {
                    duration = 800L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            else -> {
                primary.background = rounded(ACTIVE, 120f)
                val animations = listOf(
                    ObjectAnimator.ofFloat(primary, View.SCALE_X, 1f, 1.055f),
                    ObjectAnimator.ofFloat(primary, View.SCALE_Y, 1f, 1.055f),
                ).onEach {
                    it.duration = 720L
                    it.repeatMode = ValueAnimator.REVERSE
                    it.repeatCount = ValueAnimator.INFINITE
                }
                captureAnimator = AnimatorSet().apply {
                    playTogether(animations)
                    start()
                }
            }
        }
    }

    private fun stopAnimations() {
        captureAnimator?.cancel()
        timerAnimator?.cancel()
        captureAnimator = null
        timerAnimator = null
        animatedMode = null
    }

    private fun onPrimary() {
        when (store.activeSession()?.captureState) {
            CaptureState.RECORDING -> RecordingService.command(this, RecordingService.ACTION_PAUSE)
            CaptureState.PAUSED -> ensureReadyAnd(RecordingService.ACTION_RESUME)
            else -> ensureReadyAnd(RecordingService.ACTION_START)
        }
    }

    private fun retryLatest() {
        val session = store.latestSession() ?: return
        store.setRemoteState(session.sessionId, RemoteState.RECEIVING, "Повтор запрошен; аудио сохранено")
        SyncScheduler.enqueue(this)
        refresh()
    }

    private fun ensureReadyAnd(action: String) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startLocalCapture(action)
        }
    }

    private fun startLocalCapture(action: String) {
        RecordingService.command(this, action)
        if (!config.isConfigured()) {
            Toast.makeText(
                this,
                "Запись идёт локально. Настройте my-data-hub позже для отправки.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || !pendingStart) return
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Для локальной записи нужен доступ к микрофону", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Запись продолжится без обычных уведомлений; управление остаётся в приложении.",
                Toast.LENGTH_LONG,
            ).show()
        }
        val action = if (store.activeSession()?.captureState == CaptureState.PAUSED) {
            RecordingService.ACTION_RESUME
        } else {
            RecordingService.ACTION_START
        }
        startLocalCapture(action)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val url = EditText(this).apply {
            hint = "https://mcp-datahub.kenigevents.ru"
            setText(config.backendUrl.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(this).apply {
            hint = "Device token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val autoSilence = CheckBox(this).apply {
            text = "Не записывать длительную тишину и неречевой шум"
            setTextColor(FOREGROUND)
            isChecked = config.autoSilenceEnabled
        }
        box.addView(url)
        box.addView(token)
        box.addView(autoSilence)
        AlertDialog.Builder(this)
            .setTitle("my-data-hub · Android 1.1")
            .setMessage("Запись хранится локально в AAC. Gemini, лимиты и IdeaHub выполняются на devstand.")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val normalized = url.text.toString().trim().trimEnd('/')
                if (!validServerUrl(normalized)) {
                    Toast.makeText(this, "Нужен HTTPS URL", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                config.backendUrl = normalized
                if (token.text.isNotBlank()) config.deviceToken = token.text.toString()
                config.autoSilenceEnabled = autoSilence.isChecked
                if (!config.isConfigured()) {
                    Toast.makeText(this, "Укажите URL devstand и device token", Toast.LENGTH_LONG).show()
                } else {
                    SyncScheduler.enqueue(this)
                }
            }
            .show()
    }

    private fun validServerUrl(value: String): Boolean {
        val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        val host = parsed.host?.lowercase()?.takeIf(String::isNotBlank) ?: return false
        if (scheme == "https") return true
        return BuildConfig.DEBUG && scheme == "http" && host in setOf("127.0.0.1", "localhost")
    }

    private fun validDeviceToken(value: String): Boolean =
        value.length in 32..256 && value.all { it.code >= 33 && !it.isWhitespace() }

    private fun consumeProvisioningIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val rawServer = intent.getStringExtra(EXTRA_SERVER_URL) ?: intent.getStringExtra(EXTRA_BACKEND_URL)
        val rawToken = intent.getStringExtra(EXTRA_DEVICE_TOKEN)
        val server = rawServer?.trim()?.trimEnd('/')
        val token = rawToken?.trim()
        var applied = false
        if (!server.isNullOrBlank() && validServerUrl(server)) {
            config.backendUrl = server
            applied = true
        }
        if (!token.isNullOrBlank() && validDeviceToken(token)) {
            config.deviceToken = token
            applied = true
        }
        val hadExtras = rawServer != null || rawToken != null
        if (hadExtras) {
            intent.removeExtra(EXTRA_SERVER_URL)
            intent.removeExtra(EXTRA_BACKEND_URL)
            intent.removeExtra(EXTRA_DEVICE_TOKEN)
        }
        if (applied) {
            SyncScheduler.enqueue(this)
            Toast.makeText(this, "my-data-hub настроен через ADB", Toast.LENGTH_SHORT).show()
        } else if (hadExtras) {
            Toast.makeText(this, "ADB-настройка отклонена: проверьте URL и token", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatClock(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun mark(done: Boolean): String = if (done) "✓" else "●"

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_BACKEND_URL = "backend_url"
        const val EXTRA_DEVICE_TOKEN = "device_token"
        private val BACKGROUND = 0xFF101114.toInt()
        private val CARD = 0xFF1B1D22.toInt()
        private val SECONDARY = 0xFF272A31.toInt()
        private val ACTIVE = 0xFFD9E2FF.toInt()
        private val LISTENING = 0xFFB8C7F5.toInt()
        private val PAUSED = 0xFF9DA3AF.toInt()
        private val FOREGROUND = 0xFFF3F4F7.toInt()
        private val MUTED = 0xFFA9ADB7.toInt()
        private val LINK = 0xFF9DB7FF.toInt()
    }
}
