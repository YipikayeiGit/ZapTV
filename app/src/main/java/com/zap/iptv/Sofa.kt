package com.zap.iptv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * "Ver en compañía": dos Zap en casas distintas viendo lo mismo a la vez.
 *
 * Cada tele reproduce el vídeo desde sus propias fuentes; entre ellas solo viajan avisos
 * diminutos ("pausa en 12:34", "voy por 45:02") a través de un buzón público de mensajes
 * (MQTT). El canal es secreto: sale del código de 4 letras, sin él nadie lo encuentra.
 */
@SuppressLint("StaticFieldLeak")
object Sofa {
    private const val BROKER = "ssl://broker.emqx.io:8883"
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var client: MqttClient? = null
    private var topic = ""

    /** Código de la sesión (null = no estás en ninguna). */
    @Volatile var code: String? = null
        private set

    /** Quien creó la sesión: su tele es la referencia para ir a la par. */
    var host = false
        private set

    val active get() = code != null

    @Volatile var lastPeer = 0L
        private set
    val peerOnline get() = System.currentTimeMillis() - lastPeer < 25_000

    /** El reproductor abierto escucha aquí los avisos de la otra tele. */
    var listener: ((JSONObject) -> Unit)? = null

    /** Para la pantalla de espera de quien crea la sesión. */
    var onPeer: (() -> Unit)? = null

    /** Por dónde va (en tiempo común) lo que se está viendo aquí; null fuera del reproductor. */
    var position: (() -> Long)? = null
    var playing: (() -> Boolean)? = null

    /** Posición en la que debe arrancar el próximo vídeo (al abrirlo la otra tele). */
    @Volatile var pendingStart: Long? = null

    /** Lo que se está viendo aquí (clave local), para no reabrirlo si la otra tele lo anuncia. */
    @Volatile var localKey: String? = null
    private var lastOpen: JSONObject? = null
    private var lastOpenedKey: String? = null
    private var lastOpenedAt = 0L

