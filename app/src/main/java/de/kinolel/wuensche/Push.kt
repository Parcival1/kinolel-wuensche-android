package de.kinolel.wuensche

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * KinoLel (Lel 26.10.: Benachrichtigungen sofort statt alle 15 Minuten) – Push über Firebase Cloud Messaging.
 *
 * Seerr meldet „genehmigt/verfügbar/abgelehnt“ an den Dienst kinolel-push auf DockerSZ; der schickt eine inhaltslose
 * Nachricht („pruefen“) an das Thema der Person: kl-<Jellyfin-Benutzer-ID> (aus /api/v1/auth/me der eigenen Seite,
 * siehe [Benachrichtigungen]). Die App prüft dann sofort selbst – dieselben Regeln und Texte wie die 15-Minuten-Prüfung,
 * die als Rückfall bleibt. Firebase-Werte kommen beim Bauen aus google-services.json (ohne Datei: kein Push).
 */
object Push {
    private const val VORLIEBEN = "kinolel_wuensche_push"

    fun bereit() = BuildConfig.FB_APP_ID.isNotEmpty()

    /** Firebase von Hand starten (kein google-services-Plugin); true, wenn Push verfügbar ist */
    fun starten(context: Context): Boolean {
        if (!bereit()) return false
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context.applicationContext,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FB_APP_ID)
                        .setApiKey(BuildConfig.FB_API_KEY)
                        .setProjectId(BuildConfig.FB_PROJEKT)
                        .setGcmSenderId(BuildConfig.FB_SENDER)
                        .build()
                )
            }
            true
        }.onFailure { Log.w("KinoLelWuensche", "Push: Firebase nicht gestartet", it) }.getOrDefault(false)
    }

    /** Themen-Abos auf [gewuenscht] bringen (neue abonnieren, alte abbestellen) */
    fun themenAbgleichen(context: Context, gewuenscht: Set<String>) {
        if (!starten(context)) return
        val v = context.getSharedPreferences(VORLIEBEN, Context.MODE_PRIVATE)
        val bisher = v.getStringSet("themen", emptySet()) ?: emptySet()
        val fcm = FirebaseMessaging.getInstance()
        (gewuenscht - bisher).forEach { thema ->
            fcm.subscribeToTopic(thema).addOnCompleteListener { t ->
                Log.i("KinoLelWuensche", "Push: Thema ${thema.take(10)}… " + if (t.isSuccessful) "abonniert" else "nicht abonniert (${t.exception?.message})")
                if (!t.isSuccessful) v.edit().putStringSet("themen", (v.getStringSet("themen", emptySet()) ?: emptySet()) - thema).apply()
            }
        }
        (bisher - gewuenscht).forEach { thema ->
            fcm.unsubscribeFromTopic(thema).addOnCompleteListener { Log.i("KinoLelWuensche", "Push: Thema ${thema.take(10)}… abbestellt") }
        }
        if (gewuenscht != bisher) v.edit().putStringSet("themen", gewuenscht).apply()
    }
}

/** Empfängt die inhaltslose Push-Nachricht und startet sofort eine Prüfung (beschleunigter Job, auch im Ruhezustand) */
class PushEmpfang : FirebaseMessagingService() {
    override fun onMessageReceived(nachricht: RemoteMessage) {
        if (nachricht.data["kinolel"] != "pruefen") return
        Log.i("KinoLelWuensche", "Push empfangen (${nachricht.data["typ"]}, ${nachricht.data["seite"]}) – prüfe sofort")
        Benachrichtigungen.sofortPruefen(applicationContext)
    }

    override fun onNewToken(token: String) {
        // Themen-Abos hängen an der Firebase-Installation; nach neuem Token beim nächsten Lauf neu abgleichen
        getSharedPreferences("kinolel_wuensche_push", MODE_PRIVATE).edit().remove("themen").apply()
    }
}
