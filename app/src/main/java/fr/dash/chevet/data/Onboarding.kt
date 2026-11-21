package fr.dash.chevet.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate

/** Les sujets du swipe de départ : on garde ou on rejette, ça nourrit la fiche « Ce que Chevet sait de moi ». */
val SUJETS_DEPART = listOf(
    "Le fonctionnement du cerveau et de la mémoire",
    "L'histoire des révolutions et des luttes sociales",
    "L'économie politique et ses grands débats",
    "L'astronomie et l'exploration spatiale",
    "Les biais cognitifs et la psychologie sociale",
    "La philosophie politique",
    "Les méthodes statistiques et leurs pièges",
    "L'histoire des sciences",
    "L'énergie, le climat et la transition",
    "La géopolitique et les rapports de force",
    "L'étymologie et l'histoire des langues",
    "Les trains, les gares et les techniques ferroviaires",
    "L'histoire locale : villages, petits clubs, anciennes gares",
    "Le football : modèles, stats et histoire des clubs",
    "La bière, le brassage et le vocabulaire des saveurs",
    "L'histoire de la musique, du doom metal au rock",
    "La nature locale : forêts, rivières, oiseaux",
    "Les mathématiques et leurs paradoxes",
    "L'épistémologie : comment on sait ce qu'on sait",
    "L'histoire des médias et de la propagande",
    "La médecine et l'organisation du système de soin",
    "L'informatique : réseaux, systèmes, matériel",
    "Les grandes périodes de l'histoire : Antiquité, Moyen Âge, Modernes",
    "L'éthique et les dilemmes moraux",
    "La physique des choses du quotidien",
    "Les mouvements sportifs : trail, vélo, badminton",
)

/** Ce que le swipe a gardé et rejeté, et ce que l'historique YouTube a révélé, prêts à entrer dans la fiche. */
object FicheOnboarding {

    const val GARDES = "Sujets qui t'attirent (onboarding)"
    const val REJETES = "Sujets qui ne t'attirent pas (onboarding)"
    const val YOUTUBE = "Sur YouTube"

    /** Ajoute des puces à une rubrique (créée au besoin), sans doublon. Remplace celles de l'onboarding précédent. */
    fun avecPuces(fiche: List<Rubrique>, titre: String, puces: List<String>, remplacer: Boolean): List<Rubrique> {
        val existante = fiche.firstOrNull { it.titre == titre }
        val base = if (existante == null || remplacer) emptyList() else existante.puces
        val nouvelles = (base + puces.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        return if (existante == null) fiche + Rubrique(titre, nouvelles)
        else fiche.map { if (it.titre == titre) it.copy(puces = nouvelles) else it }
    }
}

/** Ce qu'on garde d'un historique YouTube : les chaînes les plus regardées et un échantillon de titres. */
data class ResumeYoutube(val videos: Int, val chaines: List<Pair<String, Int>>, val titres: List<String>)

/**
 * Lit le fichier « watch-history.json » de Google Takeout (YouTube, format JSON). Chaque entrée porte un
 * titre (« Vous avez regardé … ») et la chaîne dans « subtitles ». Rien n'est envoyé tel quel : seuls les
 * 40 premières chaînes et 120 titres partent au serveur, qui en tire des centres d'intérêt.
 */
fun lireHistoriqueYoutube(contenu: String): ResumeYoutube? {
    val racine = runCatching { Json.parseToJsonElement(contenu) }.getOrNull() as? JsonArray ?: return null
    val prefixes = listOf("Vous avez regardé ", "You watched ", "Watched ")
    val parChaine = LinkedHashMap<String, Int>()
    val titres = LinkedHashSet<String>()
    var videos = 0
    for (e in racine) {
        val o = e as? JsonObject ?: continue
        val brut = o["title"]?.jsonPrimitive?.contentOrNull ?: continue
        val prefixe = prefixes.firstOrNull { brut.startsWith(it) } ?: continue
        videos += 1
        val titre = brut.removePrefix(prefixe).trim()
        if (titre.isNotEmpty() && titres.size < 120) titres += titre.take(140)
        val chaine = (o["subtitles"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        if (!chaine.isNullOrBlank()) parChaine[chaine] = (parChaine[chaine] ?: 0) + 1
    }
    if (videos == 0) return null
    return ResumeYoutube(
        videos = videos,
        chaines = parChaine.entries.sortedByDescending { it.value }.take(40).map { it.key to it.value },
        titres = titres.toList(),
    )
}

/** Le check-in du dimanche : « top, bof ou lassant », puis pourquoi en un tap. */
object Checkin {

    val RESSENTIS = listOf("top" to "Top", "bof" to "Bof", "lassant" to "Lassant")

    private val raisons = mapOf(
        "top" to listOf("Les sujets", "Les histoires", "Les cartes courtes", "Le rituel du soir"),
        "bof" to listOf("Pas assez neuf", "Trop dense", "Trop répétitif", "Pas le bon moment"),
        "lassant" to listOf("Trop long", "Trop dense", "Sujets répétitifs", "Je n'y pense plus le soir"),
    )

    fun raisonsPour(ressenti: String): List<String> = raisons[ressenti].orEmpty()

    fun estDimanche(jour: LocalDate = LocalDate.now()): Boolean = jour.dayOfWeek == DayOfWeek.SUNDAY

    /** Le lundi de la semaine du jour donné, clé de la semaine dans chevet_checkins. */
    fun semaine(jour: LocalDate = LocalDate.now()): String = jour.with(DayOfWeek.MONDAY).toString()
}
