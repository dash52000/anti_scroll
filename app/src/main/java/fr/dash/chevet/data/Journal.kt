package fr.dash.chevet.data

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * Journal d'utilisation discret : une ligne JSON par événement, jamais affiché dans l'appli.
 * Il sera synchronisé vers la table `journal` de Supabase pour être analysé en R.
 */
class Journal(context: Context) {

    private val fichier = File(context.filesDir, "journal.jsonl")

    fun note(evenement: String, vararg details: Pair<String, Any?>) {
        val ligne = buildJsonObject {
            put("horodatage", Instant.now().toString())
            put("evenement", evenement)
            details.forEach { (cle, valeur) ->
                when (valeur) {
                    null -> Unit
                    is Number -> put(cle, valeur)
                    is Boolean -> put(cle, valeur)
                    else -> put(cle, valeur.toString())
                }
            }
        }
        runCatching { fichier.appendText(ligne.toString() + "\n") }
    }
}
