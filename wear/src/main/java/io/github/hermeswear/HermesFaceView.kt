package io.github.hermeswear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import kotlin.math.*

/** Decorative expression, never a microphone meter or proof of audible playback. */
class HermesFaceView(context: Context) : View(context), android.hardware.SensorEventListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var artwork:HermesShader?=if(android.os.Build.VERSION.SDK_INT>=33) runCatching {HermesShader(context)}.onFailure {android.util.Log.w("HermesArtwork","Shader compilation failed; using compatibility artwork",it)}.getOrNull() else null
    internal val shaderAvailable get()=artwork!=null
    private val expression=FloatArray(4)
    private val samples=FloatArray(24)
    private var lastSample=0L
    var wakeReady=false
    var audioLevel=0f
    var transcript=""
        set(value) {field=value;invalidate()}

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val sensors=context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private var tracking=false
    private var tiltX=0f
    private var tiltY=0f
    private var touchedAt=0L
    private var touchX=0f
    private var touchY=0f
    private fun updateMotion() {
        val active=isAttachedToWindow && hasWindowFocus() && isShown && !power.isPowerSaveMode && ValueAnimator.areAnimatorsEnabled()
        if(active && !tracking) {
            val sensor=sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
                ?: sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            tracking=sensor!=null && sensors.registerListener(this,sensor,android.hardware.SensorManager.SENSOR_DELAY_UI)
        } else if(!active && tracking) { sensors.unregisterListener(this);tracking=false }
    }
    override fun onSensorChanged(event:android.hardware.SensorEvent) {
        // Rotate into display coordinates, then smooth and clamp: decorative gaze only.
        val rotation=display?.rotation ?: android.view.Surface.ROTATION_0
        val x=event.values[0];val y=event.values[1]
        val (screenX,screenY)=when(rotation) {
            android.view.Surface.ROTATION_90 -> -y to x
            android.view.Surface.ROTATION_180 -> -x to -y
            android.view.Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
        tiltX=tiltX*.88f+(-screenX/7f).coerceIn(-1f,1f)*.12f
        tiltY=tiltY*.88f+(screenY/7f).coerceIn(-1f,1f)*.12f
    }
    override fun onAccuracyChanged(sensor:android.hardware.Sensor?,accuracy:Int) {}
    override fun onAttachedToWindow() {super.onAttachedToWindow();updateMotion()}
    override fun onDetachedFromWindow() {sensors.unregisterListener(this);tracking=false;super.onDetachedFromWindow()}
    var onSettingsRequested: (() -> Unit)? = null
    private val gestures=android.view.GestureDetector(context,object:android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onFling(first:android.view.MotionEvent?, last:android.view.MotionEvent, velocityX:Float, velocityY:Float):Boolean {
            if(first==null) return false
            val dx=last.x-first.x;val dy=last.y-first.y
            if(dx < -width*.25f && abs(dx)>abs(dy)*1.5f && velocityX < -200*resources.displayMetrics.density) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onSettingsRequested?.invoke();return true
            }
            return false
        }
    })
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean {
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {
            touchedAt=SystemClock.elapsedRealtime();touchX=event.x;touchY=event.y;invalidate()
        }
        if(gestures.onTouchEvent(event)) {
            val cancel=android.view.MotionEvent.obtain(event).apply {action=android.view.MotionEvent.ACTION_CANCEL}
            super.onTouchEvent(cancel);cancel.recycle();return true
        }
        return super.onTouchEvent(event)
    }
    var status: String = "Ready to talk"
        set(value) { field=value; contentDescription="Hermes. $value. Tap to speak. Hold or swipe left for settings."; invalidate() }
    var reply: String = ""
        set(value) { field=value; invalidate() }
    init {
        isClickable=true; isLongClickable=true; isFocusable=true
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        status="Ready to talk"
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); updateMotion();invalidate() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility);updateMotion(); if(visibility==VISIBLE) invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat(); val h=height.toFloat(); val s=min(w,h)
        if(s<=0) return
        updateMotion()
        val animated=hasWindowFocus() && isShown && !power.isPowerSaveMode && ValueAnimator.areAnimatorsEnabled()
        val t=if(animated) SystemClock.elapsedRealtime()/1000f else 0f
        val lower=status.lowercase()
        val listening=lower.startsWith("listening")
        val speaking=lower.startsWith("speaking") || lower.startsWith("playing")
        val busy=listOf("sending","connecting","accepted","starting","working","queued","recovering","reconnecting").any { lower.contains(it) }
        val attention=listOf("failed","error","unavailable","disconnected","timed out","exceeded","approval","busy").any { lower.contains(it) }
        canvas.drawColor(Color.rgb(12,4,28))
        val reaction=if(animated) (1f-(SystemClock.elapsedRealtime()-touchedAt)/1100f).coerceIn(0f,1f) else 0f
        val target=floatArrayOf(if(listening)1f else 0f,if(busy)1f else 0f,if(speaking)1f else 0f,if(attention)1f else 0f)
        for(i in 0..3) expression[i]+= (target[i]-expression[i])*(if(animated) .18f else 1f)
        val now=SystemClock.elapsedRealtime()
        if(now-lastSample>=50) {
            System.arraycopy(samples,1,samples,0,23)
            samples[23]=if(listening || speaking) audioLevel.coerceIn(0f,1f) else 0f
            lastSample=now
        }
        val gpuDrawn=if(canvas.isHardwareAccelerated) artwork?.let { renderer ->
            runCatching {renderer.draw(canvas,w,h,t,tiltX,tiltY,expression,touchX,touchY,reaction,samples);true}
                .getOrElse {android.util.Log.w("HermesArtwork","Shader draw failed",it);artwork=null;false}
        } ?: false else false
        if(!gpuDrawn) drawRetroFallback(canvas,w,h,s,t,speaking,busy)
        drawInterfaceLabels(canvas)
        if(animated) postInvalidateDelayed(if(busy || speaking || listening || reaction>0f) 40L else 80L)
    }
    private fun drawRetroFallback(canvas:Canvas,w:Float,h:Float,s:Float,t:Float,speaking:Boolean,busy:Boolean) {
        val cx=w*.5f+tiltX*s*.013f;val cy=h*.43f+tiltY*s*.009f
        paint.shader=null;paint.style=Paint.Style.FILL;paint.color=Color.rgb(12,4,28)
        canvas.drawRect(0f,0f,w,h,paint)
        paint.color=Color.rgb(41,12,86)
        canvas.drawRoundRect(cx-s*.30f,cy-s*.27f,cx+s*.30f,cy+s*.27f,s*.03f,s*.03f,paint)
        paint.color=Color.rgb(186,255,41);paint.strokeWidth=s*.005f;paint.style=Paint.Style.STROKE
        val path=Path()
        for(i in 0..95) {
            val x=(i/95f-.5f)*.49f
            val k=(i/95f*23).toInt().coerceIn(0,23)
            val y=cy+samples[k]*s*.15f*sin(x*86f-t*5f)
            if(i==0) path.moveTo(cx+x*s,y) else path.lineTo(cx+x*s,y)
        }
        canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
    }
    internal fun drawInterfaceLabels(canvas:Canvas) {
        val w=width.toFloat();val h=height.toFloat();val s=min(w,h);val cx=w/2
        val lower=status.lowercase()
        val speaking=lower.startsWith("speaking") || lower.startsWith("playing")
        val busy=listOf("sending","connecting","accepted","starting","working","queued","recovering","reconnecting").any {lower.contains(it)}
        val attention=listOf("failed","error","unavailable","disconnected","timed out","exceeded","approval","busy").any {lower.contains(it)}
        val color=Color.rgb(255,190,120)
        paint.alpha=255
        paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        paint.textSize=s*.031f
        paint.color=Color.rgb(175,151,216)
        canvas.drawText("H E R M E S",cx,h*.105f,paint)
        paint.color=if(attention)color else Color.rgb(226,244,202);paint.textSize=s*.039f
        val caption=when { attention -> "Needs attention"; speaking -> "Speaking"; busy -> "Thinking"; lower.startsWith("listening") -> "Listening"; lower.startsWith("transcribing") -> "Transcribing"; else -> "Tap to speak" }
        canvas.drawText(caption,cx,h*.805f,paint)
        paint.color=Color.rgb(177,157,208);paint.textSize=s*.027f
        val detail=if(transcript.isNotBlank())transcript else if(lower.startsWith("listening")) "Tap to finish" else if(wakeReady) "Say Hey Hermes" else if(attention) "Open settings" else if(reply.isNotBlank() && !reply.startsWith("You:")) "Reply available" else "Hold for settings"
        canvas.drawText(android.text.TextUtils.ellipsize(detail,android.text.TextPaint(paint),s*.36f,android.text.TextUtils.TruncateAt.END).toString(),cx,h*.875f,paint)
    }

}
