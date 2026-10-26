package io.github.hermeswear

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Explicit opt-in, foreground-only local phrase detection. Never uploads microphone audio. */
internal class WakeWordListener(private val context:Context,private val status:(String)->Unit,private val wake:()->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var model:Model?=null
    private var service:SpeechService?=null
    private var recognizer:Recognizer?=null
    private var epoch=0
    private var wanted=false
    @Volatile private var closed=false
    private var loading=false
    private var faulted=false
    fun retry() {faulted=false}
    var active=false;private set
    fun start() {
        if(closed || active || faulted) return
        wanted=true
        if(model==null) {
            if(loading) return
            loading=true;status("Preparing offline wake word…")
            worker.execute {
                val loaded=runCatching {Model(prepareModel().absolutePath)}
                main.post {
                    loading=false
                    if(closed) {loaded.getOrNull()?.close();return@post}
                    model=loaded.getOrNull()
                    if(model!=null && wanted) begin()
                    else if(loaded.isFailure && wanted) {faulted=true;wanted=false;status("Wake word unavailable. Tap to speak; try enabling again in Settings.")}
                }
            }
        } else begin()
    }
    private fun begin() {
        val local=model ?: return
        val token=++epoch
        try {
            val r=Recognizer(local,16000f,"[\"hey hermes\",\"[unk]\"]");r.setWords(true);recognizer=r
            val mic=SpeechService(r,16000f);service=mic
            val ok=mic.startListening(object:RecognitionListener {
                override fun onPartialResult(hypothesis:String?) {}
                override fun onResult(hypothesis:String?) {
                    if(token==epoch && active && matches(hypothesis)) {stop();wake()}
                }
                override fun onFinalResult(hypothesis:String?) {}
                override fun onError(exception:Exception?) {if(token==epoch) {stop();faulted=true;status("Wake microphone unavailable. Tap to speak.")}}
                override fun onTimeout() {if(token==epoch) stop()}
            })
            if(!ok) error("wake recorder did not start")
            active=true;status("Hey Hermes ready · app open")
        } catch(_:Exception) {stop();faulted=true;status("Wake microphone unavailable. Tap to speak.")}
    }
    fun stop() {
        wanted=false;epoch++;active=false
        runCatching {service?.stop()};runCatching {service?.shutdown()};service=null
        runCatching {recognizer?.close()};recognizer=null
    }
    fun close() {
        closed=true;stop()
        val old=model;model=null
        worker.execute {old?.close()};worker.shutdown()
    }
    private fun prepareModel():File {
        val home=File(context.filesDir,"wake-model")
        val ready=File(home,"ready.sha256")
        val root=File(home,"vosk-model-small-en-us-0.15")
        if(ready.exists() && ready.readText()==HASH && File(root,"am/final.mdl").exists()) return root
        home.deleteRecursively();home.mkdirs()
        val zip=File(context.cacheDir,"hermes-wake-model.zip")
        try {
            val connection=URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection().apply {connectTimeout=15000;readTimeout=15000}
            val digest=MessageDigest.getInstance("SHA-256")
            val deadline=android.os.SystemClock.elapsedRealtime()+120000
            connection.getInputStream().use {input -> zip.outputStream().use {out ->
                val buffer=ByteArray(32768);var total=0L
                while(true) {
                    if(closed || android.os.SystemClock.elapsedRealtime()>deadline) error("wake download cancelled or timed out")
                    val n=input.read(buffer);if(n<0) break
                    total+=n;require(total<=50000000);digest.update(buffer,0,n);out.write(buffer,0,n)
                }
            } }
            require(digest.digest().joinToString(""){"%02x".format(it)}==HASH)
            ZipInputStream(zip.inputStream().buffered()).use {input ->
                var total=0L;var entries=0
                while(true) {
                    val item=input.nextEntry ?: break
                    require(++entries<=2000)
                    val file=File(home,item.name)
                    require(file.canonicalPath.startsWith(home.canonicalPath+File.separator))
                    if(item.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use {out ->
                            val buffer=ByteArray(32768)
                            while(true) {val n=input.read(buffer);if(n<0)break;total+=n;require(total<=150000000);out.write(buffer,0,n)}
                        }
                    }
                }
            }
            require(File(root,"am/final.mdl").exists());ready.writeText(HASH)
            return root
        } catch(e:Exception) {home.deleteRecursively();throw e} finally {zip.delete()}
    }
    companion object {
        private const val HASH="30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        internal fun matches(json:String?):Boolean=try {
            val result=JSONObject(json ?: "{}")
            val words=result.optJSONArray("result")
            result.optString("text").trim().lowercase()=="hey hermes" && words!=null && words.length()==2 &&
                (0..1).all {words.getJSONObject(it).optDouble("conf",0.0)>=.85}
        }catch(_:Exception){false}
    }
}
