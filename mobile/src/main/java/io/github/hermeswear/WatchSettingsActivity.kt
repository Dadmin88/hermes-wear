package io.github.hermeswear

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.SharedPreferences
import android.widget.*
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** Controls the watch's preferences; this never enumerates the phone's TTS voices. */
class WatchSettingsActivity:Activity() {
    private val main=Handler(Looper.getMainLooper())
    private val skin by lazy {ThemeTokens(this)}
    private val bridge by lazy {getSharedPreferences("hermes_watch_settings",Context.MODE_PRIVATE)}
    private var snapshot=JSONObject()
    private var pending=""
    private var node=""
    private var open=true
    private lateinit var scroll:ScrollView
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key -> if(key=="reply")runOnUiThread {
        val response=try {JSONObject(bridge.getString("reply","{}")!!)}catch(_:Exception){return@runOnUiThread}
        if(response.optString("requestId")!=pending || response.optString("node")!=node)return@runOnUiThread
        pending="";main.removeCallbacksAndMessages(null)
        snapshot=response.optJSONObject("snapshot") ?: snapshot
        render(if(response.optBoolean("ok"))response.optString("message") else response.optString("error"))
    }}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=16f)=TextView(this).apply {text=value;textSize=size;setTextColor(skin.textPrimary);setPadding(0,dp(10),0,dp(8))}
    private fun button(value:String,enabled:Boolean=true,run:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;skin.styleButton(this,skin.surfaceRaised);isEnabled=enabled;alpha=if(enabled)1f else .5f;setOnClickListener {run()}
    }
    private fun values()=snapshot.optJSONObject("values") ?: JSONObject()
    override fun onCreate(state:Bundle?) {
        ThemeStore.apply(this);super.onCreate(state)
        scroll=ScrollView(this);content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(20),dp(22),dp(30));setBackgroundColor(skin.background)}
        scroll.addView(content);setContentView(scroll)
        val cached=try {JSONObject(bridge.getString("reply","{}")!!)}catch(_:Exception){JSONObject()}
        snapshot=cached.optJSONObject("snapshot") ?: JSONObject();node=cached.optString("node")
        render("Connect to the watch to refresh its settings")
    }
    override fun onResume() {super.onResume();open=true;pending="";bridge.registerOnSharedPreferenceChangeListener(listener);request("read")}
    override fun onPause() {open=false;pending="";main.removeCallbacksAndMessages(null);bridge.unregisterOnSharedPreferenceChangeListener(listener);super.onPause()}
    override fun onDestroy() {open=false;main.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun request(op:String,extra:JSONObject=JSONObject()) {
        if(pending.isNotEmpty())return
        val id=UUID.randomUUID().toString();pending=id;status.text="Contacting watch…"
        val packet=JSONObject(extra.toString()).put("op",op).put("requestId",id).put("createdMs",System.currentTimeMillis())
        main.postDelayed({if(pending==id && open){pending="";render("No watch confirmation. Check the connection, then Refresh.")}},10000)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener {nodes ->
            if(!open || pending!=id)return@addOnSuccessListener
            val peer=nodes.firstOrNull {it.id==node} ?: nodes.firstOrNull {it.isNearby} ?: nodes.firstOrNull()
            if(peer==null){pending="";render("Watch disconnected. Reconnect and Refresh.");return@addOnSuccessListener}
            node=peer.id
            Wearable.getMessageClient(this).sendMessage(peer.id,"/hermes-wear/settings/request",packet.toString().toByteArray())
                .addOnFailureListener {if(open && pending==id){pending="";render("Settings could not reach the watch. Reconnect and Refresh.")}}
        }.addOnFailureListener {if(open && pending==id){pending="";render("Wear OS connection unavailable. Reconnect and Refresh.")}}
    }
    private fun patch(key:String,value:Any) {request("patch",JSONObject().put("patch",JSONObject().put(key,value)).put("revision",snapshot.optString("revision")))}
    private fun action(name:String,extra:JSONObject=JSONObject(),confirm:Boolean=false) {
        fun send(){request("action",extra.put("action",name).put("turnId",snapshot.optString("turnId")))}
        if(confirm)AlertDialog.Builder(this).setTitle("Confirm action").setMessage(name.replace('_',' ')+" on the watch?").setPositiveButton("Continue"){_,_->send()}.setNegativeButton("Cancel",null).show() else send()
    }
    private fun choose(title:String,key:String,items:JSONArray,defaultLabel:String?=null) {
        val ids=mutableListOf<String>();val labels=mutableListOf<String>()
        if(defaultLabel!=null){ids.add("");labels.add(defaultLabel)}
        for(i in 0 until items.length()){val x=items.getJSONObject(i);ids.add(x.optString("id"));labels.add(x.optString("label"))}
        if(labels.isEmpty()){status.text="Open Hermes on the watch, let its speech engine initialize, then Refresh.";return}
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels.toTypedArray(),ids.indexOf(values().optString(key))){d,i->d.dismiss();patch(key,ids[i])}.setNegativeButton("Cancel",null).show()
    }
    private fun slider(key:String,title:String,min:Float,max:Float) {
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(10))}
        val reading=label("");var value=values().optDouble(key,1.0).toFloat()
        fun update(){reading.text=title+": "+String.format(java.util.Locale.US,"%.2f×",value)}
        val bar=SeekBar(this).apply {this.max=100;progress=((value-min)/(max-min)*100).toInt().coerceIn(0,100)}
        update();layout.addView(reading);layout.addView(bar)
        bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(v:SeekBar?,p:Int,user:Boolean){if(user){value=min+p/100f*(max-min);update()}}
            override fun onStartTrackingTouch(v:SeekBar?){};override fun onStopTrackingTouch(v:SeekBar?){}
        })
        AlertDialog.Builder(this).setTitle(title).setView(layout).setPositiveButton("Save on watch"){_,_->patch(key,value.coerceIn(min,max))}.setNegativeButton("Cancel",null).show()
    }
    private fun selectedLabel(key:String,list:JSONArray,default:String):String {
        if(!snapshot.has("revision"))return "—"
        val id=values().optString(key)
        if(id.isBlank())return default
        return (0 until list.length()).map {list.getJSONObject(it)}.firstOrNull {it.optString("id")==id}?.optString("label") ?: "Refresh to check selected value"
    }
    private fun render(message:String) {
        val y=scroll.scrollY;content.removeAllViews()
        content.addView(label("Watch settings",24f));status=label(message);content.addView(status)
        content.addView(button("Refresh from watch"){request("read")})
        content.addView(label("These controls configure the watch. Voice lists come from its installed engine. Permission prompts and microphone actions require Hermes open on the watch.",14f))
        val ready=snapshot.has("revision");val idle=ready && pending.isEmpty();val v=values()
        content.addView(label("Appearance",20f))
        val themes=snapshot.optJSONArray("themes") ?: JSONArray()
        content.addView(button("Watch theme: "+selectedLabel("theme",themes,"—"),idle){choose("Watch appearance","theme",themes)})
        content.addView(label("Voice and wake word",20f))
        content.addView(button("Spoken replies: "+if(!ready)"—" else if(v.optBoolean("speak",true))"ON" else "OFF",idle){patch("speak",!v.optBoolean("speak",true))})
        content.addView(button("Hey Hermes wake word: "+if(!ready)"—" else if(v.optBoolean("wake_word"))"ON" else "OFF",idle){
            if(v.optBoolean("wake_word"))patch("wake_word",false) else AlertDialog.Builder(this).setTitle("Enable Hey Hermes?")
                .setMessage("Downloads about 40 MB once. Listens locally only while Hermes is open with the screen awake. Uses extra battery. Grant microphone permission on the watch when prompted.")
                .setPositiveButton("Enable"){_,_->patch("wake_word",true)}.setNegativeButton("Cancel",null).show()
        })
        if(ready && !snapshot.optBoolean("microphone"))content.addView(label("Microphone permission must be granted on the watch.",14f))
        val caps=snapshot.optJSONObject("capabilities") ?: JSONObject()
        content.addView(button("Speech engine: "+selectedLabel("tts_engine",caps.optJSONArray("engines") ?: JSONArray(),"Watch default"),idle){choose("Watch speech engine","tts_engine",caps.optJSONArray("engines") ?: JSONArray(),"Watch default")})
        content.addView(button("Voice: "+selectedLabel("tts_voice",caps.optJSONArray("voices") ?: JSONArray(),"Watch default"),idle){choose("Watch voice","tts_voice",caps.optJSONArray("voices") ?: JSONArray(),"Watch default")})
        content.addView(button("Speech rate: "+v.optDouble("tts_speed",.92),idle){slider("tts_speed","Speech rate",.65f,1.45f)})
        content.addView(button("Pitch: "+v.optDouble("tts_pitch",1.0),idle){slider("tts_pitch","Pitch",.75f,1.25f)})
        content.addView(button("Test watch speaker",idle){action("speaker_test")})
        content.addView(button("Preview selected voice",idle){action("voice_preview")})
        content.addView(button("Read last reply on watch",idle){action("read_reply")})
        content.addView(button("Install voice assets on watch",idle){action("install_assets")})
        content.addView(button("Remote voice: "+if(v.optBoolean("nous_voice"))"ON" else "OFF",idle && snapshot.optBoolean("remoteVoice")){patch("nous_voice",!v.optBoolean("nous_voice"))})
        if(ready && !snapshot.optBoolean("remoteVoice"))content.addView(label("Remote voice is unavailable on this relay.",14f))
        val remote=JSONArray();listOf("alloy","echo","fable","onyx","nova","shimmer").forEach {remote.put(JSONObject().put("id",it).put("label",it))}
        content.addView(button("Choose remote voice",idle){choose("Remote voice","nous_voice_style",remote)})
        content.addView(button("Preview remote voice on watch",idle && snapshot.optBoolean("remoteVoice")){action("remote_preview")})
        content.addView(label("Conversation and tasks",20f))
        content.addView(button("Talk on watch",idle){action("talk")})
        content.addView(button("System dictation on watch",idle){action("system_dictation")})
        content.addView(button("Type a message",idle){
            val input=EditText(this).apply {hint="Message Hermes";maxLines=5;filters=arrayOf(android.text.InputFilter.LengthFilter(2000))}
            AlertDialog.Builder(this).setTitle("Send through watch").setView(input).setPositiveButton("Send"){_,_->action("text",JSONObject().put("text",input.text.toString()))}.setNegativeButton("Cancel",null).show()
        })
        content.addView(button("Saved conversations and tasks",idle){
            val history=snapshot.optJSONArray("history") ?: JSONArray()
            if(history.length()==0){status.text="No saved tasks on the watch"}else AlertDialog.Builder(this).setTitle("Recent watch tasks")
                .setItems((0 until history.length()).map {history.getJSONObject(it).optString("question")+" · "+history.getJSONObject(it).optString("state")}.toTypedArray()){_,i ->
                    val item=history.getJSONObject(i)
                    AlertDialog.Builder(this).setTitle(item.optString("question")).setMessage(item.optString("text").ifBlank {item.optString("state")})
                        .setPositiveButton("Continue on watch"){_,_->action("load_task",JSONObject().put("taskId",item.optString("id")))}.setNegativeButton("Close",null).show()
                }.setNegativeButton("Close",null).show()
        })
        content.addView(button("Retry last request",idle){action("retry")})
        content.addView(button("Cancel current task",idle && snapshot.optString("turnId").isNotBlank()){action("cancel",confirm=true)})
        content.addView(button("New chat",idle){action("new_chat",confirm=true)})
        val approval=snapshot.optJSONObject("approval")
        if(approval!=null){
            content.addView(label(approval.optString("prompt","Approval requested")))
            fun decision()=JSONObject().put("approvalId",approval.optString("requestId")).put("approvalGeneration",approval.optInt("generation",-1))
            content.addView(button("Approve once",idle){action("approve_once",decision(),true)})
            content.addView(button("Deny",idle){action("deny",decision())})
        }
        content.addView(label("Device and connection",20f))
        content.addView(label((if(snapshot.optBoolean("paired"))"Paired" else "Not paired")+" · "+snapshot.optString("transport","—")))
        content.addView(button("Enable task notifications on watch",idle){action("notifications")})
        content.addView(button("Open source notices"){AlertDialog.Builder(this).setTitle("Vosk · Apache-2.0").setMessage(resources.openRawResource(R.raw.wake_license).bufferedReader().use {it.readText()}).setPositiveButton("Close",null).show()})
        content.addView(button("Back to pairing and connection"){finish()})
        main.post {if(open)scroll.scrollTo(0,y)}
    }
}
