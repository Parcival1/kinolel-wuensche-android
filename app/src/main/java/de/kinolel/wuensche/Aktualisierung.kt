package de.kinolel.wuensche

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * KinoLel Wünsche: Auto-Update (wie in der KinoLel-App, Lel 16.10.).
 *
 * Beim Start (höchstens alle 6 Stunden) die GitHub-Releases von [BuildConfig.UPDATE_REPO] abfragen. Gibt es eine neuere
 * Version, fragt ein Dialog nach; „Jetzt aktualisieren“ lädt die APK und öffnet den Installationsdialog von Android.
 * Fehlt die Erlaubnis „Unbekannte Apps installieren“, wird sie erklärt und die passende Einstellungsseite geöffnet;
 * danach geht es in [fortsetzen] (onResume) automatisch weiter. Die APK ist mit demselben Schlüssel signiert,
 * Android installiert sie daher als Update über die vorhandene App.
 */
object Aktualisierung {
    private const val VORLIEBEN = "kinolel_update"
    private const val ABSTAND_MS = 6L * 60 * 60 * 1000
    private var ausstehend: File? = null

    private data class Neu(val version: String, val url: String)

    fun pruefen(activity: AppCompatActivity, sofort: Boolean = false) {
        activity.lifecycleScope.launch {
            val vorlieben = activity.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
            val jetzt = System.currentTimeMillis()
            if (!sofort && jetzt - vorlieben.getLong("zuletzt", 0) < ABSTAND_MS) return@launch
            vorlieben.edit().putLong("zuletzt", jetzt).apply()

            val neu = withContext(Dispatchers.IO) { runCatching { suchen() }.getOrNull() } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_titel)
                .setMessage(activity.getString(R.string.update_text, BuildConfig.APP_NAME, neu.version))
                .setPositiveButton(R.string.update_jetzt) { _, _ -> laden(activity, neu) }
                .setNegativeButton(R.string.update_spaeter, null)
                .show()
        }
    }

    /** Aus onResume: nach dem Erteilen der Installations-Erlaubnis weitermachen */
    fun fortsetzen(activity: AppCompatActivity) {
        val datei = ausstehend ?: return
        if (darfInstallieren(activity)) {
            ausstehend = null
            installieren(activity, datei)
        }
    }

    private fun suchen(): Neu? {
        val verbindung = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=10")
            .openConnection() as HttpURLConnection
        verbindung.setRequestProperty("User-Agent", "${BuildConfig.APP_NAME}-Android/${BuildConfig.VERSION_NAME}")
        verbindung.setRequestProperty("Accept", "application/vnd.github+json")
        verbindung.connectTimeout = 15_000
        verbindung.readTimeout = 15_000
        val text = verbindung.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(text)
        var besteVersion = version(BuildConfig.VERSION_NAME)
        var beste: Neu? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val v = version(release.optString("tag_name"))
            if (vergleiche(v, besteVersion) <= 0) continue
            val anhaenge = release.optJSONArray("assets") ?: continue
            for (j in 0 until anhaenge.length()) {
                val anhang = anhaenge.getJSONObject(j)
                if (anhang.optString("name").endsWith(".apk")) {
                    beste = Neu(v.joinToString("."), anhang.optString("browser_download_url"))
                    besteVersion = v
                }
            }
        }
        return beste
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

    private fun laden(activity: AppCompatActivity, neu: Neu) {
        val fortschritt = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(64, 32, 64, 16)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_laedt)
            .setView(fortschritt)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val datei = withContext(Dispatchers.IO) {
                runCatching {
                    val ordner = File(activity.cacheDir, "updates").apply {
                        mkdirs()
                        listFiles()?.forEach { it.delete() }
                    }
                    val ziel = File(ordner, "update-${neu.version}.apk")
                    val verbindung = URL(neu.url).openConnection() as HttpURLConnection
                    verbindung.instanceFollowRedirects = true
                    verbindung.setRequestProperty("User-Agent", "${BuildConfig.APP_NAME}-Android/${BuildConfig.VERSION_NAME}")
                    val gesamt = verbindung.contentLengthLong
                    verbindung.inputStream.use { eingang ->
                        ziel.outputStream().use { ausgang ->
                            val puffer = ByteArray(64 * 1024)
                            var gelesen = 0L
                            while (true) {
                                val n = eingang.read(puffer)
                                if (n < 0) break
                                ausgang.write(puffer, 0, n)
                                gelesen += n
                                if (gesamt > 0) activity.runOnUiThread { fortschritt.progress = (gelesen * 100 / gesamt).toInt() }
                            }
                        }
                    }
                    ziel
                }.getOrNull()
            }
            dialog.dismiss()
            if (datei == null || datei.length() < 1_000_000) {
                Toast.makeText(activity, R.string.update_fehler, Toast.LENGTH_LONG).show()
                return@launch
            }
            installieren(activity, datei)
        }
    }

    private fun darfInstallieren(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun installieren(activity: AppCompatActivity, datei: File) {
        if (!darfInstallieren(activity)) {
            ausstehend = datei
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_erlaubnis_titel)
                .setMessage(activity.getString(R.string.update_erlaubnis_text, BuildConfig.APP_NAME))
                .setPositiveButton(R.string.update_erlaubnis_oeffnen) { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                    )
                }
                .setNegativeButton(R.string.update_spaeter) { _, _ -> ausstehend = null }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", datei)
        val absicht = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(absicht)
    }
}

/** Eigene Unterklasse, damit es keinen Konflikt mit FileProvidern aus Bibliotheken gibt */
class UpdateDateiProvider : FileProvider()
