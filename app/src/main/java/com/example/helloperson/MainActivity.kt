package com.example.helloperson

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("hello", Context.MODE_PRIVATE) }
    private var showingGreeting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showNameScreen()
    }

    // ---------- Screen 1: choose a name ----------

    private fun showNameScreen() {
        showingGreeting = false
        val dp = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "Who should we greet?"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val input = EditText(this).apply {
            hint = "Type a name"
            setText(prefs.getString("name", ""))
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(140, 125, 170))
            textSize = 22f
            gravity = Gravity.CENTER
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 22, 48))
                setStroke((2 * dp).toInt(), Color.rgb(255, 196, 64))
                cornerRadius = 14 * dp
            }
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        }

        val go = Button(this).apply {
            text = "Light it up!"
            textSize = 18f
            setTextColor(Color.rgb(30, 18, 0))
            background = GradientDrawable().apply {
                setColor(Color.rgb(255, 196, 64))
                cornerRadius = 14 * dp
            }
        }

        val start = {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.error = "Enter a name first"
            } else {
                prefs.edit().putString("name", name).apply()
                hideKeyboard(input)
                showGreeting(name)
            }
        }
        go.setOnClickListener { start() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) { start(); true } else false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(12, 8, 22))
            val side = (32 * dp).toInt()
            setPadding(side, side, side, side)
            val gap = (20 * dp).toInt()
            addView(title)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt()).apply { topMargin = gap })
        }
        setContentView(root)
        input.requestFocus()
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ---------- Screen 2: greeting + lights + fireworks ----------

    private fun showGreeting(name: String) {
        showingGreeting = true
        setContentView(MarqueeView(this, "Hello, $name!"))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingGreeting) showNameScreen() else super.onBackPressed()
    }
}

/** Greeting framed by chasing marquee bulbs. Tap anywhere to launch fireworks. */
class MarqueeView(context: Context, private val text: String) : View(context) {

