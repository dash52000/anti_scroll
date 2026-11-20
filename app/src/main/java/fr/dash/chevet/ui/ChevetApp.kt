package fr.dash.chevet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fr.dash.chevet.data.Coeurs
import fr.dash.chevet.data.ConceptVu
import fr.dash.chevet.data.Crans
import fr.dash.chevet.data.Distant
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.PorteProposee
import fr.dash.chevet.data.Preparation
import fr.dash.chevet.data.Profil
import fr.dash.chevet.data.Progression
import fr.dash.chevet.data.Soiree
import fr.dash.chevet.model.Carte
import fr.dash.chevet.model.Concept
import fr.dash.chevet.model.Lot
import fr.dash.chevet.model.Porte
import fr.dash.chevet.model.Sens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Les étapes du rituel du soir. La porte du fil est gardée à part, pour pouvoir la réécrire. */
private sealed interface Etape {
    data object Echauffement : Etape
    data object Pepite : Etape
    data object Portes : Etape
    data object Fil : Etape
    data object Recap : Etape
    data object AutrePorte : Etape
    data object DejaFini : Etape
}

/** Régénération en arrière-plan après un changement de réglages. */
internal sealed interface Regen {
    val preparation: Preparation

    data class Lot(override val preparation: Preparation) : Regen
    data class Suite(val porteId: String, val gardes: Int, override val preparation: Preparation) : Regen
}

/** « D'autres portes » : 3 portes neuves composées, puis la porte choisie écrite, en arrière-plan. */
internal sealed interface Bonus {
    data object Aucun : Bonus
    data object Composition : Bonus
    data class Choix(val demande: String, val portes: List<PorteProposee>) : Bonus
    data class Ecriture(val choix: Choix, val porte: PorteProposee, val preparation: Preparation) : Bonus
    data class Echec(val message: String) : Bonus
}

private const val CONCEPTS_PAR_PORTE = 3

/** Plafond du fil borné : 3 portes ouvertes par soir au plus (la reprise ne compte pas). */
internal const val PLAFOND_PORTES = 3

