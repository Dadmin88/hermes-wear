package io.github.hermeswear

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchSettingsMenuTest {
    @Test fun fullWatchMenuIsAvailableWithoutUsingPhoneVoiceInventory(){
        val controller=Robolectric.buildActivity(WatchSettingsActivity::class.java).create()
        val root=controller.get().findViewById<View>(android.R.id.content)
        fun texts(v:View):List<String> = when(v){is TextView->listOf(v.text.toString());is ViewGroup->(0 until v.childCount).flatMap {texts(v.getChildAt(it))};else->emptyList()}
        val labels=texts(root)
        for(name in listOf("Watch theme","Spoken replies","Hey Hermes","Speech engine","Voice:","Speech rate","Pitch:","Test watch speaker","Preview selected voice","Read last reply","Install voice assets","Remote voice","Choose remote voice","Preview remote voice","Talk on watch","System dictation","Type a message","Saved conversations","Retry last request","Cancel current task","New chat","Enable task notifications","Open source notices","Back to pairing"))assertTrue("Missing $name",labels.any {it.contains(name)})
        val w=View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY);val h=View.MeasureSpec.makeMeasureSpec(1700,View.MeasureSpec.EXACTLY)
        root.measure(w,h);root.layout(0,0,800,1700);val image=Bitmap.createBitmap(800,1700,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
        File("build/reports/companion").mkdirs();File("build/reports/companion/watch-settings.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle();controller.destroy()
    }
}
