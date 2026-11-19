package fr.dash.chevet

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.dash.chevet.data.Progression
import fr.dash.chevet.data.Soiree
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * La notif du soir : une seule, une heure avant le coucher, jamais un reproche.
 * Programmée sans alarme exacte (fenêtre de 10 minutes), reprogrammée à chaque envoi,
 * à chaque ouverture de l'appli et au redémarrage du téléphone.
 */
object Rappel {

    private const val CANAL = "soir"
    private const val ID_NOTIF = 1

    fun creerCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Notif du soir", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Une heure avant le coucher, le teaser du lot du soir."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun intentionAlarme(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RappelRecepteur::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Programme la prochaine notif, ou l'annule si elle est coupée dans les réglages. */
    fun programmer(context: Context) {
        val alarmes = context.getSystemService(AlarmManager::class.java) ?: return
        val intention = intentionAlarme(context)
        val soiree = Soiree(context)
        if (!soiree.notifActive) {
            alarmes.cancel(intention)
            return
        }
        val minutes = soiree.minutesNotif()
        val maintenant = LocalDateTime.now()
        var quand = LocalDate.now().atTime(minutes / 60, minutes % 60)
        if (!quand.isAfter(maintenant.plusMinutes(1))) quand = quand.plusDays(1)
        val instant = quand.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmes.setWindow(AlarmManager.RTC_WAKEUP, instant, 10 * 60_000L, intention)
    }

    fun autorisee(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Envoie la notif maintenant. [test] : sans toucher au compte des notifs ignorées. */
    fun notifier(context: Context, test: Boolean = false) {
        if (!autorisee(context)) return
        val soiree = Soiree(context)
        if (!test) soiree.noterEnvoi()
        val ouvrir = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val texte = soiree.texteNotif()
        val titre = soiree.declencheur.ifBlank { "Chevet" }
        val notif = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_NOTIF, notif)
        } catch (_: SecurityException) {
            // Permission retirée entre-temps : rien à faire.
        }
    }
}

/** Reçoit l'alarme du soir et le redémarrage du téléphone. */
class RappelRecepteur : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            // Lot déjà fini ce soir : pas de notif.
            if (Soiree(context).notifActive && !Progression(context).lotConsomme()) {
                Rappel.notifier(context)
            }
        }
        Rappel.programmer(context)
    }
}
