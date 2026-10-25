package de.kinolel.wuensche

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.view.ContextThemeWrapper
import java.util.Locale

/**
 * KinoLel (Lel 25.10.: „Sind Updatenachrichten auch auf Ungarisch … wenn die ausgewählte Sprache auf Ungarisch ist?“):
 * Die eigenen Texte der App (Was ist neu, Update, Version) folgen der Sprache, in der die Wunschseite gerade erscheint –
 * die im Seerr-Konto gewählte Anzeigesprache, sonst die der Seite (KinoLel Wünsche: Deutsch, KinoLel Kívánságok: Ungarisch).
 * Die Seite fragt das selbst ab (im WebView, mit ihrer eigenen Anmeldung) – die App liest keine Cookies aus und schickt
 * nichts selbst ins Netz. Gemerkt je Server; noch unbekannt (vor dem ersten Laden, offline): Gerätesprache.
 */
object Sprache {
    private const val VORLIEBEN = "kinolel_wuensche_sprache"
    private const val FRIST_MS = 8_000L
    private const val ABFRAGE_MS = 400L
    private val erlaubt = setOf("de", "hu", "en")
    private val haupt = Handler(Looper.getMainLooper())
    // nur auf dem Hauptthread benutzt
    private val warten = mutableListOf<() -> Unit>()
    private var bekannt = false // in diesem Start schon von der Seite gelesen (für den gewählten Server)

    /** Gewählter Server (Umschalter Deutsch/Magyar, gemerkt in MainActivity) */
    private fun server(context: Context): String =
        context.getSharedPreferences("kinolel_wuensche", Context.MODE_PRIVATE).getString("server", null)
            ?: if (geraet(context) == "hu") BuildConfig.SERVER_HU else BuildConfig.SERVER_DE

    fun geraet(context: Context): String = when (context.resources.configuration.locales[0].language) {
        "hu" -> "hu"
        "en" -> "en"
        else -> "de"
    }

    private fun gemerkt(context: Context, host: String?): String? =
        context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).getString(host, null)?.takeIf { it in erlaubt }

    fun aktuell(context: Context): String = gemerkt(context, Uri.parse(server(context)).host) ?: geraet(context)

    /** [aktion] ausführen, sobald die Seite ihre Sprache gemeldet hat – spätestens nach [FRIST_MS] (dann die gemerkte) */
    fun wennBekannt(aktion: () -> Unit) {
        if (bekannt) return aktion()
        var erledigt = false
        val einmal = { if (!erledigt) { erledigt = true; aktion() } }
        warten.add(einmal)
        haupt.postDelayed(einmal, FRIST_MS)
    }

    /** Nach dem Umschalten Deutsch/Magyar: die Sprache des anderen Servers kommt mit dessen Seite */
    fun serverGewechselt() {
        bekannt = false
    }

    /**
     * Nach dem Laden einer Seite der Wunschseite (onPageFinished): Sprache in der Seite abfragen – Anzeigesprache im
     * Seerr-Konto (nur angemeldet, nicht auf /login), sonst die Standardsprache des Servers.
     */
    fun ausSeite(web: WebView) {
        val host = Uri.parse(web.url ?: return).host ?: return
        if (listOf(BuildConfig.SERVER_DE, BuildConfig.SERVER_HU).none { Uri.parse(it).host.equals(host, ignoreCase = true) }) return
        val app = web.context.applicationContext
        val ende = System.currentTimeMillis() + FRIST_MS
        fun fragen() {
            web.evaluateJavascript(SKRIPT) { wert ->
                val text = wert?.trim('"')
                when {
                    text == null || text == "null" -> if (System.currentTimeMillis() < ende) haupt.postDelayed({ fragen() }, ABFRAGE_MS)
                    else -> {
                        text.take(2).lowercase().takeIf { it in erlaubt }?.let { sprache ->
                            app.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE).edit().putString(host, sprache).apply()
                            Log.i("KinoLelWuensche", "Sprache der Wunschseite: $sprache ($host)")
                        }
                        bekannt = true
                        val faellig = warten.toList()
                        warten.clear()
                        faellig.forEach { it() }
                    }
                }
            }
        }
        fragen()
    }

    /** Läuft in der Seite (gleiche Herkunft, ihre Anmeldung); liefert null, solange die Antwort noch aussteht */
    private val SKRIPT = """
        (function () {
          if (window.__kinolelSprache === undefined) {
            window.__kinolelSprache = null;
            (async function () {
              var l = '';
              try { l = (await (await fetch('/api/v1/settings/public')).json()).locale || ''; } catch (e) {}
              if (!location.pathname.startsWith('/login')) {
                try {
                  var r = await fetch('/api/v1/auth/me');
                  if (r.ok) {
                    var u = await r.json();
                    var eigene = u.settings ? u.settings.locale : undefined;
                    if (eigene === undefined) {
                      var m = await fetch('/api/v1/user/' + u.id + '/settings/main');
                      if (m.ok) eigene = (await m.json()).locale;
                    }
                    if (eigene) l = eigene;
                  }
                } catch (e) {}
              }
              window.__kinolelSprache = l || '-';
            })();
          }
          return window.__kinolelSprache;
        })()
    """.trimIndent()

    /** Kontext für App-Texte in dieser Sprache, mit dem Design der Activity (Dialoge, Toasts) */
    fun kontext(context: Context): Context {
        val konfiguration = Configuration(context.resources.configuration).apply { setLocale(Locale(aktuell(context))) }
        return ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(konfiguration) }
    }
}

/** App-Texte in der Sprache der Wunschseite (siehe [Sprache]) */
val Context.inKinoLelSprache: Context get() = Sprache.kontext(this)
