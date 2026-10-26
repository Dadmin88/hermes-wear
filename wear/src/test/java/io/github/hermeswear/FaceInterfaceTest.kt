package io.github.hermeswear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context
import android.speech.RecognizerIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w240dp-h240dp-round-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceInterfaceTest {
    @Test fun tappingFaceDoesNotLaunchExternalSpeechActivityOrSubmitATurn() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("hermes_watch",Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("speak",false).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        val face=MainActivity::class.java.getDeclaredField("face").apply { isAccessible=true }.get(activity) as HermesFaceView
        face.performClick()
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",shadowOf(activity).nextStartedActivityForResult.intent.action)
        assertFalse(prefs.contains("pending_turn"))
        controller.destroy()
    }
    @Test fun leftSwipeOpensExistingSettingsWithoutStartingSpeech() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        val face=MainActivity::class.java.getDeclaredField("face").apply {isAccessible=true}.get(activity) as HermesFaceView
        face.layout(0,0,480,480)
        val now=android.os.SystemClock.uptimeMillis()
        for((delta,action,x) in listOf(Triple(0L,0,410f),Triple(40L,2,240f),Triple(100L,1,60f))) {
            val event=android.view.MotionEvent.obtain(now,now+delta,action,x,240f,0)
            face.onTouchEvent(event);event.recycle()
        }
        val dialog=MainActivity::class.java.getDeclaredField("controls").apply {isAccessible=true}.get(activity) as android.app.Dialog
        assertTrue(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        fun labels(view:android.view.View):List<String> = when(view) {
            is android.widget.Button -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap {labels(view.getChildAt(it))}
            else -> emptyList()
        }
        val buttons=labels(dialog.window!!.decorView)
        for(label in listOf("Choose voice","Test speaker","Read last reply","Choose Nous voice","Preview Nous voice","Retry last request","Saved conversations and tasks","Cancel current task","Enable task notifications","Connection status","New chat")) {
            assertTrue("Missing control: $label",buttons.any {it.contains(label)})
        }
        val view=dialog.window!!.decorView
        val exact=android.view.View.MeasureSpec.makeMeasureSpec(480,android.view.View.MeasureSpec.EXACTLY)
        view.measure(exact,exact);view.layout(0,0,480,480)
        val screenshot=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(screenshot))
        File("build/reports/face-preview").mkdirs()
        File("build/reports/face-preview/settings.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
        screenshot.recycle();dialog.dismiss();controller.destroy()
    }
    @Test fun edgeControlsUseCurvedTargetsAndLeaveCentreForSpeech() {
        val context=RuntimeEnvironment.getApplication()
        val reply=EdgeActionButton(context,"Reply",true)
        reply.layout(0,0,240,172)
        assertEquals("Reply",reply.contentDescription)
        assertTrue(reply.containsArc(120f,100f))
        assertFalse(reply.containsArc(230f,10f))
        val settings=EdgeActionButton(context,"Settings",false)
        settings.layout(0,0,240,172)
        assertTrue(settings.containsArc(120f,100f))
        assertFalse(settings.containsArc(10f,10f))
    }
    @Test fun softwarePreviewDoesNotDisableCompiledShader() {
        val face=HermesFaceView(RuntimeEnvironment.getApplication())
        assertTrue(face.shaderAvailable)
        face.layout(0,0,480,480)
        val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        face.draw(Canvas(bitmap))
        assertTrue("Software screenshot must not disable the GPU artwork",face.shaderAvailable)
        bitmap.recycle()
    }
    @Test @Config(sdk=[30]) fun olderWatchKeepsUsableFallback() {
        val face=HermesFaceView(RuntimeEnvironment.getApplication())
        assertFalse(face.shaderAvailable)
        face.layout(0,0,480,480)
        val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        face.draw(Canvas(bitmap))
        assertNotEquals(bitmap.getPixel(240,210),bitmap.getPixel(0,0))
        bitmap.recycle()
    }
    @Test fun homeRendersWithAccessibleEntryPoints() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("hermes_watch",Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("speak",false).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val root=controller.get().findViewById<android.view.View>(android.R.id.content)
        val exact=android.view.View.MeasureSpec.makeMeasureSpec(480,android.view.View.MeasureSpec.EXACTLY)
        root.measure(exact,exact);root.layout(0,0,480,480)
        val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.clipPath(android.graphics.Path().apply {addCircle(240f,240f,240f,android.graphics.Path.Direction.CW)})
        root.draw(canvas)
        val directory=File("build/reports/face-preview").apply {mkdirs()}
        File(directory,"home.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val face=MainActivity::class.java.getDeclaredField("face").apply {isAccessible=true}.get(controller.get()) as HermesFaceView
        face.visibility=android.view.View.INVISIBLE
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        root.draw(canvas);face.drawInterfaceLabels(canvas)
        File(directory,"chrome.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle();controller.destroy()
    }
    @Test fun realStatusRemainsAccessibleAndRendersAtRoundWatchSize() {
        val context=RuntimeEnvironment.getApplication()
        val face=HermesFaceView(context)
        face.layout(0,0,480,480)
        assertTrue("The shader must compile on the target API",face.shaderAvailable)
        val directory=File("build/reports/face-preview").apply {mkdirs()}
        for((name,status) in listOf("ready" to "Ready to talk", "thinking" to "Hermes is working…", "speaking" to "Speaking…", "error" to "Phone disconnected. Reconnect, then Retry.")) {
            face.status=status
            face.speechActive=status.startsWith("Speaking")
            face.reply="Your next idea starts here."
            assertTrue(face.contentDescription.contains(status))
            val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
            face.draw(Canvas(bitmap))
            File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
