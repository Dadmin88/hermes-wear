package io.github.hermeswear

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.widget.Button

/** Theme resources are the single source of truth for native widgets and artwork. */
data class ThemeChoice(val id:String,val name:String,val style:Int)
object ThemeStore {
    private const val PREFS="hermes_appearance"
    fun choices(context:Context):List<ThemeChoice> {
        val ids=context.resources.getStringArray(R.array.hermes_theme_ids)
        val names=context.resources.getStringArray(R.array.hermes_theme_names)
        val styles=context.resources.obtainTypedArray(R.array.hermes_theme_styles)
        return try {
            require(ids.size==names.size && ids.size==styles.length() && ids.isNotEmpty())
            require(ids.distinct().size==ids.size)
            ids.indices.map {ThemeChoice(ids[it],names[it],styles.getResourceId(it,0).also {value -> require(value!=0)})}
        } finally {styles.recycle()}
    }
    fun selected(context:Context):ThemeChoice {
        val options=choices(context)
        val id=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("theme",null)
        return options.firstOrNull {it.id==id} ?: options.first()
    }
    fun select(context:Context,id:String) {
        require(choices(context).any {it.id==id}) {"Unknown theme"}
        check(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("theme",id).commit())
    }
    fun apply(activity:Activity) {activity.setTheme(selected(activity).style)}
    fun picker(activity:Activity) {
        val options=choices(activity);val current=selected(activity)
        AlertDialog.Builder(activity).setTitle("Appearance")
            .setSingleChoiceItems(options.map {it.name}.toTypedArray(),options.indexOf(current)) {dialog,index ->
                dialog.dismiss()
                if(options[index].id!=current.id) {select(activity,options[index].id);activity.recreate()}
            }.setNegativeButton("Cancel",null).show()
    }
}
class ThemeTokens(context:Context) {
    // A view may be constructed outside an Activity (tests/previews). Use the selected skin there too.
    private val themed=android.view.ContextThemeWrapper(context,ThemeStore.selected(context).style)
    private fun color(attr:Int):Int = themed.obtainStyledAttributes(intArrayOf(attr)).let {a ->
        try {require(a.hasValue(0));a.getColor(0,Color.MAGENTA)} finally {a.recycle()}
    }
    val background=color(R.attr.hwBackground)
    val surface=color(R.attr.hwSurface)
    val surfaceRaised=color(R.attr.hwSurfaceRaised)
    val accent=color(R.attr.hwAccent)
    val onAccent=color(R.attr.hwOnAccent)
    val textPrimary=color(R.attr.hwTextPrimary)
    val textSecondary=color(R.attr.hwTextSecondary)
    val outline=color(R.attr.hwOutline)
    val pressed=color(R.attr.hwPressed)
    val warning=color(R.attr.hwWarning)
    val artworkBackground=color(R.attr.hwArtworkBackground)
    val artworkPanel=color(R.attr.hwArtworkPanel)
    val cornerRadius=themed.obtainStyledAttributes(intArrayOf(R.attr.hwCornerRadius)).let {a -> try {a.getDimension(0,16f*context.resources.displayMetrics.density)} finally {a.recycle()}}
    val fontFamily=themed.obtainStyledAttributes(intArrayOf(R.attr.hwFontFamily)).let {a -> try {a.getString(0) ?: "sans-serif-medium"} finally {a.recycle()}}
    val typeface:Typeface=Typeface.create(fontFamily,Typeface.NORMAL)
    fun styleButton(button:Button,fill:Int=accent) {
        button.setTextColor(if(fill==accent)onAccent else textPrimary)
        button.typeface=typeface
        val shape=GradientDrawable().apply {setColor(fill);cornerRadius=this@ThemeTokens.cornerRadius}
        button.background=RippleDrawable(ColorStateList.valueOf(Color.argb(65,Color.red(textPrimary),Color.green(textPrimary),Color.blue(textPrimary))),shape,null)
        button.backgroundTintList=null
    }
    fun rgb(color:Int)=floatArrayOf(Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f)
}
