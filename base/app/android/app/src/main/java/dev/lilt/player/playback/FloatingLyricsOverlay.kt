package dev.lilt.player.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import dev.lilt.player.library.LrcDocument
import dev.lilt.player.library.floatingLyricLines
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the overlay needs for one repaint. */
data class FloatingLyricsState(
    val mediaId: Long,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val lyrics: LrcDocument?,
    val offsetMs: Int,
    val accent: Int,
    val playedColor: Int,
    val unplayedColor: Int,
    val progress: Float,
    val opacity: Float,
    val fontSp: Float,
    val locked: Boolean,
    val chinese: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
) {
    val hasTrack: Boolean get() = mediaId > 0
}

interface FloatingLyricsActions {
    fun togglePlay()
    fun previous()
    fun next()
    fun close()
    fun lock()
    fun openApp()
}

/**
 * A native WindowManager overlay rather than a Compose view, so it can live
 * above other apps without an activity and without per-frame recomposition
 * when the screen is off. The service feeds it a state snapshot four times a
 * second; text changes are diffed to avoid needless relayouts.
 *
 * The overlay deliberately mirrors KuGou's mobile floating lyrics: the locked
 * state is only one outlined lyric line with no card, while the unlocked state
 * adds a small semi-transparent drag strip with lock/close buttons. There is no
 * title strip, progress bar or transport row.
 */
