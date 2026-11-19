package fr.dash.chevet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.dash.chevet.data.Coeurs
import fr.dash.chevet.data.Distant
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.LotExemple
import fr.dash.chevet.data.Progression
import fr.dash.chevet.data.Soiree
import fr.dash.chevet.ui.ChevetTheme
import fr.dash.chevet.ui.Racine
import java.time.LocalTime

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        baisserLuminositeLeSoir()

        val progression = Progression(this)
        val coeurs = Coeurs(this)
        val journal = Journal(this)
        val distant = Distant(this)
        val soiree = Soiree(this)

        // Un partage depuis YouTube, Facebook ou le web arrive dans la boîte à curiosités.
        val partage = textePartage(intent)
        if (partage == null) {
            journal.note("ouverture")
            // L'heure de coucher s'apprend sur les ouvertures du soir ; la notif suit.
            soiree.noterOuverture()
        } else {
            journal.note("ouverture_partage")
        }
        Rappel.creerCanal(this)
        Rappel.programmer(this)

        setContent {
            ChevetTheme {
                Racine(
                    distant = distant,
                    progression = progression,
                    coeurs = coeurs,
                    journal = journal,
                    soiree = soiree,
                    lotExemple = { LotExemple(this).lotDuJour() },
                    partageEntrant = partage,
                    onPartager = ::partager,
                    onRejouer = { recreate() },
                    onFermer = { finish() },
                )
            }
        }
    }

    /** Le texte reçu par le menu de partage d'Android, ou null pour une ouverture normale. */
    private fun textePartage(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
        val sujet = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val texte = intent.getStringExtra(Intent.EXTRA_TEXT)
        return listOfNotNull(sujet, texte)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .ifBlank { null }
    }

    /** Le soir, l'écran baisse de lui-même. */
    private fun baisserLuminositeLeSoir() {
        val heure = LocalTime.now().hour
        if (heure >= 20 || heure < 6) {
            window.attributes = window.attributes.apply { screenBrightness = 0.3f }
        }
    }

    private fun partager(texte: String) {
        val envoi = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texte)
        }
        startActivity(Intent.createChooser(envoi, "Partager"))
    }
}
