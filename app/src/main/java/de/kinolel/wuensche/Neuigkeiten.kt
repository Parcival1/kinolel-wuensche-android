package de.kinolel.wuensche

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import android.util.Log

/**
 * KinoLel (Lel 22.10.2026): „Wenn es ein Update gab, einmalig nach der Aktualisierung eine Meldung, in der beschrieben ist,
 * was geändert wurde oder neu dazugekommen ist.“
 *
 * Die Texte liegen in der App (assets/neuigkeiten.json, je Version de/hu/en) – funktioniert also auch offline.
 * Gezeigt werden alle Einträge, die neuer sind als die zuletzt gesehene Version, höchstens bis zur installierten.
 * Frisch installiert: nichts zeigen. Erste Version mit dieser Meldung nach einem Update: die zwei neuesten Einträge.
 */
object Neuigkeiten {
    private const val VORLIEBEN = "kinolel_neuigkeiten"
    private const val GESEHEN = "gesehen"
    private const val DATEI = "neuigkeiten.json"
    private const val OHNE_VORGAENGER = 2

    fun zeigenFallsNeu(activity: AppCompatActivity) {
        val vorlieben = activity.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
        val aktuell = version(BuildConfig.VERSION_NAME)
        val gesehenText = vorlieben.getString(GESEHEN, null)
        if (gesehenText != null && vergleiche(version(gesehenText), aktuell) >= 0) return
        vorlieben.edit().putString(GESEHEN, aktuell.joinToString(".")).apply()

        // Frisch installiert (noch nie aktualisiert) -> keine Meldung
        val aktualisiert = gesehenText != null || runCatching {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)
        if (!aktualisiert) return

        val eintraege = runCatching { lesen(activity, Sprache.aktuell(activity)) }.onFailure { Log.w("KinoLelWuensche", "Neuigkeiten", it) }
            .getOrNull() ?: return
        val passend = eintraege
            .filter { (v, _) -> vergleiche(v, aktuell) <= 0 }
            .let { liste ->
                if (gesehenText != null) liste.filter { (v, _) -> vergleiche(v, version(gesehenText)) > 0 } else liste.take(OHNE_VORGAENGER)
            }
        if (passend.isEmpty() || activity.isFinishing) return

        val texte = activity.inKinoLelSprache
        val text = SpannableStringBuilder()
        passend.forEachIndexed { i, (v, punkte) ->
            if (passend.size > 1) {
                if (i > 0) text.append("\n")
                val start = text.length
                text.append(texte.getString(R.string.neu_version, v.joinToString(".")))
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, 0)
                text.append("\n")
            }
            punkte.forEach { text.append("• ").append(it).append("\n") }
        }
        AlertDialog.Builder(texte)
            .setTitle(texte.getString(R.string.neu_titel, if (Sprache.aktuell(activity) == "hu") "KinoLel Kívánságok" else BuildConfig.APP_NAME, aktuell.joinToString(".")))
            .setMessage(text.trimEnd('\n'))
            .setPositiveButton(R.string.neu_ok, null)
            .show()
    }

    /** Einträge (neueste zuerst): Version -> Stichpunkte in der Sprache (Ersatz: Deutsch) */
    private fun lesen(context: Context, sprache: String): List<Pair<List<Int>, List<String>>> {
        val json = context.assets.open(DATEI).bufferedReader().use { it.readText() }
        val versionen = JSONObject(json).getJSONArray("versionen")
        return (0 until versionen.length()).map { i ->
            val eintrag = versionen.getJSONObject(i)
            val punkte = (eintrag.optJSONArray(sprache) ?: eintrag.getJSONArray("de"))
            version(eintrag.getString("version")) to (0 until punkte.length()).map { punkte.getString(it) }
        }.sortedWith { a, b -> vergleiche(b.first, a.first) }
    }

    private fun version(text: String): List<Int> =
        text.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    private fun vergleiche(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val unterschied = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (unterschied != 0) return unterschied
        }
        return 0
    }
}
