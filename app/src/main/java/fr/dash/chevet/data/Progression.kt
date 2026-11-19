package fr.dash.chevet.data

import android.content.Context
import fr.dash.chevet.model.Porte
import fr.dash.chevet.model.Concept
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Un concept du terrier, avec son état de répétition espacée. */
@Serializable
data class ConceptVu(
    val concept: Concept,
    val vuLe: String,
    val echeance: String,
    val intervalle: Int = 1,
    /** Porte d'où vient le concept et sa place dans le tunnel : ce sont les liens du graphe. */
    val porte: String? = null,
    val porteTitre: String? = null,
    val rang: Int = 0,
    /** « Prolonger demain », demandé depuis le graphe, éventuellement vers un voisin précis. */
    val prolonger: Boolean = false,
    val prolongerVers: String? = null,
) {
    /** Trois révisions réussies d'affilée (intervalle de 8 jours ou plus) : un fruit sur l'arbre. */
    val maitrise: Boolean get() = intervalle >= 8

    val qcmValide: Boolean get() = concept.qcm.choix.size >= 2 && concept.qcm.bonne in concept.qcm.choix.indices

    fun du(aujourdhui: LocalDate = LocalDate.now()): Boolean = !LocalDate.parse(echeance).isAfter(aujourdhui)
}

/** Terrier, révisions et portes ouvertes du soir, stockés sur le téléphone. */
class Progression(context: Context) {

    private val prefs = context.getSharedPreferences("progression", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serialiseur = ListSerializer(ConceptVu.serializer())

    private fun lire(): List<ConceptVu> =
        prefs.getString(CLE_CONCEPTS, null)
            ?.let { runCatching { json.decodeFromString(serialiseur, it) }.getOrNull() }
            ?: emptyList()

    private fun ecrire(liste: List<ConceptVu>) {
        prefs.edit().putString(CLE_CONCEPTS, json.encodeToString(serialiseur, liste)).apply()
    }

    /** Tout le terrier, dans l'ordre de découverte. */
    fun tous(): List<ConceptVu> = lire()

    fun nombreDeConcepts(): Int = lire().size

    /** Les révisions dues aujourd'hui, 5 au maximum, les concepts aimés d'abord. */
    fun revisionsDues(aime: (String) -> Boolean = { false }, aujourdhui: LocalDate = LocalDate.now()): List<ConceptVu> =
        lire()
            .filter { it.du(aujourdhui) && it.qcmValide }
            .sortedByDescending { aime(it.concept.id) }
            .take(5)

    /** Révisions libres, hors soirée : les aimés d'abord, puis les dues, puis les plus proches de l'échéance. */
    fun aReviser(aime: (String) -> Boolean = { false }, aujourdhui: LocalDate = LocalDate.now()): List<ConceptVu> =
        lire()
            .filter { it.qcmValide }
            .sortedWith(
                compareByDescending<ConceptVu> { aime(it.concept.id) }
                    .thenByDescending { it.du(aujourdhui) }
                    .thenBy { it.echeance },
            )

    fun marquerVus(porte: Porte, aujourdhui: LocalDate = LocalDate.now()) {
        val parId = lire().associateBy { it.concept.id }.toMutableMap()
        porte.concepts.forEachIndexed { rang, c ->
            if (c.id !in parId) {
                parId[c.id] = ConceptVu(
                    concept = c,
                    vuLe = aujourdhui.toString(),
                    echeance = aujourdhui.plusDays(1).toString(),
                    porte = porte.id,
                    porteTitre = porte.titre,
                    rang = rang,
                )
            }
        }
        ecrire(parId.values.toList())
    }

    /** Réussi : l'intervalle double. Raté : le concept revient dès le lendemain. */
    fun resultatRevision(id: String, reussi: Boolean, aujourdhui: LocalDate = LocalDate.now()) {
        ecrire(lire().map { vu ->
            if (vu.concept.id != id) {
                vu
            } else {
                val intervalle = if (reussi) (vu.intervalle * 2).coerceAtMost(60) else 1
                vu.copy(intervalle = intervalle, echeance = aujourdhui.plusDays(intervalle.toLong()).toString())
            }
        })
    }

    /**
     * Révision libre : un QCM réussi ne compte que s'il était dû (pas de raccourci vers les fruits),
     * un QCM raté ramène le concept dès le lendemain.
     */
    fun resultatRevisionLibre(id: String, reussi: Boolean, aujourdhui: LocalDate = LocalDate.now()) {
        val vu = lire().firstOrNull { it.concept.id == id } ?: return
        if (!reussi || vu.du(aujourdhui)) resultatRevision(id, reussi, aujourdhui)
    }

    fun prolonger(id: String, actif: Boolean, vers: String? = null) {
        ecrire(lire().map { vu ->
            if (vu.concept.id == id) vu.copy(prolonger = actif, prolongerVers = if (actif) vers else null) else vu
        })
    }

    // ------------------------------------------------------------ Soirée

    /** Vrai dès qu'une porte a été lue jusqu'au bout aujourd'hui. */
    fun lotConsomme(jour: LocalDate = LocalDate.now()): Boolean =
        prefs.getString(CLE_LOT_CONSOMME, null) == jour.toString()

    fun marquerLotConsomme(jour: LocalDate = LocalDate.now()) {
        prefs.edit().putString(CLE_LOT_CONSOMME, jour.toString()).apply()
    }

    /** Les portes ouvertes aujourd'hui (3 au plus par soir). */
    fun portesOuvertes(jour: LocalDate = LocalDate.now()): Set<String> {
        val brut = prefs.getString(CLE_PORTES_OUVERTES, null) ?: return emptySet()
        val (date, ids) = brut.split('|', limit = 2).takeIf { it.size == 2 } ?: return emptySet()
        if (date != jour.toString()) return emptySet()
        return ids.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun ouvrirPorte(id: String, jour: LocalDate = LocalDate.now()) {
        val ouvertes = portesOuvertes(jour) + id
        prefs.edit().putString(CLE_PORTES_OUVERTES, "$jour|${ouvertes.joinToString(",")}").apply()
    }

    /** Bouton de test : rouvre le lot du jour et rend toutes les révisions dues. */
    fun reinitialiserPourTest() {
        prefs.edit().remove(CLE_LOT_CONSOMME).remove(CLE_PORTES_OUVERTES).apply()
        val aujourdhui = LocalDate.now().toString()
        ecrire(lire().map { it.copy(echeance = aujourdhui) })
    }

    private companion object {
        const val CLE_CONCEPTS = "concepts"
        const val CLE_LOT_CONSOMME = "lot_consomme"
        const val CLE_PORTES_OUVERTES = "portes_ouvertes"
    }
}