    val myId: String by lazy {
        val p = app.getSharedPreferences("zap", Context.MODE_PRIVATE)
        p.getString("sofa_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("sofa_id", it).apply() }
    }

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    // ---------- Sesión ----------

    fun create(ctx: Context): String {
        init(ctx)
        leave()
        val c = (1..4).map { LETTERS.random() }.joinToString("")
        host = true
        start(c)
        return c
    }

    /** Unirse: [done] recibe null si la otra tele ha contestado, o el motivo del fallo. */
    fun join(ctx: Context, raw: String, done: (String?) -> Unit) {
        init(ctx)
        leave()
        val c = raw.uppercase().filter { it in LETTERS }
        if (c.length != 4) {
            done("El código tiene 4 letras o números")
            return
        }
        host = false
        val asked = System.currentTimeMillis()
        start(c)
        // Esperar respuesta de la otra tele
        val check = object : Runnable {
            var tries = 0
            override fun run() {
                if (code != c) return
                when {
                    lastPeer > asked -> done(null)
                    tries++ >= 20 -> {
                        leave()
                        done("No hay ninguna tele esperando con el código $c")
                    }
                    else -> {
                        if (tries % 4 == 0) send("hello")
                        main.postDelayed(this, 500)
                    }
                }
            }
        }
        main.postDelayed(check, 1_000)
    }

    fun leave() {
        val c = client
        if (c != null) {
            send("bye")
            io.execute { runCatching { Thread.sleep(300); c.disconnect(1_000); c.close() } }
        }
        client = null
        code = null
        lastPeer = 0
        lastOpen = null
        localKey = null
        pendingStart = null
        onPeer = null
    }

    private fun start(c: String) {
        code = c
        topic = "zaptv/sofa/" + sha("zaptv-sofa-v1:$c").take(24)
        io.execute {
            try {
                val cl = MqttClient(BROKER, "zap-" + UUID.randomUUID().toString().take(12), MemoryPersistence())
                cl.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        runCatching { cl.subscribe(topic, 0) }
                        send("hello")
                    }

                    override fun connectionLost(cause: Throwable?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    override fun messageArrived(t: String?, message: MqttMessage) {
                        val j = runCatching { JSONObject(String(message.payload)) }.getOrNull() ?: return
                        if (j.optString("id") == myId || code != c) return
                        main.post { handle(j) }
                    }
                })
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    keepAliveInterval = 30
                    connectionTimeout = 10
                }
                client = cl
                cl.connect(opts)
            } catch (e: Exception) {
                main.post { toast("Ver en compañía: no se pudo conectar (${e.message ?: "sin conexión"})") }
            }
        }
    }

    fun send(kind: String, fill: JSONObject.() -> Unit = {}) {
        val cl = client ?: return
        val j = JSONObject().put("id", myId).put("k", kind).apply(fill)
        io.execute { runCatching { if (cl.isConnected) cl.publish(topic, MqttMessage(j.toString().toByteArray()).apply { qos = 0 }) } }
    }

    // ---------- Avisos recibidos ----------

    private fun handle(j: JSONObject) {
        val first = !peerOnline
        lastPeer = System.currentTimeMillis()
        when (j.optString("k")) {
            "hello" -> {
                send("here")
                onPeer?.invoke()
                if (first) toast("🛋 La otra tele se ha unido")
                // Si ya estás viendo algo, que se le abra también
                val open = lastOpen
                val pos = position
                if (open != null && pos != null) send("open") { copyFrom(open); put("p", pos()); put("pl", playing?.invoke() ?: true) }
            }
            "here" -> onPeer?.invoke()
            "open" -> open(j)
            "missing" -> toast("La otra tele no tiene «${j.optString("t")}» en su Zap")
            "bye" -> toast("La otra tele ha dejado de ver en compañía")
            else -> listener?.invoke(j)
        }
    }

    private fun JSONObject.copyFrom(o: JSONObject) {
        for (k in o.keys()) if (k != "id" && k != "k") put(k, o.get(k))
    }

    // ---------- Abrir lo mismo en las dos teles ----------

    /** Lo que se empieza a ver aquí se anuncia a la otra tele. */
    fun announce(item: CatalogItem, resumeKey: String?, startAt: Long) {
        val (s, n) = episodeOf(resumeKey)
        localKey = keyOf(item, s, n)
        if (!active) return
        val o = JSONObject()
            .put("t", item.title)
            .put("y", item.year ?: 0)
            .put("ty", if (item is Show) "show" else "movie")
            .put("ids", JSONArray(item.ids))
            .put("s", s)
            .put("n", n)
        lastOpen = o
        send("open") { copyFrom(o); put("p", startAt) }
    }

    private fun episodeOf(resumeKey: String?): Pair<Int, Int> {
        if (resumeKey == null || !resumeKey.startsWith("ep:")) return -1 to -1
        val parts = resumeKey.split(':')
        return (parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: -1) to (parts.lastOrNull()?.toIntOrNull() ?: -1)
    }

    private fun keyOf(item: CatalogItem, s: Int, n: Int) = "${item.key}|$s|$n"

    private fun open(j: JSONObject) {
        val title = j.optString("t")
        val show = j.optString("ty") == "show"
        val ids = j.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val w = WatchItem(guid = null, title = title, year = j.optInt("y").takeIf { it > 0 }, type = if (show) "show" else "movie", extIds = ids)
        val found = Watch.Index(if (show) Catalog.shows else Catalog.movies).find(w)?.takeIf { it.available }
        val s = j.optInt("s", -1)
        val n = j.optInt("n", -1)
        if (found == null) {
            send("missing") { put("t", title) }
            toast("La otra tele está viendo «$title», pero no lo tienes en tu Zap")
            return
        }
        val k = keyOf(found, s, n)
        if (k == localKey && listener != null) return // ya lo estás viendo
        // El mismo aviso repetido (p. ej. al conectar): no abrirlo dos veces
        val now = System.currentTimeMillis()
        if (k == lastOpenedKey && now - lastOpenedAt < 20_000) return
        lastOpenedKey = k
        lastOpenedAt = now
        localKey = k
        lastOpen = j
        pendingStart = j.optLong("p", 0L)
        // Cerrar lo que se esté viendo y abrir lo nuevo
        listener?.invoke(JSONObject().put("k", "close"))
        Playback.selected = found
        app.startActivity(
            Intent(app, DetailActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DetailActivity.EXTRA_SOFA_SEASON, s)
                .putExtra(DetailActivity.EXTRA_SOFA_EPISODE, n),
        )
        toast(if (s >= 0) "🛋 Abriendo ${found.title} · T${s}E$n" else "🛋 Abriendo ${found.title}")
    }

    fun toast(text: String) {
        if (::app.isInitialized) Toast.makeText(app, text, Toast.LENGTH_LONG).show()
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
