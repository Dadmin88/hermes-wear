package io.github.hermeswear

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader

/** One compiled per-pixel AGSL material; app controls and speech remain native. */
@TargetApi(33)
internal class HermesShader(context:Context) {
    private val shader=RuntimeShader(context.resources.openRawResource(R.raw.hermes_face).bufferedReader().use {it.readText()})
    private val skin=ThemeTokens(context)
    private val paint=Paint().apply {this.shader=this@HermesShader.shader}
    init {
        shader.setFloatUniform("themeAccent",skin.rgb(skin.accent))
        shader.setFloatUniform("themePanel",skin.rgb(skin.artworkPanel))
        shader.setFloatUniform("themeBackground",skin.rgb(skin.artworkBackground))
        shader.setFloatUniform("themeWarning",skin.rgb(skin.warning))
    }
    fun draw(canvas:Canvas,w:Float,h:Float,time:Float,x:Float,y:Float,mood:FloatArray,touchX:Float,touchY:Float,reaction:Float,samples:FloatArray) {
        shader.setFloatUniform("resolution",w,h)
        shader.setFloatUniform("time",time)
        shader.setFloatUniform("gaze",x,y)
        shader.setFloatUniform("mood",mood)
        shader.setFloatUniform("contact",touchX,touchY,reaction)

        shader.setFloatUniform("samples",samples)
        canvas.drawRect(0f,0f,w,h,paint)
    }
}
