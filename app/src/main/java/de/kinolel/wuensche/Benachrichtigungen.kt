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
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * KinoLel (Lel 25.10.: „Können wir in KinoLel Wünsche … Benachrichtigungen einbauen, sodass eine Benachrichtigung kommt,
 * wenn eine Serie genehmigt wurde oder verfügbar ist?“)
 *
 * Etwa alle 15 Minuten (Android-Minimum, nur mit Netz) lädt die App unsichtbar eine kleine Seite des eigenen Servers und
 * fragt dort – gleiche Herkunft, mit der Anmeldung der Seite – die eigenen Anfragen ab. Die App liest keine Cookies und
 * schickt nichts selbst ins Netz; sie bekommt nur Titel, Nummern, Status und Zeiten zurück (wie bei der Sprache).
 *
 * v1.3 (Lel 26.10.: „… es kam 7:40 eine Mail, auf die aber keine Push-Benachrichtigung folgte“):
 * - beide Server prüfen: angemeldete immer, die anderen höchstens alle 6 Stunden auf Anmeldung (vorher nur der in der
 *   App zuletzt angezeigte – Lels Wünsche lagen auf Kívánságok)
 * - erster Lauf je Server meldet „verfügbar“ der letzten 24 Stunden (vorher nur Stand gemerkt), bekannte Anfragen bleiben
 *   gemerkt; unbekannte nur, wenn die Änderung jünger als 24 Stunden ist; höchstens 5 Meldungen je Lauf
 * - auch beim Öffnen der App prüfen; „Letzte Prüfung“, „Jetzt prüfen“ und Akku-Hinweis im Logo-Menü
 */
object Benachrichtigungen {
    private const val VORLIEBEN = "kinolel_wuensche_benachrichtigungen"
    private const val JOB_ID = 4202
    const val JOB_ID_SOFORT = 4203 // nach einer Push-Nachricht (v1.4)
    private const val KANAL = "wuensche"
    private const val ABSTAND_MS = 15 * 60 * 1000L
    private const val PROBE_MS = 6 * 60 * 60 * 1000L
    private const val FRISCH_MS = 24 * 60 * 60 * 1000L
    private const val BEIM_OEFFNEN_MS = 5 * 60 * 1000L
    private const val HOECHSTENS = 5
    private const val FRIST_MS = 60_000L
    /** Nur Debug-Version (Test): sofort prüfen; Anfragen aus files/test_anfragen.json statt vom Server */
    const val TEST_EXTRA = "kinolel_test_benachrichtigungen"

