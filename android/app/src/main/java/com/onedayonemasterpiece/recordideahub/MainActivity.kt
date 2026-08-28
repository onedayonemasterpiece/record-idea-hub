package com.onedayonemasterpiece.recordideahub

import android.Manifest
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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(RecordingService.EXTRA_MESSAGE)
                ?.takeIf { it.isNotBlank() }
                ?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            refresh()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
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
        intent?.let { consumeProvisioningIntent(it) }
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
        super.onPause()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val top = FrameLayout(this)
        content.addView(
            top,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )
        top.addView(
            TextView(this).apply {
                text = "Record Idea Hub"
                textSize = 20f
                setTextColor(FOREGROUND)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START,
            ),
        )
        top.addView(
            Button(this).apply {
                text = "⚙"
                textSize = 20f
                setTextColor(MUTED)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showSettings() }
                contentDescription = "Настройки my-data-hub"
            },
            FrameLayout.LayoutParams(
                dp(56),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END,
            ),
        )

        timer = TextView(this).apply {
            text = "00:00"
            textSize = 42f
            setTextColor(FOREGROUND)
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        content.addView(
            timer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(82)),
        )

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(0xFF1B1D22.toInt(), 18f)
        }
        content.addView(
            statusCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(20)
            },
        )
        captureStatus = statusRow(statusCard)
        uploadStatus = statusRow(statusCard)
        transcriptionStatus = statusRow(statusCard)
        githubStatus = statusRow(statusCard)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        statusCard.addView(
            progress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply {
                topMargin = dp(10)
            },
        )

        retry = Button(this).apply {
            text = "Повторить сейчас"
            isAllCaps = false
            textSize = 14f
            setTextColor(FOREGROUND)
            background = rounded(0xFF272A31.toInt(), 16f)
            visibility = View.GONE
            setOnClickListener { retryLatest() }
        }
        content.addView(
            retry,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                bottomMargin = dp(12)
            },
        )

        content.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        primary = Button(this).apply {
            isAllCaps = false
            textSize = 23f
            setTextColor(Color.BLACK)
            background = rounded(0xFFD9E2FF.toInt(), 120f)
            setOnClickListener { onPrimary() }
        }
        content.addView(primary, LinearLayout.LayoutParams(dp(220), dp(220)))

        finish = Button(this).apply {
            text = "Завершить и отправить"
            isAllCaps = false
            textSize = 16f
            setTextColor(FOREGROUND)
            background = rounded(0xFF272A31.toInt(), 18f)
            setOnClickListener {
                RecordingService.command(this@MainActivity, RecordingService.ACTION_FINISH)
            }
        }
        content.addView(
            finish,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(18)
            },
        )

        resultLink = TextView(this).apply {
            text = "Открыть запись в IdeaHub"
            textSize = 15f
            setTextColor(0xFF9DB7FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                val url = store.latestSession()?.githubUrl ?: return@setOnClickListener
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        content.addView(
            resultLink,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)),
        )
        content.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        setContentView(root)
    }

    private fun statusRow(parent: LinearLayout): TextView = TextView(this).also { view ->
        view.textSize = 15f
        view.setTextColor(MUTED)
        view.setPadding(0, dp(4), 0, dp(4))
        view.maxLines = 2
        parent.addView(
            view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)),
        )
    }

    private fun refresh() {
        val active = store.activeSession()
        val latest = active ?: store.latestSession()
        val duration = if (latest == null) {
            0L
        } else {
            max(latest.durationMs, runtime.durationFor(latest.sessionId) ?: 0L)
        }
        timer.text = formatDuration(duration)

        if (latest == null) {
            captureStatus.text = "Запись            ○ не идёт"
            uploadStatus.text = "Передача        ○ нет данных"
            transcriptionStatus.text = "Распознано    ○ нет данных"
            githubStatus.text = "IdeaHub           ○ нет данных"
            progress.progress = 0
            primary.text = "Записать"
            primary.isEnabled = true
            finish.visibility = View.GONE
            retry.visibility = View.GONE
            resultLink.visibility = View.GONE
            return
        }

        val total = latest.chunkCount.coerceAtLeast(1)
        captureStatus.text = when (latest.captureState) {
            CaptureState.RECORDING -> "Запись            ● идёт"
            CaptureState.PAUSED -> "Запись            Ⅱ пауза"
            CaptureState.FINISHED -> "Запись            ✓ сохранена локально"
            else -> "Запись            ○ не идёт"
        }
        uploadStatus.text = buildString {
            append("Передача        ")
            append(mark(latest.chunksUploaded >= latest.chunkCount && latest.chunkCount > 0))
            append(" ${latest.chunksUploaded}/${latest.chunkCount}")
        }
        transcriptionStatus.text = buildString {
            append("Распознано    ")
            append(mark(latest.chunksTranscribed >= latest.chunkCount && latest.chunkCount > 0))
            append(" ${latest.chunksTranscribed}/${latest.chunkCount}")
        }
        githubStatus.text = processLabel(latest)

        val capturePart = if (latest.captureState == CaptureState.FINISHED) 20 else 8
        val uploadPart = (20.0 * latest.chunksUploaded / total).toInt().coerceIn(0, 20)
        val transcriptPart = (35.0 * latest.chunksTranscribed / total).toInt().coerceIn(0, 35)
        val githubPart = when (latest.remoteState) {
            RemoteState.PUBLISHING -> 12
            RemoteState.PUBLISHED_VERIFIED -> 25
            else -> 0
        }
        progress.progress = (capturePart + uploadPart + transcriptPart + githubPart).coerceIn(0, 100)

        when (active?.captureState) {
            CaptureState.RECORDING -> {
                primary.text = "Пауза"
                primary.isEnabled = true
                finish.visibility = View.VISIBLE
            }
            CaptureState.PAUSED -> {
                primary.text = "Продолжить"
                primary.isEnabled = true
                finish.visibility = View.VISIBLE
            }
            else -> {
                primary.text = "Новая запись"
                primary.isEnabled = true
                finish.visibility = View.GONE
            }
        }
        retry.visibility = if (
            latest.remoteState == RemoteState.WAITING_FOR_QUOTA ||
            latest.remoteState == RemoteState.RETRYABLE_ERROR
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        resultLink.visibility = if (
            latest.remoteState == RemoteState.PUBLISHED_VERIFIED && latest.githubUrl != null
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun processLabel(session: SessionSnapshot): String = when (session.remoteState) {
        RemoteState.LOCAL_ONLY -> "my-data-hub  ○ ожидает передачу"
        RemoteState.RECEIVING -> "my-data-hub  ● принимает сессию"
        RemoteState.PROCESSING -> "Gemini Lite    ● распознаёт"
        RemoteState.PUBLISHING -> "IdeaHub           ● commit и readback"
        RemoteState.PUBLISHED_VERIFIED -> "IdeaHub           ✓ проверено · аудио удалено"
        RemoteState.WAITING_FOR_QUOTA -> {
            val retryAt = session.retryAtEpochMs
            if (retryAt == null) {
                "Лимит Gemini  ◌ ожидание, аудио сохранено"
            } else {
                "Лимит Gemini  ◌ повтор в ${formatClock(retryAt)}"
            }
        }
        RemoteState.RETRYABLE_ERROR -> {
            val error = session.lastError?.take(62).orEmpty()
            if (error.isBlank()) "Процесс          ! данные сохранены" else "Процесс          ! $error"
        }
        else -> "my-data-hub  ○ ожидает"
    }

    private fun formatClock(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun mark(done: Boolean): String = if (done) "✓" else "●"

    private fun onPrimary() {
        val active = store.activeSession()
        when (active?.captureState) {
            CaptureState.RECORDING -> RecordingService.command(this, RecordingService.ACTION_PAUSE)
            CaptureState.PAUSED -> ensureReadyAnd(RecordingService.ACTION_RESUME)
            else -> ensureReadyAnd(RecordingService.ACTION_START)
        }
    }

    private fun retryLatest() {
        val session = store.latestSession() ?: return
        store.setRemoteState(
            session.sessionId,
            RemoteState.PROCESSING,
            "Повтор запрошен; исходные данные сохранены",
        )
        SyncScheduler.enqueue(this)
        refresh()
    }

    private fun ensureReadyAnd(action: String) {
        if (!config.isConfigured()) {
            pendingStart = action == RecordingService.ACTION_START ||
                action == RecordingService.ACTION_RESUME
            showSettings()
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            RecordingService.command(this, action)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode == REQUEST_PERMISSIONS &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
            pendingStart
        ) {
            pendingStart = false
            val action = if (store.activeSession()?.captureState == CaptureState.PAUSED) {
                RecordingService.ACTION_RESUME
            } else {
                RecordingService.ACTION_START
            }
            RecordingService.command(this, action)
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val url = EditText(this).apply {
            hint = "https://devstand.example"
            setText(config.backendUrl.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(this).apply {
            hint = "Device token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(url)
        box.addView(token)
        AlertDialog.Builder(this)
            .setTitle("my-data-hub")
            .setMessage(
                "Телефон хранит запись и очередь. Gemini, контроль лимитов и IdeaHub " +
                    "выполняются на devstand.",
            )
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val normalized = url.text.toString().trim().trimEnd('/')
                if (!validServerUrl(normalized)) {
                    Toast.makeText(
                        this,
                        "Нужен HTTPS URL; HTTP разрешён только локально в debug",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                config.backendUrl = normalized
                if (token.text.isNotBlank()) config.deviceToken = token.text.toString()
                if (!config.isConfigured()) {
                    Toast.makeText(this, "Укажите URL devstand и device token", Toast.LENGTH_LONG).show()
                } else if (pendingStart) {
                    pendingStart = false
                    ensureReadyAnd(
                        if (store.activeSession()?.captureState == CaptureState.PAUSED) {
                            RecordingService.ACTION_RESUME
                        } else {
                            RecordingService.ACTION_START
                        },
                    )
                }
            }
            .show()
    }

    private fun validServerUrl(value: String): Boolean {
        if (value.startsWith("https://")) return true
        return BuildConfig.DEBUG && (
            value.startsWith("http://127.0.0.1") ||
                value.startsWith("http://localhost")
            )
    }

    private fun consumeProvisioningIntent(intent: Intent) {
        val server = intent.getStringExtra(EXTRA_SERVER_URL)
            ?: intent.getStringExtra(EXTRA_BACKEND_URL)
        val token = intent.getStringExtra(EXTRA_DEVICE_TOKEN)
        if (!server.isNullOrBlank()) config.backendUrl = server
        if (!token.isNullOrBlank()) config.deviceToken = token
        if (!server.isNullOrBlank() || !token.isNullOrBlank()) {
            intent.removeExtra(EXTRA_SERVER_URL)
            intent.removeExtra(EXTRA_BACKEND_URL)
            intent.removeExtra(EXTRA_DEVICE_TOKEN)
            Toast.makeText(this, "my-data-hub настроен через ADB", Toast.LENGTH_SHORT).show()
        }
    }

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
        private val FOREGROUND = 0xFFF3F4F7.toInt()
        private val MUTED = 0xFFA9ADB7.toInt()
    }
}
