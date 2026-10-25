package de.kinolel.wuensche

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * KinoLel (Lel 25.10.: „Können wir in KinoLel Wünsche … Benachrichtigungen einbauen, sodass eine Benachrichtigung kommt,
 * wenn eine Serie genehmigt wurde oder verfügbar ist?“)
 *
 * Etwa alle 15 Minuten (Android-Minimum, nur mit Netz) lädt die App unsichtbar eine kleine Seite des eigenen Servers und
 * fragt dort – gleiche Herkunft, mit der Anmeldung der Seite – die eigenen Anfragen ab. Die App liest keine Cookies und
 * schickt nichts selbst ins Netz; sie bekommt nur Titel, Nummern und Status zurück (wie bei der Sprache, siehe [Sprache]).
 * Gegen den gemerkten Stand verglichen: genehmigt, teilweise verfügbar (Serien), verfügbar, abgelehnt.
 * Erster Lauf je Server: nur merken. Nur Server, auf denen man angemeldet ist (sonst keine Anfrage → keine 403).
 */
object Benachrichtigungen {
    private const val VORLIEBEN = "kinolel_wuensche_benachrichtigungen"
    private const val JOB_ID = 4202
    private const val KANAL = "wuensche"
    private const val ABSTAND_MS = 15 * 60 * 1000L
    private const val FRIST_MS = 45_000L
    /** Nur Debug-Version (Test): sofort prüfen; Anfragen aus files/test_anfragen.json statt vom Server */
    const val TEST_EXTRA = "kinolel_test_benachrichtigungen"

