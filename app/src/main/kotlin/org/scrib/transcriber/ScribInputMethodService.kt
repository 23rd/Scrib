package org.scrib.transcriber

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriptionError
import java.util.concurrent.Executors
import kotlin.math.sqrt

// Scrib as a voice keyboard. A keyboard's microphone key switches to whichever input method
// declares a voice subtype, so this is the only way to be reachable from one; the text then lands
// in the field the user was already typing in, and the keyboard comes back when the take is done.
// Whisper decodes whole utterances, so words appear at the pauses rather than one at a time.
class ScribInputMethodService : InputMethodService() {

    private enum class Stage { Idle, Listening, Finishing }

    private enum class Blocker { None, Microphone, Model }

    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    private var view: DictationView? = null
    private var stage = Stage.Idle
    private var stream: AudioStream? = null
    private var microphone: MicrophoneStream? = null

    // What has already been put into the field, so a progress report only adds what is new.
    private var inserted = ""

    // Bumped whenever a take ends, so a report from the one before it cannot reach the field.
    private var session = 0

    private var notice: String? = null

    override fun onCreateInputView(): View {
        val fresh = DictationView(this)
        fresh.onAction = { act() }
        fresh.onKeyboard = { leave() }
        view = fresh
        return fresh
    }

    // The take starts on its own: the user got here by pressing a microphone, not by choosing a
    // keyboard, and a second tap to begin would only be in the way.
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        notice = null
        if (stage == Stage.Idle && blocker() == Blocker.None) {
            listen()
        } else {
            render()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        abandon()
    }

    // Never take over the whole screen: the field being dictated into is the point.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        abandon()
        background.shutdown()
        view = null
        super.onDestroy()
    }

    private fun act() {
        when (blocker()) {
            // An input method cannot ask for a permission itself, so the app is opened to do it.
            Blocker.Microphone -> openApp(requestMicrophone = true)
            Blocker.Model -> openApp(requestMicrophone = false)
            Blocker.None -> when (stage) {
                Stage.Idle -> listen()
                Stage.Listening -> finish()
                Stage.Finishing -> Unit
            }
        }
    }

    private fun listen() {
        val id = session
        notice = null
        inserted = ""
        val request = StreamRequest()
        request.sampleRate = MicrophoneStream.SAMPLE_RATE
        request.channels = 1
        val audio = TranscriptionEngine.get(applicationContext).openStream(request, report(id))
        val capture = MicrophoneStream(
            audio,
            onLevel = { level -> main.post { if (id == session) view?.push(level) } },
            // The microphone dying mid-take is not a reason to lose what it already caught.
            onFailure = { main.post { if (id == session) finish() } }
        )
        audio.start()
        if (!capture.start()) {
            audio.cancel()
            reset()
            notice = getString(R.string.record_failed)
            render()
            return
        }
        stream = audio
        microphone = capture
        stage = Stage.Listening
        render()
    }

    // Closes the microphone and waits for the tail: the last utterance is still being decoded.
    private fun finish() {
        if (stage != Stage.Listening) {
            return
        }
        stage = Stage.Finishing
        microphone?.stop()
        stream?.endOfStream()
        render()
    }

    // Drops the take, tail and all. The cancelled report that follows belongs to a spent session.
    private fun abandon() {
        if (stage == Stage.Idle) {
            return
        }
        microphone?.stop()
        stream?.cancel()
        reset()
        render()
    }

    private fun reset() {
        session++
        stage = Stage.Idle
        stream = null
        microphone = null
        inserted = ""
        view?.resetMeter()
        releaseModel()
    }

    private fun settled(message: String?) {
        val complete = stage == Stage.Finishing && message == null
        reset()
        notice = message
        render()
        if (complete) {
            keyboard()
        }
    }

    private fun leave() {
        abandon()
        keyboard()
    }

    // Nothing here may leave the user in a voice keyboard with no way to type.
    private fun keyboard() {
        if (Build.VERSION.SDK_INT >= 28) {
            if (switchToPreviousInputMethod() || switchToNextInputMethod(false)) {
                return
            }
        }
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (manager != null) {
            manager.showInputMethodPicker()
        } else {
            requestHideSelf(0)
        }
    }

    private fun report(id: Int): ITranscriptionCallback = object : ITranscriptionCallback.Stub() {

        override fun onTranscriptionProgress(text: String?) {
            val value = text ?: return
            main.post { if (id == session) insert(value) }
        }

        override fun onTranscriptionResult(text: String?) {
            val value = text ?: ""
            main.post {
                if (id == session) {
                    insert(value)
                    settled(null)
                }
            }
        }

        override fun onTranscriptionError(error: TranscriptionError?) {
            val message = when (error?.type) {
                ErrorType.CANCELLED -> null
                ErrorType.MODEL_NOT_AVAILABLE -> getString(R.string.ime_needs_model)
                else -> getString(R.string.transcribe_failed)
            }
            main.post { if (id == session) settled(message) }
        }
    }

    // Progress carries everything recognised so far and only ever grows, so what is new is
    // whatever the field has not been given yet.
    private fun insert(text: String) {
        val connection = currentInputConnection ?: return
        val delta = if (text.startsWith(inserted)) text.substring(inserted.length) else text
        if (delta.isEmpty()) {
            return
        }
        connection.commitText(spaced(connection, delta), 1)
        inserted = text
    }

    // Dictation lands wherever the cursor was left, which is often right after a word.
    private fun spaced(connection: InputConnection, delta: String): String {
        if (inserted.isNotEmpty() || delta.first().isWhitespace()) {
            return delta
        }
        val before = connection.getTextBeforeCursor(1, 0)
        return if (before.isNullOrEmpty() || before.last().isWhitespace()) delta else " $delta"
    }

    // An input method is kept alive for as long as the user is typing, and a model resident there
    // is hundreds of megabytes the low-memory killer takes first. A run in the app is still using
    // it, though, and reloading under one would only cost that run its warm start.
    private fun releaseModel() {
        val app = applicationContext
        try {
            background.execute {
                if (!TranscriptionRun.running) {
                    TranscriptionEngine.get(app).releaseModel()
                }
            }
        } catch (ignore: Exception) {
        }
    }

    private fun blocker(): Blocker = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            Blocker.Microphone
        !ModelManager.hasActiveModel(this) -> Blocker.Model
        else -> Blocker.None
    }

    private fun openApp(requestMicrophone: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_MICROPHONE, requestMicrophone)
        try {
            startActivity(intent)
        } catch (ignore: Exception) {
        }
        requestHideSelf(0)
    }

    private fun render() {
        val blocker = blocker()
        val message = notice ?: when {
            blocker == Blocker.Microphone -> getString(R.string.record_denied)
            blocker == Blocker.Model -> getString(R.string.ime_needs_model)
            stage == Stage.Listening -> getString(R.string.ime_listening)
            stage == Stage.Finishing -> getString(R.string.ime_finishing)
            else -> getString(R.string.ime_ready)
        }
        val action = when {
            blocker == Blocker.Microphone -> getString(R.string.ime_allow)
            blocker == Blocker.Model -> getString(R.string.ime_open_app)
            stage == Stage.Listening -> getString(R.string.action_stop)
            stage == Stage.Finishing -> getString(R.string.ime_working)
            else -> getString(R.string.ime_speak)
        }
        view?.render(
            message = message,
            alert = notice != null || blocker != Blocker.None,
            action = action,
            enabled = stage != Stage.Finishing,
            live = stage == Stage.Listening
        )
    }
}

