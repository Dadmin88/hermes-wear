package io.github.hermeswear

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.widget.Button
import kotlin.math.*

/** A real accessible button, painted along the bezel with an annular touch target. */
class EdgeActionButton(context: Context, label: String, private val left: Boolean) : Button(context) {
    private val skin=ThemeTokens(context)
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val curve=Path()
    init {
        text=label
        contentDescription=label
        background=null
        setPadding(0,0,0,0)
        isAllCaps=false
        minimumWidth=0; minimumHeight=0
    }
    private val diameter get()=width*2f
    private val centreX get()=if(left) width.toFloat() else 0f
    private val centreY get()=height-diameter/2
    private val start get()=if(left) 118f else 20f
    private val sweep get()=42f
    internal fun containsArc(x:Float,y:Float):Boolean {
        val dx=x-centreX;val dy=y-centreY
        val radius=hypot(dx,dy)/diameter
        val angle=atan2(dy,dx)*180f/PI.toFloat()
        return radius in .34f.. .495f && angle in (start-5)..(start+sweep+5)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN && !containsArc(event.x,event.y)) return false
        return super.onTouchEvent(event)
    }
    override fun onDraw(canvas:Canvas) {
        val d=diameter
        if(d<=0) return
        val r=d*.425f
        val oval=RectF(centreX-r,centreY-r,centreX+r,centreY+r)
        ink.style=Paint.Style.STROKE;ink.strokeCap=Paint.Cap.ROUND;ink.strokeWidth=d*.096f
        ink.color=if(isPressed || isFocused) skin.pressed else skin.surfaceRaised
        canvas.drawArc(oval,start,sweep,false,ink)
        ink.strokeWidth=d*.002f;ink.color=skin.outline
        canvas.drawArc(oval,start,sweep,false,ink)
        // Reverse the lower arc so the letters stay upright as they follow the rim.
        curve.reset();curve.addArc(oval,start+sweep,-sweep)
        ink.style=Paint.Style.FILL;ink.typeface=skin.typeface
        ink.textSize=d*.042f;ink.textAlign=Paint.Align.LEFT;ink.color=skin.accent
        val length=r*sweep*PI.toFloat()/180f
        canvas.drawTextOnPath(text.toString(),curve,(length-ink.measureText(text.toString()))/2,
            -(ink.ascent()+ink.descent())/2,ink)
    }
}
