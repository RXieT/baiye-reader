package io.github.rxiet.baiye.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A captured *whole* reading page, including the native running head and footer.
 * The live reader underneath is already on the destination before reveal starts.
 */
class PageCurlView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    var paperColor: Int = Color.rgb(246, 241, 231)
    private var capturedPaperColor = paperColor
    val isBusy: Boolean get() = busy
    private val columns = 64
    private val rows = 8
    private val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
    private val colors = IntArray((columns + 1) * (rows + 1))
    private val silhouette = Path()
    var onActiveChanged: ((Boolean) -> Unit)? = null
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var direction = 1
    private var mode = "curl"
    private var fraction = 0f
    private var token = 0
    private var busy = false
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { clear() }

    init { setWillNotDraw(false); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** PixelCopy is asynchronous, unlike software WebView.draw which can miss its surface. */
    fun capture(window: Window, area: Rect, direction: Int, mode: String, captured: () -> Unit) {
        if (busy) return
        if (area.width() <= 0 || area.height() <= 0) { captured(); return }
        busy = true
        onActiveChanged?.invoke(true)
        started = false
        this.direction = if (direction < 0) -1 else 1
        this.mode = mode
        fraction = 0f
        val generation = ++token
        handler.postDelayed(watchdog, 2200)
        val image = try { Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888) }
            catch (_: OutOfMemoryError) { clear(); captured(); return }
        // onActiveChanged removes the menu subtree (not its animated exit).
        // Wait for that Compose frame to be submitted before PixelCopy reads it.
        // Two vsyncs avoid capturing the previous window buffer, not a 110ms exit.
        capturedPaperColor = paperColor
        postOnAnimation { postOnAnimation captureFrame@{
            if (token != generation) { image.recycle(); return@captureFrame }
            try {
                PixelCopy.request(window, area, image, { result ->
                    if (token != generation) { image.recycle(); return@request }
                    if (result == PixelCopy.SUCCESS) {
                        bitmap = image
                        invalidate()
                        // Render the opaque old page before asking JS to relocate.
                        postOnAnimation { if (token == generation) captured() }
                    } else {
                        image.recycle()
                        clear()
                        captured()
                    }
                }, handler)
            } catch (_: Exception) {
                image.recycle()
                clear()
                captured()
            }
        } }
    }

    fun turnEnd() {
        if (!busy || started || bitmap == null) return
        started = true
        // Relocate/turnEnd can precede the WebView compositor's new frame.
        postOnAnimation { postOnAnimation {
            if (!busy || bitmap == null) return@postOnAnimation
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (mode == "slide") 260L else 460L
                interpolator = DecelerateInterpolator(1.15f)
                addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { clear() }
                })
                start()
            }
        } }
    }

    fun clear() {
        ++token
        handler.removeCallbacks(watchdog)
        val oldAnimator = animator
        animator = null
        oldAnimator?.removeAllListeners()
        oldAnimator?.cancel()
        bitmap?.recycle()
        bitmap = null
        busy = false
        onActiveChanged?.invoke(false)
        started = false
        invalidate()
    }

    private fun tone(color: Int, multiplier: Float): Int = Color.rgb(
        (Color.red(color) * multiplier).toInt().coerceIn(0, 255),
        (Color.green(color) * multiplier).toInt().coerceIn(0, 255),
        (Color.blue(color) * multiplier).toInt().coerceIn(0, 255),
    )

    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        if (image.isRecycled) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        // Always construct the forward curl, reflecting both geometry and texture
        // for a backward turn. Both directions peel from their outside edge.
        if (direction < 0) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
        if (mode == "slide") {
            canvas.translate(-w * fraction, 0f)
            if (direction < 0) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
            canvas.drawBitmap(image, null, Rect(0, 0, width, height), paint)
            canvas.restoreToCount(save)
            return
        }
        if (fraction == 0f) {
            if (direction < 0) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
            canvas.drawBitmap(image, null, Rect(0, 0, width, height), paint)
            canvas.restoreToCount(save)
            return
        }
        val radius = w * .115f * sin(PI * fraction).toFloat().coerceAtLeast(.025f)
        val arc = PI.toFloat() * radius
        val fold = w - (w + arc) * fraction
        val peak = fold + radius
        // Narrow contact shadow and broad raised-page shadow, not a rotated rectangle.
        shadow.shader = LinearGradient(fold - radius * .4f, 0f, peak + radius * 1.6f, 0f,
            intArrayOf(Color.TRANSPARENT, 0x55000000, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(fold - radius * .4f, 0f, peak + radius * 1.6f, h, shadow)
        shadow.shader = null
        for (row in 0..rows) {
            val y = h * row / rows
            for (column in 0..columns) {
                val textureX = w * column / columns
                val sourceX = if (direction < 0) w - textureX else textureX
                val distance = sourceX - fold
                val theta = (distance / radius).coerceIn(0f, PI.toFloat())
                val x = when {
                    distance <= 0f -> sourceX
                    distance < arc -> fold + radius * sin(theta)
                    else -> fold - (distance - arc)
                }
                val elevation = if (distance <= 0f) 0f else radius * (1f - cos(theta))
                val index = row * (columns + 1) + column
                // Slight perspective fan from the cylinder's elevation, anchored at mid-page.
                vertices[index * 2] = if (direction < 0) w - x else x
                vertices[index * 2 + 1] = y + (y - h * .5f) * elevation / (w * 5f)
                val shade = if (distance <= 0f) 255 else (238 - 65 * sin(theta)).toInt().coerceIn(0, 255)
                colors[index] = Color.rgb(shade, shade, shade)
            }
        }
        // Mesh texture remains readable (not mirrored) on the stationary front.
        if (direction < 0) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
        canvas.drawBitmapMesh(image, columns, rows, vertices, 0, colors, 0, paint)
        // Opaque paper back follows the returning half of the cylinder and hides
        // mirrored front text. Its edge is curved, not a whole-page rotation.
        if (direction < 0) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
        val tip = fold - (w - fold - arc).coerceAtLeast(0f)
        val edge = fold + radius
        val lift = radius * .2f
        silhouette.reset()
        silhouette.moveTo(tip, -lift)
        silhouette.lineTo(edge, -lift * .5f)
        silhouette.quadTo(edge + radius * .12f, h * .5f, edge, h + lift * .5f)
        silhouette.lineTo(tip, h + lift)
        silhouette.close()
        back.shader = LinearGradient(tip.coerceAtMost(edge - 1f), 0f, edge, 0f,
            intArrayOf(tone(capturedPaperColor, .97f), tone(capturedPaperColor, .85f), tone(capturedPaperColor, 1.05f)),
            floatArrayOf(0f, .75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(silhouette, back)
        back.shader = null
        canvas.restoreToCount(save)
    }
}
