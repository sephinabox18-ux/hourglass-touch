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
 * Joystick:    free-floating on the left side: it appears wherever your thumb slides or rests. A quick tap
 *              there without sliding still reaches the game (the Menu tab). 8 directions with hysteresis;
 *              a light push walks slowly (Y). It can be hidden (Stick in the ⋯ tray); the choice is remembered.
 * B (sword):   tap = slash the way Link faces; tap while moving = lunge that way (B + D-pad);
 *              hold until the ring fills, then let go = spin attack (Y + B).
 * A:           on its own = talk / open / pick up / grab. While moving = roll.
 * Game screen: never attacks. Taps go to the game (talk, throw at a spot, menus, the game's own buttons);
 *              slides go to the game (item paths, sailing routes, map notes);
 *              press and hold grabs a block or lever, then slide onto an arrow and hold to keep pushing.
 *              Right after A or a grab, touches go to the game at once and repeat while held.
 * Save / Load: quick save on tap; quick load needs a short hold.
 * Pen:         on = every touch on the game screen goes straight to the game, left side included
 *              (charting courses, map notes, minigames). The joystick still works from the side bar.
 * FF:          fast-forward on/off (for sailing).
 * ⋯:           tray with Start, Select, Mic (hold and blow), Lid (hold to close) and Stick (show/hide joystick).
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
        const val JOY_RADIUS_DP = 58f
        const val SPLIT = 0.42f              // the left part of the screen (by width) belongs to the joystick
        const val JOY_DECIDE_MS = 160L       // a still thumb on the left becomes the joystick after this
        const val JOY_SLIDE_DP = 10f         // ...or as soon as it slides this far
        const val DEADZONE = 0.18f
        const val DIR_HYSTERESIS = 0.14f     // radians beyond the 22.5° sector edge before switching direction
        const val WALK_ENTER = 0.48f         // push below this to walk slowly...
        const val WALK_EXIT = 0.60f          // ...and past this to run again
        // ---- sword ----
        const val SPIN_CHARGE_MS = 450L      // hold the sword this long, then let go, to spin
        const val SLASH_PRESS_MS = 90L
        // ---- game screen ----
        const val TAP_DP = 12f               // a touch that moves less than this is a tap
        const val TAP_HOLD_MS = 60L
        const val HOLD_MS = 320L             // press and hold this long (without sliding) to grab
        const val REPEAT_MS = 230L           // while holding on a block arrow, it is pressed again this often
        const val GRAB_SLIDE_DP = 18f        // after grabbing, slide this far onto an arrow before repeating starts
        const val GRAB_KEEP_MS = 3000L       // after letting go of a grab, the next touch carries on pushing
        const val A_WINDOW_MS = 8000L        // after A (standing still), touches go to the game at once
        const val LOAD_HOLD_MS = 600L        // hold Load this long to quick load
        const val TAP_ID = -2
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
    private var split = 0f
    private var trayBottom = 0f

    private enum class B { A, SWORD, MIC, LID, MENU, START, SELECT, SAVE, LOAD, FF, PEN, STICK, MORE }
    private class Btn(val id: B, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, val label: String)
    private val buttons = listOf(
        Btn(B.MENU, label = "☰"), Btn(B.SAVE, label = "Save"), Btn(B.LOAD, label = "Load"),
        Btn(B.FF, label = "FF"), Btn(B.PEN, label = "Pen"), Btn(B.SWORD, label = "B"), Btn(B.A, label = "A"), Btn(B.MORE, label = "⋯"),
        Btn(B.START, label = "START"), Btn(B.SELECT, label = "SEL"), Btn(B.MIC, label = "Mic"), Btn(B.LID, label = "Lid"), Btn(B.STICK, label = "Stick")
    )
    private val trayIds = setOf(B.START, B.SELECT, B.MIC, B.LID, B.STICK)
    private val prefs = context.getSharedPreferences("hourglass_touch", Context.MODE_PRIVATE)
    private var stickVisible = prefs.getBoolean("stick_visible", true)
    private var penMode = false
    private var trayOpen = false
    private var ffOn = false
    private var micInit = false
    private var loadStart = 0L
    private var swordStart = 0L
    private var swordCharged = false
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

    private var stylusPointer = -1
    private var aWindowUntil = 0L
    private var grabUntil = 0L

    private enum class Kind { BUTTON, JOY, STYLUS, PENDING, HOLDING, UNDECIDED, NONE }
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

        val rightBar = w - game.right
        val rbx = if (rightBar >= 84 * dp) (game.right + w) / 2f else w - 44 * dp
        fun place(id: B, x: Float, y: Float, r: Float) { btn(id).apply { cx = x; cy = y; this.r = r } }
        place(B.MENU, rbx, 34 * dp, 22 * dp)
        place(B.SAVE, rbx - 27 * dp, 86 * dp, 21 * dp)
        place(B.LOAD, rbx + 27 * dp, 86 * dp, 21 * dp)
        place(B.FF, rbx, h * 0.37f, 21 * dp)
        place(B.PEN, rbx, h * 0.37f + 50 * dp, 21 * dp)
        // Sword and A: staggered so the thumb rocks between them (Sword up-left, A down-right)
        val spread = min(34 * dp, (rightBar / 2f - 38 * dp).coerceAtLeast(16 * dp))
        place(B.SWORD, rbx - spread, h - 122 * dp, 37 * dp)
        place(B.A, rbx + spread, h - 50 * dp, 34 * dp)
        // left bar: the ⋯ tray button under the minimap, and the tray row below it
        val lbx = if (miniInBar) bar / 2f else 40 * dp
        place(B.MORE, lbx, mini.bottom + 26 * dp, 16 * dp)
        val trayY = mini.bottom + 64 * dp
        listOf(B.START, B.SELECT, B.MIC, B.LID, B.STICK).forEachIndexed { i, id -> place(id, lbx + (i - 2f) * 33 * dp, trayY, 15 * dp) }
        trayBottom = trayY + 15 * dp

        joyR = JOY_RADIUS_DP * dp
        split = w * SPLIT

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
    private fun pulse(i: Input, ms: Long) { press(i); ui.postDelayed({ release(i) }, ms) }

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
        if (d > PI) d -= (2 * PI).toFloat()
        if (d < -PI) d += (2 * PI).toFloat()
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
    /** A short real touch at that spot. */
    private fun stylusTap(x: Float, y: Float) {
        if (stylusPointer != -1) return
        stylusDown(TAP_ID, x, y)
        ui.postDelayed({ if (stylusPointer == TAP_ID) stylusUp() }, TAP_HOLD_MS)
    }

    // ================= sword =================
    private fun swordDown() {
        swordStart = SystemClock.uptimeMillis(); swordCharged = false
        ui.postDelayed({
            if (swordStart != 0L && tracks.values.any { it.btn?.id == B.SWORD }) { swordCharged = true; buzz(); invalidate() }
        }, SPIN_CHARGE_MS)
        postInvalidateOnAnimation()
    }
    private fun swordUp() {
        if (swordStart == 0L) return
        val charged = swordCharged || SystemClock.uptimeMillis() - swordStart >= SPIN_CHARGE_MS
        swordStart = 0L; swordCharged = false
        if (charged) {
            // spin: Y + B
            press(Input.Y)
            ui.postDelayed({ press(Input.B) }, 34)
            ui.postDelayed({ release(Input.B) }, 190)
            ui.postDelayed({ release(Input.Y) }, 260)
        } else {
            // slash: B (with the joystick held, the D-pad patch makes this a lunge that way)
            pulse(Input.B, SLASH_PRESS_MS)
        }
        buzz()
    }

    // ================= grab and push =================
    private fun startHolding(id: Int, t: Track, fromWindow: Boolean) {
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
    private fun startJoy(id: Int, ox: Float, oy: Float, x: Float, y: Float) {
        joyId = id; joyCx = ox; joyCy = oy; joyX = x; joyY = y
        updateJoy()
    }

    private fun updateJoy() {
        // the joystick follows the thumb if it goes past the edge, so it never "runs out"
        val dx0 = joyX - joyCx; val dy0 = joyY - joyCy; val d0 = hypot(dx0, dy0)
        if (d0 > joyR * 1.35f) { val k = (d0 - joyR * 1.35f) / d0; joyCx += dx0 * k; joyCy += dy0 * k }
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
        // 1. buttons (Sword and A get generous touch areas)
        val big = setOf(B.A, B.SWORD)
        buttons.filter { visible(it) && hypot(x - it.cx, y - it.cy) < it.r + (if (it.id in big) 14 else 8) * dp }
            .minByOrNull { hypot(x - it.cx, y - it.cy) - it.r }?.let { b ->
                tracks[id] = Track(Kind.BUTTON, b); buttonDown(b); buzz(); return
            }
        // 2. the bar right of the game is for buttons only: low down, the nearer of Sword and A; else ignored
        if (x > game.right) {
            if (y > btn(B.PEN).cy + btn(B.PEN).r) {
                val b = listOf(btn(B.SWORD), btn(B.A)).minByOrNull { hypot(x - it.cx, y - it.cy) - it.r }!!
                tracks[id] = Track(Kind.BUTTON, b); buttonDown(b); buzz()
            } else tracks[id] = Track(Kind.NONE)
            return
        }
        // 3. minimap swaps the screens
        if (mini.contains(x, y)) { bigMap = !bigMap; pushScreenAreas(); buzz(); tracks[id] = Track(Kind.NONE); return }
        // 4. the side bar left of the game (below the tray): joystick straight away
        if (x < game.left && joyId == -1) {
            if (y > trayBottom + 6 * dp || !trayOpen) { startJoy(id, x, y, x, y); tracks[id] = Track(Kind.JOY) }
            else tracks[id] = Track(Kind.NONE)
            return
        }
        if (!bottomRect.contains(x, y)) { tracks[id] = Track(Kind.NONE); return }
        // 5. Pen on: everything on the game screen goes straight to the game
        if (penMode) {
            if (stylusPointer == -1) { tracks[id] = Track(Kind.STYLUS); stylusDown(id, x, y) } else tracks[id] = Track(Kind.NONE)
            return
        }
        // 6. left side of the game screen: joystick if the thumb slides or rests, a game tap if it's quick
        if (x < split && joyId == -1) {
            val t = Track(Kind.UNDECIDED, sx = x, sy = y)
            tracks[id] = t
            val r = Runnable { if (tracks[id] === t && t.kind == Kind.UNDECIDED && joyId == -1) { t.kind = Kind.JOY; startJoy(id, t.sx, t.sy, t.cx, t.cy); invalidate() } }
            t.timer = r
            ui.postDelayed(r, JOY_DECIDE_MS)
            return
        }
        // 7. the rest of the game screen
        val now = SystemClock.uptimeMillis()
        val t = Track(Kind.PENDING, sx = x, sy = y, t0 = now)
        tracks[id] = t
        // right after A or a grab: the touch goes to the game at once (throw target, block arrows), repeating while held
        if ((now < aWindowUntil || now < grabUntil) && stylusPointer == -1) { startHolding(id, t, fromWindow = true); return }
        // otherwise: a tap, a slide (drawing), or a hold (grab)
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
            Kind.UNDECIDED -> {
                t.cx = x; t.cy = y
                if (hypot(x - t.sx, y - t.sy) > JOY_SLIDE_DP * dp && joyId == -1) {
                    t.timer?.let { ui.removeCallbacks(it) }
                    t.kind = Kind.JOY; startJoy(id, t.sx, t.sy, x, y)
                }
            }
            Kind.PENDING -> {
                t.cx = x; t.cy = y
                if (hypot(x - t.sx, y - t.sy) >= TAP_DP * dp) {
                    // a slide: goes to the game as a drawing (item paths, sailing routes, map notes)
                    t.timer?.let { ui.removeCallbacks(it) }
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
            Kind.STYLUS -> if (stylusPointer == id) stylusUp()
            Kind.PENDING -> {
                t.timer?.let { ui.removeCallbacks(it) }
                stylusTap(t.sx, t.sy)
            }
            Kind.UNDECIDED -> {
                t.timer?.let { ui.removeCallbacks(it) }
                stylusTap(t.sx, t.sy)
            }
            Kind.HOLDING -> {
                t.timer?.let { ui.removeCallbacks(it) }
                val now = SystemClock.uptimeMillis()
                // a quick touch right after A was a throw: done. A held push keeps the grab going a moment.
                aWindowUntil = 0L
                grabUntil = if (t.fromWindow && now - t.t0 < HOLD_MS) 0L else now + GRAB_KEEP_MS
            }
            Kind.NONE -> {}
        }
    }

    private fun buttonDown(b: Btn) {
        when (b.id) {
            B.A -> { press(Input.A); if (joyDirs == 0) aWindowUntil = SystemClock.uptimeMillis() + A_WINDOW_MS }
            B.SWORD -> swordDown()
            B.SAVE -> host?.onFrontendKey(Input.QUICK_SAVE)
            B.LOAD -> {
                loadStart = SystemClock.uptimeMillis()
                ui.postDelayed({ if (loadStart != 0L && tracks.values.any { it.btn?.id == B.LOAD }) { loadStart = 0L; buzz(); host?.onFrontendKey(Input.QUICK_LOAD) } }, LOAD_HOLD_MS)
                postInvalidateOnAnimation()
            }
            B.FF -> { ffOn = !ffOn; host?.onFrontendKey(Input.FAST_FORWARD) }
            B.MORE -> trayOpen = !trayOpen
            B.PEN -> penMode = !penMode
            B.STICK -> { stickVisible = !stickVisible; prefs.edit().putBoolean("stick_visible", stickVisible).apply() }
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
            B.SWORD -> swordUp()
            B.START -> release(Input.START)
            B.SELECT -> release(Input.SELECT)
            B.MIC -> MelonEmulator.setMicrophoneEnabled(false)
            B.LID -> MelonEmulator.onInputUp(Input.HINGE)
            B.LOAD -> loadStart = 0L
            else -> {}
        }
    }

    private fun releaseAll() {
        for ((_, t) in tracks) if (t.kind == Kind.BUTTON && t.btn?.id != B.SWORD) t.btn?.let { buttonUp(it) }
        tracks.clear()
        ui.removeCallbacksAndMessages(null)
        loadStart = 0L; swordStart = 0L; swordCharged = false
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
    private fun ring(canvas: Canvas, b: Btn, start: Long, total: Long) {
        val k = ((SystemClock.uptimeMillis() - start).toFloat() / total).coerceIn(0f, 1f)
        stroke.color = brass; stroke.strokeWidth = 4 * dp
        canvas.drawArc(b.cx - b.r - 5 * dp, b.cy - b.r - 5 * dp, b.cx + b.r + 5 * dp, b.cy + b.r + 5 * dp, -90f, 360f * k, false, stroke)
        if (k < 1f) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        stroke.color = cream; stroke.strokeWidth = 3 * dp
        canvas.drawRoundRect(mini.left - 2 * dp, mini.top - 2 * dp, mini.right + 2 * dp, mini.bottom + 2 * dp, 8 * dp, 8 * dp, stroke)

        for (b in buttons) {
            if (!visible(b)) continue
            val active = tracks.values.any { it.btn === b } || (b.id == B.FF && ffOn) || (b.id == B.MORE && trayOpen) ||
                (b.id == B.PEN && penMode) || (b.id == B.STICK && stickVisible)
            fill.color = if (active) brass else ink
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            stroke.color = cream; stroke.strokeWidth = 2 * dp
            canvas.drawCircle(b.cx, b.cy, b.r, stroke)
            text.color = if (active) 0xFF12324A.toInt() else cream
            text.textSize = when (b.id) { B.A, B.SWORD -> 24 * dp; else -> if (b.label.length > 3) 10 * dp else 13 * dp }
            canvas.drawText(b.label, b.cx, b.cy - (text.descent() + text.ascent()) / 2, text)
            if (b.id == B.LOAD && loadStart != 0L) ring(canvas, b, loadStart, LOAD_HOLD_MS)
            if (b.id == B.SWORD && swordStart != 0L) ring(canvas, b, swordStart, SPIN_CHARGE_MS)
        }

        // grab ring under the finger while holding a block or lever
        tracks.values.firstOrNull { it.kind == Kind.HOLDING }?.let { g ->
            stroke.color = brass; stroke.strokeWidth = 3 * dp
            canvas.drawCircle(g.cx, g.cy, 24 * dp, stroke)
        }

        // floating joystick: drawn where the thumb is, unless hidden
        if (joyId != -1 && stickVisible) {
            fill.color = 0x26F3E6C4
            canvas.drawCircle(joyCx, joyCy, joyR, fill)
            stroke.color = 0x99F3E6C4.toInt(); stroke.strokeWidth = 2 * dp
            canvas.drawCircle(joyCx, joyCy, joyR, stroke)
            val dx = joyX - joyCx; val dy = joyY - joyCy; val d = hypot(dx, dy)
            var kx = joyCx; var ky = joyCy
            if (d > 0f) { val m = min(1f, d / joyR); kx = joyCx + dx / d * m * joyR; ky = joyCy + dy / d * m * joyR }
            fill.color = if (walking) 0x99F3E6C4.toInt() else 0xD9F3E6C4.toInt()
            canvas.drawCircle(kx, ky, joyR * 0.44f, fill)
        }
    }
}
