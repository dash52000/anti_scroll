package fr.dash.chevet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.LotExemple
import fr.dash.chevet.data.Progression
import fr.dash.chevet.ui.ChevetApp
import fr.dash.chevet.ui.ChevetTheme
import java.time.LocalTime

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        baisserLuminositeLeSoir()

        val lot = LotExemple(this).lotDuJour()
        val progression = Progression(this)
        val journal = Journal(this)
        journal.note("ouverture", "lot" to lot.jour)

        setContent {
            ChevetTheme {
                ChevetApp(
                    lot = lot,
                    progression = progression,
                    journal = journal,
                    onPartager = ::partager,
                    onRejouer = { recreate() },
                    onBonneNuit = { finish() },
                )
            }
        }
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
