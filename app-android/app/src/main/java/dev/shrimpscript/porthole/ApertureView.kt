package dev.shrimpscript.porthole

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.max

/**
 * The launch, second half: the porthole opens. Drawn over the app at the moment the
 * system splash is removed, starting as an exact copy of it - the ground and the ring
 * where the splash left them - so nothing jumps. Then the ring becomes the edge of a
 * hole in the ground that grows until it has left the screen, and the app is seen
 * through it. The stroke thins and fades as the ring passes the edges.
 *
 * A SurfaceView with its own drawing thread, on purpose: the first second of a launch
 * is when the app is busiest (the first list, the first connection), and an animation
 * on the UI thread arrives as a series of cuts. The compositor shows this one at its
 * own pace whatever the app is doing.
 */
class ApertureView(
    context: Context,
    private val cx: Float,
    private val cy: Float,
    private val startRadius: Float,
    private val strokeWidth: Float,
    private val ground: Int,
    /** The ring as the splash drew it: the brand teal. */
    private val accentFrom: Int,
    /** The theme's accent: the ring takes it on as it opens. */
    private val accentTo: Int,
    /** The wordmark as the splash placed it, a software bitmap so this thread may draw it. */
    private val brand: android.graphics.Bitmap?,
    private val brandBounds: android.graphics.Rect?,
) : SurfaceView(context), SurfaceHolder.Callback {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accentFrom
    }
    private val brandPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val curve = PathInterpolator(0.4f, 0f, 0.2f, 1f) // PortholeMotion.standard: an iris gathers speed, then eases
    private val hole = Path()
    @Volatile private var openAt = 0L
    @Volatile private var durationMs = 640L
    @Volatile private var onEnd: (() -> Unit)? = null
    @Volatile private var onReady: (() -> Unit)? = null
    private var thread: Thread? = null

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    /** [block] runs on the UI thread once the first frame - the copy of the splash - is on screen. */
    fun ready(block: () -> Unit) { onReady = block }

    /** Starts the opening; [end] runs on the UI thread when the view can be removed. */
    fun open(ms: Long, end: () -> Unit) {
        durationMs = ms
        onEnd = end
        openAt = SystemClock.uptimeMillis()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val at = openAt
        draw(holder, if (at == 0L) 0f else curve.getInterpolation(((SystemClock.uptimeMillis() - at).toFloat() / durationMs).coerceIn(0f, 1f)))
        post { onReady?.invoke(); onReady = null }
        thread = Thread({
            var done = false
            while (!done && !Thread.currentThread().isInterrupted) {
                val at = openAt
                if (at == 0L) { try { Thread.sleep(8) } catch (_: InterruptedException) { return@Thread }; continue }
                val t = ((SystemClock.uptimeMillis() - at).toFloat() / durationMs).coerceIn(0f, 1f)
                draw(holder, curve.getInterpolation(t))
                done = t >= 1f
            }
            if (done) post { onEnd?.invoke(); onEnd = null }
        }, "aperture").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        thread?.interrupt()
        thread = null
    }

    private fun draw(holder: SurfaceHolder, progress: Float) {
        val canvas = try { holder.lockHardwareCanvas() ?: holder.lockCanvas() } catch (_: Throwable) { null } ?: return
        try { paint(canvas, progress) } finally { try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {} }
    }

    private fun paint(canvas: Canvas, progress: Float) {
        val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // Far enough that the whole screen is inside the hole at the end.
        val reach = max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy))) + strokeWidth
        val r = startRadius + (reach - startRadius) * progress
        hole.rewind()
        hole.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipOutPath(hole)
        canvas.drawColor(ground)
        val mark = brand; val markBounds = brandBounds
        if (mark != null && markBounds != null) {
            brandPaint.alpha = (255 * (1f - (progress / 0.4f).coerceIn(0f, 1f))).toInt()
            // Never let the wordmark take the launch down: a failed draw is just no wordmark.
            if (brandPaint.alpha > 0) runCatching { canvas.drawBitmap(mark, null, markBounds, brandPaint) }
        }
        canvas.restore()
        val fade = ((1f - progress) / 0.45f).coerceIn(0f, 1f)
        stroke.strokeWidth = strokeWidth * (0.6f + 0.4f * fade)
        // Teal to the theme's own accent over the first half: the brand's ring becomes the app's.
        val mix = (progress / 0.5f).coerceIn(0f, 1f)
        stroke.color = android.animation.ArgbEvaluator().evaluate(mix, accentFrom, accentTo) as Int
        stroke.alpha = (255 * fade).toInt()
        if (stroke.alpha > 0) canvas.drawCircle(cx, cy, r, stroke)
    }

    companion object {
        fun groundOf(drawable: android.graphics.drawable.Drawable?, fallback: Int = Color.BLACK): Int =
            (drawable as? android.graphics.drawable.ColorDrawable)?.color ?: fallback
    }
}
