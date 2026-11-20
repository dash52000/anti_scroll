package fr.dash.chevet.data

import android.content.Context
import fr.dash.chevet.model.Concept
import fr.dash.chevet.model.Lot
import fr.dash.chevet.model.Porte
import fr.dash.chevet.model.Sens
import fr.dash.chevet.model.TitreCran
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** Résultat du test d'une nouvelle source d'actu. */
@Serializable
data class ResultatFlux(
    val ok: Boolean = false,
    val nom: String? = null,
    val url: String? = null,
    val articles: Int = 0,
    val dernier: String? = null,
    val erreur: String? = null,
)

/** Une porte bonus proposée par « d'autres portes », pas encore écrite. */
@Serializable
data class PorteProposee(
    val id: String,
    val titre: String,
    val accroche: String = "",
    val mystere: Boolean = false,
    val indice: String? = null,
)

/** Un voisin d'un concept dans le terrier : un article lié, pas encore exploré. */
@Serializable
data class Voisin(
    val titre: String,
    val accroche: String = "",
    val url: String = "",
)

/** Une entrée de la boîte à curiosités. */
@Serializable
data class Curiosite(
    val id: String = "",
    val texte: String? = null,
    val url: String? = null,
    val statut: String = "en_attente",
    @kotlinx.serialization.SerialName("cree_le") val creeLe: String = "",
)

/**
 * Les deux sorties d'un concept, telles que le serveur les prépare : les titres arrivent d'abord
 * (pour les languettes), les concepts écrits et vérifiés ensuite.
 * [statut] : en_cours, pret, erreur ou indisponible.
 */
data class Crans(
    val statut: String,
    val titrePlusLoin: TitreCran? = null,
    val titreACote: TitreCran? = null,
    val plusLoin: Concept? = null,
    val aCote: Concept? = null,
) {
    val fini: Boolean get() = statut == "pret" || statut == "erreur" || statut == "indisponible"

    fun titre(sens: String): TitreCran? = if (sens == Sens.PLUS_LOIN) titrePlusLoin else titreACote

    fun concept(sens: String): Concept? = if (sens == Sens.PLUS_LOIN) plusLoin else aCote

    /** Vrai si ce côté n'aura jamais de suite : la languette disparaît. */
    fun impasse(sens: String): Boolean = fini && concept(sens) == null

    companion object {
        val EN_COURS = Crans("en_cours")
        val ECHEC = Crans("erreur")
    }
}

private const val FONCTION_LOT = "/functions/v1/chevet-lot"

