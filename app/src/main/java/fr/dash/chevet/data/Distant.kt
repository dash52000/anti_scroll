package fr.dash.chevet.data

import android.content.Context
import fr.dash.chevet.model.Concept
import fr.dash.chevet.model.Lot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Accès à Supabase (projet partagé « Panier », tables chevet_*).
 * La clé ci-dessous est la clé publique : la RLS protège les données, et Groq n'est appelé
 * que par les fonctions serveur, jamais depuis l'appli.
 */
private const val SUPABASE_URL = "https://iolmjlqcvipzsihvrxlj.supabase.co"
private const val CLE_PUBLIQUE =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlvbG1qbHFjdmlwenNpaHZyeGxqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTMxNDgzNDMsImV4cCI6MjEwODcyNDM0M30.WVYQtlX69QpBYjsIUnH2srL0bSpavVM3dO9pH6mAv0I"

/** Avancement de la préparation du lot, pour l'écran d'attente. */
data class Preparation(val etape: Int, val total: Int)

/** D'où vient le lot affiché ce soir. */
data class LotCharge(val lot: Lot, val exemple: Boolean, val message: String? = null)

class Distant(context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val dossier = context.filesDir
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------ HTTP

    private fun requete(
        methode: String,
        chemin: String,
        corps: String? = null,
        jeton: String? = null,
        entetes: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val cnx = (URL(SUPABASE_URL + chemin).openConnection() as HttpURLConnection).apply {
            requestMethod = methode
            connectTimeout = 15_000
            readTimeout = 170_000
            setRequestProperty("apikey", CLE_PUBLIQUE)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${jeton ?: CLE_PUBLIQUE}")
            entetes.forEach { (cle, valeur) -> setRequestProperty(cle, valeur) }
        }
        try {
            if (corps != null) {
                cnx.doOutput = true
                cnx.outputStream.use { it.write(corps.toByteArray()) }
            }
            val code = cnx.responseCode
            val flux = if (code in 200..299) cnx.inputStream else cnx.errorStream
            val texte = flux?.bufferedReader()?.use { it.readText() } ?: ""
            return code to texte
        } finally {
            cnx.disconnect()
        }
    }

    private fun objet(texte: String): JsonObject? =
        runCatching { json.parseToJsonElement(texte).jsonObject }.getOrNull()

    // ------------------------------------------------------------ Session anonyme

    private fun memoriserSession(o: JsonObject) {
        val maintenant = System.currentTimeMillis() / 1000
        val expire = o["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (maintenant + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
        prefs.edit()
            .putString("acces", o["access_token"]?.jsonPrimitive?.contentOrNull)
            .putString("rafraichir", o["refresh_token"]?.jsonPrimitive?.contentOrNull)
            .putLong("expire", expire)
            .putString("utilisateur", o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull)
            .apply()
    }

    /** Renvoie un jeton valide : connexion anonyme au premier lancement, puis rafraîchissement. */
    private fun jeton(): String {
        val acces = prefs.getString("acces", null)
        val expire = prefs.getLong("expire", 0)
        if (acces != null && expire - 60 > System.currentTimeMillis() / 1000) return acces

        val rafraichir = prefs.getString("rafraichir", null)
        if (rafraichir != null) {
            val (code, texte) = requete(
                "POST",
                "/auth/v1/token?grant_type=refresh_token",
                buildJsonObject { put("refresh_token", rafraichir) }.toString(),
            )
            val o = objet(texte)
            if (code in 200..299 && o != null) {
                memoriserSession(o)
                return prefs.getString("acces", null)!!
            }
        }

        val (code, texte) = requete("POST", "/auth/v1/signup", """{"data":{}}""")
        val o = objet(texte)
        check(code in 200..299 && o != null) { "Connexion impossible ($code)" }
        memoriserSession(o)
        return prefs.getString("acces", null)!!
    }

    private fun utilisateur(): String? = prefs.getString("utilisateur", null)

    // ------------------------------------------------------------ Lot du jour

    private fun cache(jour: LocalDate) = File(dossier, "lot_$jour.json")

    /**
     * Le lot du jour : depuis le cache s'il est déjà là, sinon on fait avancer sa préparation
     * étape par étape. En cas d'échec, le lot d'exemple prend le relais.
     */
    suspend fun lotDuJour(exemple: () -> Lot, onPreparation: (Preparation) -> Unit): LotCharge =
        withContext(Dispatchers.IO) {
            val jour = LocalDate.now()
            val enCache = cache(jour)
            if (enCache.exists()) {
                runCatching { json.decodeFromString(Lot.serializer(), enCache.readText()) }
                    .getOrNull()?.let { return@withContext LotCharge(it, exemple = false) }
            }

            var dernierMessage: String? = null
            try {
                repeat(16) {
                    val (code, texte) = requete("POST", "/functions/v1/chevet-lot", """{"action":"avancer"}""", jeton())
                    val o = objet(texte) ?: error("Réponse illisible ($code)")
                    dernierMessage = o["erreur"]?.jsonPrimitive?.contentOrNull
                    val statut = o["statut"]?.jsonPrimitive?.contentOrNull
                    val etape = o["etape"]?.jsonPrimitive?.intOrNull ?: 0
                    val total = o["total"]?.jsonPrimitive?.intOrNull ?: 8
                    withContext(Dispatchers.Main) { onPreparation(Preparation(etape, total)) }

                    when (statut) {
                        "pret" -> {
                            val brut = o["lot"] ?: error("Lot vide")
                            val lot = json.decodeFromJsonElement(Lot.serializer(), brut)
                            if (lot.portes.isEmpty()) error("Lot sans porte")
                            enCache.writeText(json.encodeToString(Lot.serializer(), lot))
                            nettoyerVieuxCaches(jour)
                            return@withContext LotCharge(lot, exemple = false)
                        }
                        "erreur" -> error(dernierMessage ?: "Préparation en erreur")
                    }
                    // Étape occupée ou limite Groq : on souffle un peu avant de relancer.
                    if (o["occupe"] != null || dernierMessage != null) delay(15_000)
                }
                LotCharge(exemple(), exemple = true, message = "Le lot met du temps à se préparer : voici le lot d'exemple.")
            } catch (e: Exception) {
                LotCharge(exemple(), exemple = true, message = "Lot d'exemple : ${dernierMessage ?: e.message}")
            }
        }

    private fun nettoyerVieuxCaches(jour: LocalDate) {
        dossier.listFiles { f -> f.name.startsWith("lot_") && f.name != "lot_$jour.json" }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ Creuser

    /** Réponse de l'IA, ou un message d'attente si le quota du soir est atteint. */
    suspend fun creuser(concept: Concept, question: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val corps = buildJsonObject {
                put("question", question)
                put("concept", concept.titre)
                put("contexte", concept.cartes.joinToString(" ") { it.texte })
                put("source", concept.source.url)
            }.toString()
            val (_, texte) = requete("POST", "/functions/v1/chevet-creuser", corps, jeton())
            val o = objet(texte)
            when {
                o == null -> "Pas de réponse pour l'instant."
                o["quota"] != null -> "Quota du soir atteint. Ta question attendra demain : elle pourra devenir une porte."
                else -> o["reponse"]?.jsonPrimitive?.contentOrNull
                    ?: o["erreur"]?.jsonPrimitive?.contentOrNull
                    ?: "Pas de réponse pour l'instant."
            }
        }.getOrElse { "Pas de connexion : réessaie plus tard." }
    }

    // ------------------------------------------------------------ Terrier et journal

    /** Les concepts explorés ce soir rejoignent le terrier côté serveur (pour les tunnels de demain). */
    suspend fun enregistrerConcepts(concepts: List<Concept>, jour: String) = withContext(Dispatchers.IO) {
        runCatching {
            val jeton = jeton()
            val uid = utilisateur() ?: return@runCatching
            val lignes = buildJsonArray {
                concepts.forEach { c ->
                    add(buildJsonObject {
                        put("user_id", uid)
                        put("slug", c.id)
                        put("titre", c.titre)
                        put("theme", c.theme)
                        put("wikipedia_url", c.source.url)
                        put("a_retenir", c.aRetenir)
                        put("a_raconter", c.aRaconter)
                        put("qcm", json.encodeToJsonElement(fr.dash.chevet.model.Qcm.serializer(), c.qcm))
                        put("lot_jour", jour)
                    })
                }
            }
            requete(
                "POST",
                "/rest/v1/chevet_concepts?on_conflict=user_id,slug",
                lignes.toString(),
                jeton,
                mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal"),
            )
        }
    }

    /** Envoie le journal local vers la table chevet_journal, puis le vide. */
    suspend fun envoyerJournal(journal: Journal) = withContext(Dispatchers.IO) {
        runCatching {
            val lignes = journal.lignes()
            if (lignes.isEmpty()) return@runCatching
            val jeton = jeton()
            val uid = utilisateur() ?: return@runCatching
            val rangs = buildJsonArray {
                lignes.mapNotNull { objet(it) }.forEach { o -> add(versRang(o, uid)) }
            }
            val (code, _) = requete(
                "POST",
                "/rest/v1/chevet_journal",
                rangs.toString(),
                jeton,
                mapOf("Prefer" to "return=minimal"),
            )
            if (code in 200..299) journal.oublier(lignes.size)
        }
    }

    private fun versRang(o: JsonObject, uid: String): JsonObject {
        val connus = setOf("horodatage", "evenement", "concept", "page", "duree_ms", "lot")
        val donnees = JsonObject(o.filterKeys { it !in connus })
        return buildJsonObject {
            put("user_id", uid)
            texte(o["horodatage"])?.let { put("horodatage", it) }
            put("evenement", texte(o["evenement"]) ?: "inconnu")
            put("concept_ref", texte(o["concept"]))
            o["page"]?.jsonPrimitive?.intOrNull?.let { put("page", it) }
            o["duree_ms"]?.jsonPrimitive?.longOrNull?.let { put("duree_ms", it.coerceAtMost(Int.MAX_VALUE.toLong())) }
            texte(o["lot"])?.let { put("lot_jour", it) }
            put("donnees", donnees)
        }
    }

    private fun texte(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonArray, is JsonObject -> e.toString()
        else -> null
    }
}
