package fr.dash.chevet.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Une source d'actu. [ajoutee] : ajoutée depuis les réglages, donc supprimable. */
@Serializable
data class SourceRss(
    val nom: String,
    val url: String,
    val actif: Boolean = true,
    val ajoutee: Boolean = false,
)

/** Une rubrique de « Ce que Chevet sait de moi ». */
@Serializable
data class Rubrique(
    val titre: String,
    val puces: List<String> = emptyList(),
)

/** La partie réglable du profil, telle que la renvoie l'action « profil » de chevet-lot. */
@Serializable
data class Profil(
    val niveaux: Map<String, String> = emptyMap(),
    @SerialName("a_eviter") val aEviter: List<String> = emptyList(),
    @SerialName("sources_rss") val sources: List<SourceRss> = emptyList(),
    @SerialName("poids_themes") val poidsThemes: Map<String, Int> = emptyMap(),
    val fiche: List<Rubrique> = emptyList(),
    @SerialName("creusages_par_soir") val creusagesParSoir: Int = 3,
    val declencheur: String? = null,
    @SerialName("heure_coucher") val heureCoucher: String? = null,
) {
    /** Vrai si ce qui nourrit les lots a changé (pas la soirée ni le quota de « creuser »). */
    fun contenuDiffere(autre: Profil): Boolean =
        niveaux != autre.niveaux ||
            aEviter != autre.aEviter ||
            sources != autre.sources ||
            poidsThemes != autre.poidsThemes ||
            fiche != autre.fiche
}

/** Les trois crans de niveau par domaine. */
enum class Cran(val libelle: String, val mot: String) {
    Debutant("Débutant", "débutant"),
    Curieux("Curieux", "curieux éclairé"),
    Expert("Expert", "expert"),
}

/**
 * Les niveaux sont stockés en texte libre (« expert : inutile d'expliquer une régression »).
 * Le premier mot donne le cran, le reste est une précision gardée telle quelle.
 */
object Niveaux {

    private val prefixes = listOf(
        "curieux éclairé" to Cran.Curieux,
        "curieux" to Cran.Curieux,
        "expert" to Cran.Expert,
        "débutant" to Cran.Debutant,
        "debutant" to Cran.Debutant,
        "novice" to Cran.Debutant,
        "rouillé" to Cran.Debutant,
        "rouille" to Cran.Debutant,
    )

    private val libelles = mapOf(
        "stats_data" to "Stats et data",
        "maths_hors_stats" to "Maths (hors stats)",
        "sciences_dures" to "Sciences dures",
        "economie" to "Économie",
        "informatique_dure" to "Informatique « dure »",
        "defaut" to "Partout ailleurs",
    )

    fun libelle(cle: String): String =
        libelles[cle] ?: cle.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun cran(valeur: String): Cran {
        val v = valeur.trim().lowercase()
        return prefixes.firstOrNull { v.startsWith(it.first) }?.second ?: Cran.Curieux
    }

    fun precision(valeur: String): String {
        val v = valeur.trim()
        val prefixe = prefixes.firstOrNull { v.lowercase().startsWith(it.first) }?.first ?: return v
        return v.drop(prefixe.length).trimStart(' ', ':', ',', '-', '–').trim()
    }

    fun avecCran(valeur: String, cran: Cran): String {
        val precision = precision(valeur)
        return when {
            precision.isBlank() -> cran.mot
            precision.startsWith("(") -> "${cran.mot} $precision"
            else -> "${cran.mot} : $precision"
        }
    }
}
