package dev.lilt.player.playback

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import dev.lilt.player.library.FloatingLyricLines
import dev.lilt.player.library.LrcDocument
import dev.lilt.player.library.floatingLyricLines
import kotlin.math.abs
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
    private var controls: LinearLayout? = null
    private var progress: ProgressBar? = null
    private var titleStrip: TextView? = null
    private var beforeText: TextView? = null
    private var activeText: TextView? = null
    private var afterText: TextView? = null
    private var playButton: ImageButton? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var openButton: ImageButton? = null
    private var lockButton: ImageButton? = null
    private var closeButton: ImageButton? = null

    private var lastLocked: Boolean? = null
    private var lastPlaying: Boolean? = null
    private var lastFont: Float = 0f
    private var lastAccent: Int = 0
    private var lastOpacity: Float = 0f
    private var lastLines: FloatingLyricLines? = null
    private var lastActiveColor: Int = 0
    private var lastProgress: Int = -1
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
        val width = min(screenWidth - dp(20), dp(680)).coerceAtLeast(dp(240))
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
        if (state.locked != lastLocked || force) {
            applyLock(state)
            view.post { clampIntoViewport() }
        }
        if (state.accent != lastAccent || force) {
            paintAccent(view, state.accent)
            lastAccent = state.accent
        }
        if (state.opacity != lastOpacity || force) {
            lastOpacity = state.opacity
            titleStrip?.alpha = state.opacity
            beforeText?.alpha = state.opacity
            afterText?.alpha = state.opacity
            activeText?.alpha = state.opacity
        }
        if (state.fontSp != lastFont || force) {
            lastFont = state.fontSp
            val active = state.fontSp.coerceIn(18f, 44f)
            val neighbour = (active * 0.54f).coerceIn(13f, 18f)
            activeText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, active)
            beforeText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, neighbour)
            afterText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, neighbour)
        }
        if (state.isPlaying != lastPlaying || force) {
            lastPlaying = state.isPlaying
            playButton?.setImageResource(
                if (state.isPlaying) dev.lilt.player.R.drawable.ic_float_pause
                else dev.lilt.player.R.drawable.ic_float_play,
            )
            playButton?.contentDescription = if (state.isPlaying) {
                if (state.chinese) "暂停" else "Pause"
            } else {
                if (state.chinese) "播放" else "Play"
            }
        }

        val title = if (state.hasTrack) {
            if (state.artist.isBlank()) state.title else "${state.title}  ·  ${state.artist}"
        } else {
            if (state.chinese) "LILT 悬浮歌词" else "LILT FLOATING LYRICS"
        }
        setText(titleStrip, title)

        val lines = when {
            !state.hasTrack -> FloatingLyricLines(
                before = "",
                active = if (state.chinese) "打开 Lilt 开始播放" else "Open Lilt to play",
                after = "",
                activeIndex = -1,
            )
            state.lyrics == null || state.lyrics.isEmpty -> FloatingLyricLines(
                before = "",
                active = state.title,
                after = if (state.chinese) "这首歌没有歌词" else "No lyrics for this track",
                activeIndex = -1,
            )
            else -> floatingLyricLines(
                state.lyrics,
                state.positionMs,
                state.offsetMs,
            )
        }
        if (lines != lastLines || force) {
            lastLines = lines
            setText(beforeText, lines.before)
            setText(activeText, lines.active)
            setText(afterText, lines.after)
            beforeText?.visibility = if (lines.before.isBlank()) View.GONE else View.VISIBLE
            afterText?.visibility = if (lines.after.isBlank()) View.GONE else View.VISIBLE
            view.post { clampIntoViewport() }
        }
        val activeColor = when {
            !state.hasTrack -> withAlpha(INK, 230)
            lines.activeIndex >= 0 -> state.accent
            state.lyrics == null || state.lyrics.isEmpty -> withAlpha(INK, 235)
            else -> withAlpha(INK, 245)
        }
        if (activeColor != lastActiveColor || force) {
            lastActiveColor = activeColor
            activeText?.setTextColor(activeColor)
        }

        beforeText?.setTextColor(withAlpha(INK, 180))
        afterText?.setTextColor(withAlpha(INK, 195))
        titleStrip?.setTextColor(withAlpha(INK, 145))

        val enabled = state.hasTrack
        previousButton?.isEnabled = enabled
        nextButton?.isEnabled = enabled
        playButton?.isEnabled = enabled
        previousButton?.alpha = if (enabled) 1f else 0.35f
        nextButton?.alpha = if (enabled) 1f else 0.35f
        playButton?.alpha = if (enabled) 1f else 0.35f

        val fraction = if (state.durationMs > 0) {
            (state.positionMs.toDouble() / state.durationMs).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val nextProgress = (fraction * 1000).roundToInt()
        if (progress?.progress != nextProgress && (force || abs(nextProgress - lastProgress) >= 5)) {
            lastProgress = nextProgress
            progress?.progress = nextProgress
        }
    }

    /** Re-fits the card after rotation, split-screen or a display-size change. */
    fun onScreenChanged() {
        val view = root ?: return
        val p = params ?: return
        val (sw, sh) = screenSize()
        val width = min(sw - dp(20), dp(680)).coerceAtLeast(dp(240))
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
        controls = null
        progress = null
        titleStrip = null
        beforeText = null
        activeText = null
        afterText = null
        playButton = null
        previousButton = null
        nextButton = null
        openButton = null
        lockButton = null
        closeButton = null
        lastLocked = null
        lastPlaying = null
        lastFont = 0f
        lastAccent = 0
        lastOpacity = 0f
        lastLines = null
        lastActiveColor = 0
        lastProgress = -1
    }

    private fun buildView(): LinearLayout {
        val card = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xF60D0B0A.toInt())
            setStroke(dp(1), 0x2EF2B46B)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = card
            setPadding(dp(14), dp(7), dp(14), dp(10))
            elevation = dp(10).toFloat()
            // The controls appear and disappear with the track/lock state. Keep
            // the grown window inside the screen even when it was dragged to
            // the bottom while the card was still short.
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
                dp(28),
            )
        }
        val drag = TextView(context).apply {
            text = "⠿  LILT LYRICS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            letterSpacing = 0.12f
            setTextColor(withAlpha(INK, 145))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f)
            isClickable = true
            isFocusable = false
            contentDescription = "Drag floating lyrics"
        }
        drag.setOnTouchListener { _, event ->
            val view = root
            val p = params
            if (p == null) {
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
                        val viewHeight = view.height.coerceAtLeast(dp(72))
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
        header.addView(drag)
        val lock = iconButton(
            dev.lilt.player.R.drawable.ic_float_lock,
            if (prefs.getString("language", "en") == "zh") "锁定悬浮歌词" else "Lock floating lyrics",
            dp(28),
            dp(7),
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
            dp(28),
            dp(7),
        ) { actions.close() }
        header.addView(lock)
        header.addView(close)

        val titleStrip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(1), dp(2), 0)
        }

        val before = lyricLine(14f, false)
        val active = lyricLine(26f, true)
        val after = lyricLine(14f, false)

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2),
            ).also { it.topMargin = dp(7) }
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(0x2EFFFFFF)
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40),
            ).also { it.topMargin = dp(2) }
        }
        val previous = iconButton(
            dev.lilt.player.R.drawable.ic_float_previous,
            if (prefs.getString("language", "en") == "zh") "上一首" else "Previous",
            dp(40),
            dp(10),
        ) { actions.previous() }
        val play = iconButton(
            dev.lilt.player.R.drawable.ic_float_play,
            if (prefs.getString("language", "en") == "zh") "播放或暂停" else "Play or pause",
            dp(44),
            dp(10),
        ) { actions.togglePlay() }
        val next = iconButton(
            dev.lilt.player.R.drawable.ic_float_next,
            if (prefs.getString("language", "en") == "zh") "下一首" else "Next",
            dp(40),
            dp(10),
        ) { actions.next() }
        val open = iconButton(
            dev.lilt.player.R.drawable.ic_float_open,
            if (prefs.getString("language", "en") == "zh") "打开 Lilt" else "Open Lilt",
            dp(36),
            dp(10),
        ) { actions.openApp() }
        controls.addView(previous)
        controls.addView(play)
        controls.addView(next)
        controls.addView(open)

        root.addView(header)
        root.addView(titleStrip)
        root.addView(before)
        root.addView(active)
        root.addView(after)
        root.addView(progress)
        root.addView(controls)

        this.header = header
        this.titleStrip = titleStrip
        this.beforeText = before
        this.activeText = active
        this.afterText = after
        this.progress = progress
        this.controls = controls
        this.previousButton = previous
        this.playButton = play
        this.nextButton = next
        this.openButton = open
        this.lockButton = lock
        this.closeButton = close
        this.lastLocked = null
        this.lastPlaying = null
        this.lastFont = 0f
        this.lastAccent = 0
        this.lastOpacity = 0f
        this.lastLines = null
        this.lastActiveColor = 0
        this.lastProgress = -1
        return root
    }

    private fun lyricLine(sizeSp: Float, bold: Boolean): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(withAlpha(INK, 195))
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
            setPadding(dp(2), dp(1), dp(2), dp(1))
            if (bold) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }

    private fun applyLock(state: FloatingLyricsState) {
        lastLocked = state.locked
        header?.visibility = if (state.locked) View.GONE else View.VISIBLE
        controls?.visibility = if (state.locked) View.GONE else View.VISIBLE
        progress?.visibility = if (state.locked) View.GONE else View.VISIBLE
        updateFlags(state.locked)
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

    private fun paintAccent(view: View, accent: Int) {
        val background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xF60D0B0A.toInt())
            setStroke(dp(1), withAlpha(accent, 46))
        }
        view.background = background
        playButton?.setColorFilter(accent)
        lockButton?.setColorFilter(withAlpha(INK, 195))
        closeButton?.setColorFilter(withAlpha(INK, 195))
        openButton?.setColorFilter(withAlpha(INK, 180))
        previousButton?.setColorFilter(withAlpha(INK, 220))
        nextButton?.setColorFilter(withAlpha(INK, 220))
        progress?.progressTintList = android.content.res.ColorStateList.valueOf(accent)
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
        const val ACCENT = 0xFFF2B46B.toInt()
    }
}
