package de.kinolel.wuensche

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import androidx.appcompat.view.ContextThemeWrapper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * KinoLel (Lel 25.10.: „Sind Updatenachrichten auch auf Ungarisch … wenn die ausgewählte Sprache auf Ungarisch ist?“):
 * Die eigenen Texte der App (Was ist neu, Update) folgen der Sprache, in der die Wunschseite gerade erscheint –
 * die im Seerr-Konto gewählte Anzeigesprache, sonst die der Seite (KinoLel Wünsche: Deutsch, KinoLel Kívánságok: Ungarisch).
 * Die Sprache wird je Server gemerkt; noch unbekannt (vor dem ersten Laden, offline): Gerätesprache.
 */
object Sprache {
    private const val VORLIEBEN = "kinolel_wuensche_sprache"
    private const val FRIST_MS = 8_000L
    private val erlaubt = setOf("de", "hu", "en")
    private val warten = mutableListOf<() -> Unit>()
    private val haupt = Handler(Looper.getMainLooper())
    // nur auf dem Hauptthread benutzt
    private var ermittelt: String? = null // Server, für den die Sprache in diesem Prozess gelesen wurde
    private var laeuft = false

    /** Gewählter Server (Umschalter Deutsch/Magyar, gemerkt in MainActivity) */
    private fun server(context: Context): String =
        context.getSharedPreferences("kinolel_wuensche", Context.MODE_PRIVATE).getString("server", null)
            ?: if (geraet(context) == "hu") BuildConfig.SERVER_HU else BuildConfig.SERVER_DE

    fun geraet(context: Context): String = when (context.resources.configuration.locales[0].language) {
        "hu" -> "hu"
        "en" -> "en"
        else -> "de"
    }

    private fun gemerkt(context: Context, server: String): String? =
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).getString(Uri.parse(server).host, null)?.takeIf { it in erlaubt }

    fun aktuell(context: Context): String = gemerkt(context, server(context)) ?: geraet(context)

    /**
     * Sprache des gewählten Servers lesen (einmal je Prozess und Server; nur vom Hauptthread aufrufen). [danach] kommt,
     * sobald sie bekannt ist – spätestens nach [FRIST_MS] (dann mit der gemerkten bzw. Gerätesprache).
     */
    fun ermitteln(context: Context, danach: (() -> Unit)? = null) {
        val app = context.applicationContext
        val ziel = server(app)
        if (danach != null) {
            if (ermittelt == ziel) {
                haupt.post(danach)
            } else {
                var erledigt = false
                val einmal = { if (!erledigt) { erledigt = true; danach() } }
                warten.add(einmal)
                haupt.postDelayed(einmal, FRIST_MS)
            }
        }
        if (ermittelt == ziel || laeuft) return
        laeuft = true
        val cookies = runCatching { CookieManager.getInstance().getCookie(ziel) }.getOrNull()
        thread(name = "KinoLel-Sprache") {
            val sprache = runCatching { lesen(ziel, cookies) }
                .onFailure { Log.i("KinoLelWuensche", "Sprache nicht lesbar: ${it.message}") }.getOrNull()
            if (sprache != null) {
                app.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString(Uri.parse(ziel).host, sprache).apply()
                Log.i("KinoLelWuensche", "Sprache der Wunschseite: $sprache")
            }
            // Warteliste nur auf dem Hauptthread anfassen (dort wird auch eingetragen)
            haupt.post {
                ermittelt = ziel
                laeuft = false
                val faellig = warten.toList()
                warten.clear()
                faellig.forEach { it() }
            }
        }
    }

    /** Nach dem Umschalten Deutsch/Magyar: Sprache des anderen Servers lesen */
    fun serverGewechselt(context: Context) {
        ermittelt = null
        ermitteln(context)
    }

    /** Anzeigesprache im Seerr-Konto (falls angemeldet und gewählt), sonst die Standardsprache des Servers */
    private fun lesen(server: String, cookies: String?): String? {
        // nur angemeldet (Sitzungs-Cookie der Wunschseite) nach dem Konto fragen – sonst gäbe es unnötige 403
        if (cookies?.contains("connect.sid") == true) {
            runCatching {
                val ich = JSONObject(holen("$server/api/v1/auth/me", cookies))
                val eigene = ich.optJSONObject("settings")?.optString("locale")
                    ?: JSONObject(holen("$server/api/v1/user/${ich.getInt("id")}/settings/main", cookies)).optString("locale")
                kuerzel(eigene)?.let { return it }
            }
        }
        return kuerzel(JSONObject(holen("$server/api/v1/settings/public", null)).optString("locale"))
    }

    private fun kuerzel(locale: String?): String? = locale?.take(2)?.lowercase()?.takeIf { it in erlaubt }

    private fun holen(adresse: String, cookies: String?): String {
        val verbindung = URL(adresse).openConnection() as HttpURLConnection
        verbindung.connectTimeout = 6_000
        verbindung.readTimeout = 6_000
        verbindung.setRequestProperty("Accept", "application/json")
        cookies?.let { verbindung.setRequestProperty("Cookie", it) }
        try {
            return verbindung.inputStream.bufferedReader().use { it.readText() }
        } finally {
            verbindung.disconnect()
        }
    }

    /** Kontext für App-Texte in dieser Sprache, mit dem Design der Activity (Dialoge, Toasts) */
    fun kontext(context: Context): Context {
        val konfiguration = Configuration(context.resources.configuration).apply { setLocale(Locale(aktuell(context))) }
        return ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(konfiguration) }
    }
}

/** App-Texte in der Sprache der Wunschseite (siehe [Sprache]) */
val Context.inKinoLelSprache: Context get() = Sprache.kontext(this)
