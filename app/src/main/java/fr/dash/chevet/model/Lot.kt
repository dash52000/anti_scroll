package fr.dash.chevet.model

import kotlinx.serialization.Serializable

/** Le lot du jour : préparé le matin, consommé le soir, remplacé le lendemain (zéro dette). */
@Serializable
data class Lot(
    val jour: String,
    val portes: List<Porte>,
    val pepite: Carte,
    val teaserDemain: String,
)

/** Une porte ouvre sur un tunnel du terrier : 3 concepts dans le vrai lot. */
@Serializable
data class Porte(
    val id: String,
    val titre: String,
    val accroche: String,
    val mystere: Boolean = false,
    val indice: String? = null,
    val concepts: List<Concept>,
    /** graine, actu, terrier, curiosite, saut, ephemeride, reprise. */
    val type: String? = null,
    /** « Reprendre là où tu t'es arrêté » : en tête des portes, hors plafond. */
    val reprise: Boolean = false,
    val ephemeride: Ephemeride? = null,
)

/** Une porte qui part d'un événement survenu ce jour-là. */
@Serializable
data class Ephemeride(
    val annee: Int,
    val evenement: String = "",
)

@Serializable
data class Concept(
    val id: String,
    val titre: String,
    val theme: String,
    /** La question qui ouvre le concept. */
    val question: String,
    /** 2 à 5 cartes d'environ 40 mots. */
    val cartes: List<Carte>,
    val aRetenir: String,
    val aRaconter: String,
    val ordreDeGrandeur: String? = null,
    val source: Source,
    /** QCM servi plus tard, en révision. */
    val qcm: Qcm,
    /** Questions suggérées pour « creuser ». */
    val creuser: List<String> = emptyList(),
    /** « L'autre camp répondrait » : la meilleure objection que rapporte l'article. */
    val contrePoint: Carte? = null,
    /** Rappel d'un concept vu il y a plus d'une semaine, que l'article relie à celui-ci. */
    val pont: Pont? = null,
    /** Crans : « plus_loin » ou « a_cote », le concept d'où ils partent, et leur profondeur. */
    val sens: String? = null,
    val depuis: String? = null,
    val depuisTitre: String? = null,
    val profondeur: Int = 0,
) {
    val estCran: Boolean get() = sens != null && depuis != null
}

/** Une carte, avec la phrase de l'article qui la prouve et le paragraphe autour (preuve en un tap). */
@Serializable
data class Carte(
    val texte: String,
    val format: String = "texte",
    val preuve: String? = null,
    val contexte: String? = null,
)

@Serializable
data class Pont(
    val texte: String,
    val avecId: String,
    val avecTitre: String,
    /** Date (AAAA-MM-JJ) où le concept rappelé a été découvert. */
    val avecLe: String? = null,
    val preuve: String? = null,
    val contexte: String? = null,
) {
    fun commeCarte() = Carte(texte = texte, preuve = preuve, contexte = contexte)
}

@Serializable
data class Source(
    val titre: String,
    val url: String,
)

@Serializable
data class Qcm(
    val question: String,
    val choix: List<String>,
    val bonne: Int,
    /** Réexplication sous un autre angle si la réponse est fausse. */
    val autreAngle: String,
)

/** Le titre et l'accroche d'un cran, connus dès sa composition (pour les languettes). */
@Serializable
data class TitreCran(
    val titre: String,
    val accroche: String = "",
)

/** Les deux sorties d'un concept : « plus loin » et « à côté ». */
object Sens {
    const val PLUS_LOIN = "plus_loin"
    const val A_COTE = "a_cote"
}
