package de.kinolel.wuensche

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
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
    private var fragtNachBenachrichtigungen = false

    // Android 13+: Erlaubnis für Benachrichtigungen (einmal, nach freundlicher Frage)
    private val erlaubnis = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) {
            Benachrichtigungen.setzen(this, false)
        } else {
            Benachrichtigungen.planen(this)
            Benachrichtigungen.pruefen(this, vonHand = true) {}
        }
    }

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
        // einmal nach einem Update: was ist neu – in der Sprache der Wunschseite (Lel 25.10.), daher erst, wenn sie bekannt ist
        Sprache.wennBekannt { if (!isDestroyed) Neuigkeiten.zeigenFallsNeu(this) { benachrichtigungenFragen() } }
        // Lel 25.10.: Benachrichtigungen, wenn ein Wunsch genehmigt oder verfügbar ist
        Benachrichtigungen.kanalAnlegen(this)
        Benachrichtigungen.planen(this)
        Sprache.beiAnmeldung = { if (!isDestroyed) benachrichtigungenFragen() }
        testPruefen(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        oeffneLink(intent)
        testPruefen(intent)
    }

    /** Einmal fragen, sobald man angemeldet ist (nicht über „Was ist neu“ oder einen anderen Dialog) */
    private fun benachrichtigungenFragen() {
        if (fragtNachBenachrichtigungen || Benachrichtigungen.entschieden(this) || Sprache.angemeldeteServer(this).isEmpty() ||
            isFinishing || isDestroyed) return
        fragtNachBenachrichtigungen = true
        val texte = inKinoLelSprache
        AlertDialog.Builder(texte)
            .setTitle(R.string.benachrichtigung_frage_titel)
            .setMessage(R.string.benachrichtigung_frage_text)
            .setPositiveButton(R.string.benachrichtigung_ja) { _, _ -> benachrichtigungenEinschalten() }
            .setNegativeButton(R.string.benachrichtigung_nein) { _, _ -> Benachrichtigungen.setzen(this, false) }
            .setOnDismissListener { fragtNachBenachrichtigungen = false }
            .show()
    }

    private fun benachrichtigungenEinschalten() {
        Benachrichtigungen.setzen(this, true)
        if (!Benachrichtigungen.erlaubt(this) && Build.VERSION.SDK_INT >= 33) {
            erlaubnis.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Benachrichtigungen.pruefen(this, vonHand = true) {}
        }
    }

    /** Nur Debug-Version (Rauchtest): sofort prüfen, Anfrage-Stände aus files/test_anfragen.json */
    private fun testPruefen(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(Benachrichtigungen.TEST_EXTRA, false) == true) {
            Benachrichtigungen.pruefen(this) { Log.i("KinoLelWuensche", "Test-Prüfung fertig") }
        }
    }

    override fun onResume() {
        super.onResume()
        Aktualisierung.fortsetzen(this)
        Benachrichtigungen.beimOeffnen(this) // v1.3: auch beim Öffnen nachsehen (Hintergrund kann sich verspäten)
        // KinoLel (Lel 22.10.2026): bei jedem Öffnen/Zurückkehren nach Updates suchen (Hinweis in der Sprache der Wunschseite)
        Sprache.wennBekannt { if (!isDestroyed) Aktualisierung.pruefen(this) }
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
            Sprache.serverGewechselt()
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
        Sprache.serverGewechselt()
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
        // Lel 25.10.: „Mache das Logo der App an sich in der App anklickbar, sodass man darunter nach z. B. Updates suchen
        // kann und die aktuelle Version angezeigt wird.“ – Logo und Name öffnen „Version und Updates“
        leiste.addView(ImageView(this).apply {
            setImageResource(R.drawable.leiste_logo)
            adjustViewBounds = true
            contentDescription = inKinoLelSprache.getString(R.string.info_oeffnen)
            setOnClickListener { infoZeigen() }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        titel = TextView(this).apply {
            setOnClickListener { infoZeigen() }
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

    /** Version, „Nach Updates suchen“, „Was ist neu“ – in der Sprache der Wunschseite */
    private fun infoZeigen() {
        val texte = inKinoLelSprache
        val name = if (Sprache.aktuell(this) == "hu") "KinoLel Kívánságok" else BuildConfig.APP_NAME
        val inhalt = LinearLayout(texte).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(texte).apply {
                text = texte.getString(R.string.info_version, BuildConfig.VERSION_NAME)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(SwitchCompat(texte).apply {
                text = texte.getString(R.string.benachrichtigung_schalter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                isChecked = Benachrichtigungen.an(this@MainActivity) && Benachrichtigungen.erlaubt(this@MainActivity)
                setOnCheckedChangeListener { _, an ->
                    if (an) benachrichtigungenEinschalten() else Benachrichtigungen.setzen(this@MainActivity, false)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            // Lel 26.10.: Diagnose – wann zuletzt nachgesehen, sofort prüfen, Akku-Hinweis
            if (Benachrichtigungen.an(this@MainActivity)) {
                val diagnose = TextView(texte).apply {
                    text = Benachrichtigungen.letztePruefung(this@MainActivity, texte)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    alpha = 0.8f
                }
                addView(diagnose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                addView(TextView(texte).apply {
                    text = texte.getString(R.string.benachrichtigung_jetzt)
                    setTextColor(farbe(R.color.lavendel))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(0, dp(10), 0, dp(4))
                    setOnClickListener {
                        diagnose.text = texte.getString(R.string.benachrichtigung_prueft)
                        Benachrichtigungen.pruefen(this@MainActivity, vonHand = true) {
                            diagnose.text = Benachrichtigungen.letztePruefung(this@MainActivity, texte)
                        }
                    }
                })
                if (!Benachrichtigungen.akkuFrei(this@MainActivity)) {
                    addView(TextView(texte).apply {
                        text = texte.getString(R.string.benachrichtigung_akku)
                        setTextColor(farbe(R.color.lavendel))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(8), 0, 0)
                        setOnClickListener {
                            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                        }
                    })
                }
            }
        }
        AlertDialog.Builder(texte)
            .setTitle(name)
            .setView(inhalt)
            .setPositiveButton(R.string.update_suchen) { _, _ -> Aktualisierung.pruefen(this, vonHand = true) }
            .setNeutralButton(R.string.neu_anzeigen) { _, _ -> Neuigkeiten.erneutZeigen(this) }
            .setNegativeButton(R.string.schliessen, null)
            .show()
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
                    Sprache.serverGewechselt()
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

        override fun onPageFinished(view: WebView, url: String) {
            Sprache.ausSeite(view)
        }

        override fun onReceivedError(view: WebView, anfrage: WebResourceRequest, error: WebResourceError) {
            if (anfrage.isForMainFrame) fehler.visibility = View.VISIBLE
        }
    }

    private fun farbe(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(wert: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wert.toFloat(), resources.displayMetrics).toInt()
}