    private val density = resources.displayMetrics.density
    private val startTime = System.nanoTime()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }
    private val bulbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(70, 45, 15)
    }

    private val frame = RectF()
    private val bulbs = ArrayList<PointF>()
    private val hsv = FloatArray(3)
    private var baseTextSize = 0f
    private var lastFrameNanos = 0L
    private var tapped = false

    // Fireworks: rockets rise to the tap point, then burst into sparks.
    private class Rocket(var x: Float, var y: Float, val tx: Float, val ty: Float, val hue: Float)
    private class Spark(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val color: Int, var life: Float, val maxLife: Float
    )
    private val rockets = ArrayList<Rocket>()
    private val sparks = ArrayList<Spark>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        textPaint.textSize = 100f
        val fitWidth = 100f * (w * 0.72f) / textPaint.measureText(text)
        baseTextSize = min(fitWidth, h * 0.16f)
        hintPaint.textSize = 14f * density

        val pad = max(22f * density, min(w, h) * 0.06f)
        frame.set(pad, pad, w - pad, h - pad)

        bulbs.clear()
        val perimeter = 2f * (frame.width() + frame.height())
        val count = (perimeter / (26f * density)).toInt()
        for (i in 0 until count) bulbs.add(pointOnFrame(perimeter * i / count))
    }

    private fun pointOnFrame(distance: Float): PointF {
        var d = distance
        val fw = frame.width()
        val fh = frame.height()
        if (d < fw) return PointF(frame.left + d, frame.top); d -= fw
        if (d < fh) return PointF(frame.right, frame.top + d); d -= fh
        if (d < fw) return PointF(frame.right - d, frame.bottom); d -= fw
        return PointF(frame.left, frame.bottom - d)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            launch(event.getX(i), event.getY(i))
            tapped = true
            return true
        }
        return event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP ||
            super.onTouchEvent(event)
    }

    private fun launch(tx: Float, ty: Float) {
        val startX = tx + (Random.nextFloat() - 0.5f) * width * 0.2f
        rockets.add(Rocket(startX, height.toFloat(), tx, ty, Random.nextFloat() * 360f))
    }

    private fun burst(x: Float, y: Float, hue: Float) {
        val n = 70
        val speed = 260f * density
        for (k in 0 until n) {
            val a = (k.toFloat() / n) * 2f * Math.PI.toFloat() + Random.nextFloat() * 0.1f
            val s = speed * (0.45f + Random.nextFloat() * 0.65f)
            hsv[0] = (hue + Random.nextFloat() * 50f - 25f + 360f) % 360f
            hsv[1] = 0.75f + Random.nextFloat() * 0.25f
            hsv[2] = 1f
            val life = 1.1f + Random.nextFloat() * 0.8f
            sparks.add(Spark(x, y, cos(a) * s, sin(a) * s, Color.HSVToColor(hsv), life, life))
        }
    }

    private fun update(dt: Float) {
        val rocketSpeed = 900f * density
        val it = rockets.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val dx = r.tx - r.x
            val dy = r.ty - r.y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val stepLen = rocketSpeed * dt
            if (dist <= stepLen) {
                burst(r.tx, r.ty, r.hue)
                it.remove()
            } else {
                r.x += dx / dist * stepLen
                r.y += dy / dist * stepLen
                // trail sparks
                sparks.add(Spark(r.x, r.y, (Random.nextFloat() - 0.5f) * 40f, 60f * density, Color.rgb(255, 210, 140), 0.35f, 0.35f))
            }
        }

        val gravity = 220f * density
        val drag = 0.985f
        val si = sparks.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.life -= dt
            if (s.life <= 0f) { si.remove(); continue }
            s.vx *= drag
            s.vy = s.vy * drag + gravity * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else min((now - lastFrameNanos) / 1e9f, 0.05f)
        lastFrameNanos = now
        val t = (now - startTime) / 1_000_000_000f
        update(dt)

        canvas.drawColor(Color.rgb(12, 8, 22))

        framePaint.strokeWidth = 16f * density
        canvas.drawRoundRect(frame, 14f * density, 14f * density, framePaint)

        val step = (t * 10f).toInt()
        val flashAll = (t % 4f) > 3.4f && ((t * 12f).toInt() % 2 == 0)
        val bulbR = 7f * density
        val glowR = bulbR * 3.5f

        bulbs.forEachIndexed { i, p ->
            val on = flashAll || (i + step) % 3 == 0
            hsv[0] = (i * 360f / bulbs.size + t * 90f) % 360f
            hsv[1] = if (on) 0.55f else 0.9f
            hsv[2] = if (on) 1f else 0.25f
            val color = Color.HSVToColor(hsv)
            if (on) {
                glowPaint.shader = RadialGradient(
                    p.x, p.y, glowR,
                    intArrayOf(Color.argb(190, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(p.x, p.y, glowR, glowPaint)
            }
            bulbPaint.color = color
            canvas.drawCircle(p.x, p.y, bulbR, bulbPaint)
        }

        // Greeting text
        textPaint.textSize = baseTextSize * (1f + 0.04f * sin(t * 4f))
        hsv[0] = (t * 60f) % 360f; hsv[1] = 0.8f; hsv[2] = 1f
        textPaint.setShadowLayer(18f * density, 0f, 0f, Color.HSVToColor(hsv))
        val y = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, width / 2f, y, textPaint)

        if (!tapped) {
            canvas.drawText("Tap anywhere for fireworks", width / 2f, frame.bottom - 28f * density, hintPaint)
        }

        // Rockets
        sparkPaint.color = Color.WHITE
        for (r in rockets) canvas.drawCircle(r.x, r.y, 3f * density, sparkPaint)

        // Sparks
        for (s in sparks) {
            val a = (s.life / s.maxLife).coerceIn(0f, 1f)
            sparkPaint.color = s.color
            sparkPaint.alpha = (a * 255).toInt()
            canvas.drawCircle(s.x, s.y, 2.6f * density * (0.5f + a * 0.5f), sparkPaint)
        }

        postInvalidateOnAnimation()
    }
}
