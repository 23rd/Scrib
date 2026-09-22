package org.scrib.transcriber

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriptionError
import java.util.concurrent.Executors
import kotlin.math.min
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

    private companion object {
        const val LINGER_AFTER_STOP_MS = 250L
    }

    override fun onCreateInputView(): View {
        val fresh = DictationView(this)
        fresh.onRecord = { act() }
        fresh.onKeyboard = { leave() }
        fresh.onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        view = fresh
        return fresh
    }

    // Showing the keyboard is not the same as wanting to talk: the microphone opens on the key
    // below and nowhere else, so switching here by accident never starts recording.
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        notice = null
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        abandon()
    }

    // Never take over the whole screen: the field being dictated into is the point.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onWindowShown() {
        super.onWindowShown()
        paintSystemKeys()
    }

    // Gesture navigation draws the system's own hide and switcher keys inside the bottom of this
    // window, and their colour is picked from what the window declares — left alone they come out
    // white on a light keyboard.
    private fun paintSystemKeys() {
        val window = window?.window ?: return
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.navigationBarColor = getColor(R.color.ime_background)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
            return
        }
        val decor = window.decorView
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = if (night) {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

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
        render()
        main.postDelayed({
            if (stage == Stage.Finishing) {
                microphone?.stop()
                stream?.endOfStream()
                render()
            }
        }, LINGER_AFTER_STOP_MS)
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
        view?.render(
    private val backspace = RoundKey(context, filled = false)

    private val alertColor = color(R.color.ime_alert)
    private val quietColor = color(R.color.ime_on_surface_variant)
    private val basePadding = dp(24)

    init {
        orientation = VERTICAL
        setBackgroundColor(color(R.color.ime_background))
        setPadding(dp(20), dp(16), dp(20), basePadding)
        applyBottomInset()

        status.textSize = 13.5f
        status.gravity = Gravity.CENTER
        keyboard.glyph = RoundKey.Glyph.Keyboard
        keyboard.contentDescription = context.getString(R.string.ime_keyboard)
        keyboard.setOnClickListener { onKeyboard() }

        backspace.glyph = RoundKey.Glyph.Backspace
        backspace.contentDescription = context.getString(R.string.ime_backspace)
        backspace.setOnClickListener { onBackspace() }
        backspace.onRepeat = { onBackspace() }

        record.glyph = RoundKey.Glyph.Mic
        record.setOnClickListener { onRecord() }

        val keys = LinearLayout(context)
        keys.orientation = HORIZONTAL
        keys.gravity = Gravity.CENTER
        keys.addView(keyboard, LayoutParams(dp(46), dp(46)))
        keys.addView(record, LayoutParams(dp(64), dp(64)).apply {
            leftMargin = dp(32)
            rightMargin = dp(32)
        })
        keys.addView(backspace, LayoutParams(dp(46), dp(46)))
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        record.alpha = if (enabled) 1f else 0.45f
        meter.alpha = if (listening) 1f else 0.4f
    }

    fun push(level: Float) = meter.push(level)

    fun resetMeter() = meter.reset()

    // Gesture navigation puts its bar over the bottom of the window, and the system draws its own
    // input method strip there too — without this the keys sit underneath both.
    private fun applyBottomInset() {
        setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom)
            insets
        }
    }

    private fun color(id: Int): Int = resources.getColor(id, context.theme)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// One round key, drawn rather than themed, so the filled microphone and the two quiet keys beside
// it are the same shape at different weights.
private class RoundKey(context: Context, private val filled: Boolean) : View(context) {

    enum class Glyph { Mic, Stop, Keyboard, Backspace }

    var glyph = Glyph.Mic
        set(value) {
            field = value
            invalidate()
        }

    // Held down, backspace keeps deleting — a single tap per character is no way to fix a word.
    var onRepeat: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = Path()

    private val faceColor = color(if (filled) R.color.ime_primary else R.color.ime_surface)
    private val markColor = color(if (filled) R.color.ime_on_primary else R.color.ime_on_surface_variant)

    private val repeater = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            onRepeat?.invoke()
            repeater.postDelayed(this, REPEAT_MS)
        }
    }

    init {
        isClickable = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (onRepeat == null) {
            return
        }
        repeater.removeCallbacks(tick)
        if (pressed) {
            repeater.postDelayed(tick, FIRST_REPEAT_MS)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        repeater.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = faceColor
        paint.alpha = if (isPressed) 170 else 255
        canvas.drawCircle(cx, cy, size / 2f, paint)
        paint.color = markColor
        paint.alpha = 255
        when (glyph) {
            Glyph.Mic -> drawMic(canvas, cx, cy, size)
            Glyph.Stop -> drawStop(canvas, cx, cy, size)
            Glyph.Keyboard -> drawKeyboard(canvas, cx, cy, size)
            Glyph.Backspace -> drawBackspace(canvas, cx, cy, size)
        }
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val width = size * 0.23f
        val top = cy - size * 0.29f
        box.set(cx - width / 2f, top, cx + width / 2f, top + size * 0.36f)
        canvas.drawRoundRect(box, width / 2f, width / 2f, paint)
        stroke(size * 0.07f)
        val radius = size * 0.19f
        box.set(cx - radius, cy - radius * 0.6f, cx + radius, cy + radius)
        canvas.drawArc(box, 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + radius, cx, cy + size * 0.29f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawStop(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size * 0.16f
        box.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(box, size * 0.05f, size * 0.05f, paint)
    }

    private fun drawKeyboard(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val halfWidth = size * 0.27f
        val halfHeight = size * 0.19f
        stroke(size * 0.055f)
        box.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        canvas.drawRoundRect(box, size * 0.05f, size * 0.05f, paint)
        paint.style = Paint.Style.FILL
        val key = size * 0.035f
        val gap = size * 0.105f
        for (column in -1..1) {
            canvas.drawCircle(cx + column * gap, cy - size * 0.075f, key, paint)
        }
        box.set(cx - gap, cy + size * 0.055f, cx + gap, cy + size * 0.105f)
        canvas.drawRoundRect(box, key, key, paint)
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val left = cx - size * 0.28f
        val right = cx + size * 0.24f
        val half = size * 0.17f
        val corner = size * 0.11f
        path.reset()
        path.moveTo(left, cy)
        path.lineTo(left + corner, cy - half)
        path.lineTo(right, cy - half)
        path.lineTo(right, cy + half)
        path.lineTo(left + corner, cy + half)
        path.close()
        stroke(size * 0.055f)
        canvas.drawPath(path, paint)
        val cross = size * 0.075f
        val center = cx + size * 0.055f
        canvas.drawLine(center - cross, cy - cross, center + cross, cy + cross, paint)
        canvas.drawLine(center + cross, cy - cross, center - cross, cy + cross, paint)
        paint.style = Paint.Style.FILL
    }

    private fun stroke(width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun color(id: Int): Int = resources.getColor(id, context.theme)

    private companion object {
        const val FIRST_REPEAT_MS = 400L
        const val REPEAT_MS = 55L
    }
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
        val minimum = 2.5f * density
        val radius = 1.5f * density
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
            paint.alpha = if (level > 0f) 255 else 77
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    private companion object {
        const val BARS = 32
    }
}
