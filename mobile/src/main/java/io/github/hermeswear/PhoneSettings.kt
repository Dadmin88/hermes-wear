package io.github.hermeswear
import android.content.Context
class PhoneSettings(context:Context) {
    private val secure=ConnectionSettings(context)
    init {
        val old=context.getSharedPreferences("hermes_wear",Context.MODE_PRIVATE)
        val token=old.getString("relay_token","") ?: ""
        val url=old.getString("url","") ?: ""
        if(token.length>=24 && secure.token.isEmpty() && secure.validUrl(url)) {
            secure.url=url;secure.token=token
            check(old.edit().remove("relay_token").remove("url").commit())
        }
    }
    var url:String get()=secure.url;set(v){secure.url=v}
    var token:String get()=secure.token;set(v){secure.token=v}
    fun validUrl(v:String)=secure.validUrl(v)
}
