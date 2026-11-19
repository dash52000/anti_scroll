package fr.dash.chevet.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import fr.dash.chevet.model.Concept
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Une carte aimée. [carte] : numéro de la carte ; -1 pour la phrase à retenir, -2 pour la question. */
@Serializable
data class CoeurCarte(
    val concept: String,
    val conceptTitre: String,
    val theme: String,
    val carte: Int,
    val texte: String,
    val le: String,
)

/**
 * Les cœurs posés sur les cartes, gardés sur le téléphone. Le concept hérite du cœur de ses cartes :
 * il passe en tête des révisions et nourrit plus souvent les portes « prolongement ».
 * L'état est observable par Compose : les icônes se remplissent partout à la fois.
 */
class Coeurs(context: Context) {

    private val prefs = context.getSharedPreferences("coeurs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serialiseur = ListSerializer(CoeurCarte.serializer())
    private val etat = mutableStateOf(lire())

    private fun lire(): List<CoeurCarte> =
        prefs.getString(CLE, null)
            ?.let { runCatching { json.decodeFromString(serialiseur, it) }.getOrNull() }
            ?: emptyList()

    /** Les plus récents d'abord. */
    val tous: List<CoeurCarte> get() = etat.value.asReversed()

    fun aime(concept: String, carte: Int): Boolean = etat.value.any { it.concept == concept && it.carte == carte }

    fun conceptAime(concept: String): Boolean = etat.value.any { it.concept == concept }

    fun conceptsAimes(): Set<String> = etat.value.map { it.concept }.toSet()

    /** Pose ou retire le cœur d'une carte ; renvoie vrai si la carte est maintenant aimée. */
    fun basculer(concept: Concept, carte: Int, texte: String): Boolean {
        val deja = aime(concept.id, carte)
        val nouvelle = if (deja) {
            etat.value.filterNot { it.concept == concept.id && it.carte == carte }
        } else {
            etat.value + CoeurCarte(concept.id, concept.titre, concept.theme, carte, texte, LocalDate.now().toString())
        }
        etat.value = nouvelle
        prefs.edit().putString(CLE, json.encodeToString(serialiseur, nouvelle)).apply()
        return !deja
    }

    private companion object {
        const val CLE = "coeurs"
    }
}
