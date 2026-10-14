package android.graphics

class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
}

class Paint(flags: Int = 0) {
    enum class Style { STROKE, FILL }
    var style = Style.FILL
    var color = 0
    var strokeWidth = 0f
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}

class Canvas {
    var left = 0f; private set
    var top = 0f; private set
    private val stack = mutableListOf<Pair<Float, Float>>()
    fun save() { stack += left to top }
    fun restore() { val at = stack.removeAt(stack.lastIndex); left = at.first; top = at.second }
    fun translate(x: Float, y: Float) { left += x; top += y }
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, paint: Paint) {}
    fun drawCircle(x: Float, y: Float, radius: Float, paint: Paint) {}
}

object Color {
    fun colorToHSV(color: Int, hsv: FloatArray): Unit = error("Colour rendering needs Android")
    fun HSVToColor(hsv: FloatArray): Int = error("Colour rendering needs Android")
}