@Composable
fun ChevetApp(
    lotInitial: Lot,
    lotExemple: Boolean,
    progression: Progression,
    coeurs: Coeurs,
    journal: Journal,
    distant: Distant,
    soiree: Soiree,
    onPartager: (String) -> Unit,
    onRejouer: () -> Unit,
    onBonneNuit: () -> Unit,
    onQuitter: () -> Unit,
    bandeau: String? = null,
    reprendreRegeneration: Boolean = false,
    partageEntrant: String? = null,
    onConceptsExplores: (List<Concept>) -> Unit = {},
) {
    val revisions = remember { progression.revisionsDues(coeurs::conceptAime) }
    var lot by remember { mutableStateOf(lotInitial) }
    var ouvertes by remember { mutableStateOf(progression.portesOuvertes()) }
    // La reprise ne compte pas dans le plafond : seules les portes de sujets neufs comptent.
    fun comptees(ids: Set<String>, l: Lot = lot) = ids.count { id -> l.portes.none { it.id == id && it.reprise } }
    var etape by remember {
        mutableStateOf<Etape>(
            when {
                progression.lotConsomme() || comptees(ouvertes, lotInitial) >= PLAFOND_PORTES -> Etape.DejaFini
                revisions.isNotEmpty() -> Etape.Echauffement
                else -> Etape.Portes
            }
        )
    }
    var porteFil by remember { mutableStateOf<Porte?>(null) }
    var conceptCourant by remember { mutableIntStateOf(0) }
    var progresFil by remember { mutableFloatStateOf(0f) }
    var etoiles by remember { mutableStateOf(false) }
    var creusagesParSoir by remember { mutableIntStateOf(soiree.creusagesParSoir) }
    var creusagesFaits by remember { mutableIntStateOf(0) }
    var conceptsDuSoir by remember { mutableStateOf<List<Concept>>(emptyList()) }
    var nouvellesFeuilles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reglages by remember { mutableStateOf<Boolean?>(null) }
    var menu by remember { mutableStateOf(if (partageEntrant != null) PageMenu.Curiosites else null) }
    var regen by remember { mutableStateOf<Regen?>(null) }
    var tache by remember { mutableStateOf<Job?>(null) }
    var bonus by remember { mutableStateOf<Bonus>(Bonus.Aucun) }
    var tacheBonus by remember { mutableStateOf<Job?>(null) }
    var annonce by remember { mutableStateOf<String?>(null) }
    val portee = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    // ------------------------------------------------ Exploration : crans, marque-page, ponts

    /** « Plus loin » et « à côté » de chaque concept, par concept de départ. */
    val crans = remember { mutableStateMapOf<String, Crans>() }
    val demandes = remember { mutableSetOf<String>() }
    val preparation = remember { Semaphore(2) }
    var inseres by remember { mutableStateOf<List<Concept>>(emptyList()) }
    var enAttente by remember { mutableStateOf<Pair<String, String>?>(null) }
    var position by remember { mutableStateOf<Position?>(null) }
    var pontDuSoir by remember { mutableStateOf(soiree.pontDuSoir()) }
    var apresCoucher by remember { mutableStateOf(soiree.apresCoucher()) }
    val fremir = remember { soiree.carrefoursVus < 3 }
    var marqueGardee by remember { mutableStateOf(false) }
    var porteDuRecap by remember { mutableStateOf<Porte?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            apresCoucher = soiree.apresCoucher()
        }
    }

    val restantes = lot.portes.filter { it.id !in ouvertes }
    val plafondAtteint = comptees(ouvertes) >= PLAFOND_PORTES
    val peutAutrePorte = !plafondAtteint && ouvertes.isNotEmpty() && etape != Etape.Fil
    val filCourant = porteFil?.let { ordreDuFil(it.concepts, inseres) }.orEmpty()

    // Le pont du soir : déjà vu ce soir, sinon le premier concept du fil qui en porte un.
    val pontAffiche = pontDuSoir ?: filCourant.firstOrNull { it.pont != null }?.id

    // La nuit tombe au fil du rituel ; chaque nouvelle porte repart d'un ciel un peu plus sombre,
    // chaque cran de plus fonce un peu, et plus encore une fois l'heure de coucher passée.
    val debutFil = (0.25f + 0.15f * (ouvertes.size - 1).coerceAtLeast(0)).coerceAtMost(0.55f)
    val profondeur = position?.concept?.profondeur ?: 0
    val nuit = when (etape) {
        Etape.Echauffement -> 0.05f
        Etape.Pepite -> 0.12f
        Etape.Portes -> 0.2f
        Etape.Fil -> (debutFil + (0.85f - debutFil) * progresFil + 0.03f * profondeur + if (apresCoucher) 0.06f else 0f)
            .coerceAtMost(0.95f)
        Etape.Recap -> 0.9f
        Etape.AutrePorte -> 0.85f
        Etape.DejaFini -> 0.95f
    }

    if (etoiles) {
        LaunchedEffect(Unit) {
            delay(2500)
            onBonneNuit()
        }
    }

    LaunchedEffect(annonce) {
        if (annonce != null) {
            delay(3500)
            annonce = null
        }
    }

    /** Prépare en arrière-plan les deux sorties d'un concept, pendant qu'on le lit (deux à la fois au plus). */
    fun demanderCrans(c: Concept) {
        val porte = porteFil ?: return
        if (lotExemple || !demandes.add(c.id)) return
        val chemin = cheminVers(c, ordreDuFil(porte.concepts, inseres))
        val court = apresCoucher
        portee.launch {
            preparation.withPermit {
                distant.crans(porte, c, chemin, court) { etat -> crans[c.id] = etat }
            }
        }
    }

    /** Ouvre le cran [sens] du concept [depuisId] s'il est prêt ; renvoie la clé de sa première page. */
    fun inserer(depuisId: String, sens: String): String? {
        inseres.firstOrNull { it.depuis == depuisId && it.sens == sens }?.let { return "${it.id}:q" }
        val brut = crans[depuisId]?.concept(sens) ?: return null
        val dejaLa = ordreDuFil(porteFil?.concepts.orEmpty(), inseres).map { it.id }.toSet()
        var cran = brut.copy(depuis = depuisId, sens = sens)
        if (cran.id in dejaLa) cran = cran.copy(id = "${cran.id}-${if (sens == Sens.PLUS_LOIN) "pl" else "ac"}")
        inseres = inseres + cran
        journal.note("cran_ouvert", "depuis" to depuisId, "sens" to sens, "concept" to cran.id, "profondeur" to cran.profondeur)
        return "${cran.id}:q"
    }

    // Un cran attendu arrive (ou échoue) : on l'ouvre, ou on le dit.
    val attendu = enAttente
    val etatAttendu = attendu?.let { crans[it.first] }
    LaunchedEffect(attendu, etatAttendu) {
        val (depuis, sens) = attendu ?: return@LaunchedEffect
        val etat = etatAttendu ?: return@LaunchedEffect
        when {
            etat.concept(sens) != null -> {
                inserer(depuis, sens)
                enAttente = null
            }
            etat.impasse(sens) -> {
                enAttente = null
                annonce = "Pas de suite de ce côté ce soir : le conteur n'a rien trouvé de solide."
            }
        }
    }

    /**
     * L'endroit où l'on s'arrête : le dernier concept lu jusqu'à son carrefour, et les concepts déjà
     * écrits qui suivent (la suite de la porte, sinon le cran « plus loin » préparé).
     */
    fun etatMarquePage(): Pair<Concept?, List<Concept>>? {
        val porte = porteFil ?: return null
        val pos = position ?: return null
        val fil = ordreDuFil(porte.concepts, inseres)
        val i = fil.indexOfFirst { it.id == pos.concept.id }
        if (i < 0) return null
        return if (pos.carrefourAtteint) {
            var suite = fil.drop(i + 1).take(3)
            if (suite.isEmpty()) suite = listOfNotNull(crans[pos.concept.id]?.let { it.plusLoin ?: it.aCote })
            fil[i] to suite
        } else {
            fil.getOrNull(i - 1) to fil.drop(i).take(3)
        }
    }

    /** Garde le marque-page pour demain. [explicite] : « garder pour demain » plutôt qu'une fermeture. */
    fun garderMarquePage(explicite: Boolean) {
        if (lotExemple || etape != Etape.Fil) return
        val porte = porteFil ?: return
        val (dernier, suite) = etatMarquePage() ?: return
        if (suite.isEmpty() && !explicite) return
        if (dernier == null && suite.isEmpty()) return
        soiree.memoriserMarquePage(suite.firstOrNull()?.titre ?: dernier?.titre)
        journal.note("marque_page", "explicite" to explicite, "suite" to suite.size, "dernier" to dernier?.id)
        distant.enFond { distant.marquePage(porte.titre, dernier, suite) }
    }

    // L'appli passe en arrière-plan en pleine lecture : le marque-page est gardé.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (etape == Etape.Fil && !marqueGardee) garderMarquePage(explicite = false)
    }

    // ------------------------------------------------ Cœurs

    /** Pose ou retire un cœur ; le serveur apprend quand un concept devient aimé (ou ne l'est plus). */
    val basculerCoeur: (Concept, Int, String, String?) -> Unit = { c, carte, texte, jour ->
        val avant = coeurs.conceptAime(c.id)
        val aimee = coeurs.basculer(c, carte, texte)
        journal.note(if (aimee) "like" else "unlike", "concept" to c.id, "carte" to carte)
        val apres = coeurs.conceptAime(c.id)
        if (avant != apres && jour != null) portee.launch { distant.majConcept(c, jour, aime = apres) }
    }

    // ------------------------------------------------ « Trop dense » : la longueur s'ajuste toute seule

    fun tropDense() {
        if (soiree.noterTropDense() < 3) return
        portee.launch {
            val profil = distant.profil() ?: return@launch
            soiree.oublierTropDense()
            val plusCourt = when (profil.motsParCarte) {
                60 -> 40
                40 -> 25
                else -> null
            }
            if (plusCourt == null) {
                annonce = "Noté. Les cartes sont déjà au plus court : le conteur gardera des idées plus simples."
                return@launch
            }
            val ok = distant.enregistrerProfil(profil.copy(motsParCarte = plusCourt))
            journal.note("longueur_auto", "mots" to plusCourt, "ok" to ok)
            annonce = "Tu trouves ça dense : je passe à $plusCourt mots par carte dès le prochain lot. " +
                "Tu peux revenir en arrière dans les réglages."
        }
    }

    // ------------------------------------------------ Régénération en arrière-plan

    fun lancerRegenLot(reprise: Boolean) {
        tache?.cancel()
        journal.note("regeneration", "type" to "lot", "reprise" to reprise)
        regen = Regen.Lot(Preparation(0, 8))
        tache = portee.launch {
            val nouveau = distant.regenererLot(reprise) { p -> if (regen is Regen.Lot) regen = Regen.Lot(p) }
            regen = null
            if (nouveau == null) {
                journal.note("regeneration_echec", "type" to "lot")
                annonce = "Le nouveau lot n'a pas pu être préparé : on garde celui-ci."
                return@launch
            }
            journal.note("regeneration_ok", "type" to "lot")
            soiree.memoriserLot(nouveau)
            lot = nouveau
            if (etape == Etape.Portes) annonce = "De nouvelles portes t'attendent."
        }
    }

    fun lancerSuite(porte: Porte, gardes: Int) {
        tache?.cancel()
        val demande = System.currentTimeMillis().toString()
        journal.note("regeneration", "type" to "suite", "porte" to porte.id, "gardes" to gardes)
        regen = Regen.Suite(porte.id, gardes, Preparation(0, 4))
        tache = portee.launch {
            val nouvelle = distant.suite(porte.id, gardes, demande) { p ->
                val r = regen
                if (r is Regen.Suite) regen = r.copy(preparation = p)
            }
            regen = null
            if (nouvelle == null) {
                journal.note("regeneration_echec", "type" to "suite")
                annonce = "La suite n'a pas pu être réécrite : tu lis la version d'origine."
                return@launch
            }
            lot = lot.copy(portes = lot.portes.map { if (it.id == nouvelle.id) nouvelle else it })
            if (etape == Etape.Fil && porteFil?.id == nouvelle.id) {
                journal.note("regeneration_ok", "type" to "suite")
                porteFil = nouvelle
            }
        }
    }

    // Une régénération lancée avant une fermeture de l'appli reprend en arrière-plan.
    LaunchedEffect(Unit) {
        if (reprendreRegeneration) lancerRegenLot(reprise = true)
    }

    /** Applique tout de suite un changement de contenu, selon où en est la soirée. */
    fun appliquerReglages() {
        when (etape) {
            Etape.Echauffement, Etape.Pepite, Etape.Portes -> lancerRegenLot(reprise = false)
            Etape.Fil -> {
                val porte = porteFil
                val gardes = conceptCourant + 1
                if (porte != null && !lotExemple && !porte.reprise && gardes < CONCEPTS_PAR_PORTE) {
                    lancerSuite(porte, gardes)
                } else {
                    annonce = "C'est noté : ça vaudra pour le lot de demain."
                }
            }
            Etape.Recap, Etape.AutrePorte, Etape.DejaFini -> annonce = "C'est noté : ça vaudra pour le lot de demain."
        }
    }

    val fermerReglages: (Profil?, Profil?) -> Unit = { avant, apres ->
        reglages = null
        if (apres != null && apres != avant) {
            creusagesParSoir = apres.creusagesParSoir
            soiree.creusagesParSoir = apres.creusagesParSoir
            val contenu = avant != null && apres.contenuDiffere(avant)
            journal.note("reglages_modifies", "contenu" to contenu)
            portee.launch {
                val ok = distant.enregistrerProfil(apres)
                when {
                    !ok -> annonce = "Pas de connexion : tes réglages partiront à la prochaine ouverture."
                    contenu -> appliquerReglages()
                }
            }
        }
    }

    // ------------------------------------------------ Portes : la première, puis les autres

    fun ouvrirPorte(porte: Porte) {
        progression.ouvrirPorte(porte.id)
        ouvertes = progression.portesOuvertes()
        journal.note("porte_choisie", "porte" to porte.id, "rang" to ouvertes.size, "type" to porte.type)
        porteFil = porte
        conceptCourant = 0
        progresFil = 0f
        inseres = emptyList()
        enAttente = null
        position = null
        marqueGardee = false
        etape = Etape.Fil
    }

    /** Fin d'une porte : ses concepts et les crans ouverts rejoignent le terrier, puis le récap. */
    fun finirPorte(porte: Porte) {
        val lus = ordreDuFil(porte.concepts, inseres)
        progression.marquerVus(porte, lus)
        progression.marquerLotConsomme()
        onConceptsExplores(lus)
        conceptsDuSoir = lus
        nouvellesFeuilles = lus.map { it.id }.toSet()
        porteDuRecap = porte
        journal.note("fil_termine", "porte" to porte.id, "crans" to inseres.size, "garde" to marqueGardee)
        if (!marqueGardee && !lotExemple) {
            // Porte finie : plus rien à reprendre demain.
            soiree.memoriserMarquePage(null)
            distant.enFond { distant.marquePage("", null, emptyList(), effacer = true) }
        }
        etape = Etape.Recap
    }

    fun composerBonus() {
        tacheBonus?.cancel()
        val demande = System.currentTimeMillis().toString()
        bonus = Bonus.Composition
        journal.note("bonus_demande")
        tacheBonus = portee.launch {
            val portes = distant.composerBonus(demande)
            bonus = if (portes.isNullOrEmpty()) {
                Bonus.Echec("Le conteur n'a pas trouvé d'autres portes. Réessaie dans un moment.")
            } else {
                Bonus.Choix(demande, portes)
            }
        }
    }

    fun ecrireBonus(choix: Bonus.Choix, proposee: PorteProposee) {
        tacheBonus?.cancel()
        bonus = Bonus.Ecriture(choix, proposee, Preparation(0, 3))
        journal.note("bonus_choisie", "porte" to proposee.id)
        tacheBonus = portee.launch {
            val porte = distant.ecrireBonus(choix.demande, proposee.id) { p ->
                val b = bonus
                if (b is Bonus.Ecriture) bonus = b.copy(preparation = p)
            }
            if (porte == null) {
                bonus = choix
                annonce = "Cette porte n'a pas pu s'écrire : choisis-en une autre."
                return@launch
            }
            lot = lot.copy(portes = lot.portes + porte)
            bonus = Bonus.Aucun
            if (etape == Etape.AutrePorte && comptees(progression.portesOuvertes()) < PLAFOND_PORTES) {
                ouvrirPorte(porte)
            } else {
                annonce = "Ta porte « ${porte.titre} » est prête."
            }
        }
    }

    val direBonneNuit: () -> Unit = {
        journal.note("bonne_nuit")
        if (etape == Etape.Fil && !marqueGardee) garderMarquePage(explicite = false)
        menu = null
        etoiles = true
    }
    val rejouer: () -> Unit = {
        journal.note("rejouer_test")
        progression.reinitialiserPourTest()
        onRejouer()
    }
    val allerAutrePorte: () -> Unit = {
        journal.note("autre_porte_ouverte")
        menu = null
        etape = Etape.AutrePorte
    }
    val noter: (Porte, Int) -> Unit = { porte, note ->
        progression.noterPorte(lot.jour, porte, note)
        journal.note("note_porte", "porte" to porte.id, "note" to note)
        if (!lotExemple) portee.launch { distant.noterPorte(lot.jour, porte, note) }
    }

    // Le geste retour, pendant la soirée, ouvre le menu (la soirée reste gardée).
    BackHandler(enabled = menu == null && reglages == null && !etoiles) {
        journal.note("menu_ouvert", "par" to "retour")
        menu = PageMenu.Accueil
    }

    Box(Modifier.fillMaxSize()) {
        CielDuSoir(nuit = if (etoiles) 1f else nuit, etoiles = etoiles)

        Crossfade(targetState = etape, label = "etape") { e ->
            when (e) {
                Etape.Echauffement -> EcranEchauffement(
                    revisions = revisions,
                    journal = journal,
                    onResultat = { id, reussi -> progression.resultatRevision(id, reussi) },
                    onFini = { toutJuste -> etape = if (toutJuste) Etape.Pepite else Etape.Portes },
                    onPasser = {
                        journal.note("echauffement_passe")
                        etape = Etape.Portes
                    },
                )

                Etape.Pepite -> {
                    LaunchedEffect(Unit) { soiree.marquerPepiteVue() }
                    EcranPepite(lot.pepite) { etape = Etape.Portes }
                }

                Etape.Portes -> EcranPortes(
                    portes = lot.portes,
                    jour = lot.jour,
                    ouvertes = ouvertes,
                    bandeau = bandeau,
                    preparation = (regen as? Regen.Lot)?.preparation,
                    onChoix = { ouvrirPorte(it) },
                )

                Etape.Fil -> porteFil?.let { porte ->
                    EcranFil(
                        porte = porte,
                        inseres = inseres,
                        enAttente = enAttente,
                        attente = (regen as? Regen.Suite)?.takeIf { it.porteId == porte.id },
                        crans = crans,
                        exploration = !lotExemple,
                        apresCoucher = apresCoucher,
                        pontAffiche = pontAffiche,
                        fremir = fremir,
                        journal = journal,
                        coeurs = coeurs,
                        creusagesRestants = (creusagesParSoir - creusagesFaits).coerceAtLeast(0),
                        creusagesParSoir = creusagesParSoir,
                        creuser = { concept, question -> distant.creuser(concept, question) },
                        onCreuser = { creusagesFaits += 1 },
                        onCoeur = { c, carte, texte -> basculerCoeur(c, carte, texte, if (lotExemple) null else lot.jour) },
                        onTropDense = { tropDense() },
                        onPartager = onPartager,
                        onProgres = { progresFil = it },
                        onConcept = { conceptCourant = it },
                        onPosition = { p ->
                            position = p
                            demanderCrans(p.concept)
                        },
                        onSortie = { depuis, sens ->
                            inserer(depuis.id, sens) ?: run {
                                enAttente = depuis.id to sens
                                demanderCrans(depuis)
                                null
                            }
                        },
                        onGarder = {
                            garderMarquePage(explicite = true)
                            marqueGardee = true
                            annonce = "Marque-page posé : la suite t'attendra demain, en tête des portes."
                            finirPorte(porte)
                        },
                        onPontVu = { id ->
                            if (pontDuSoir == null) {
                                soiree.montrerPont(id)
                                pontDuSoir = id
                                journal.note("pont_vu", "concept" to id)
                            }
                        },
                        onCarrefourVu = { soiree.carrefoursVus = soiree.carrefoursVus + 1 },
                        onLireAncienne = {
                            journal.note("regeneration_annulee", "type" to "suite")
                            tache?.cancel()
                            regen = null
                        },
                        onFin = { finirPorte(porte) },
                    )
                }

                Etape.Recap -> EcranRecap(
                    concepts = conceptsDuSoir,
                    porte = porteDuRecap,
                    note = porteDuRecap?.let { progression.note(lot.jour, it.id) },
                    teaser = lot.teaserDemain,
                    terrier = progression.tous(),
                    aime = coeurs::conceptAime,
                    nouvelles = nouvellesFeuilles,
                    autrePorte = autrePorteLibelle(peutAutrePorte, comptees(ouvertes)),
                    onNote = noter,
                    onAutrePorte = allerAutrePorte,
                    onBonneNuit = direBonneNuit,
                )

                Etape.AutrePorte -> EcranAutrePorte(
                    restantes = restantes,
                    ouvertes = comptees(ouvertes),
                    plafondAtteint = plafondAtteint,
                    bonus = bonus,
                    onChoix = { ouvrirPorte(it) },
                    onDautres = { composerBonus() },
                    onChoixBonus = { choix, p -> ecrireBonus(choix, p) },
                    onBonneNuit = direBonneNuit,
                )

                Etape.DejaFini -> EcranDejaFini(
                    teaser = lot.teaserDemain,
                    terrier = progression.tous(),
                    aime = coeurs::conceptAime,
                    autrePorte = autrePorteLibelle(peutAutrePorte, comptees(ouvertes)),
                    onAutrePorte = allerAutrePorte,
                    onBonneNuit = direBonneNuit,
                )
            }
        }

        // Icône maison et engrenage, discrets : le menu et les réglages s'ouvrent par-dessus la soirée.
        if (menu == null && reglages == null && !etoiles) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(4.dp),
            ) {
                IconButton(onClick = {
                    journal.note("menu_ouvert", "par" to "maison")
                    menu = PageMenu.Accueil
                }) {
                    Icon(Icons.Filled.Home, contentDescription = "Menu", tint = Encre.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = {
                    journal.note("reglages_ouverts")
                    reglages = false
                }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Réglages", tint = Encre.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
                }
            }
        }

        menu?.let { page ->
            EcranMenu(
                page = page,
                nuit = nuit,
                soiree = EtatSoiree(
                    ceSoir = when (etape) {
                        Etape.Echauffement -> "Reprendre l'échauffement"
                        Etape.Pepite -> "Revoir la pépite"
                        Etape.Portes -> "Choisir ma porte"
                        Etape.Fil -> "Reprendre ma porte"
                        Etape.Recap -> "Revoir le récap"
                        Etape.AutrePorte -> "Choisir une autre porte"
                        Etape.DejaFini -> "Ce soir"
                    },
                    detail = when (etape) {
                        Etape.Fil -> porteFil?.let { "« ${it.titre} »" }.orEmpty()
                        Etape.Portes -> "${lot.portes.size} portes t'attendent"
                        Etape.DejaFini -> if (peutAutrePorte) "Ta porte est lue ; une autre reste possible" else "C'est tout pour aujourd'hui"
                        else -> ""
                    },
                    autrePorte = when {
                        etape == Etape.Fil -> "Finis d'abord ta porte en cours"
                        ouvertes.isEmpty() -> "Après ta première porte"
                        plafondAtteint -> "Plafond atteint : $PLAFOND_PORTES portes ce soir"
                        else -> "${restantes.size} restante${if (restantes.size > 1) "s" else ""}, ou 3 neuves · ${comptees(ouvertes)}/$PLAFOND_PORTES ce soir"
                    },
                    autrePortePossible = peutAutrePorte,
                ),
                progression = progression,
                coeurs = coeurs,
                distant = distant,
                journal = journal,
                creusagesRestants = (creusagesParSoir - creusagesFaits).coerceAtLeast(0),
                creusagesParSoir = creusagesParSoir,
                partage = partageEntrant,
                actions = ActionsMenu(
                    onPage = { menu = it },
                    onCeSoir = {
                        journal.note("menu_ce_soir")
                        menu = null
                    },
                    onAutrePorte = allerAutrePorte,
                    onReglages = { fiche -> reglages = fiche },
                    onCoeur = basculerCoeur,
                    onSource = { url -> runCatching { uri.openUri(url) } },
                    onPartager = onPartager,
                    onAnnonce = { annonce = it },
                    onCreuse = { creusagesFaits += 1 },
                    onQuitter = {
                        journal.note("quitter_menu")
                        if (etape == Etape.Fil && !marqueGardee) garderMarquePage(explicite = false)
                        onQuitter()
                    },
                ),
            )
        }

        reglages?.let { fiche ->
            EcranReglages(
                distant = distant,
                soiree = soiree,
                journal = journal,
                etatLot = if (lotExemple) "lot d'exemple" else "serveur, ${lot.jour}",
                ficheDirecte = fiche,
                onFermer = fermerReglages,
                onRejouer = {
                    reglages = null
                    rejouer()
                },
                onRegenererLot = {
                    reglages = null
                    menu = null
                    lancerRegenLot(reprise = false)
                },
                onAnnonce = { annonce = it },
            )
        }

        annonce?.let { texte ->
            Text(
                texte,
                color = Encre,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 56.dp, start = 24.dp, end = 24.dp)
                    .background(Color(0xEE1A1630), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private fun autrePorteLibelle(possible: Boolean, ouvertes: Int): String? =
    if (possible) "Une autre porte ? (${ouvertes}/$PLAFOND_PORTES ce soir)" else null

// ---------------------------------------------------------------- Briques communes

@Composable
private fun Colonne(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun Panneau(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Voile, RoundedCornerShape(16.dp))
            .padding(20.dp),
        content = content,
    )
}

@Composable
internal fun Etiquette(texte: String) {
    Text(
        texte.uppercase(),
        color = Ambre,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

// ---------------------------------------------------------------- Échauffement

@Composable
private fun EcranEchauffement(
    revisions: List<ConceptVu>,
    journal: Journal,
    onResultat: (String, Boolean) -> Unit,
    onFini: (Boolean) -> Unit,
    onPasser: () -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    var choix by remember { mutableStateOf<Int?>(null) }
    var toutJuste by remember { mutableStateOf(true) }
    val vu = revisions[index]
    val qcm = vu.concept.qcm
    val dernier = index + 1 >= revisions.size

    Colonne {
        // Place pour l'engrenage des réglages, en haut à droite.
        Spacer(Modifier.height(40.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Etiquette("Échauffement · ${index + 1}/${revisions.size}")
            TextButton(onClick = onPasser) { Text("Passer", color = EncreDouce) }
        }
        Spacer(Modifier.height(32.dp))
        Text(qcm.question, color = Encre, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        val reponse = choix
        qcm.choix.forEachIndexed { i, libelle ->
            val bord = when {
                reponse == null -> Encre.copy(alpha = 0.35f)
                i == qcm.bonne -> Color(0xFF8FD18B)
                i == reponse -> Color(0xFFE38B7A)
                else -> Encre.copy(alpha = 0.15f)
            }
            OutlinedButton(
                onClick = {
                    if (choix == null) {
                        choix = i
                        val reussi = i == qcm.bonne
                        if (!reussi) toutJuste = false
                        onResultat(vu.concept.id, reussi)
                        journal.note("qcm", "concept" to vu.concept.id, "reussi" to reussi)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                border = BorderStroke(1.dp, bord),
            ) {
                Text(libelle, color = Encre)
            }
        }

        if (reponse != null) {
            Spacer(Modifier.height(16.dp))
            if (reponse != qcm.bonne) {
                Text(qcm.autreAngle, color = EncreDouce, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = {
                    if (dernier) {
                        onFini(toutJuste)
                    } else {
                        index += 1
                        choix = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (dernier) "Continuer" else "Suivant")
            }
        }
    }
}

// ---------------------------------------------------------------- Pépite

@Composable
private fun EcranPepite(pepite: Carte, onSuite: () -> Unit) {
    Colonne {
        Spacer(Modifier.height(64.dp))
        Panneau(Modifier.border(1.dp, Ambre, RoundedCornerShape(16.dp))) {
            Etiquette("Pépite")
            Spacer(Modifier.height(12.dp))
            Text(pepite.texte, color = Encre, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSuite, modifier = Modifier.fillMaxWidth()) { Text("Choisir ma porte") }
    }
}

// ---------------------------------------------------------------- Portes

@Composable
private fun EcranPortes(
    portes: List<Porte>,
    jour: String,
    ouvertes: Set<String>,
    bandeau: String?,
    preparation: Preparation?,
    onChoix: (Porte) -> Unit,
) {
    // La reprise d'hier s'affiche en tête, à part ; les portes bonus d'un soir précédent de la
    // journée s'ajoutent au lot : on garde les 3 du matin.
    val reprise = portes.firstOrNull { it.reprise && it.id !in ouvertes }
    val regulieres = portes.filter { !it.reprise }.take(3)
    Colonne {
        Spacer(Modifier.height(40.dp))
        if (bandeau != null) {
            Text(bandeau, color = EncreDouce.copy(alpha = 0.7f), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
        if (preparation != null) {
            Text(
                "De nouvelles portes se préparent avec tes réglages (étape ${preparation.etape} sur ${preparation.total}). " +
                    "Celles-ci restent ouvertes en attendant.",
                color = EncreDouce.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (reprise != null) {
            CartePorte(reprise, jour, enTete = true) { onChoix(reprise) }
            Spacer(Modifier.height(14.dp))
        }
        Etiquette("Ce soir")
        Spacer(Modifier.height(6.dp))
        Text(
            when (regulieres.size) {
                1 -> "Une porte. Tu la pousses ?"
                2 -> "Deux portes. Laquelle tu pousses ?"
                else -> "Trois portes. Laquelle tu pousses ?"
            },
            color = Encre,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(20.dp))
        regulieres.forEach { porte ->
            CartePorte(porte, jour) { onChoix(porte) }
        }
    }
}

/** Il y a combien d'années, jour pour jour, pour une porte éphéméride. */
private fun ilYaAns(porte: Porte, jour: String): Int? {
    val annee = porte.ephemeride?.annee ?: return null
    val maintenant = jour.take(4).toIntOrNull() ?: return null
    return (maintenant - annee).takeIf { it > 0 }
}

@Composable
private fun CartePorte(porte: Porte, jour: String, enTete: Boolean = false, onClick: () -> Unit) {
    CartePorte(
        titre = porte.titre,
        accroche = porte.accroche,
        mystere = porte.mystere,
        indice = porte.indice,
        etiquette = when {
            porte.reprise -> "Reprendre là où tu t'es arrêté"
            else -> ilYaAns(porte, jour)?.let { "Il y a $it ans, jour pour jour" }
        },
        detail = porte.ephemeride?.evenement?.takeIf { it.isNotBlank() && !porte.reprise },
        bordure = if (enTete) Ambre.copy(alpha = 0.7f) else null,
        onClick = onClick,
    )
}

@Composable
private fun CartePorte(
    titre: String,
    accroche: String,
    mystere: Boolean,
    indice: String?,
    etiquette: String? = null,
    detail: String? = null,
    bordure: Color? = null,
    onClick: () -> Unit,
) {
    Panneau(
        Modifier
            .padding(vertical = 6.dp)
            .then(if (bordure != null) Modifier.border(1.dp, bordure, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        if (etiquette != null) {
            Etiquette(etiquette)
            Spacer(Modifier.height(6.dp))
        }
        if (mystere) {
            if (etiquette == null) {
                Etiquette("Porte mystère")
                Spacer(Modifier.height(6.dp))
            }
            Text(
                indice ?: "…",
                color = Encre,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
            )
        } else {
            Text(
                titre,
                color = Encre,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(accroche, color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, color = EncreDouce.copy(alpha = 0.75f), fontSize = 12.sp, fontStyle = FontStyle.Italic)
        }
    }
}

// ---------------------------------------------------------------- Autre porte

@Composable
private fun EcranAutrePorte(
    restantes: List<Porte>,
    ouvertes: Int,
    plafondAtteint: Boolean,
    bonus: Bonus,
    onChoix: (Porte) -> Unit,
    onDautres: () -> Unit,
    onChoixBonus: (Bonus.Choix, PorteProposee) -> Unit,
    onBonneNuit: () -> Unit,
) {
    Colonne {
        Spacer(Modifier.height(40.dp))
        Etiquette("Autre porte · $ouvertes/$PLAFOND_PORTES ce soir")
        Spacer(Modifier.height(6.dp))
        if (plafondAtteint) {
            Text(
                "Trois portes ce soir : le fil est bouclé. La suite demain matin.",
                color = Encre,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBonneNuit, modifier = Modifier.fillMaxWidth()) { Text("Bonne nuit") }
        } else {
            ChoixAutrePorte(restantes, bonus, onChoix, onDautres, onChoixBonus, onBonneNuit)
        }
    }
}

@Composable
private fun ColumnScope.ChoixAutrePorte(
    restantes: List<Porte>,
    bonus: Bonus,
    onChoix: (Porte) -> Unit,
    onDautres: () -> Unit,
    onChoixBonus: (Bonus.Choix, PorteProposee) -> Unit,
    onBonneNuit: () -> Unit,
) {
    Text("Encore une ?", color = Encre, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))

    if (restantes.isNotEmpty()) {
        Etiquette("Les portes de ce soir")
        Spacer(Modifier.height(4.dp))
        restantes.forEach { porte ->
            CartePorte(porte, "") { onChoix(porte) }
        }
        Spacer(Modifier.height(16.dp))
    }

    when (bonus) {
        Bonus.Aucun, is Bonus.Echec -> {
            if (bonus is Bonus.Echec) {
                Text(bonus.message, color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onDautres, modifier = Modifier.fillMaxWidth()) {
                Text(if (restantes.isEmpty()) "Composer 3 portes neuves" else "Aucune ne me tente : d'autres portes", color = Encre)
            }
            Text(
                "Le conteur en compose 3 en une vingtaine de secondes ; celle que tu choisis s'écrit en une minute environ.",
                color = EncreDouce.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Bonus.Composition -> {
            Etiquette("Portes neuves")
            Spacer(Modifier.height(8.dp))
            Text("Le conteur compose trois portes…", color = Encre, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Ambre, trackColor = Voile)
        }

        is Bonus.Choix -> {
            Etiquette("Portes neuves")
            Spacer(Modifier.height(4.dp))
            bonus.portes.forEach { p ->
                CartePorte(p.titre, p.accroche, p.mystere, p.indice) { onChoixBonus(bonus, p) }
            }
            TextButton(onClick = onDautres) { Text("Recomposer", color = EncreDouce) }
        }

        is Bonus.Ecriture -> {
            Etiquette("Porte neuve")
            Spacer(Modifier.height(8.dp))
            Text(
                "Le conteur écrit « ${if (bonus.porte.mystere) "la porte mystère" else bonus.porte.titre} »…",
                color = Encre,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { bonus.preparation.etape.toFloat() / bonus.preparation.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = Ambre,
                trackColor = Voile,
            )
            Text(
                "Étape ${bonus.preparation.etape} sur ${bonus.preparation.total}",
                color = EncreDouce,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onBonneNuit, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Non, bonne nuit", color = EncreDouce)
    }
}

// ---------------------------------------------------------------- Récap et fin

@Composable
private fun EcranRecap(
    concepts: List<Concept>,
    porte: Porte?,
    note: Int?,
    teaser: String,
    terrier: List<ConceptVu>,
    aime: (String) -> Boolean,
    nouvelles: Set<String>,
    autrePorte: String?,
    onNote: (Porte, Int) -> Unit,
    onAutrePorte: () -> Unit,
    onBonneNuit: () -> Unit,
) {
    Colonne {
        Spacer(Modifier.height(32.dp))
        Etiquette("Ce soir, tu as appris")
        Spacer(Modifier.height(12.dp))
        concepts.filter { it.aRetenir.isNotBlank() }.forEach { c ->
            Text(
                (if (c.estCran) "↳ " else "• ") + c.aRetenir,
                color = if (c.estCran) EncreDouce else Encre,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = if (c.estCran) 12.dp else 0.dp),
            )
        }
        if (porte != null) {
            Spacer(Modifier.height(20.dp))
            NoteDePorte(porte, note) { onNote(porte, it) }
        }
        FinDeSoiree(teaser, terrier, aime, nouvelles, autrePorte, onAutrePorte, onBonneNuit)
    }
}

/**
 * La note de la porte, de 1 à 10, facultative : deux rangées de 5 pastilles sous le pouce,
 * et « passer ». Une note de 8 ou plus annonce que sa suite attendra demain.
 */
@Composable
private fun ColumnScope.NoteDePorte(porte: Porte, noteInitiale: Int?, onNote: (Int) -> Unit) {
    var note by remember(porte.id) { mutableStateOf(noteInitiale) }
    var passee by remember(porte.id) { mutableStateOf(false) }
    if (passee && note == null) return
    Etiquette("Ta note pour cette porte")
    Spacer(Modifier.height(4.dp))
    Text(
        "« ${porte.titre} »",
        color = EncreDouce,
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
    )
    Spacer(Modifier.height(10.dp))
    listOf(1..5, 6..10).forEach { rangee ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            rangee.forEach { n ->
                val choisie = note == n
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(44.dp)
                        .background(if (choisie) Ambre else Voile, CircleShape)
                        .border(1.dp, Ambre.copy(alpha = if (choisie) 1f else 0.35f), CircleShape)
                        .clickable {
                            note = n
                            onNote(n)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        color = if (choisie) Color(0xFF2A1A10) else Encre,
                        fontWeight = if (choisie) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
    val n = note
    when {
        n == null -> TextButton(onClick = { passee = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Passer", color = EncreDouce)
        }
        n >= 8 -> Text(
            "Sa suite t'attendra demain.",
            color = Ambre,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )
        else -> Text(
            "Noté.",
            color = EncreDouce,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )
    }
}

@Composable
private fun EcranDejaFini(
    teaser: String,
    terrier: List<ConceptVu>,
    aime: (String) -> Boolean,
    autrePorte: String?,
    onAutrePorte: () -> Unit,
    onBonneNuit: () -> Unit,
) {
    Colonne {
        Spacer(Modifier.height(32.dp))
        Etiquette("C'est tout pour aujourd'hui")
        Spacer(Modifier.height(12.dp))
        Text(
            if (autrePorte != null) {
                "Ta porte du soir est lue. Si l'envie dure, une autre t'attend ; sinon, à demain."
            } else {
                "Le lot du jour est fini. Le prochain sera prêt demain matin."
            },
            color = Encre,
            style = MaterialTheme.typography.bodyLarge,
        )
        FinDeSoiree(teaser, terrier, aime, emptySet(), autrePorte, onAutrePorte, onBonneNuit)
    }
}

@Composable
private fun ColumnScope.FinDeSoiree(
    teaser: String,
    terrier: List<ConceptVu>,
    aime: (String) -> Boolean,
    nouvelles: Set<String>,
    autrePorte: String?,
    onAutrePorte: () -> Unit,
    onBonneNuit: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Etiquette("Demain")
    Spacer(Modifier.height(6.dp))
    Text(teaser, color = EncreDouce, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
    Spacer(Modifier.height(16.dp))
    // L'arbre du terrier : les feuilles du soir poussent sous les yeux.
    Arbre(
        concepts = terrier,
        aime = aime,
        nouvelles = nouvelles,
        modifier = Modifier.fillMaxWidth().height(220.dp),
    )
    val fruits = terrier.count { it.maitrise }
    Text(
        "Ton terrier : ${terrier.size} concept${if (terrier.size > 1) "s" else ""}" +
            (if (nouvelles.isNotEmpty()) ", dont ${nouvelles.size} de ce soir" else "") +
            (if (fruits > 0) " · $fruits fruit${if (fruits > 1) "s" else ""}" else ""),
        color = Ambre,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.height(24.dp))
    if (autrePorte != null) {
        OutlinedButton(onClick = onAutrePorte, modifier = Modifier.fillMaxWidth()) {
            Text(autrePorte, color = Encre)
        }
        Spacer(Modifier.height(8.dp))
    }
    Button(onClick = onBonneNuit, modifier = Modifier.fillMaxWidth()) { Text("Bonne nuit") }
}