    private fun vorlieben(context: Context) = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)

    /** Hat die Person schon Ja/Nein gesagt? */
    fun entschieden(context: Context) = vorlieben(context).contains("an")

    fun an(context: Context) = vorlieben(context).getBoolean("an", false)

    fun setzen(context: Context, an: Boolean) {
        vorlieben(context).edit().putBoolean("an", an).apply()
        planen(context)
    }

    fun erlaubt(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Hintergrundprüfung ein- bzw. ausplanen (bei jedem Öffnen der App, nach Ja/Nein und nach der Erlaubnis) */
    fun planen(context: Context) {
        val planer = context.getSystemService(JobScheduler::class.java) ?: return
        if (!an(context) || !erlaubt(context)) {
            planer.cancel(JOB_ID)
            return
        }
        if (planer.getPendingJob(JOB_ID) != null) return
        planer.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WunschPruefung::class.java))
                .setPeriodic(ABSTAND_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
        )
        Log.i("KinoLelWuensche", "Benachrichtigungen: Prüfung alle 15 Minuten geplant")
    }

    fun kanalAnlegen(context: Context) {
        val texte = context.inKinoLelSprache
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(KANAL, texte.getString(R.string.benachrichtigung_kanal), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = texte.getString(R.string.benachrichtigung_kanal_text)
            }
        )
    }

    private val haupt = Handler(Looper.getMainLooper())
    private var laeuft: WebView? = null

    /** Alle angemeldeten Server prüfen (Hauptthread); [fertig] kommt immer – auch bei Fehlern und nach der Frist */
    fun pruefen(context: Context, fertig: () -> Unit) {
        val app = context.applicationContext
        var erledigt = false
        val ende = {
            if (!erledigt) {
                erledigt = true
                laeuft?.destroy()
                laeuft = null
                fertig()
            }
        }
        haupt.postDelayed(ende, FRIST_MS)
        if (BuildConfig.DEBUG) {
            val test = File(app.filesDir, "test_anfragen.json")
            if (test.exists()) {
                runCatching { auswerten(app, JSONObject(test.readText())) }.onFailure { Log.w("KinoLelWuensche", "Test", it) }
                return ende()
            }
        }
        val server = Sprache.angemeldeteServer(app)
        fun naechster(i: Int) {
            if (erledigt) return
            if (i >= server.size) return ende()
            abfragen(app, server[i]) { ergebnis ->
                ergebnis?.let { runCatching { auswerten(app, it) }.onFailure { e -> Log.w("KinoLelWuensche", "Benachrichtigungen", e) } }
                naechster(i + 1)
            }
        }
        naechster(0)
    }

    fun abbrechen() {
        laeuft?.destroy()
        laeuft = null
    }

    /** Unsichtbare WebView: kleine Seite des Servers laden, darin die eigenen Anfragen abfragen */
    private fun abfragen(context: Context, host: String, fertig: (JSONObject?) -> Unit) {
        val web = WebView(context)
        laeuft = web
        web.settings.javaScriptEnabled = true
        var gestartet = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (gestartet) return
                gestartet = true
                val skript = skript(bekannteTitel(context, host), Sprache.fuerServer(context, host))
                val bis = System.currentTimeMillis() + 30_000L
                fun fragen() {
                    view.evaluateJavascript(skript) { wert ->
                        val text = runCatching { JSONTokener(wert).nextValue() as? String }.getOrNull()
                        when {
                            text != null -> {
                                view.destroy()
                                if (laeuft === view) laeuft = null
                                fertig(runCatching { JSONObject(text).put("host", host) }.getOrNull())
                            }
                            System.currentTimeMillis() < bis -> haupt.postDelayed({ fragen() }, 500)
                            else -> {
                                view.destroy()
                                fertig(null)
                            }
                        }
                    }
                }
                fragen()
            }
        }
        // kleine JSON-Seite derselben Herkunft (nicht die ganze Oberfläche)
        web.loadUrl("https://$host/api/v1/settings/public")
    }

    private fun skript(bekannt: JSONObject, sprache: String) = """
        (function () {
          if (window.__kinolelWuensche === undefined) {
            window.__kinolelWuensche = null;
            (async function () {
              var aus = { a: false, l: [] };
              try {
                var me = await fetch('/api/v1/auth/me');
                if (me.ok) {
                  aus.a = true;
                  var u = await me.json();
                  var r = await fetch('/api/v1/request?take=20&skip=0&filter=all&sort=modified&requestedBy=' + u.id);
                  var d = r.ok ? await r.json() : { results: [] };
                  var bekannt = $bekannt;
                  for (var x of (d.results || [])) {
                    var m = x.media || {};
                    var typ = x.type === 'tv' ? 'tv' : 'movie';
                    var t = bekannt[typ + ':' + m.tmdbId] || '';
                    if (!t && m.tmdbId) {
                      try { var i = await (await fetch('/api/v1/' + typ + '/' + m.tmdbId + '?language=$sprache')).json(); t = i.title || i.name || ''; } catch (e) {}
                    }
                    aus.l.push({ id: x.id, s: x.status || 0, m: m.status || 0, typ: typ, tmdb: m.tmdbId || 0, t: t });
                  }
                } else {
                  aus.s = me.status;
                }
              } catch (e) { aus.f = String(e); }
              window.__kinolelWuensche = JSON.stringify(aus);
            })();
          }
          return window.__kinolelWuensche;
        })()
    """.trimIndent()

    private fun bekannteTitel(context: Context, host: String): JSONObject =
        runCatching { JSONObject(vorlieben(context).getString("titel_$host", "{}")!!) }.getOrDefault(JSONObject())

    /** Ergebnis mit dem gemerkten Stand vergleichen, Änderungen melden, neuen Stand merken */
    private fun auswerten(context: Context, ergebnis: JSONObject) {
        val host = ergebnis.getString("host")
        val v = vorlieben(context)
        if (!ergebnis.optBoolean("a")) {
            // abgemeldet (401/403): bis zur nächsten Anmeldung in der App nicht mehr fragen; sonst (offline, Server weg) später wieder
            if (ergebnis.optInt("s") in setOf(401, 403)) Sprache.abgemeldet(context, host)
            return
        }
        val alt = v.getString("stand_$host", null)?.let { JSONObject(it) }
        val neu = JSONObject()
        val titel = bekannteTitel(context, host)
        val liste = ergebnis.getJSONArray("l")
        for (k in 0 until liste.length()) {
            val x = liste.getJSONObject(k)
            val id = x.getInt("id").toString()
            val s = x.optInt("s")
            val m = x.optInt("m")
            val typ = x.optString("typ")
            val tmdb = x.optInt("tmdb")
            val t = x.optString("t")
            if (t.isNotEmpty()) titel.put("$typ:$tmdb", t)
            neu.put(id, "$s,$m")
            if (alt == null) continue // erster Lauf: nur merken
            val (sAlt, mAlt) = alt.optString(id, "1,1").split(",").map { it.toIntOrNull() ?: 0 }
            val genehmigt = s in GENEHMIGT && sAlt !in GENEHMIGT
            val ereignis = when {
                m == VERFUEGBAR && mAlt != VERFUEGBAR -> Ereignis.VERFUEGBAR
                m == TEILWEISE && mAlt != TEILWEISE && mAlt != VERFUEGBAR -> Ereignis.TEILWEISE
                s == ABGELEHNT && sAlt != ABGELEHNT -> Ereignis.ABGELEHNT
                genehmigt -> Ereignis.GENEHMIGT
                else -> null
            } ?: continue
            melden(context, host, id.toInt(), ereignis, t.ifEmpty { titel.optString("$typ:$tmdb") }, typ, tmdb)
        }
        v.edit().putString("stand_$host", neu.toString()).putString("titel_$host", titel.toString()).apply()
        Log.i("KinoLelWuensche", "Benachrichtigungen: $host – ${liste.length()} eigene Anfragen" + if (alt == null) " (erster Stand gemerkt)" else "")
    }

    private enum class Ereignis(val titel: Int, val text: Int) {
        GENEHMIGT(R.string.benachrichtigung_genehmigt_titel, R.string.benachrichtigung_genehmigt_text),
        TEILWEISE(R.string.benachrichtigung_teilweise_titel, R.string.benachrichtigung_teilweise_text),
        VERFUEGBAR(R.string.benachrichtigung_verfuegbar_titel, R.string.benachrichtigung_verfuegbar_text),
        ABGELEHNT(R.string.benachrichtigung_abgelehnt_titel, R.string.benachrichtigung_abgelehnt_text),
    }

    // Seerr: Anfrage 1 offen, 2 genehmigt, 3 abgelehnt, 4 fehlgeschlagen, 5 erledigt; Medien 4 teilweise, 5 verfügbar
    private val GENEHMIGT = setOf(2, 5)
    private const val ABGELEHNT = 3
    private const val TEILWEISE = 4
    private const val VERFUEGBAR = 5

    private fun melden(context: Context, host: String, id: Int, ereignis: Ereignis, titel: String, typ: String, tmdb: Int) {
        if (!erlaubt(context)) return
        val texte = Sprache.kontextFuerServer(context, host)
        val ziel = Intent(Intent.ACTION_VIEW, Uri.parse(if (tmdb > 0) "https://$host/$typ/$tmdb" else "https://$host/requests"))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nummer = (host.hashCode() * 31 + id) * 8 + ereignis.ordinal
        val antippen = PendingIntent.getActivity(context, nummer, ziel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val name = titel.ifEmpty { texte.getString(R.string.benachrichtigung_ohne_titel) }
        val meldung = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.benachrichtigung)
            .setColor(ContextCompat.getColor(context, R.color.lavendel))
            .setContentTitle(texte.getString(ereignis.titel))
            .setContentText(texte.getString(ereignis.text, name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte.getString(ereignis.text, name)))
            .setContentIntent(antippen)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(nummer, meldung) }
        Log.i("KinoLelWuensche", "Benachrichtigung: ${ereignis.name} ($host, Anfrage $id)")
    }
}

/** Hintergrundprüfung (JobScheduler, etwa alle 15 Minuten, nur mit Netz) */
class WunschPruefung : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Benachrichtigungen.pruefen(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Benachrichtigungen.abbrechen()
        return true
    }
}
