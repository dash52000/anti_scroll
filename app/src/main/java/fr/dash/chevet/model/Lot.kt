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
)

@Serializable
data class Carte(
    val texte: String,
    val format: String = "texte",
)

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