// Built by hand rather than inflated: an input method window is a strip with three controls, and a
// layout file would say less than this does.
private class DictationView(context: Context) : LinearLayout(context) {

    var onAction: () -> Unit = {}
    var onKeyboard: () -> Unit = {}

    private val status = TextView(context)
    private val meter = LevelMeter(context)
    private val action = Button(context)
    private val keyboard = Button(context)

    private val alertColor = color(R.color.ime_alert)
    private val quietColor = color(R.color.ime_on_surface_variant)

    init {
        orientation = VERTICAL
        setBackgroundColor(color(R.color.ime_background))
        setPadding(dp(20), dp(16), dp(20), dp(18))

        status.textSize = 13.5f
        status.setTextColor(quietColor)
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        meter.color = color(R.color.ime_primary)
        addView(meter, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(14) })

        keyboard.text = context.getString(R.string.ime_keyboard)
        style(keyboard, filled = false)
        keyboard.setOnClickListener { onKeyboard() }

        style(action, filled = true)
        action.setOnClickListener { onAction() }

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.addView(keyboard, LayoutParams(0, dp(52), 1f))
        row.addView(action, LayoutParams(0, dp(52), 1.6f).apply { leftMargin = dp(10) })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    }

    fun render(message: String, alert: Boolean, action: String, enabled: Boolean, live: Boolean) {
        status.text = message
        status.setTextColor(if (alert) alertColor else quietColor)
        this.action.text = action
        this.action.isEnabled = enabled
        this.action.alpha = if (enabled) 1f else 0.55f
        meter.alpha = if (live) 1f else 0.4f
    }

    fun push(level: Float) = meter.push(level)

    fun resetMeter() = meter.reset()

    private fun style(button: Button, filled: Boolean) {
        val shape = GradientDrawable()
        shape.cornerRadius = dp(16).toFloat()
        if (filled) {
            shape.setColor(color(R.color.ime_primary))
            button.setTextColor(color(R.color.ime_on_primary))
        } else {
            shape.setColor(color(R.color.ime_surface))
            shape.setStroke(dp(1), color(R.color.ime_outline))
            button.setTextColor(color(R.color.ime_on_surface))
        }
        button.background = shape
        button.isAllCaps = false
        button.textSize = 15f
        button.gravity = Gravity.CENTER
        button.stateListAnimator = null
    }

    private fun color(id: Int): Int = resources.getColor(id, context.theme)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// The app's own meter, redrawn for a plain view: one bar per sampled level, newest on the right,
// and a square root so ordinary speech still moves them.
private class LevelMeter(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val levels = FloatArray(BARS)
    private var next = 0

    var color = 0

    fun push(level: Float) {
        levels[next] = level.coerceIn(0f, 1f)
        next = (next + 1) % BARS
        invalidate()
    }

    fun reset() {
        levels.fill(0f)
        next = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val gap = 3f * density
        val minimum = 3f * density
        val radius = 2f * density
        val width = (width - gap * (BARS - 1)) / BARS
        if (width <= 0f) {
            return
        }
        for (i in 0 until BARS) {
            val level = levels[(next + i) % BARS]
            val height = minimum + (getHeight() - minimum) * sqrt(level)
            val left = i * (width + gap)
            val top = (getHeight() - height) / 2f
            bar.set(left, top, left + width, top + height)
            paint.color = color
            paint.alpha = if (level > 0f) 255 else 89
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    private companion object {
        const val BARS = 28
    }
}