class Distant(context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val dossier = context.filesDir
    private val json = Json { ignoreUnknownKeys = true }

    /** Pour les envois qui doivent survivre à la fermeture de l'écran (marque-page au « bonne nuit »). */
    private val fond = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enFond(bloc: suspend () -> Unit) {
        fond.launch { runCatching { bloc() } }
    }

    /** Pour le profil : les champs absents ou nuls prennent leur valeur par défaut. */
    private val souple = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

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
            lotEnCache()?.let { return@withContext LotCharge(it, exemple = false) }
            try {
                LotCharge(preparer(dureeMax = 12 * 60_000L, onPreparation), exemple = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LotLent) {
                LotCharge(exemple(), exemple = true, message = "Le lot met du temps à se préparer : voici le lot d'exemple.")
            } catch (e: Exception) {
                LotCharge(exemple(), exemple = true, message = "Lot d'exemple : ${e.message}")
            }
        }

    private class LotLent : Exception("Préparation trop longue")

    private class LotEnErreur(message: String) : Exception(message)

    private fun lotEnCache(jour: LocalDate = LocalDate.now()): Lot? {
        val enCache = cache(jour)
        if (!enCache.exists()) return null
        return runCatching { json.decodeFromString(Lot.serializer(), enCache.readText()) }.getOrNull()
    }

    /**
     * Fait avancer la préparation du lot côté serveur jusqu'à ce qu'il soit prêt, puis le met en cache.
     * Le cache précédent n'est remplacé qu'à la fin : en cas d'échec, l'ancien lot reste.
     */
    private suspend fun preparer(dureeMax: Long, onPreparation: (Preparation) -> Unit): Lot {
        val jour = LocalDate.now()
        val debut = System.currentTimeMillis()
        var dernierMessage: String? = null
        while (System.currentTimeMillis() - debut < dureeMax) {
            val (code, texte) = requete("POST", FONCTION_LOT, """{"action":"avancer"}""", jeton())
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
                    cache(jour).writeText(json.encodeToString(Lot.serializer(), lot))
                    nettoyerVieuxCaches(jour)
                    prefs.edit().remove(CLE_REGENERATION).apply()
                    return lot
                }
                "erreur" -> throw LotEnErreur(dernierMessage ?: "Préparation en erreur")
            }
            // Étape occupée ou limite Groq : on souffle un peu avant de relancer.
            if (o["occupe"] != null || dernierMessage != null) delay(15_000)
        }
        throw LotLent()
    }

    private fun nettoyerVieuxCaches(jour: LocalDate) {
        dossier.listFiles { f -> f.name.startsWith("lot_") && f.name != "lot_$jour.json" }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ Régénération (réglages)

    /** Vrai si une régénération du lot du jour a été lancée et n'a pas encore abouti. */
    fun regenerationEnCours(): Boolean = prefs.getString(CLE_REGENERATION, null) == LocalDate.now().toString()

    /**
     * Recompose tout le lot du jour avec le profil à jour, en arrière-plan.
     * [reprise] : la remise à zéro a déjà été faite, on reprend juste la préparation.
     * Renvoie le nouveau lot, ou null en cas d'échec (l'ancien reste en cache).
     */
    suspend fun regenererLot(reprise: Boolean, onPreparation: (Preparation) -> Unit): Lot? =
        withContext(Dispatchers.IO) {
            try {
                if (!reprise) {
                    val (code, _) = requete("POST", FONCTION_LOT, """{"action":"regenerer"}""", jeton())
                    check(code in 200..299) { "Remise à zéro impossible ($code)" }
                    prefs.edit().putString(CLE_REGENERATION, LocalDate.now().toString()).apply()
                }
                preparer(dureeMax = 15 * 60_000L, onPreparation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LotEnErreur) {
                prefs.edit().remove(CLE_REGENERATION).apply()
                null
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Réécrit les concepts d'une porte après les [gardes] premiers, en arrière-plan.
     * Renvoie la porte complétée (et met le cache à jour), ou null si la suite n'a pas pu être écrite.
     */
    suspend fun suite(
        porteId: String,
        gardes: Int,
        demande: String,
        onEtape: (Preparation) -> Unit,
    ): Porte? = withContext(Dispatchers.IO) {
        try {
            val debut = System.currentTimeMillis()
            while (System.currentTimeMillis() - debut < 8 * 60_000L) {
                val corps = buildJsonObject {
                    put("action", "suite")
                    put("porte", porteId)
                    put("gardes", gardes)
                    put("demande", demande)
                }.toString()
                val (code, texte) = requete("POST", FONCTION_LOT, corps, jeton())
                val o = objet(texte) ?: error("Réponse illisible ($code)")
                val etape = o["etape"]?.jsonPrimitive?.intOrNull ?: 0
                val total = o["total"]?.jsonPrimitive?.intOrNull ?: 4
                withContext(Dispatchers.Main) { onEtape(Preparation(etape, total)) }
                when (o["statut"]?.jsonPrimitive?.contentOrNull) {
                    "pret" -> {
                        val brut = o["porte"] ?: return@withContext null
                        val porte = json.decodeFromJsonElement(Porte.serializer(), brut)
                        remplacerPorteEnCache(porte)
                        return@withContext porte
                    }
                    "en_cours" -> {
                        val souffle = o["occupe"] != null || o["erreur"]?.jsonPrimitive?.contentOrNull != null
                        if (souffle) delay(15_000)
                    }
                    else -> return@withContext null
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun remplacerPorteEnCache(porte: Porte) {
        val jour = LocalDate.now()
        val lot = lotEnCache(jour) ?: return
        val nouveau = lot.copy(portes = lot.portes.map { if (it.id == porte.id) porte else it })
        cache(jour).writeText(json.encodeToString(Lot.serializer(), nouveau))
    }

    private fun ajouterPorteEnCache(porte: Porte) {
        val jour = LocalDate.now()
        val lot = lotEnCache(jour) ?: return
        if (lot.portes.any { it.id == porte.id }) return
        cache(jour).writeText(json.encodeToString(Lot.serializer(), lot.copy(portes = lot.portes + porte)))
    }

    // ------------------------------------------------------------ D'autres portes (menu)

    /** Un appel « bonus » ; [porteId] null pour composer, sinon pour écrire la porte choisie. */
    private fun appelBonus(demande: String, porteId: String?): JsonObject? {
        val corps = buildJsonObject {
            put("action", "bonus")
            put("demande", demande)
            if (porteId != null) put("porte", porteId)
        }.toString()
        val (_, texte) = requete("POST", FONCTION_LOT, corps, jeton())
        return objet(texte)
    }

    /** Compose 3 portes neuves pour ce soir ; null si le serveur n'y arrive pas. */
    suspend fun composerBonus(demande: String): List<PorteProposee>? = withContext(Dispatchers.IO) {
        try {
            val debut = System.currentTimeMillis()
            while (System.currentTimeMillis() - debut < 4 * 60_000L) {
                val o = appelBonus(demande, null) ?: return@withContext null
                when (o["statut"]?.jsonPrimitive?.contentOrNull) {
                    "choix" -> {
                        val brut = o["portes"] ?: return@withContext null
                        return@withContext json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(PorteProposee.serializer()),
                            brut,
                        )
                    }
                    "en_cours" -> delay(if (o["occupe"] != null) 5_000 else 15_000)
                    else -> return@withContext null
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Écrit la porte bonus choisie, l'ajoute au lot en cache et la renvoie ; null en cas d'échec. */
    suspend fun ecrireBonus(demande: String, porteId: String, onEtape: (Preparation) -> Unit): Porte? =
        withContext(Dispatchers.IO) {
            try {
                val debut = System.currentTimeMillis()
                while (System.currentTimeMillis() - debut < 6 * 60_000L) {
                    val o = appelBonus(demande, porteId) ?: return@withContext null
                    val etape = o["etape"]?.jsonPrimitive?.intOrNull ?: 0
                    val total = o["total"]?.jsonPrimitive?.intOrNull ?: 3
                    withContext(Dispatchers.Main) { onEtape(Preparation(etape, total)) }
                    when (o["statut"]?.jsonPrimitive?.contentOrNull) {
                        "pret" -> {
                            val brut = o["porte"] ?: return@withContext null
                            val porte = json.decodeFromJsonElement(Porte.serializer(), brut)
                            ajouterPorteEnCache(porte)
                            return@withContext porte
                        }
                        "en_cours" -> {
                            val souffle = o["occupe"] != null || o["erreur"]?.jsonPrimitive?.contentOrNull != null
                            if (souffle) delay(10_000)
                        }
                        else -> return@withContext null
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    // ------------------------------------------------------------ Terrier (menu)

    /** Les voisins d'un concept, choisis par l'IA ; null hors connexion. */
    suspend fun voisins(vu: ConceptVu): List<Voisin>? = withContext(Dispatchers.IO) {
        try {
            val corps = buildJsonObject {
                put("action", "voisins")
                put("url", vu.concept.source.url)
                put("titre", vu.concept.titre)
                put("slug", vu.concept.id)
            }.toString()
            val (code, texte) = requete("POST", FONCTION_LOT, corps, jeton())
            val brut = objet(texte)?.get("voisins")
            if (code !in 200..299 || brut == null) return@withContext null
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Voisin.serializer()), brut)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Met à jour un concept du terrier côté serveur (cœur, « prolonger demain »), en le créant
     * s'il n'y est pas encore : le lot de demain en tient compte.
     */
    suspend fun majConcept(
        concept: Concept,
        jour: String,
        aime: Boolean? = null,
        prolonger: Boolean? = null,
        vers: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val jeton = jeton()
            val uid = utilisateur() ?: return@runCatching false
            val ligne = buildJsonObject {
                put("user_id", uid)
                put("slug", concept.id)
                put("titre", concept.titre)
                put("theme", concept.theme)
                put("wikipedia_url", concept.source.url)
                put("a_retenir", concept.aRetenir)
                put("a_raconter", concept.aRaconter)
                put("qcm", json.encodeToJsonElement(fr.dash.chevet.model.Qcm.serializer(), concept.qcm))
                put("lot_jour", jour)
                aime?.let { put("aime", it) }
                prolonger?.let {
                    put("prolonger", it)
                    put("prolonger_vers", if (it) vers else null)
                }
            }
            val (code, _) = requete(
                "POST",
                "/rest/v1/chevet_concepts?on_conflict=user_id,slug",
                buildJsonArray { add(ligne) }.toString(),
                jeton,
                mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
            )
            code in 200..299
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ Exploration : plus loin, à côté

    private fun lireCrans(o: JsonObject): Crans {
        fun titre(cle: String): TitreCran? =
            (o["titres"] as? JsonObject)?.get(cle)?.let { runCatching { json.decodeFromJsonElement(TitreCran.serializer(), it) }.getOrNull() }
        fun concept(cle: String): Concept? =
            o[cle]?.takeIf { it is JsonObject }?.let { runCatching { json.decodeFromJsonElement(Concept.serializer(), it) }.getOrNull() }
        return Crans(
            statut = o["statut"]?.jsonPrimitive?.contentOrNull ?: "indisponible",
            titrePlusLoin = titre(Sens.PLUS_LOIN),
            titreACote = titre(Sens.A_COTE),
            plusLoin = concept(Sens.PLUS_LOIN),
            aCote = concept(Sens.A_COTE),
        )
    }

    /**
     * Prépare « plus loin » et « à côté » depuis [depuis], étape par étape, jusqu'à ce qu'ils soient prêts.
     * [onEtat] reçoit chaque état (les titres arrivent dès la première étape). [court] : après l'heure
     * de coucher, des cartes plus courtes. Renvoie le dernier état connu.
     */
    suspend fun crans(
        porte: Porte,
        depuis: Concept,
        chemin: List<String>,
        court: Boolean,
        onEtat: (Crans) -> Unit,
    ): Crans = withContext(Dispatchers.IO) {
        var dernier = Crans.EN_COURS
        try {
            val corps = buildJsonObject {
                put("action", "crans")
                put("porte", buildJsonObject {
                    put("titre", porte.titre)
                    put("accroche", porte.accroche)
                })
                put("depuis", buildJsonObject {
                    put("id", depuis.id)
                    put("titre", depuis.titre)
                    put("url", depuis.source.url)
                    put("theme", depuis.theme)
                    put("profondeur", depuis.profondeur)
                })
                put("chemin", buildJsonArray { chemin.forEach { add(JsonPrimitive(it)) } })
                put("court", court)
            }.toString()
            val debut = System.currentTimeMillis()
            var echecs = 0
            while (System.currentTimeMillis() - debut < 8 * 60_000L) {
                val (code, texte) = requete("POST", FONCTION_LOT, corps, jeton())
                val o = objet(texte)
                if (o == null || code !in 200..299) {
                    if (++echecs >= 3) return@withContext Crans.ECHEC.also { withContext(Dispatchers.Main) { onEtat(it) } }
                    delay(10_000)
                    continue
                }
                dernier = lireCrans(o)
                val etat = dernier
                withContext(Dispatchers.Main) { onEtat(etat) }
                if (etat.fini) return@withContext etat
                // Étape occupée ou limite Groq : on souffle avant de relancer.
                val souffle = o["occupe"] != null || o["erreur"]?.jsonPrimitive?.contentOrNull != null
                if (souffle) delay(12_000)
            }
            dernier
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Crans.ECHEC.also { withContext(Dispatchers.Main) { onEtat(it) } }
        }
    }

    /**
     * Garde l'endroit où le lecteur s'arrête : le dernier concept lu et les concepts déjà écrits qui
     * suivent. Le lot de demain en fait la porte « reprise ». [effacer] : la porte est finie.
     */
    suspend fun marquePage(
        porteTitre: String,
        dernier: Concept?,
        suite: List<Concept>,
        effacer: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val corps = buildJsonObject {
                put("action", "marque_page")
                if (effacer) {
                    put("effacer", true)
                } else {
                    put("porteTitre", porteTitre)
                    dernier?.let { d ->
                        put("dernier", buildJsonObject {
                            put("id", d.id)
                            put("titre", d.titre)
                            put("url", d.source.url)
                        })
                    }
                    put("suite", buildJsonArray { suite.forEach { add(json.encodeToJsonElement(Concept.serializer(), it)) } })
                }
            }.toString()
            val (code, _) = requete("POST", FONCTION_LOT, corps, jeton())
            code in 200..299
        }.getOrDefault(false)
    }

    /** Enregistre la note de 1 à 10 d'une porte ; 8 ou plus propose sa suite dans le lot de demain. */
    suspend fun noterPorte(jour: String, porte: Porte, note: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val jeton = jeton()
            val uid = utilisateur() ?: return@runCatching false
            val ligne = buildJsonObject {
                put("user_id", uid)
                put("jour", jour)
                put("porte_id", porte.id)
                put("porte_titre", porte.titre)
                put("theme", porte.concepts.firstOrNull()?.theme)
                put("type", porte.type)
                put("note", note.coerceIn(1, 10))
            }
            val (code, _) = requete(
                "POST",
                "/rest/v1/chevet_notes_portes?on_conflict=user_id,jour,porte_id",
                buildJsonArray { add(ligne) }.toString(),
                jeton,
                mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
            )
            code in 200..299
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ Boîte à curiosités (menu)

    /** Dépose une curiosité ; elle nourrira la prochaine composition de portes. */
    suspend fun envoyerCuriosite(texte: String, url: String?, origine: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val corps = buildJsonObject {
                put("texte", texte)
                put("url", url)
                put("origine", origine)
            }
            val (code, _) = requete(
                "POST",
                "/rest/v1/chevet_curiosites",
                corps.toString(),
                jeton(),
                mapOf("Prefer" to "return=minimal"),
            )
            code in 200..299
        }.getOrDefault(false)
    }

    /** Les 30 dernières curiosités ; null hors connexion. */
    suspend fun curiosites(): List<Curiosite>? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, texte) = requete(
                "GET",
                "/rest/v1/chevet_curiosites?select=id,texte,url,statut,cree_le&order=cree_le.desc&limit=30",
                jeton = jeton(),
            )
            if (code !in 200..299) return@runCatching null
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Curiosite.serializer()), texte)
        }.getOrNull()
    }

    // ------------------------------------------------------------ Profil (réglages)

    fun profilEnCache(): Profil? =
        prefs.getString(CLE_PROFIL, null)?.let { runCatching { souple.decodeFromString(Profil.serializer(), it) }.getOrNull() }

    /** Le profil réglable depuis le serveur, sinon la dernière copie connue. */
    suspend fun profil(): Profil? = withContext(Dispatchers.IO) {
        try {
            val (code, texte) = requete("POST", FONCTION_LOT, """{"action":"profil"}""", jeton())
            val brut = objet(texte)?.get("profil")
            if (code in 200..299 && brut is JsonObject) {
                val profil = souple.decodeFromJsonElement(Profil.serializer(), brut)
                prefs.edit().putString(CLE_PROFIL, souple.encodeToString(Profil.serializer(), profil)).apply()
                return@withContext profil
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // hors ligne : copie locale
        }
        profilEnCache()
    }

    /** Enregistre le profil. Hors ligne, il est gardé et renvoyé à la prochaine ouverture. */
    suspend fun enregistrerProfil(profil: Profil): Boolean = withContext(Dispatchers.IO) {
        val texte = souple.encodeToString(Profil.serializer(), profil)
        val ok = try {
            envoyerProfil(profil)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        prefs.edit().apply {
            if (ok) {
                putString(CLE_PROFIL, texte)
                remove(CLE_PROFIL_EN_ATTENTE)
            } else {
                putString(CLE_PROFIL_EN_ATTENTE, texte)
            }
        }.apply()
        ok
    }

    fun profilEnAttente(): Boolean = prefs.contains(CLE_PROFIL_EN_ATTENTE)

    suspend fun renvoyerProfilEnAttente() {
        val texte = prefs.getString(CLE_PROFIL_EN_ATTENTE, null) ?: return
        val profil = runCatching { souple.decodeFromString(Profil.serializer(), texte) }.getOrNull()
        if (profil == null) {
            prefs.edit().remove(CLE_PROFIL_EN_ATTENTE).apply()
            return
        }
        enregistrerProfil(profil)
    }

    /** L'écriture passe par la fonction serveur, qui borne et nettoie chaque champ. */
    private fun envoyerProfil(profil: Profil): Boolean {
        val champs = souple.encodeToJsonElement(Profil.serializer(), profil).jsonObject
        val corps = buildJsonObject {
            put("action", "enregistrer_profil")
            put("profil", JsonObject(champs.filterValues { it !is JsonNull }))
        }
        val (code, _) = requete("POST", FONCTION_LOT, corps.toString(), jeton())
        return code in 200..299
    }

    /** Vérifie côté serveur qu'une URL donne un flux RSS lisible. */
    suspend fun testerFlux(url: String): ResultatFlux = withContext(Dispatchers.IO) {
        try {
            val corps = buildJsonObject {
                put("action", "tester_flux")
                put("url", url)
            }.toString()
            val (_, texte) = requete("POST", FONCTION_LOT, corps, jeton())
            json.decodeFromString(ResultatFlux.serializer(), texte)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ResultatFlux(ok = false, erreur = "Pas de connexion")
        }
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
            val heure = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            prefs.edit().putString(CLE_DERNIER_ENVOI, "$heure · ${lignes.size} ligne(s) · code $code").apply()
        }
    }

    // ------------------------------------------------------------ Outils de test

    /** État de la synchro Supabase, pour les outils de test. */
    fun etatSynchro(journal: Journal): List<Pair<String, String>> {
        val expire = prefs.getLong("expire", 0)
        val finJeton = if (expire > 0) {
            Instant.ofEpochSecond(expire).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
        } else {
            "aucun"
        }
        return listOf(
            "Utilisateur" to (utilisateur()?.take(8)?.let { "$it…" } ?: "aucun"),
            "Jeton valide jusqu'au" to finJeton,
            "Journal en attente" to "${journal.lignes().size} ligne(s)",
            "Dernier envoi du journal" to (prefs.getString(CLE_DERNIER_ENVOI, null) ?: "jamais"),
            "Profil en attente d'envoi" to if (profilEnAttente()) "oui" else "non",
            "Régénération du lot" to if (regenerationEnCours()) "en cours" else "aucune",
        )
    }

    private companion object {
        const val CLE_REGENERATION = "regeneration"
        const val CLE_PROFIL = "profil"
        const val CLE_PROFIL_EN_ATTENTE = "profil_en_attente"
        const val CLE_DERNIER_ENVOI = "dernier_envoi_journal"
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
