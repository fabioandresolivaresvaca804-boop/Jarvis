package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val MODEL = "claude-haiku-5-5"
private const val SYSTEM = "Eres Jarvis, un asistente de voz amable y eficaz. " +
    "Responde siempre en español, en una o dos frases cortas, sin listas ni formato."
private val IDLE = 0xFF3EE0FF.toInt()
private val LISTEN = 0xFF7CFF8A.toInt()
private val THINK = 0xFFFFC24A.toInt()
private val TALK = 0xFFB48CFF.toInt()

private fun norm(s: String) =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

class JarvisService : Service(), TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var running = false
    private var busy = false
    private var speaking = false
    private var awaitingCommand = false
    private var onSpeechDone: () -> Unit = {}
    private val history = mutableListOf<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        tts = TextToSpeech(this, this)
        addBubble()
        running = true
        listen()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        bubble?.let { wm.removeView(it) }
        super.onDestroy()
    }

    // ---------- Servicio en primer plano ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis activo")
            .setContentText("Di «Jarvis» para hablar")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 30)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // ---------- Burbuja flotante ----------
    private fun addBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "J"; gravity = Gravity.CENTER; textSize = 22f
            setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(IDLE) }
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                    wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onBubbleTap(); true }
                else -> false
            }
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    private fun setBubble(c: Int) { (bubble?.background as? GradientDrawable)?.setColor(c) }

    private fun onBubbleTap() {
        if (speaking) { tts?.stop(); return }
        if (busy) return
        awaitingCommand = true
        listen()
    }

    // ---------- Voz (TTS) ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("es", "ES")
        tts?.setPitch(0.85f)
        tts?.setSpeechRate(0.95f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { finishSpeech() } }
            override fun onStop(id: String?, interrupted: Boolean) { main.post { finishSpeech() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { finishSpeech() } }
        })
        ttsReady = true
    }

    private fun say(text: String, after: () -> Unit) {
        busy = true; speaking = true; setBubble(TALK)
        recognizer?.cancel()
        onSpeechDone = after
        if (!ttsReady) { finishSpeech(); return }
        tts?.speak(text.replace(Regex("[*_#`]"), ""), TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun finishSpeech() {
        if (!speaking) return
        speaking = false; busy = false; setBubble(IDLE)
        val a = onSpeechDone
        onSpeechDone = {}
        a()
    }

    // ---------- Escucha (palabra clave «Jarvis») ----------
    private fun listen() {
        if (!running || busy || speaking) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { if (awaitingCommand) setBubble(LISTEN) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onResults(res: Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                handle(t)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return }
                awaitingCommand = false
                setBubble(IDLE)
                main.postDelayed({ listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 300)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(i)
    }

    private fun handle(text: String) {
        val t = text.trim()
        val m = Regex("(?i)\\b[jy]arvis\\b[\\s,.:;]*(.*)").find(t)
        val cmd: String
        if (awaitingCommand) {
            awaitingCommand = false
            cmd = (if (m != null) m.groupValues[1] else t).trim()
        } else {
            if (m == null) { listen(); return }
            cmd = m.groupValues[1].trim()
            if (cmd.isEmpty()) {
                say("Dime") { awaitingCommand = true; listen() }
                return
            }
        }
        if (cmd.isEmpty()) { listen(); return }
        process(cmd)
    }

    // ---------- Comandos y IA ----------
    private fun process(cmd: String) {
        busy = true; setBubble(THINK)
        val local = localReply(cmd)
        if (local != null) { reply(cmd, local); return }
        val msgs = JSONArray()
        history.takeLast(4).forEach { (u, a) ->
            msgs.put(JSONObject().put("role", "user").put("content", u))
            msgs.put(JSONObject().put("role", "assistant").put("content", a))
        }
        msgs.put(JSONObject().put("role", "user").put("content", cmd))
        Thread {
            val r = askClaude(msgs)
            main.post { reply(cmd, r) }
        }.start()
    }

    private fun reply(cmd: String, text: String) {
        history.add(cmd to text)
        busy = false
        say(text) { listen() }
    }

    private fun localReply(cmd: String): String? {
        val c = norm(cmd).replace(Regex("[¿?¡!.,]"), "").trim()
        val es = Locale("es", "ES")
        if (c.contains("que hora")) return "Son las " + SimpleDateFormat("HH:mm", es).format(Date())
        if (c.contains("que dia") || c.contains("que fecha"))
            return "Hoy es " + SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", es).format(Date())
        val m = Regex("^(abre|abrir|ejecuta)\\s+(.+)").find(c)
        if (m != null) return openApp(m.groupValues[2].trim())
        return null
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(q, 0).firstOrNull { norm(it.loadLabel(pm).toString()).contains(name) }
            ?: return "No encuentro la aplicación $name"
        val launch = pm.getLaunchIntentForPackage(app.activityInfo.packageName)
            ?: return "No puedo abrir $name"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "Abriendo " + app.loadLabel(pm)
    }

    private fun askClaude(msgs: JSONArray): String {
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) return "Falta la clave de API. Introdúcela en la aplicación."
        return try {
            val body = JSONObject().put("model", MODEL).put("max_tokens", 300)
                .put("system", SYSTEM).put("messages", msgs)
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (!ok) "Error ${c.responseCode} al consultar la IA"
            else JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            "No pude conectar con la IA"
        }
    }
}