class FloatingLyricsOverlay(
    private val context: Context,
    private val actions: FloatingLyricsActions,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("lilt_settings", Context.MODE_PRIVATE)

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var header: LinearLayout? = null
    private var activeText: OutlinedTextView? = null
    private var lockButton: ImageButton? = null
    private var closeButton: ImageButton? = null

    private var lastLocked: Boolean? = null
    private var lastFont: Float = 0f
    private var lastAccent: Int = 0
    private var lastOpacity: Float = 0f
    private var lastLine: String? = null
    private var lastUnplayedColor: Int = 0
    private var lastPlayedColor: Int = 0
    private var lastProgress: Float = -1f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f

    val showing: Boolean get() = root != null

    fun show(state: FloatingLyricsState) {
        if (root != null) {
            applyState(state, force = true)
            return
        }
        if (!Settings.canDrawOverlays(context) || windowManager == null) return
        val (screenWidth, screenHeight) = screenSize()
        val width = overlayWidth(screenWidth)
        val view = buildView()
        val savedX = prefs.getInt(KEY_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(KEY_Y, Int.MIN_VALUE)
        val layout = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags() or if (state.locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            x = if (savedX == Int.MIN_VALUE) {
                ((screenWidth - width) / 2).coerceAtLeast(0)
            } else {
                savedX.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            }
            y = if (savedY == Int.MIN_VALUE) {
                (screenHeight * 0.55f).toInt()
            } else {
                savedY.coerceIn(dp(24), (screenHeight - dp(80)).coerceAtLeast(dp(24)))
            }
        }
        root = view
        params = layout
        runCatching { windowManager.addView(view, layout) }.onFailure {
            root = null
            params = null
            return
        }
        applyState(state, force = true)
        view.post {
            clampIntoViewport()
            savePosition()
        }
    }

    fun applyState(state: FloatingLyricsState, force: Boolean = false) {
        val view = root ?: return
        if (state.locked != lastLocked || state.accent != lastAccent || force) {
            applyVisualStyle(state)
        }
        if (state.opacity != lastOpacity || force) {
            lastOpacity = state.opacity
            activeText?.alpha = state.opacity
        }
        if (state.fontSp != lastFont || force) {
            lastFont = state.fontSp
            val maxSp = state.fontSp.coerceIn(18f, 44f)
            val minSp = (maxSp * 0.45f).coerceAtLeast(11f)
            activeText?.setAutoSizeTextTypeUniformWithConfiguration(
                minSp.roundToInt(),
                maxSp.roundToInt(),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }

        val line = when {
            !state.hasTrack -> {
                if (state.chinese) "打开 Lilt 开始播放" else "Open Lilt to play"
            }
            state.lyrics == null || state.lyrics.isEmpty -> {
                if (state.chinese) "这首歌没有歌词" else "No lyrics for this track"
            }
            else -> {
                val active = floatingLyricLines(
                    state.lyrics,
                    state.positionMs,
                    state.offsetMs,
                ).active
                active.ifBlank { "♪" }
            }
        }
        if (line != lastLine || force) {
            lastLine = line
            setText(activeText, line)
            view.post { clampIntoViewport() }
        }

        val activeColor = when {
            !state.hasTrack -> withAlpha(INK, 235)
            state.lyrics == null || state.lyrics.isEmpty -> withAlpha(INK, 245)
            else -> state.unplayedColor
        }
        val playedColor = if (state.hasTrack && state.lyrics != null && !state.lyrics.isEmpty) {
            state.playedColor
        } else {
            activeColor
        }
        val progress = if (state.hasTrack && state.lyrics != null && !state.lyrics.isEmpty) {
            state.progress
        } else {
            0f
        }
        if (activeColor != lastUnplayedColor || force) {
            lastUnplayedColor = activeColor
            activeText?.setTextColor(activeColor)
        }
        if (playedColor != lastPlayedColor || progress != lastProgress || force) {
            lastPlayedColor = playedColor
            lastProgress = progress
            activeText?.setLyricProgress(playedColor, progress)
        }
    }

    /** Re-fits the card after rotation, split-screen or a display-size change. */
    fun onScreenChanged() {
        val view = root ?: return
        val p = params ?: return
        val (sw, sh) = screenSize()
        val width = overlayWidth(sw)
        p.width = width
        p.x = p.x.coerceIn(0, (sw - width).coerceAtLeast(0))
        p.y = p.y.coerceIn(dp(24), (sh - dp(80)).coerceAtLeast(dp(24)))
        runCatching { windowManager?.updateViewLayout(view, p) }
        view.post {
            clampIntoViewport()
            savePosition()
        }
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager?.removeView(view) }
        root = null
        params = null
        header = null
        activeText = null
        lockButton = null
        closeButton = null
        lastLocked = null
        lastFont = 0f
        lastAccent = 0
        lastOpacity = 0f
        lastLine = null
        lastUnplayedColor = 0
        lastPlayedColor = 0
        lastProgress = -1f
    }

    private fun buildView(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(10))
            elevation = dp(8).toFloat()
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    clampIntoViewport()
                }
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(26),
            )
        }
        val drag = TextView(context).apply {
            text = "⠿"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(withAlpha(INK, 155))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
            isClickable = true
            isFocusable = false
            contentDescription = "Drag floating lyrics"
        }
        attachDrag(drag)
        header.addView(drag)

        val lock = iconButton(
            dev.lilt.player.R.drawable.ic_float_lock,
            if (prefs.getString("language", "en") == "zh") "锁定悬浮歌词" else "Lock floating lyrics",
            dp(26),
            dp(6),
        ) {
            actions.lock()
            Toast.makeText(
                context,
                if (prefs.getString("language", "en") == "zh") {
                    "悬浮歌词已锁定，请在设置中解锁"
                } else {
                    "Floating lyrics locked. Unlock in Settings."
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
        val close = iconButton(
            dev.lilt.player.R.drawable.ic_float_close,
            if (prefs.getString("language", "en") == "zh") "关闭悬浮歌词" else "Close floating lyrics",
            dp(26),
            dp(6),
        ) { actions.close() }
        header.addView(lock)
        header.addView(close)

        val active = lyricLine(26f)
        attachDrag(active)

        root.addView(header)
        root.addView(active)

        this.header = header
        this.activeText = active
        this.lockButton = lock
        this.closeButton = close
        this.lastLocked = null
        this.lastFont = 0f
        this.lastAccent = 0
        this.lastOpacity = 0f
        this.lastLine = null
        this.lastUnplayedColor = 0
        this.lastPlayedColor = 0
        this.lastProgress = -1f
        return root
    }

    private fun attachDrag(target: View) {
        target.setOnTouchListener { _, event ->
            val view = root
            val p = params
            if (view == null || p == null) {
                false
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = p.x
                        dragStartY = p.y
                        dragDownRawX = event.rawX
                        dragDownRawY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val (sw, sh) = screenSize()
                        val viewWidth = view.width.coerceAtLeast(p.width)
                        val viewHeight = view.height.coerceAtLeast(dp(48))
                        p.x = (dragStartX + (event.rawX - dragDownRawX).toInt())
                            .coerceIn(0, (sw - viewWidth).coerceAtLeast(0))
                        p.y = (dragStartY + (event.rawY - dragDownRawY).toInt())
                            .coerceIn(
                                dp(24),
                                (sh - viewHeight - dp(28)).coerceAtLeast(dp(24)),
                            )
                        windowManager?.updateViewLayout(view, p)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        savePosition()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun lyricLine(sizeSp: Float): OutlinedTextView =
        OutlinedTextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(withAlpha(INK, 195))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
            setPadding(dp(4), dp(1), dp(4), dp(1))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun applyVisualStyle(state: FloatingLyricsState) {
        lastLocked = state.locked
        lastAccent = state.accent
        val view = root ?: return
        header?.visibility = if (state.locked) View.GONE else View.VISIBLE
        if (state.locked) {
            view.background = null
            view.elevation = 0f
            view.setPadding(dp(8), dp(3), dp(8), dp(3))
        } else {
            view.background = card(state.accent)
            view.elevation = dp(8).toFloat()
            view.setPadding(dp(14), dp(6), dp(14), dp(10))
        }
        lockButton?.setColorFilter(withAlpha(INK, 205))
        closeButton?.setColorFilter(withAlpha(INK, 205))
        updateFlags(state.locked)
        view.post { clampIntoViewport() }
    }

    private fun updateFlags(locked: Boolean) {
        val view = root ?: return
        val p = params ?: return
        val wanted = baseFlags() or if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0
        if (p.flags != wanted || p.alpha != 1f) {
            p.flags = wanted
            p.alpha = 1f
            runCatching { windowManager?.updateViewLayout(view, p) }
        }
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun card(accent: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xCC0D0B0A.toInt())
            setStroke(dp(1), withAlpha(accent, 70))
        }

    private fun iconButton(
        resource: Int,
        description: String,
        size: Int,
        padding: Int,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(resource)
        contentDescription = description
        background = null
        isFocusable = false
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(padding, padding, padding, padding)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setOnClickListener { onClick() }
    }

    private fun setText(view: TextView?, text: String) {
        if (view != null && view.text.toString() != text) {
            view.text = text
        }
    }

    private fun clampIntoViewport() {
        val view = root ?: return
        val p = params ?: return
        val (sw, sh) = screenSize()
        val maxX = (sw - view.width).coerceAtLeast(0)
        val maxY = (sh - view.height - dp(28)).coerceAtLeast(dp(24))
        val x = p.x.coerceIn(0, maxX)
        val y = p.y.coerceIn(dp(24), maxY)
        if (x != p.x || y != p.y) {
            p.x = x
            p.y = y
            runCatching { windowManager?.updateViewLayout(view, p) }
        }
    }

    private fun savePosition() {
        val p = params ?: return
        prefs.edit {
            putInt(KEY_X, p.x)
            putInt(KEY_Y, p.y)
        }
    }

    private fun overlayWidth(screenWidth: Int): Int =
        min(screenWidth - dp(24), dp(560)).coerceAtLeast(dp(240))

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.currentWindowMetrics?.bounds
            if (bounds != null) bounds.width() to bounds.height()
            else context.resources.displayMetrics.widthPixels to
                context.resources.displayMetrics.heightPixels
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.widthPixels to
                context.resources.displayMetrics.heightPixels
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private companion object {
        const val KEY_X = "floating_x"
        const val KEY_Y = "floating_y"
        const val INK = 0xFFF8F3EA.toInt()
    }
}

/**
 * Draws the same text twice: first as a dark stroke, then as the normal fill.
 * This keeps the single lyric readable on white pages without adding a card
 * background.
 */
private class OutlinedTextView(context: Context) : TextView(context) {
    private val outlineColor = 0xE6000000.toInt()
    private var playedColor = 0
    private var progress = 0f

    fun setLyricProgress(color: Int, value: Float) {
        val clean = value.coerceIn(0f, 1f)
        if (color != playedColor || kotlin.math.abs(clean - progress) > 0.001f) {
            playedColor = color
            progress = clean
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val fillColor = currentTextColor
        val amount = progress.coerceIn(0f, 1f)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (textSize * 0.06f).coerceAtLeast(2.0f)
        paint.color = outlineColor
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        paint.color = if (amount >= 1f) playedColor else fillColor
        val textLayout = layout
        if (amount > 0f && amount < 1f && playedColor != fillColor && textLayout != null) {
            val lineLeft = textLayout.getLineLeft(0)
            val lineRight = textLayout.getLineRight(0)
            if (lineRight > lineLeft) {
                paint.shader = LinearGradient(
                    lineLeft,
                    0f,
                    lineRight,
                    0f,
                    intArrayOf(playedColor, playedColor, fillColor, fillColor),
                    floatArrayOf(0f, amount, amount, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        super.onDraw(canvas)
        paint.shader = null
    }
}
