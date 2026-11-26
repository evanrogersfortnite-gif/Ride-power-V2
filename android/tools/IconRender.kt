import com.interop.ridepowerble.*
import java.awt.*
import java.awt.geom.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File
fun main() {
    val names = IconPaths.P.keys.toList()
    val cols = 8; val cell = 96; val rows = (names.size + cols - 1) / cols
    val img = BufferedImage(cols * cell, rows * (cell + 18), BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
    names.forEachIndexed { k, n ->
        val p = Path2D.Float(Path2D.WIND_NON_ZERO)
        SvgPath.parse(IconPaths.P[n]!!, object : SvgPath.Sink {
            override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.curveTo(x1, y1, x2, y2, x, y)
            override fun close() = p.closePath()
        })
        val ox = (k % cols) * cell; val oy = (k / cols) * (cell + 18)
        val t = AffineTransform(); t.translate(ox + 12.0, oy + 6.0); t.scale(3.0, 3.0)
        g.color = Color(0x1A73E8); g.fill(t.createTransformedShape(p))
        g.color = Color.DARK_GRAY; g.font = Font("SansSerif", Font.PLAIN, 11); g.drawString(n, ox + 4, oy + cell + 10)
    }
    ImageIO.write(img, "png", File(System.getProperty("out")))
    println("rendered ${names.size}")
}
