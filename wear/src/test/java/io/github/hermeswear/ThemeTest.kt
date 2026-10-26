package io.github.hermeswear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w240dp-h240dp-round-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {context.getSharedPreferences("hermes_appearance",0).edit().clear().commit()}
    @After fun cleanup() {reset()}
    @Test fun defaultAndUnknownSavedThemeResolveSafely() {
        assertEquals("royal",ThemeStore.selected(context).id)
        context.getSharedPreferences("hermes_appearance",0).edit().putString("theme","removed-skin").commit()
        assertEquals("royal",ThemeStore.selected(context).id)
    }
    @Test fun selectionPersistsWithoutChangingConversationOrVoicePreferences() {
        val prefs=context.getSharedPreferences("hermes_watch",Context.MODE_PRIVATE)
        prefs.edit().putString("session","test-session").putString("pending_turn","test-request").putBoolean("speak",false).commit()
        val before=prefs.all.toMap()
        ThemeStore.select(context,"amber")
        assertEquals("amber",ThemeStore.selected(context).id)
        assertEquals(before,prefs.all)
        try {ThemeStore.select(context,"invalid");fail("Invalid theme must not be saved")} catch(_:IllegalArgumentException) {}
        assertEquals("amber",ThemeStore.selected(context).id)
        prefs.edit().clear().commit()
    }
    private fun luminance(c:Int):Double {
        fun linear(v:Int):Double {val x=v/255.0;return if(x<=0.04045)x/12.92 else Math.pow((x+0.055)/1.055,2.4)}
        return .2126*linear(Color.red(c))+.7152*linear(Color.green(c))+.0722*linear(Color.blue(c))
    }
    private fun contrast(a:Int,b:Int):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
    @Test fun everySkinHasReadableTextAndCompleteNativeColorTokens() {
        for(choice in ThemeStore.choices(context)) {
            ThemeStore.select(context,choice.id);val skin=ThemeTokens(context)
            assertTrue(choice.name,contrast(skin.textPrimary,skin.background)>=4.5)
            assertTrue(choice.name,contrast(skin.textSecondary,skin.background)>=4.5)
            assertTrue(choice.name,contrast(skin.onAccent,skin.accent)>=4.5)
            assertTrue(choice.name,contrast(skin.textPrimary,skin.surfaceRaised)>=4.5)
            val native=android.view.ContextThemeWrapper(context,choice.style)
            val a=native.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent,android.R.attr.textColorPrimary))
            assertEquals(skin.accent,a.getColor(0,0));assertEquals(skin.textPrimary,a.getColor(1,0));a.recycle()
        }
    }
    @Test fun settingsVoiceDialogAndArtworkUseSelectedSkin() {
        for(choice in ThemeStore.choices(context)) {
            ThemeStore.select(context,choice.id)
            context.getSharedPreferences("hermes_watch",0).edit().clear().putBoolean("speak",false).commit()
            val controller=Robolectric.buildActivity(MainActivity::class.java).create()
            val activity=controller.get();val skin=ThemeTokens(activity)
            val face=MainActivity::class.java.getDeclaredField("face").apply {isAccessible=true}.get(activity) as HermesFaceView
            assertTrue("Shader compiles for "+choice.name,face.shaderAvailable)
            face.layout(0,0,480,480);val bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888);face.draw(Canvas(bitmap))
            assertEquals(skin.artworkBackground,bitmap.getPixel(0,0))
            face.performLongClick()
            val dialog=MainActivity::class.java.getDeclaredField("controls").apply {isAccessible=true}.get(activity) as android.app.Dialog
            fun find(view:View):Button? {
                if(view is Button && view.text.toString().startsWith("Appearance:"))return view
                if(view is android.view.ViewGroup)for(i in 0 until view.childCount) find(view.getChildAt(i))?.let{return it}
                return null
            }
            val appearance=find(dialog.window!!.decorView)!!
            assertTrue(appearance.text.contains(choice.name));assertEquals(skin.textPrimary,appearance.currentTextColor)
            val exact=View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY)
            val root=dialog.window!!.decorView;root.measure(exact,exact);root.layout(0,0,480,480);root.draw(Canvas(bitmap))
            File("build/reports/themes").mkdirs();File("build/reports/themes/"+choice.id+"-settings.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            appearance.performClick()
            val picker=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(skin.accent,picker.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent)).let {a ->try {a.getColor(0,0)} finally {a.recycle()}})
            picker.dismiss();bitmap.recycle();dialog.dismiss();controller.destroy()
        }
    }
    @Test @Config(sdk=[30]) fun fallbackArtworkUsesSelectedTheme() {
        ThemeStore.select(context,"amber");val skin=ThemeTokens(context);val face=HermesFaceView(context)
        assertFalse(face.shaderAvailable);face.layout(0,0,480,480)
        val b=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888);face.draw(Canvas(b))
        assertEquals(skin.artworkBackground,b.getPixel(0,0));assertNotEquals(b.getPixel(0,0),b.getPixel(240,210));b.recycle()
    }

    @Test fun pickerSelectionPersistsAndCancelKeepsExistingTheme() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=controller.get()
        ThemeStore.picker(activity)
        val cancelled=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        cancelled.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        assertEquals("royal",ThemeStore.selected(activity).id)
        ThemeStore.picker(activity)
        val picker=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        picker.listView.performItemClick(null,2,2)
        assertEquals("amber",ThemeStore.selected(activity).id)
        controller.destroy()
    }
}
