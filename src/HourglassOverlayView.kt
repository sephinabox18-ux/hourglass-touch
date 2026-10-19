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
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Rect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phone-native touch controls for Zelda: Phantom Hourglass (with the D-pad patch applied).
 *
 * Left side:  free-floating joystick -> D-pad (a light push also holds Y = walk slowly).
 *             A quick tap without sliding is passed to the game as a normal touch (Menu tab etc.).
 * Right side: the finger IS the stylus. The game's own touch controls handle everything there:
 *             swipe to slash, tap an enemy to attack, tap to throw, item paths, sailing routes,
 *             map notes and menus. Double-tap = spin attack (Y + B via the D-pad patch).
 *             A still finger is held back ~0.2 s so a double-tap never sends a stray first tap.
 * A button:   on its own = talk / open / pick up. Pressed while moving with the joystick = roll.
 * Minimap:    the DS top screen; tap it to swap it to the big view and back.
 */
class HourglassOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Host {
        fun onScreenAreasChanged(top: Rect, bottom: Rect, topOnTop: Boolean, bottomOnTop: Boolean)
        fun onMenuRequested()
    }

    var host: Host? = null
        set(value) { field = value; pushScreenAreas() }

    val isActive: Boolean get() = true

    private companion object {
        const val SPLIT = 0.42f              // fraction of screen width that belongs to the joystick side
        const val JOY_DECIDE_MS = 160L       // a left-side touch that hasn't slid by now becomes the joystick anyway
        const val TAP_HOLD_MS = 60L          // how long a passed-through left-side tap is held on the DS screen
        const val DOUBLE_TAP_MS = 220L       // right side: window for the second tap of a double-tap (spin)
        const val RIGHT_HOLD_MS = 200L       // right side: a still finger held this long becomes a normal touch
        const val WALK_THRESHOLD = 0.55f
        const val DEADZONE = 0.2f
        const val DIR_U = 1; const val DIR_D = 2; const val DIR_L = 4; const val DIR_R = 8
    }

    private val dp = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())

    // ---- layout ----
    private val game = RectF()
    private val mini = RectF()
    private var bigMap = false
    private var split = 0f
    private var joyR = 0f

    private enum class B { A, MIC, LID, MENU, START, SELECT }
    private class Btn(val id: B, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, val label: String)
    private val buttons = listOf(
        Btn(B.MENU, label = "☰"), Btn(B.START, label = "START"), Btn(B.SELECT, label = "SEL"),
        Btn(B.MIC, label = "Mic"), Btn(B.A, label = "A"), Btn(B.LID, label = "Lid")
    )
    private fun btn(id: B) = buttons.first { it.id == id }

    // ---- input state ----
    private val held = IntArray(Input.entries.size)
    private var joyDirs = 0
    private var appliedDirs = 0
    private var walking = false
    private var stylusPointer = -1
    private var pendingTap: Runnable? = null
    private var lastTapX = 0f; private var lastTapY = 0f

    private var joyId = -1
    private var joyOx = 0f; private var joyOy = 0f; private var joyX = 0f; private var joyY = 0f

    private enum class Kind { BUTTON, JOY, STYLUS, UNDECIDED, PENDING_RIGHT, NONE }
    private class Track(var kind: Kind, val btn: Btn? = null, val sx: Float = 0f, val sy: Float = 0f) {
        var decide: Runnable? = null
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
        val miniInBar = mh >= 70 * dp
        if (!miniInBar) mh = h * 0.26f
        val mw = mh * 4f / 3f
        val ml = if (miniInBar) (bar - mw) / 2f else game.left + m
        mini.set(ml, m, ml + mw, m + mh)
        split = w * SPLIT
        joyR = 58 * dp

        val rbx = if (w - game.right >= 84 * dp) (game.right + w) / 2f else w - 44 * dp
        fun place(id: B, x: Float, y: Float, r: Float) { btn(id).apply { cx = x; cy = y; this.r = r } }
        place(B.MENU, rbx, 34 * dp, 22 * dp)
        place(B.START, rbx - 26 * dp, 84 * dp, 20 * dp)
        place(B.SELECT, rbx + 26 * dp, 84 * dp, 20 * dp)
        place(B.MIC, rbx, h * 0.5f, 22 * dp)
        place(B.A, rbx, h - 58 * dp, 40 * dp)
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

    // ================= keys =================
    private fun press(i: Input) { if (held[i.ordinal]++ == 0) MelonEmulator.onInputDown(i) }
    private fun release(i: Input) { if (held[i.ordinal] > 0 && --held[i.ordinal] == 0) MelonEmulator.onInputUp(i) }

    private fun applyDpad() {
        val want = joyDirs
        val diff = want xor appliedDirs
        val map = listOf(DIR_U to Input.UP, DIR_D to Input.DOWN, DIR_L to Input.LEFT, DIR_R to Input.RIGHT)
        for ((bit, input) in map) if (diff and bit != 0) {
            if (want and bit != 0) MelonEmulator.onInputDown(input) else MelonEmulator.onInputUp(input)
        }
        appliedDirs = want
    }

    private fun dirsFor(angle: Float): Int {
        val sector = (((angle / (PI / 4)).roundToInt() % 8) + 8) % 8
        return when (sector) {
            0 -> DIR_R; 1 -> DIR_R or DIR_D; 2 -> DIR_D; 3 -> DIR_D or DIR_L
            4 -> DIR_L; 5 -> DIR_L or DIR_U; 6 -> DIR_U; else -> DIR_U or DIR_R
        }
    }

    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

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
    /** A quick tap on the left side: a short real touch at that spot. */
    private fun stylusTap(x: Float, y: Float) {
        if (stylusPointer != -1) return
        stylusDown(-2, x, y)
        ui.postDelayed({ if (stylusPointer == -2) stylusUp() }, TAP_HOLD_MS)
    }

    private fun spin() {
        press(Input.Y)
        ui.postDelayed({ press(Input.B) }, 34)
        ui.postDelayed({ release(Input.B) }, 190)
        ui.postDelayed({ release(Input.Y) }, 260)
        buzz()
    }

    /** A right-side tap that didn't move: wait briefly for a second tap (spin) before sending it to the game. */
    private fun rightTap(x: Float, y: Float) {
        val p = pendingTap
        if (p != null && hypot(x - lastTapX, y - lastTapY) < 90 * dp) {
            ui.removeCallbacks(p); pendingTap = null
            spin(); return
        }
        p?.let { ui.removeCallbacks(it); it.run() }
        lastTapX = x; lastTapY = y
        val r = Runnable { pendingTap = null; stylusTap(x, y) }
        pendingTap = r
        ui.postDelayed(r, DOUBLE_TAP_MS)
    }

    // ================= joystick =================
    private fun startJoy(id: Int, ox: Float, oy: Float, x: Float, y: Float) {
        joyId = id; joyOx = ox; joyOy = oy; joyX = x; joyY = y
        updateJoy()
    }
    private fun updateJoy() {
        val dx = joyX - joyOx; val dy = joyY - joyOy; val d = hypot(dx, dy)
        if (d > joyR * 1.35f) { val k = (d - joyR * 1.35f) / d; joyOx += dx * k; joyOy += dy * k }
        val ex = joyX - joyOx; val ey = joyY - joyOy; val m = min(1f, hypot(ex, ey) / joyR)
        val newDirs = if (m < DEADZONE) 0 else dirsFor(atan2(ey, ex))
        val newWalk = m >= DEADZONE && m < WALK_THRESHOLD
        if (newWalk != walking) { walking = newWalk; if (walking) press(Input.Y) else release(Input.Y) }
        if (newDirs != joyDirs) { joyDirs = newDirs; applyDpad() }
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
        // 1. buttons (A gets a generous touch area)
        buttons.firstOrNull { hypot(x - it.cx, y - it.cy) < it.r + (if (it.id == B.A) 18 else 8) * dp }?.let { b ->
            tracks[id] = Track(Kind.BUTTON, b); buttonDown(b); buzz(); return
        }
        // 2. the bar right of the game is for buttons only: low down counts as A, anything else is ignored
        if (x > game.right) {
            if (y > btn(B.MIC).cy + btn(B.MIC).r) { val a = btn(B.A); tracks[id] = Track(Kind.BUTTON, a); buttonDown(a); buzz() }
            else tracks[id] = Track(Kind.NONE)
            return
        }
        // 3. minimap swaps the screens
        if (mini.contains(x, y)) { bigMap = !bigMap; pushScreenAreas(); buzz(); tracks[id] = Track(Kind.NONE); return }

        // 4. left side: joystick (decided by sliding); a quick tap still reaches the game
        if (x < split) {
            if (joyId != -1) {
                // joystick already in use: treat this finger like the right side
                if (bottomRect.contains(x, y) && stylusPointer == -1) { tracks[id] = Track(Kind.STYLUS); stylusDown(id, x, y) }
                else tracks[id] = Track(Kind.NONE)
                return
            }
            if (!bottomRect.contains(x, y)) { tracks[id] = Track(Kind.JOY); startJoy(id, x, y, x, y); return }
            val t = Track(Kind.UNDECIDED, sx = x, sy = y)
            tracks[id] = t
            val r = Runnable { if (tracks[id] === t && t.kind == Kind.UNDECIDED && joyId == -1) { t.kind = Kind.JOY; startJoy(id, t.sx, t.sy, t.sx, t.sy); invalidate() } }
            t.decide = r
            ui.postDelayed(r, JOY_DECIDE_MS)
            return
        }

        // 5. right side: straight to the game. A finger that slides goes through at once (swipes, paths);
        //    a still finger is held back briefly so a double-tap can become a spin.
        if (bottomRect.contains(x, y) && stylusPointer == -1) {
            val t = Track(Kind.PENDING_RIGHT, sx = x, sy = y)
            tracks[id] = t
            val r = Runnable { if (tracks[id] === t && t.kind == Kind.PENDING_RIGHT && stylusPointer == -1) { flushPendingTap(); t.kind = Kind.STYLUS; stylusDown(id, t.sx, t.sy) } }
            t.decide = r
            ui.postDelayed(r, RIGHT_HOLD_MS)
        } else tracks[id] = Track(Kind.NONE)
    }

    /** A new slide or hold supersedes a tap still waiting for its double-tap window. */
    private fun flushPendingTap() { pendingTap?.let { ui.removeCallbacks(it); pendingTap = null } }

    private fun move(id: Int, x: Float, y: Float) {
        val t = tracks[id] ?: return
        when (t.kind) {
            Kind.JOY -> if (joyId == id) { joyX = x; joyY = y; updateJoy() }
            Kind.STYLUS -> if (stylusPointer == id) stylusMove(x, y)
            Kind.PENDING_RIGHT -> if (hypot(x - t.sx, y - t.sy) > 8 * dp) {
                t.decide?.let { ui.removeCallbacks(it) }
                flushPendingTap()
                if (stylusPointer == -1) { t.kind = Kind.STYLUS; stylusDown(id, t.sx, t.sy); stylusMove(x, y) } else t.kind = Kind.NONE
            }
            Kind.UNDECIDED -> if (hypot(x - t.sx, y - t.sy) > 10 * dp && joyId == -1) {
                t.decide?.let { ui.removeCallbacks(it) }
                t.kind = Kind.JOY; startJoy(id, t.sx, t.sy, x, y)
            }
            else -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val t = tracks.remove(id) ?: return
        when (t.kind) {
            Kind.BUTTON -> t.btn?.let { buttonUp(it) }
            Kind.JOY -> if (joyId == id) releaseJoy()
            Kind.STYLUS -> if (stylusPointer == id) stylusUp()
            Kind.UNDECIDED -> { t.decide?.let { ui.removeCallbacks(it) }; stylusTap(t.sx, t.sy) }
            Kind.PENDING_RIGHT -> { t.decide?.let { ui.removeCallbacks(it) }; rightTap(t.sx, t.sy) }
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
            B.MENU -> {}
        }
    }

    private fun releaseAll() {
        for ((_, t) in tracks) if (t.kind == Kind.BUTTON) t.btn?.let { buttonUp(it) }
        tracks.clear()
        ui.removeCallbacksAndMessages(null)
        pendingTap = null
        if (stylusPointer != -1) stylusUp()
        joyId = -1; joyDirs = 0; applyDpad()
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
        stroke.color = cream; stroke.strokeWidth = 3 * dp
        canvas.drawRoundRect(mini.left - 2 * dp, mini.top - 2 * dp, mini.right + 2 * dp, mini.bottom + 2 * dp, 8 * dp, 8 * dp, stroke)

        for (b in buttons) {
            val active = tracks.values.any { it.btn === b }
            fill.color = if (active) brass else ink
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            stroke.color = cream; stroke.strokeWidth = 2 * dp
            canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            text.color = if (active) 0xFF12324A.toInt() else cream
            text.textSize = when { b.label.length > 3 -> 10 * dp; b.id == B.A -> 24 * dp; else -> 13 * dp }
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
    }
}
