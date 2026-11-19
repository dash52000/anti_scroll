package fr.dash.chevet.data

import android.content.Context
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
)

/** Terrier et révisions, stockés localement en attendant Supabase. */
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

    fun nombreDeConcepts(): Int = lire().size

    /** Les révisions dues aujourd'hui, 5 au maximum. */
    fun revisionsDues(aujourdhui: LocalDate = LocalDate.now()): List<ConceptVu> =
        lire()
            .filter { !LocalDate.parse(it.echeance).isAfter(aujourdhui) }
            .filter { it.concept.qcm.choix.size >= 2 && it.concept.qcm.bonne in it.concept.qcm.choix.indices }
            .take(5)

    fun marquerVus(concepts: List<Concept>, aujourdhui: LocalDate = LocalDate.now()) {
        val parId = lire().associateBy { it.concept.id }.toMutableMap()
        concepts.forEach { c ->
            if (c.id !in parId) {
                parId[c.id] = ConceptVu(c, aujourdhui.toString(), aujourdhui.plusDays(1).toString())
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

    fun lotConsomme(jour: LocalDate = LocalDate.now()): Boolean =
        prefs.getString(CLE_LOT_CONSOMME, null) == jour.toString()

    fun marquerLotConsomme(jour: LocalDate = LocalDate.now()) {
        prefs.edit().putString(CLE_LOT_CONSOMME, jour.toString()).apply()
    }

    /** Bouton de test : rouvre le lot du jour et rend toutes les révisions dues. */
    fun reinitialiserPourTest() {
        prefs.edit().remove(CLE_LOT_CONSOMME).apply()
        val aujourdhui = LocalDate.now().toString()
        ecrire(lire().map { it.copy(echeance = aujourdhui) })
    }

    private companion object {
        const val CLE_CONCEPTS = "concepts"
        const val CLE_LOT_CONSOMME = "lot_consomme"
    }
}
