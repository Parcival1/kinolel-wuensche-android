package de.kinolel.wuensche

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * KinoLel Wünsche: App-Hülle für Seerr (KinoLel Wünsche / KinoLel Kívánságok).
 *
 * - Oben eine schmale Leiste: Logo, Name, Umschalter Deutsch/Magyar (Wahl wird gemerkt), Neu laden.
 * - Darunter die Seerr-Oberfläche in voller Funktion (Anmeldung mit den Jellyfin-/KinoLel-Zugangsdaten, Anfragen,
 *   Probleme melden …); die Anmeldung bleibt gespeichert.
 * - Links auf wuensche.kinolel.de / kivansagok.kinolel.de (z. B. aus Jellyfin) öffnen direkt diese App (App-Links).
 * - Fremde Links (Jellyfin, TMDB, Trailer …) öffnen in der passenden App bzw. im Browser.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var knopfDe: TextView
    private lateinit var knopfHu: TextView
    private lateinit var titel: TextView
    private lateinit var lade: ProgressBar
    private lateinit var fehler: LinearLayout

    private val vorlieben by lazy { getSharedPreferences("kinolel_wuensche", Context.MODE_PRIVATE) }

    private var server: String
        get() = vorlieben.getString("server", null) ?: standardServer()
        set(wert) = vorlieben.edit().putString("server", wert).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(oberflaeche())
        aktualisiereLeiste()

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else if (!oeffneLink(intent)) {
            web.loadUrl(server)
        }
        Neuigkeiten.zeigenFallsNeu(this)   // einmal nach einem Update: was ist neu
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        oeffneLink(intent)
    }

    override fun onResume() {
        super.onResume()
        Aktualisierung.fortsetzen(this)
        // KinoLel (Lel 22.10.2026): bei jedem Öffnen/Zurückkehren nach Updates suchen
        Aktualisierung.pruefen(this)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** App-Link (https://wuensche… / https://kivansagok…): passenden Server wählen und die Seite öffnen */
    private fun oeffneLink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        val ziel = serverFuer(uri) ?: return false
        if (ziel != server) {
            server = ziel
            aktualisiereLeiste()
        }
        web.loadUrl(uri.toString())
        return true
    }

    private fun serverFuer(uri: Uri): String? = listOf(BuildConfig.SERVER_DE, BuildConfig.SERVER_HU)
        .firstOrNull { Uri.parse(it).host.equals(uri.host, ignoreCase = true) }

    private fun standardServer(): String =
        if (resources.configuration.locales[0].language == "hu") BuildConfig.SERVER_HU else BuildConfig.SERVER_DE

    private fun wechsle(ziel: String) {
        if (ziel == server && web.url?.startsWith(ziel) == true) return
        server = ziel
        aktualisiereLeiste()
        web.loadUrl(ziel)
    }

    private fun aktualisiereLeiste() {
        val hu = server == BuildConfig.SERVER_HU
        titel.text = if (hu) "KinoLel Kívánságok" else BuildConfig.APP_NAME
        for ((knopf, aktiv) in listOf(knopfDe to !hu, knopfHu to hu)) {
            knopf.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (aktiv) setColor(farbe(R.color.lavendel)) else setStroke(dp(1), farbe(R.color.lavendel))
            }
            knopf.setTextColor(if (aktiv) farbe(R.color.grund) else farbe(R.color.text))
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Oberfläche (ohne XML)

    @SuppressLint("SetJavaScriptEnabled")
    private fun oberflaeche(): View {
        val wurzel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(farbe(R.color.grund))
        }

        val leiste = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(farbe(R.color.grund))
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        leiste.addView(ImageView(this).apply {
            setImageResource(R.drawable.leiste_logo)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        titel = TextView(this).apply {
            setTextColor(farbe(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), 0, dp(8), 0)
            maxLines = 1
        }
        leiste.addView(titel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        knopfDe = umschalter(getString(R.string.server_de)) { wechsle(BuildConfig.SERVER_DE) }
        knopfHu = umschalter(getString(R.string.server_hu)) { wechsle(BuildConfig.SERVER_HU) }
        leiste.addView(knopfDe)
        leiste.addView(knopfHu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6)
        })
        leiste.addView(TextView(this).apply {
            text = "⟳"
            contentDescription = getString(R.string.neu_laden)
            setTextColor(farbe(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(14), 0, dp(6), 0)
            setOnClickListener { web.reload() }
        })
        wurzel.addView(leiste)

        lade = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        wurzel.addView(lade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val inhalt = FrameLayout(this)
        web = WebView(this).apply {
            setBackgroundColor(farbe(R.color.grund))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString + " KinoLelWuensche/" + BuildConfig.VERSION_NAME
            webViewClient = Steuerung()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, fortschritt: Int) {
                    lade.visibility = if (fortschritt < 100) View.VISIBLE else View.GONE
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        inhalt.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fehler = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(farbe(R.color.grund))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.nicht_erreichbar)
                setTextColor(farbe(R.color.text))
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.nochmal)
                setOnClickListener {
                    this@MainActivity.fehler.visibility = View.GONE
                    web.reload()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        inhalt.addView(fehler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wurzel.addView(inhalt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Android 15+: randlos – Statusleiste/Navigation/Tastatur als Abstand
        ViewCompat.setOnApplyWindowInsetsListener(wurzel) { v, insets ->
            val r = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(r.left, r.top, r.right, r.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return wurzel
    }

    private fun umschalter(text: String, beiKlick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        setOnClickListener { beiKlick() }
    }

    private inner class Steuerung : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, anfrage: WebResourceRequest): Boolean {
            val uri = anfrage.url
            val ziel = serverFuer(uri)
            if (ziel != null) {
                // Link auf den anderen Wunsch-Server -> umschalten und in der App bleiben
                if (ziel != server) {
                    server = ziel
                    aktualisiereLeiste()
                }
                return false
            }
            if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto" || uri.scheme == "tel") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    return false
                }
                return true
            }
            return true // unbekannte Schemata (intent:, market: …) nicht im WebView öffnen
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            fehler.visibility = View.GONE
        }

        override fun onReceivedError(view: WebView, anfrage: WebResourceRequest, error: WebResourceError) {
            if (anfrage.isForMainFrame) fehler.visibility = View.VISIBLE
        }
    }

    private fun farbe(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(wert: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wert.toFloat(), resources.displayMetrics).toInt()
}
