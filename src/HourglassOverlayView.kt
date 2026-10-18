package me.magnum.melonds.ui.emulator.hourglass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phone-native touch controls for Zelda: Phantom Hourglass (with the D-pad patch applied).
 *
 * Left side: dynamic joystick -> D-pad (light push also holds Y = walk slowly).
 * Right side: tap = B (slash), swipe = B + direction (long slash), circle = Y + B (spin),
 * double-tap = A + direction (roll). A single tap waits briefly so a double-tap never swings first.
 * Touches on the game's own HUD strips pass straight through as stylus touches, and briefly
 * switch the right side to stylus so item paths, menus and map drawing work natively.
 * The top screen is shown as a minimap; tap it to swap it to the big view.
 */
class HourglassOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Host {
        fun onScreenAreasChanged(top: Rect, bottom: Rect, topOnTop: Boolean, bottomOnTop: Boolean)
        fun onMenuRequested()
    }

    var host: Host? = null
        set(value) { field = value; pushScreenAreas() }

    val isActive: Boolean get() = true

    // ---- tuning ----
    private companion object {
        const val DOUBLE_TAP_MS = 200L      // a single tap waits this long for a second tap before slashing
        const val HUD_STRIP_TOP = 28        // DS pixels at the top of the touch screen passed through as stylus
        const val HUD_STRIP_BOTTOM = 30     // DS pixels at the bottom (right side only) passed through as stylus
        const val AUTO_STYLUS_MS = 3000L    // after a HUD tap, the right side stays in stylus mode this long
        const val WALK_THRESHOLD = 0.55f
        const val DEADZONE = 0.2f
        const val DIR_U = 1; const val DIR_D = 2; const val DIR_L = 4; const val DIR_R = 8
    }

    private val dp = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())

    // ---- layout ----
    private val game = RectF()
    private val mini = RectF()
    private var miniInBar = true
    private var bigMap = false
    private var split = 0f
    private var joyR = 0f

    private enum class B { A, STYLUS, MIC, LID, MENU, START, SELECT }
    private class Btn(val id: B, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, val label: String)
    private val buttons = listOf(
        Btn(B.MENU, label = "☰"), Btn(B.START, label = "START"), Btn(B.SELECT, label = "SEL"),
        Btn(B.STYLUS, label = "Pen"), Btn(B.MIC, label = "Mic"), Btn(B.A, label = "A"), Btn(B.LID, label = "Lid")
    )

    // ---- input state ----
    private val held = IntArray(Input.entries.size)
    private var joyDirs = 0
    private var lastDirs = DIR_D
    private var overrideDirs = -1
    private var overrideToken = 0
    private var appliedDirs = 0
    private var walking = false
    private var stylusMode = false
    private var stylusUntil = 0L
    private var stylusPointer = -1
    private var pendingTap: Runnable? = null
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var joyId = -1
    private var joyOx = 0f; private var joyOy = 0f; private var joyX = 0f; private var joyY = 0f

    private enum class Kind { BUTTON, JOY, STYLUS, GESTURE, NONE }
    private class Track(val kind: Kind, val btn: Btn? = null) {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        var t0 = SystemClock.uptimeMillis(); var turn = 0f; var len = 0f; var lastA = Float.NaN; var done = false
    }
    private val tracks = HashMap<Int, Track>()

    // ---- paints ----
    private val ink = 0xB312324A.toInt()
    private val cream = 0xFFF3E6C4.toInt()
    private val brass = 0xFFD9A63A.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    init {
        isClickable = true
        isFocusable = false
    }

    // ================= layout =================
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gw = min(w, h * 4f / 3f); val gh = gw * 3f / 4f
        game.set((w - gw) / 2f, (h - gh) / 2f, (w + gw) / 2f, (h + gh) / 2f)
        val bar = game.left
        val m = 10 * dp
        var mh = min(h * 0.40f, (bar - 2 * m) * 0.75f)
        miniInBar = mh >= 70 * dp
        if (!miniInBar) mh = h * 0.26f
        val mw = mh * 4f / 3f
        val ml = if (miniInBar) (bar - mw) / 2f else game.left + m
        mini.set(ml, m, ml + mw, m + mh)
        split = w * 0.42f
        joyR = 58 * dp

        // right-side column (inside the bar if there is room, else hugging the right edge)
        val rbx = if (w - game.right >= 84 * dp) (game.right + w) / 2f else w - 44 * dp
        fun place(id: B, x: Float, y: Float, r: Float) { buttons.first { it.id == id }.apply { cx = x; cy = y; this.r = r } }
        place(B.MENU, rbx, 34 * dp, 22 * dp)
        place(B.START, rbx - 26 * dp, 84 * dp, 20 * dp)
        place(B.SELECT, rbx + 26 * dp, 84 * dp, 20 * dp)
        place(B.STYLUS, rbx, h * 0.46f, 26 * dp)
        place(B.MIC, rbx, h * 0.46f + 62 * dp, 22 * dp)
        place(B.A, rbx, h - 54 * dp, 36 * dp)
        val lbx = if (miniInBar) bar / 2f else 40 * dp
        place(B.LID, lbx, mini.bottom + 34 * dp, 20 * dp)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ex = (200 * dp).toInt()
            systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, height - ex, width / 2, height),
                android.graphics.Rect(width / 2, height - ex, width, height)
            )
        }
        pushScreenAreas()
        invalidate()
    }

    private fun RectF.toDs(): Rect = Rect(left.roundToInt(), top.roundToInt(), width().roundToInt(), height().roundToInt())
    private val topRect get() = if (bigMap) game else mini
    private val bottomRect get() = if (bigMap) mini else game

    fun pushScreenAreas() {
        if (width == 0) return
        host?.onScreenAreasChanged(topRect.toDs(), bottomRect.toDs(), !bigMap, bigMap)
    }

    // ================= key helpers =================
    private fun press(i: Input) { if (held[i.ordinal]++ == 0) MelonEmulator.onInputDown(i) }
    private fun release(i: Input) { if (held[i.ordinal] > 0 && --held[i.ordinal] == 0) MelonEmulator.onInputUp(i) }
    private fun pulse(i: Input, ms: Long) { press(i); ui.postDelayed({ release(i) }, ms) }

    private fun applyDpad() {
        val want = if (overrideDirs >= 0) overrideDirs else joyDirs
        val diff = want xor appliedDirs
        val map = listOf(DIR_U to Input.UP, DIR_D to Input.DOWN, DIR_L to Input.LEFT, DIR_R to Input.RIGHT)
        for ((bit, input) in map) if (diff and bit != 0) {
            if (want and bit != 0) MelonEmulator.onInputDown(input) else MelonEmulator.onInputUp(input)
        }
        appliedDirs = want
    }

    /** Holds [dirs] on the D-pad for [ms], running [then] once the direction has registered. */
    private fun withDir(dirs: Int, ms: Long, then: () -> Unit) {
        val token = ++overrideToken
        overrideDirs = dirs; applyDpad()
        ui.postDelayed(then, 34)
        ui.postDelayed({ if (overrideToken == token) { overrideDirs = -1; applyDpad() } }, ms)
    }

    private fun dirsFor(angle: Float): Int {
        val sector = (((angle / (PI / 4)).roundToInt() % 8) + 8) % 8
        return when (sector) {
            0 -> DIR_R; 1 -> DIR_R or DIR_D; 2 -> DIR_D; 3 -> DIR_D or DIR_L
            4 -> DIR_L; 5 -> DIR_L or DIR_U; 6 -> DIR_U; else -> DIR_U or DIR_R
        }
    }

    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    // ================= actions =================
    private fun slash() { pulse(Input.B, 80); buzz() }
    private fun lunge(angle: Float) { val d = dirsFor(angle); lastDirs = d; withDir(d, 230) { pulse(Input.B, 130) }; buzz() }
    private fun spin() { pulse(Input.Y, 240); ui.postDelayed({ pulse(Input.B, 150) }, 34); buzz() }
    private fun roll() { val d = if (joyDirs != 0) joyDirs else lastDirs; withDir(d, 250) { pulse(Input.A, 150) }; buzz() }

    private fun onTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val pending = pendingTap
        if (pending != null && now - lastTapAt < DOUBLE_TAP_MS + 40 && hypot(x - lastTapX, y - lastTapY) < 90 * dp) {
            ui.removeCallbacks(pending); pendingTap = null
            roll()
            return
        }
        lastTapAt = now; lastTapX = x; lastTapY = y
        val r = Runnable { pendingTap = null; slash() }
        pendingTap = r
        ui.postDelayed(r, DOUBLE_TAP_MS)
    }

    // ================= stylus =================
    private fun toDs(x: Float, y: Float): Pair<Int, Int> {
        val r = bottomRect
        val dx = ((x - r.left) / r.width() * 256f).toInt().coerceIn(0, 255)
        val dy = ((y - r.top) / r.height() * 192f).toInt().coerceIn(0, 191)
        return dx to dy
    }
    private fun stylusDown(id: Int, x: Float, y: Float) {
        stylusPointer = id
        val (dx, dy) = toDs(x, y)
        MelonEmulator.onInputDown(Input.TOUCHSCREEN); MelonEmulator.onScreenTouch(dx, dy)
    }
    private fun stylusMove(x: Float, y: Float) { val (dx, dy) = toDs(x, y); MelonEmulator.onScreenTouch(dx, dy) }
    private fun stylusUp() {
        stylusPointer = -1
        MelonEmulator.onInputUp(Input.TOUCHSCREEN); MelonEmulator.onScreenRelease()
    }
    private val stylusActive get() = stylusMode || SystemClock.uptimeMillis() < stylusUntil

    // ================= joystick =================
    private fun updateJoy() {
        val dx = joyX - joyOx; val dy = joyY - joyOy; val d = hypot(dx, dy)
        if (d > joyR * 1.35f) { val k = (d - joyR * 1.35f) / d; joyOx += dx * k; joyOy += dy * k }
        val ex = joyX - joyOx; val ey = joyY - joyOy; val m = min(1f, hypot(ex, ey) / joyR)
        val newDirs = if (m < DEADZONE) 0 else dirsFor(atan2(ey, ex))
        val newWalk = m >= DEADZONE && m < WALK_THRESHOLD
        if (newWalk != walking) { walking = newWalk; if (walking) press(Input.Y) else release(Input.Y) }
        if (newDirs != joyDirs) { joyDirs = newDirs; if (newDirs != 0) lastDirs = newDirs; applyDpad() }
    }
    private fun releaseJoy() {
        joyId = -1; joyDirs = 0; applyDpad()
        if (walking) { walking = false; release(Input.Y) }
    }

    // ================= touch =================
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { val i = ev.actionIndex; down(ev.getPointerId(i), ev.getX(i), ev.getY(i)) }
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) move(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { val i = ev.actionIndex; up(ev.getPointerId(i), ev.getX(i), ev.getY(i)) }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        // buttons first
        buttons.firstOrNull { hypot(x - it.cx, y - it.cy) < it.r + 8 * dp }?.let { b ->
            tracks[id] = Track(Kind.BUTTON, b); buttonDown(b); buzz(); return
        }
        // minimap swaps the screens
        if (mini.contains(x, y)) {
            bigMap = !bigMap; pushScreenAreas(); buzz(); tracks[id] = Track(Kind.NONE); return
        }
        val onBottom = bottomRect.contains(x, y)
        if (onBottom && stylusPointer == -1) {
            val (_, dy) = toDs(x, y)
            val hudTop = dy < HUD_STRIP_TOP
            val hudBottom = dy >= 192 - HUD_STRIP_BOTTOM && x >= split
            val stylusHere = stylusMode || (stylusActive && x >= split) || hudTop || hudBottom
            if (stylusHere) {
                if (hudTop || hudBottom) stylusUntil = SystemClock.uptimeMillis() + AUTO_STYLUS_MS
                tracks[id] = Track(Kind.STYLUS); stylusDown(id, x, y); return
            }
        }
        if (x < split && joyId == -1) {
            joyId = id; joyOx = x; joyOy = y; joyX = x; joyY = y; updateJoy()
            tracks[id] = Track(Kind.JOY); return
        }
        tracks[id] = Track(Kind.GESTURE).apply { xs.add(x); ys.add(y) }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val t = tracks[id] ?: return
        when (t.kind) {
            Kind.JOY -> { joyX = x; joyY = y; updateJoy() }
            Kind.STYLUS -> if (stylusPointer == id) stylusMove(x, y)
            Kind.GESTURE -> {
                if (t.done) return
                val lx = t.xs.last(); val ly = t.ys.last(); val d = hypot(x - lx, y - ly)
                if (d < 5 * dp) return
                val a = atan2(y - ly, x - lx)
                if (!t.lastA.isNaN()) { var da = a - t.lastA; while (da > PI) da -= (2 * PI).toFloat(); while (da < -PI) da += (2 * PI).toFloat(); t.turn += da }
                t.lastA = a; t.len += d; t.xs.add(x); t.ys.add(y)
                if (abs(t.turn) > 5.1f && t.len > 120 * dp) { t.done = true; spin() }
            }
            else -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val t = tracks.remove(id) ?: return
        when (t.kind) {
            Kind.BUTTON -> t.btn?.let { buttonUp(it) }
            Kind.JOY -> releaseJoy()
            Kind.STYLUS -> {
                if (stylusPointer == id) stylusUp()
                if (!stylusMode && SystemClock.uptimeMillis() < stylusUntil) stylusUntil = SystemClock.uptimeMillis() + AUTO_STYLUS_MS
            }
            Kind.GESTURE -> {
                if (t.done) return
                val sx = t.xs.first(); val sy = t.ys.first()
                val disp = hypot(x - sx, y - sy); val dt = SystemClock.uptimeMillis() - t.t0
                if (disp >= 30 * dp) lunge(atan2(y - sy, x - sx))
                else if (disp < 18 * dp && dt < 350) onTap(x, y)
            }
            Kind.NONE -> {}
        }
    }

    private fun buttonDown(b: Btn) {
        when (b.id) {
            B.A -> press(Input.A)
            B.START -> press(Input.START)
            B.SELECT -> press(Input.SELECT)
            B.MIC -> MelonEmulator.setMicrophoneEnabled(true)
            B.LID -> MelonEmulator.onInputDown(Input.HINGE)
            B.STYLUS -> { stylusMode = !stylusMode; stylusUntil = 0L }
            B.MENU -> { releaseAll(); host?.onMenuRequested() }
        }
    }

    private fun buttonUp(b: Btn) {
        when (b.id) {
            B.A -> release(Input.A)
            B.START -> release(Input.START)
            B.SELECT -> release(Input.SELECT)
            B.MIC -> MelonEmulator.setMicrophoneEnabled(false)
            B.LID -> MelonEmulator.onInputUp(Input.HINGE)
            else -> {}
        }
    }

    private fun releaseAll() {
        for ((_, t) in tracks) if (t.kind == Kind.BUTTON) t.btn?.let { buttonUp(it) }
        tracks.clear()
        if (stylusPointer != -1) stylusUp()
        pendingTap?.let { ui.removeCallbacks(it) }; pendingTap = null
        ui.removeCallbacksAndMessages(null)
        joyId = -1; joyDirs = 0; overrideDirs = -1; overrideToken++; applyDpad()
        walking = false
        for (i in Input.entries) if (held[i.ordinal] > 0) { held[i.ordinal] = 0; MelonEmulator.onInputUp(i) }
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseAll()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    // ================= drawing =================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // minimap frame
        val mr = mini
        stroke.color = cream; stroke.strokeWidth = 3 * dp
        canvas.drawRoundRect(mr.left - 2 * dp, mr.top - 2 * dp, mr.right + 2 * dp, mr.bottom + 2 * dp, 8 * dp, 8 * dp, stroke)

        for (b in buttons) {
            val active = (b.id == B.STYLUS && stylusMode) || tracks.values.any { it.btn === b }
            fill.color = if (active) brass else ink
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            stroke.color = cream; stroke.strokeWidth = 2 * dp
            canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            text.color = if (active) 0xFF12324A.toInt() else cream
            text.textSize = when { b.label.length > 3 -> 10 * dp; b.id == B.A -> 22 * dp; else -> 13 * dp }
            canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2, text)
        }

        if (joyId != -1) {
            fill.color = 0x1FF3E6C4; canvas.drawCircle(joyOx, joyOy, joyR, fill)
            stroke.color = 0x73F3E6C4; stroke.strokeWidth = 2 * dp; canvas.drawCircle(joyOx, joyOy, joyR, stroke)
            val dx = joyX - joyOx; val dy = joyY - joyOy; val d = hypot(dx, dy); val m = min(1f, d / joyR)
            val kx = if (d > 0) joyOx + dx / d * m * joyR else joyOx; val ky = if (d > 0) joyOy + dy / d * m * joyR else joyOy
            fill.color = if (walking) 0x80F3E6C4.toInt() else 0xBFF3E6C4.toInt()
            canvas.drawCircle(kx, ky, joyR * 0.44f, fill)
        }

        if (stylusActive) {
            text.textSize = 14 * dp; text.color = brass
            val msg = if (stylusMode) "Pen mode: touches go straight to the game" else "Pen: draw or tap in the game"
            canvas.drawText(msg, width / 2f, game.top + 22 * dp, text)
            if (!stylusMode) postInvalidateDelayed(250)
        }
    }
}
