import android.graphics.*
import com.pranvir.tether.*
import java.awt.image.BufferedImage
object Dbg {
    @JvmStatic fun main(a: Array<String>) {
        val img = BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB)
        val c = Canvas(img)
        val sh = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0xFF00FFFF.toInt(), 0xFFFF00FF.toInt()), null, Shader.TileMode.CLAMP)
        Draw.shadeRound(c, sh, RectF(0f, 0f, 400f, 100f), 20f, 0f, 0f, 400f, 1f)
        val sh2 = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0xFF00FFFF.toInt(), 0xFFFF00FF.toInt()), null, Shader.TileMode.CLAMP)
        Draw.shadeRect(c, sh2, 0f, 100f, 400f, 200f, 0f, 100f, 1f, 100f)
        for (x in intArrayOf(5, 200, 395)) println("x=$x top=" + Integer.toHexString(img.getRGB(x, 50)))
        for (y in intArrayOf(105, 150, 195)) println("y=$y " + Integer.toHexString(img.getRGB(200, y)))
    }
}