    private fun vorlieben(context: Context) = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)

    /** Hat die Person schon Ja/Nein gesagt? */
    fun entschieden(context: Context) = vorlieben(context).contains("an")

    fun an(context: Context) = vorlieben(context).getBoolean("an", false)

    fun setzen(context: Context, an: Boolean) {
        vorlieben(context).edit().putBoolean("an", an).apply()
        planen(context)
        themenAbgleichen(context)
    }

    /** v1.4: nach einer Push-Nachricht sofort prüfen – beschleunigter Job, läuft auch im Ruhezustand des Geräts */
    fun sofortPruefen(context: Context) {
        val planer = context.getSystemService(JobScheduler::class.java) ?: return
        val auftrag = JobInfo.Builder(JOB_ID_SOFORT, ComponentName(context, WunschPruefung::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        if (Build.VERSION.SDK_INT >= 31) auftrag.setExpedited(true) else auftrag.setOverrideDeadline(10_000)
        val ok = runCatching { planer.schedule(auftrag.build()) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        if (!ok) haupt.post { pruefen(context, vonHand = true) {} }
    }

    /** Push-Themen der angemeldeten Server (kl-<Jellyfin-ID>) abonnieren – nur wenn Benachrichtigungen an sind */
    fun themenAbgleichen(context: Context) {
        val v = vorlieben(context)
        val themen = if (an(context) && erlaubt(context)) alleServer().mapNotNull { v.getString("thema_$it", null) }.toSet() else emptySet()
        Push.themenAbgleichen(context, themen)
    }

    private fun themaMerken(context: Context, host: String, ergebnis: JSONObject?) {
        val jf = ergebnis?.optString("jf").orEmpty().replace("-", "").lowercase()
        val uid = ergebnis?.optInt("uid") ?: 0
        val instanz = if (Uri.parse(BuildConfig.SERVER_HU).host == host) "hu" else "de"
        val thema = when {
            ergebnis == null -> null
            Regex("[0-9a-f]{32}").matches(jf) -> "kl-$jf"
            uid > 0 -> "kl-$instanz-u$uid"
            else -> null
        }
        val v = vorlieben(context)
        if (v.getString("thema_$host", null) != thema) {
            v.edit().apply { if (thema == null) remove("thema_$host") else putString("thema_$host", thema) }.apply()
            themenAbgleichen(context)
        }
    }

    fun erlaubt(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Darf die App im Hintergrund ohne Akku-Einschränkung laufen? (sonst können sich Prüfungen um Stunden verschieben) */
    fun akkuFrei(context: Context) =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

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

    /** Beim Öffnen der App: prüfen, wenn die letzte Prüfung länger als 5 Minuten her ist */
    fun beimOeffnen(context: Context) {
        if (an(context) && erlaubt(context) && System.currentTimeMillis() - vorlieben(context).getLong("letzte", 0) > BEIM_OEFFNEN_MS) {
            pruefen(context, vonHand = false) {}
        }
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
    private var aktiv = false

    private fun alleServer() = listOf(BuildConfig.SERVER_DE, BuildConfig.SERVER_HU).mapNotNull { Uri.parse(it).host }

    /**
     * Server prüfen (Hauptthread); [fertig] kommt immer – auch bei Fehlern und nach der Frist.
     * [vonHand] („Jetzt prüfen“, nach „Ja“): auch nicht angemeldete Server sofort auf Anmeldung prüfen.
     */
    fun pruefen(context: Context, vonHand: Boolean = false, fertig: () -> Unit) {
        val app = context.applicationContext
        if (aktiv) return fertig()
        aktiv = true
        val ergebnisse = JSONObject()
        var erledigt = false
        val ende = {
            if (!erledigt) {
                erledigt = true
                aktiv = false
                laeuft?.destroy()
                laeuft = null
                protokollieren(app, ergebnisse)
                fertig()
            }
        }
        haupt.postDelayed(ende, FRIST_MS)
        if (BuildConfig.DEBUG) {
            val test = File(app.filesDir, "test_anfragen.json")
            if (test.exists()) {
                runCatching { auswerten(app, JSONObject(test.readText()), ergebnisse) }.onFailure { Log.w("KinoLelWuensche", "Test", it) }
                return ende()
            }
        }
        val v = vorlieben(app)
        val jetzt = System.currentTimeMillis()
        val angemeldet = Sprache.angemeldeteServer(app)
        val server = alleServer().filter { it in angemeldet || vonHand || jetzt - v.getLong("probe_$it", 0) > PROBE_MS }
        fun naechster(i: Int) {
            if (erledigt) return
            if (i >= server.size) return ende()
            abfragen(app, server[i]) { ergebnis ->
                if (ergebnis == null) {
                    ergebnisse.put(server[i], JSONObject().put("z", "fehler"))
                } else {
                    runCatching { auswerten(app, ergebnis, ergebnisse) }.onFailure { e -> Log.w("KinoLelWuensche", "Benachrichtigungen", e) }
                }
                naechster(i + 1)
            }
        }
        naechster(0)
    }

    fun abbrechen() {
        laeuft?.destroy()
        laeuft = null
        aktiv = false
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
                                if (laeuft === view) laeuft = null
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
                  aus.jf = u.jellyfinUserId || '';
                  aus.uid = u.id || 0;
                  var r = await fetch('/api/v1/request?take=30&skip=0&filter=all&sort=modified&requestedBy=' + u.id);
                  var d = r.ok ? await r.json() : { results: [] };
                  var bekannt = $bekannt;
                  for (var x of (d.results || [])) {
                    var m = x.media || {};
                    var typ = x.type === 'tv' ? 'tv' : 'movie';
                    var t = bekannt[typ + ':' + m.tmdbId] || '';
                    if (!t && m.tmdbId) {
                      try { var i = await (await fetch('/api/v1/' + typ + '/' + m.tmdbId + '?language=$sprache')).json(); t = i.title || i.name || ''; } catch (e) {}
                    }
                    aus.l.push({ id: x.id, s: x.status || 0, m: m.status || 0, typ: typ, tmdb: m.tmdbId || 0, t: t,
                                 ru: x.updatedAt || '', mu: m.updatedAt || '', ma: m.mediaAddedAt || '' });
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

    private fun zeit(iso: String?): Long? =
        iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private data class Meldung(val id: Int, val ereignis: Ereignis, val titel: String, val typ: String, val tmdb: Int)

    /** Ergebnis mit dem gemerkten Stand vergleichen, Änderungen melden, neuen Stand merken */
    private fun auswerten(context: Context, ergebnis: JSONObject, ergebnisse: JSONObject) {
        val host = ergebnis.getString("host")
        val v = vorlieben(context)
        val jetzt = System.currentTimeMillis()
        if (!ergebnis.optBoolean("a")) {
            v.edit().putLong("probe_$host", jetzt).apply()
            // abgemeldet (401/403): bis zur nächsten Anmeldung nicht mehr regelmäßig fragen; sonst (offline, Server weg) später
            if (ergebnis.optInt("s") in setOf(401, 403)) {
                Sprache.abgemeldet(context, host)
                themaMerken(context, host, null)
                ergebnisse.put(host, JSONObject().put("z", "abgemeldet"))
            } else {
                ergebnisse.put(host, JSONObject().put("z", "fehler"))
            }
            return
        }
        Sprache.angemeldet(context, host)
        themaMerken(context, host, ergebnis)
        val alt = v.getString("stand_$host", null)?.let { JSONObject(it) }
        val erster = alt == null
        val neu = alt?.let { JSONObject(it.toString()) } ?: JSONObject() // bekannte Anfragen bleiben gemerkt
        val titel = bekannteTitel(context, host)
        val liste = ergebnis.getJSONArray("l")
        val meldungen = mutableListOf<Meldung>()
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
            val bekannt = alt?.has(id) == true
            val (sAlt, mAlt) = (alt?.optString(id, "1,1") ?: "1,1").split(",").map { it.toIntOrNull() ?: 0 }
            val ereignis = when {
                m == VERFUEGBAR && mAlt != VERFUEGBAR -> Ereignis.VERFUEGBAR
                m == TEILWEISE && mAlt != TEILWEISE && mAlt != VERFUEGBAR -> Ereignis.TEILWEISE
                s == ABGELEHNT && sAlt != ABGELEHNT -> Ereignis.ABGELEHNT
                s in GENEHMIGT && sAlt !in GENEHMIGT -> Ereignis.GENEHMIGT
                else -> null
            } ?: continue
            // erster Lauf: nur „(teilweise) verfügbar“ nachholen; Unbekanntes nur, wenn die Änderung jünger als 24 h ist
            if (erster && ereignis !in setOf(Ereignis.VERFUEGBAR, Ereignis.TEILWEISE)) continue
            if (!bekannt) {
                val wann = when (ereignis) {
                    Ereignis.VERFUEGBAR, Ereignis.TEILWEISE -> zeit(x.optString("ma")) ?: zeit(x.optString("mu"))
                    else -> zeit(x.optString("ru"))
                }
                if (wann == null || jetzt - wann > FRISCH_MS) continue
            }
            meldungen += Meldung(id.toInt(), ereignis, t.ifEmpty { titel.optString("$typ:$tmdb") }, typ, tmdb)
        }
        meldungen.take(if (meldungen.size > HOECHSTENS) HOECHSTENS - 1 else HOECHSTENS).forEach { melden(context, host, it) }
        if (meldungen.size > HOECHSTENS) weitere(context, host, meldungen.size - (HOECHSTENS - 1))
        v.edit().putString("stand_$host", neu.toString()).putString("titel_$host", titel.toString()).apply()
        ergebnisse.put(host, JSONObject().put("z", "ok").put("n", liste.length()).put("neu", meldungen.size))
        Log.i("KinoLelWuensche", "Benachrichtigungen: $host – ${liste.length()} eigene Anfragen, ${meldungen.size} Meldungen" +
            if (erster) " (erster Lauf)" else "")
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

    private fun antippen(context: Context, host: String, pfad: String, nummer: Int): PendingIntent {
        val ziel = Intent(Intent.ACTION_VIEW, Uri.parse("https://$host$pfad"))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, nummer, ziel, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun anzeigen(context: Context, nummer: Int, titel: String, text: String, tippen: PendingIntent) {
        if (!erlaubt(context)) return
        val meldung = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(R.drawable.benachrichtigung)
            .setColor(ContextCompat.getColor(context, R.color.lavendel))
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tippen)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(nummer, meldung) }
    }

    private fun melden(context: Context, host: String, m: Meldung) {
        val texte = Sprache.kontextFuerServer(context, host)
        val nummer = (host.hashCode() * 31 + m.id) * 8 + m.ereignis.ordinal
        val name = m.titel.ifEmpty { texte.getString(R.string.benachrichtigung_ohne_titel) }
        val pfad = if (m.tmdb > 0) "/${m.typ}/${m.tmdb}" else "/requests"
        anzeigen(context, nummer, texte.getString(m.ereignis.titel), texte.getString(m.ereignis.text, name), antippen(context, host, pfad, nummer))
        Log.i("KinoLelWuensche", "Benachrichtigung: ${m.ereignis.name} ($host, Anfrage ${m.id})")
    }

    private fun weitere(context: Context, host: String, anzahl: Int) {
        val texte = Sprache.kontextFuerServer(context, host)
        val nummer = host.hashCode() * 8 + 7
        anzeigen(context, nummer, texte.getString(R.string.benachrichtigung_weitere_titel),
            texte.resources.getQuantityString(R.plurals.benachrichtigung_weitere_text, anzahl, anzahl), antippen(context, host, "/requests", nummer))
    }

    // --- Diagnose (Logo-Menü) -------------------------------------------------------------------------------------

    private fun protokollieren(context: Context, ergebnisse: JSONObject) {
        val v = vorlieben(context)
        val jetzt = System.currentTimeMillis()
        val zeile = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(jetzt)) + " " +
            ergebnisse.keys().asSequence().joinToString(", ") { h ->
                val e = ergebnisse.getJSONObject(h)
                "$h: ${e.optString("z")}" + (if (e.has("n")) " ${e.optInt("n")}/${e.optInt("neu")}" else "")
            }.ifEmpty { "kein angemeldeter Server" }
        val protokoll = runCatching { JSONArray(v.getString("protokoll", "[]")) }.getOrDefault(JSONArray())
        protokoll.put(zeile)
        while (protokoll.length() > 20) protokoll.remove(0)
        v.edit().putLong("letzte", jetzt).putString("letzte_ergebnis", ergebnisse.toString()).putString("protokoll", protokoll.toString()).apply()
        Log.i("KinoLelWuensche", "Benachrichtigungen-Protokoll: $zeile")
    }

    /** „Letzte Prüfung: 08:52 · Kívánságok: 31 Wünsche“ in der Sprache von [texte] */
    fun letztePruefung(context: Context, texte: Context): String {
        val v = vorlieben(context)
        val wann = v.getLong("letzte", 0)
        if (wann == 0L) return texte.getString(R.string.benachrichtigung_letzte, texte.getString(R.string.benachrichtigung_noch_nie))
        val heute = SimpleDateFormat("yyyyMMdd", Locale.ROOT).let { it.format(Date(wann)) == it.format(Date()) }
        val uhr = SimpleDateFormat(if (heute) "HH:mm" else "dd.MM. HH:mm", Locale.ROOT).format(Date(wann))
        val e = runCatching { JSONObject(v.getString("letzte_ergebnis", "{}")!!) }.getOrDefault(JSONObject())
        val teile = alleServer().filter { e.has(it) }.map { h ->
            val name = if (Uri.parse(BuildConfig.SERVER_HU).host == h) "Kívánságok" else BuildConfig.APP_NAME.removePrefix("KinoLel ")
            val x = e.getJSONObject(h)
            when (x.optString("z")) {
                "ok" -> texte.resources.getQuantityString(R.plurals.benachrichtigung_server_ok, x.optInt("n"), name, x.optInt("n"))
                "abgemeldet" -> texte.getString(R.string.benachrichtigung_server_abgemeldet, name)
                else -> texte.getString(R.string.benachrichtigung_server_fehler, name)
            }
        }
        return texte.getString(R.string.benachrichtigung_letzte, uhr) + if (teile.isEmpty()) "" else "\n" + teile.joinToString("\n")
    }
}

/** Hintergrundprüfung (JobScheduler, etwa alle 15 Minuten, nur mit Netz) */
class WunschPruefung : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // nach einer Push-Nachricht auch nicht angemeldete Server gleich mitprüfen
        Benachrichtigungen.pruefen(this, vonHand = params.jobId == Benachrichtigungen.JOB_ID_SOFORT) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Benachrichtigungen.abbrechen()
        return true
    }
}
