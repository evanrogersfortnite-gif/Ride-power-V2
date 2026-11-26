package com.interop.ridepowerble

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Draws [IconPaths] Material icons as crisp vector Drawables / Bitmaps in any colour and size. */
object AppIcons {
    private val cache = HashMap<String, Path>()

    fun path(name: String): Path = cache.getOrPut(name) {
        val p = Path()
        SvgPath.parse(IconPaths.P[name] ?: IconPaths.P.getValue("place"), object : SvgPath.Sink {
            override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
            override fun close() = p.close()
        })
        p
    }

    /** A Drawable of the icon, [sizeDp] square, in [color]. */
    fun drawable(ctx: Context, name: String, color: Int, sizeDp: Int = 20): Drawable {
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
        return IconDrawable(path(name), color).apply { setBounds(0, 0, px, px) }
    }

    /** The icon drawn into a bitmap of [sizePx], optionally inset by [padPx]. */
    fun bitmap(name: String, sizePx: Int, color: Int, padPx: Int = 0): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), name, color, padPx.toFloat(), padPx.toFloat(), (sizePx - 2 * padPx).toFloat())
        return b
    }

    /** Draws the icon at (x, y) with the given size onto a canvas. */
    fun draw(c: Canvas, name: String, color: Int, x: Float, y: Float, size: Float) {
        c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f)
        c.drawPath(path(name), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
        c.restore()
    }

    private class IconDrawable(private val p: Path, private var color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width() / 24f, b.height() / 24f)
            canvas.drawPath(p, paint); canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = bounds.width()
        override fun getIntrinsicHeight() = bounds.height()
    }
}
