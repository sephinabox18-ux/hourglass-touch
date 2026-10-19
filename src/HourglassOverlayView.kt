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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Phone-native touch controls for Zelda: Phantom Hourglass (with the D-pad patch applied).
 *
 * Joystick:    fixed in the lower-left. Once your thumb is on it, it stays the joystick wherever the thumb
 *              slides, even onto the game. 8 directions with hysteresis so it doesn't flicker between
 *              them; a light push walks slowly (Y).
 * Game screen: weapons only, never movement.
 *              Fast flick  -> a short slash stroke is drawn beside Link, so he attacks that way
 *                             (the game aims slashes by where you draw relative to Link).
 *              Tap         -> slash the way Link faces. Double-tap -> spin (Y + B).
 *              Slow drag   -> straight to the game: item paths, sailing routes, map notes.
 *              Press and hold -> grabs what's under your finger (a block, a lever); keep holding and slide
 *                             onto the arrow you want, and it keeps pushing or pulling until you let go.
 *              Taps on the game's own buttons (top and bottom edges), taps for a few seconds after
 *              that (menus), and touches right after A (throw target, block arrows) go straight to the game.
 *              Flicks always attack, wherever they start.
 * A button:    on its own = talk / open / pick up / grab. While moving = roll.
 * Save / Load: quick save on tap; quick load needs a short hold so it can't happen by accident.
 * FF:          fast-forward on/off (for sailing).
 * ⋯:           opens the tray with Start, Select, Mic (hold and blow) and Lid (hold to close).
 * Minimap:     the DS top screen; tap to swap it to the big view and back.
 */
class HourglassOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Host {
        fun onScreenAreasChanged(top: Rect, bottom: Rect, topOnTop: Boolean, bottomOnTop: Boolean)
        fun onMenuRequested()
        fun onFrontendKey(input: Input)
    }

    var host: Host? = null
        set(value) { field = value; pushScreenAreas() }

    val isActive: Boolean get() = true

    private companion object {
        // ---- joystick ----
        const val JOY_RADIUS_DP = 60f
        const val DEADZONE = 0.18f
        const val DIR_HYSTERESIS = 0.14f     // radians beyond the 22.5° sector edge before switching direction
        const val WALK_ENTER = 0.48f         // push below this to walk slowly...
        const val WALK_EXIT = 0.60f          // ...and past this to run again
        // ---- attacks ----
        const val FLICK_MS = 180L            // a slide this fast counts as an attack flick...
        const val FLICK_DP = 18f             // ...if it travels at least this far (dp)
        const val LATE_FLICK_MS = 280L       // a quick release after a short slide still counts as a flick
        const val TAP_DP = 12f
        const val DRAW_DP = 12f              // a slower slide past this distance is a drawing for the game
        const val DOUBLE_TAP_MS = 220L       // window for the second tap of a double-tap (spin)
        // ---- the game's own buttons and menus ----
        const val HUD_STRIP_TOP = 28         // DS pixels along the top edge
        const val HUD_STRIP_BOTTOM = 30      // DS pixels along the bottom edge
        const val RAW_KEEP_MS = 6000L        // after tapping a game button, taps go to the game this long
        const val A_WINDOW_MS = 8000L        // after A (standing still), touches go to the game (throws, block arrows)
        const val HOLD_MS = 320L             // press and hold this long (without sliding) to grab
        const val REPEAT_MS = 230L           // while holding on a block arrow, it is pressed again this often
        const val GRAB_SLIDE_DP = 18f        // after grabbing, slide this far onto an arrow before repeating starts
        const val GRAB_KEEP_MS = 3000L       // after letting go of a grab, the next touch carries on pushing
        const val LOAD_HOLD_MS = 600L        // hold Load this long to quick load
        const val TAP_HOLD_MS = 60L
        // ---- the slash stroke drawn beside Link ----
        const val LINK_X = 128f              // where Link usually is on the DS touch screen
        const val LINK_Y = 92f
        const val SLASH_REACH = 22f          // DS pixels from Link to the middle of the stroke
        const val SLASH_HALF = 14f           // half the stroke length (drawn across the attack direction)
        const val SLASH_STEPS = 5
        const val SLASH_STEP_MS = 17L        // about one frame per step
        const val SYNTH = -3                 // pointer id used for drawn strokes
        const val TAP_ID = -2                // pointer id used for passed-through taps
        const val DIR_U = 1; const val DIR_D = 2; const val DIR_L = 4; const val DIR_R = 8
    }

    private val dp = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())

    // ---- layout ----
    private val game = RectF()
    private val mini = RectF()
    private var bigMap = false
    private var joyR = 0f
    private var joyCx = 0f
    private var joyCy = 0f
    private var joyZoneRight = 0f
    private var joyZoneTop = 0f

    private enum class B { A, MIC, LID, MENU, START, SELECT, SAVE, LOAD, FF, MORE }
    private class Btn(val id: B, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, val label: String)
    private val buttons = listOf(
        Btn(B.MENU, label = "☰"), Btn(B.SAVE, label = "Save"), Btn(B.LOAD, label = "Load"),
        Btn(B.FF, label = "FF"), Btn(B.A, label = "A"), Btn(B.MORE, label = "⋯"),
        Btn(B.START, label = "START"), Btn(B.SELECT, label = "SEL"), Btn(B.MIC, label = "Mic"), Btn(B.LID, label = "Lid")
    )
    private val trayIds = setOf(B.START, B.SELECT, B.MIC, B.LID)
    private var trayOpen = false
    private var ffOn = false
    private var micInit = false
    private var loadStart = 0L
    private fun btn(id: B) = buttons.first { it.id == id }
    private fun visible(b: Btn) = b.id !in trayIds || trayOpen

    // ---- input state ----
    private val held = IntArray(Input.entries.size)
    private var joyId = -1
    private var joyX = 0f
    private var joyY = 0f
    private var joySector = -1
    private var joyDirs = 0
    private var appliedDirs = 0
    private var walking = false
    private var facing = (PI / 2).toFloat()   // the way Link last moved or attacked (screen angle)

    private var stylusPointer = -1
    private var queuedAttack: Float? = null
    private var pendingTap: Runnable? = null
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var rawUntil = 0L
    private var aWindowUntil = 0L
    private var grabUntil = 0L

    private enum class Kind { BUTTON, JOY, STYLUS, PENDING, HOLDING, NONE }
    private class Track(var kind: Kind, val btn: Btn? = null, val sx: Float = 0f, val sy: Float = 0f, val t0: Long = 0L) {
        var cx = sx; var cy = sy
        var armed = false          // HOLDING: whether the repeat presses are active yet
        var fromWindow = false     // HOLDING: started inside the A / grab window (no grab tap needed)
        var timer: Runnable? = null
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

        val rbx = if (w - game.right >= 84 * dp) (game.right + w) / 2f else w - 44 * dp
        fun place(id: B, x: Float, y: Float, r: Float) { btn(id).apply { cx = x; cy = y; this.r = r } }
        place(B.MENU, rbx, 34 * dp, 22 * dp)
        place(B.SAVE, rbx - 27 * dp, 86 * dp, 21 * dp)
        place(B.LOAD, rbx + 27 * dp, 86 * dp, 21 * dp)
        place(B.FF, rbx, h * 0.5f, 22 * dp)
        place(B.A, rbx, h - 58 * dp, 40 * dp)
        // left bar: the ⋯ tray button under the minimap, and the tray row below it
        val lbx = if (miniInBar) bar / 2f else 40 * dp
        place(B.MORE, lbx, mini.bottom + 26 * dp, 16 * dp)
        val trayY = mini.bottom + 64 * dp
        listOf(B.START, B.SELECT, B.MIC, B.LID).forEachIndexed { i, id -> place(id, lbx + (i - 1.5f) * 38 * dp, trayY, 17 * dp) }

        // fixed joystick in the lower-left (in the side bar when there is room)
        joyR = JOY_RADIUS_DP * dp
        joyCx = if (bar >= 2 * joyR + 24 * dp) bar / 2f else game.left + joyR + 24 * dp
        joyCy = h - joyR - 32 * dp
        joyZoneRight = maxOf(bar + 16 * dp, joyCx + joyR)
        joyZoneTop = btn(B.LID).let { it.cy + it.r + 10 * dp }.coerceAtMost(joyCy - joyR)

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

    private fun sectorOf(angle: Float) = (((angle / (PI / 4)).roundToInt() % 8) + 8) % 8
    private fun dirsOfSector(sector: Int) = when (sector) {
        0 -> DIR_R; 1 -> DIR_R or DIR_D; 2 -> DIR_D; 3 -> DIR_D or DIR_L
        4 -> DIR_L; 5 -> DIR_L or DIR_U; 6 -> DIR_U; 7 -> DIR_U or DIR_R
        else -> 0
    }
    private fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % (2 * PI).toFloat()
        if (d > PI) d -= (2 * PI).toFloat(); if (d < -PI) d += (2 * PI).toFloat()
        return abs(d)
    }

    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    // ================= stylus =================
    private fun toDs(x: Float, y: Float): Pair<Int, Int> {
        val r = bottomRect
        val dx = ((x - r.left) / r.width() * 256f).toInt().coerceIn(0, 255)
        val dy = ((y - r.top) / r.height() * 192f).toInt().coerceIn(0, 191)
        return dx to dy
    }
    private fun isHud(x: Float, y: Float): Boolean {
        if (!bottomRect.contains(x, y)) return false
        val (_, dy) = toDs(x, y); return dy < HUD_STRIP_TOP || dy >= 192 - HUD_STRIP_BOTTOM
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
        queuedAttack?.let { a -> queuedAttack = null; ui.postDelayed({ slashToward(a) }, 34) }
    }
    /** A short real touch at that spot. */
    private fun stylusTap(x: Float, y: Float) {
        if (stylusPointer != -1) return
        stylusDown(TAP_ID, x, y)
        ui.postDelayed({ if (stylusPointer == TAP_ID) stylusUp() }, TAP_HOLD_MS)
    }

    // ================= attacks =================
    /** Draws a short, fast stroke across the space beside Link, so the game slashes toward [angle]. */
    private fun slashToward(angle: Float) {
        facing = angle
        if (stylusPointer == SYNTH) { queuedAttack = angle; return }   // chain it right after the current one
        if (stylusPointer != -1) return
        val cx = LINK_X + cos(angle) * SLASH_REACH; val cy = LINK_Y + sin(angle) * SLASH_REACH
        val px = -sin(angle); val py = cos(angle)
        stylusPointer = SYNTH
        for (i in 0..SLASH_STEPS) {
            val k = -1f + 2f * i / SLASH_STEPS
            val sx = (cx + px * SLASH_HALF * k).roundToInt().coerceIn(0, 255)
            val sy = (cy + py * SLASH_HALF * k).roundToInt().coerceIn(0, 191)
            ui.postDelayed({
                if (stylusPointer == SYNTH) {
                    if (i == 0) MelonEmulator.onInputDown(Input.TOUCHSCREEN)
                    MelonEmulator.onScreenTouch(sx, sy)
                }
            }, i * SLASH_STEP_MS)
        }
        ui.postDelayed({ if (stylusPointer == SYNTH) stylusUp() }, (SLASH_STEPS + 1) * SLASH_STEP_MS)
        buzz()
    }

    private fun spin() {
        queuedAttack = null
        press(Input.Y)
        ui.postDelayed({ press(Input.B) }, 34)
        ui.postDelayed({ release(Input.B) }, 190)
        ui.postDelayed({ release(Input.Y) }, 260)
        buzz()
    }

    private fun flick(angle: Float) {
        cancelPendingTap()
        slashToward(angle)
    }

    private fun cancelPendingTap() { pendingTap?.let { ui.removeCallbacks(it); pendingTap = null } }

    /** A tap on the game screen. */
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val hud = isHud(x, y)
        // game buttons, and menus right after them, go straight to the game
        if (hud || now < rawUntil) {
            cancelPendingTap()
            stylusTap(x, y)
            rawUntil = now + RAW_KEEP_MS
            return
        }
        // otherwise: a sword tap, or the second half of a double-tap (spin)
        val p = pendingTap
        if (p != null && hypot(x - lastTapX, y - lastTapY) < 100 * dp) {
            ui.removeCallbacks(p); pendingTap = null
            spin(); return
        }
        lastTapX = x; lastTapY = y
        val r = Runnable { pendingTap = null; slashToward(facing) }
        pendingTap = r
        ui.postDelayed(r, DOUBLE_TAP_MS)
    }

    // ================= grab and push =================
    private fun startHolding(id: Int, t: Track, fromWindow: Boolean) {
        cancelPendingTap()
        t.kind = Kind.HOLDING; t.fromWindow = fromWindow
        if (fromWindow) { t.armed = true; stylusTap(t.cx, t.cy) }
        else { stylusTap(t.sx, t.sy); buzz() }            // the grab
        val r = object : Runnable {
            override fun run() {
                if (tracks[id] !== t || t.kind != Kind.HOLDING) return
                if (!t.armed && hypot(t.cx - t.sx, t.cy - t.sy) > GRAB_SLIDE_DP * dp) t.armed = true
                if (t.armed) stylusTap(t.cx, t.cy)
                ui.postDelayed(this, REPEAT_MS)
            }
        }
        t.timer = r
        ui.postDelayed(r, REPEAT_MS)
        invalidate()
    }

    // ================= joystick =================
    private fun inJoyZone(x: Float, y: Float): Boolean =
        hypot(x - joyCx, y - joyCy) < joyR * 1.6f || (x < joyZoneRight && y > joyZoneTop)

    private fun updateJoy() {
        val ex = joyX - joyCx; val ey = joyY - joyCy
        val m = min(1f, hypot(ex, ey) / joyR)
        val ang = atan2(ey, ex)
        if (m < DEADZONE) joySector = -1
        else if (joySector == -1 || angleDiff(ang, (joySector * PI / 4).toFloat()) > (PI / 8).toFloat() + DIR_HYSTERESIS) joySector = sectorOf(ang)
        val newDirs = dirsOfSector(joySector)
        val newWalk = when {
            m < DEADZONE -> false
            walking -> m < WALK_EXIT
            else -> m < WALK_ENTER
        }
        if (newWalk != walking) { walking = newWalk; if (walking) press(Input.Y) else release(Input.Y) }
        if (newDirs != joyDirs) { joyDirs = newDirs; applyDpad() }
        if (joySector >= 0) facing = (joySector * PI / 4).toFloat()
    }
    private fun releaseJoy() {
        joyId = -1; joySector = -1; joyDirs = 0; applyDpad()
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
        if (!micInit) { micInit = true; MelonEmulator.setMicrophoneEnabled(false) }   // mic only while Mic is held
        // 1. buttons (A gets a generous touch area)
        buttons.firstOrNull { visible(it) && hypot(x - it.cx, y - it.cy) < it.r + (if (it.id == B.A) 18 else 8) * dp }?.let { b ->
            tracks[id] = Track(Kind.BUTTON, b); buttonDown(b); buzz(); return
        }
        // 2. the bar right of the game is for buttons only: low down counts as A, anything else is ignored
        if (x > game.right) {
            if (y > btn(B.FF).cy + btn(B.FF).r) { val a = btn(B.A); tracks[id] = Track(Kind.BUTTON, a); buttonDown(a); buzz() }
            else tracks[id] = Track(Kind.NONE)
            return
        }
        // 3. minimap swaps the screens
        if (mini.contains(x, y)) { bigMap = !bigMap; pushScreenAreas(); buzz(); tracks[id] = Track(Kind.NONE); return }
        // 4. joystick: its zone in the lower-left; it then follows that thumb anywhere
        if (joyId == -1 && inJoyZone(x, y)) {
            joyId = id; joyX = x; joyY = y; updateJoy()
            tracks[id] = Track(Kind.JOY); return
        }
        // 5. the game screen
        if (!bottomRect.contains(x, y)) { tracks[id] = Track(Kind.NONE); return }
        val now = SystemClock.uptimeMillis()
        val t = Track(Kind.PENDING, sx = x, sy = y, t0 = now)
        tracks[id] = t
        // right after A or a grab: the touch goes to the game at once (throw target, block arrows), repeating while held
        if ((now < aWindowUntil || now < grabUntil) && stylusPointer == -1) { startHolding(id, t, fromWindow = true); return }
        // otherwise weapons, decided by how the finger moves; holding still grabs
        val r = Runnable {
            if (tracks[id] === t && t.kind == Kind.PENDING && hypot(t.cx - t.sx, t.cy - t.sy) < TAP_DP * dp && stylusPointer == -1) startHolding(id, t, fromWindow = false)
        }
        t.timer = r
        ui.postDelayed(r, HOLD_MS)
    }

    private fun move(id: Int, x: Float, y: Float) {
        val t = tracks[id] ?: return
        when (t.kind) {
            Kind.JOY -> if (joyId == id) { joyX = x; joyY = y; updateJoy() }
            Kind.STYLUS -> if (stylusPointer == id) stylusMove(x, y)
            Kind.HOLDING -> { t.cx = x; t.cy = y }
            Kind.PENDING -> {
                t.cx = x; t.cy = y
                val d = hypot(x - t.sx, y - t.sy); val el = SystemClock.uptimeMillis() - t.t0
                if (el <= FLICK_MS && d >= FLICK_DP * dp) {
                    t.kind = Kind.NONE; flick(atan2(y - t.sy, x - t.sx))
                } else if (el > FLICK_MS && d >= DRAW_DP * dp) {
                    // a slower slide: a drawing for the game (item paths, sailing routes, map notes)
                    cancelPendingTap()
                    if (stylusPointer == -1) { t.kind = Kind.STYLUS; stylusDown(id, t.sx, t.sy); stylusMove(x, y) } else t.kind = Kind.NONE
                }
            }
            else -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val t = tracks.remove(id) ?: return
        when (t.kind) {
            Kind.BUTTON -> t.btn?.let { buttonUp(it) }
            Kind.JOY -> if (joyId == id) releaseJoy()
            Kind.STYLUS -> {
                if (stylusPointer == id) stylusUp()
                val now = SystemClock.uptimeMillis()
                if (now < rawUntil) rawUntil = now + RAW_KEEP_MS
            }
            Kind.PENDING -> {
                t.timer?.let { ui.removeCallbacks(it) }
                val d = hypot(x - t.sx, y - t.sy); val el = SystemClock.uptimeMillis() - t.t0
                if (d < TAP_DP * dp) tap(t.sx, t.sy)
                else if (el <= LATE_FLICK_MS) flick(atan2(y - t.sy, x - t.sx))
            }
            Kind.HOLDING -> {
                t.timer?.let { ui.removeCallbacks(it) }
                val now = SystemClock.uptimeMillis()
                // a quick touch right after A was a throw: back to the sword. A held push keeps the grab going.
                if (t.fromWindow && now - t.t0 < HOLD_MS) { aWindowUntil = 0L; grabUntil = 0L }
                else { aWindowUntil = 0L; grabUntil = now + GRAB_KEEP_MS }
            }
            Kind.NONE -> {}
        }
    }

    private fun buttonDown(b: Btn) {
        when (b.id) {
            B.A -> { press(Input.A); if (joyDirs == 0) aWindowUntil = SystemClock.uptimeMillis() + A_WINDOW_MS }
            B.SAVE -> host?.onFrontendKey(Input.QUICK_SAVE)
            B.LOAD -> {
                loadStart = SystemClock.uptimeMillis()
                ui.postDelayed({ if (loadStart != 0L && tracks.values.any { it.btn?.id == B.LOAD }) { loadStart = 0L; buzz(); host?.onFrontendKey(Input.QUICK_LOAD) } }, LOAD_HOLD_MS)
                postInvalidateOnAnimation()
            }
            B.FF -> { ffOn = !ffOn; host?.onFrontendKey(Input.FAST_FORWARD) }
            B.MORE -> trayOpen = !trayOpen
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
            B.LOAD -> loadStart = 0L
            else -> {}
        }
    }

    private fun releaseAll() {
        for ((_, t) in tracks) if (t.kind == Kind.BUTTON) t.btn?.let { buttonUp(it) }
        tracks.clear()
        ui.removeCallbacksAndMessages(null)
        pendingTap = null; queuedAttack = null; loadStart = 0L
        if (stylusPointer != -1) { stylusPointer = -1; MelonEmulator.onInputUp(Input.TOUCHSCREEN); MelonEmulator.onScreenRelease() }
        joyId = -1; joySector = -1; joyDirs = 0; applyDpad()
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
            if (!visible(b)) continue
            val active = tracks.values.any { it.btn === b } || (b.id == B.FF && ffOn) || (b.id == B.MORE && trayOpen)
            fill.color = if (active) brass else ink
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            stroke.color = cream; stroke.strokeWidth = 2 * dp
            canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            text.color = if (active) 0xFF12324A.toInt() else cream
            text.textSize = when { b.label.length > 3 -> 10 * dp; b.id == B.A -> 24 * dp; else -> 13 * dp }
            canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2, text)
            if (b.id == B.LOAD && loadStart != 0L) {
                val k = ((SystemClock.uptimeMillis() - loadStart).toFloat() / LOAD_HOLD_MS).coerceIn(0f, 1f)
                stroke.color = brass; stroke.strokeWidth = 4 * dp
                canvas.drawArc(b.cx - b.r - 4 * dp, b.cy - b.r - 4 * dp, b.cx + b.r + 4 * dp, b.cy + b.r + 4 * dp, -90f, 360f * k, false, stroke)
                postInvalidateOnAnimation()
            }
        }

        // grab ring under the finger while holding a block or lever
        tracks.values.firstOrNull { it.kind == Kind.HOLDING }?.let { g ->
            stroke.color = brass; stroke.strokeWidth = 3 * dp
            canvas.drawCircle(g.cx, g.cy, 24 * dp, stroke)
        }

        // fixed joystick: always visible, knob follows the thumb
        val on = joyId != -1
        fill.color = if (on) 0x26F3E6C4 else 0x14F3E6C4
        canvas.drawCircle(joyCx, joyCy, joyR, fill)
        stroke.color = if (on) 0x99F3E6C4.toInt() else 0x59F3E6C4; stroke.strokeWidth = 2 * dp
        canvas.drawCircle(joyCx, joyCy, joyR, stroke)
        var kx = joyCx; var ky = joyCy
        if (on) {
            val dx = joyX - joyCx; val dy = joyY - joyCy; val d = hypot(dx, dy)
            if (d > 0f) { val m = min(1f, d / joyR); kx = joyCx + dx / d * m * joyR; ky = joyCy + dy / d * m * joyR }
        }
        fill.color = if (!on) 0x66F3E6C4 else if (walking) 0x99F3E6C4.toInt() else 0xD9F3E6C4.toInt()
        canvas.drawCircle(kx, ky, joyR * 0.44f, fill)
    }
}
