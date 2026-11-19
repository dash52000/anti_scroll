package fr.dash.chevet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dash.chevet.data.Coeurs
import fr.dash.chevet.data.ConceptVu
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Plafond du fil borné : 3 portes ouvertes par soir au plus. */
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
    var etape by remember {
        mutableStateOf<Etape>(
            when {
                progression.lotConsomme() || ouvertes.size >= PLAFOND_PORTES -> Etape.DejaFini
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

    val restantes = lot.portes.filter { it.id !in ouvertes }
    val plafondAtteint = ouvertes.size >= PLAFOND_PORTES
    val peutAutrePorte = !plafondAtteint && ouvertes.isNotEmpty() && etape != Etape.Fil

    // La nuit tombe au fil du rituel ; chaque nouvelle porte repart d'un ciel un peu plus sombre.
    val debutFil = (0.25f + 0.15f * (ouvertes.size - 1).coerceAtLeast(0)).coerceAtMost(0.55f)
    val nuit = when (etape) {
        Etape.Echauffement -> 0.05f
        Etape.Pepite -> 0.12f
        Etape.Portes -> 0.2f
        Etape.Fil -> debutFil + (0.85f - debutFil) * progresFil
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
                if (porte != null && !lotExemple && gardes < CONCEPTS_PAR_PORTE) {
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
        journal.note("porte_choisie", "porte" to porte.id, "rang" to ouvertes.size)
        porteFil = porte
        conceptCourant = 0
        progresFil = 0f
        etape = Etape.Fil
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
            if (etape == Etape.AutrePorte && progression.portesOuvertes().size < PLAFOND_PORTES) {
                ouvrirPorte(porte)
            } else {
                annonce = "Ta porte « ${porte.titre} » est prête."
            }
        }
    }

    val direBonneNuit: () -> Unit = {
        journal.note("bonne_nuit")
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
                    bandeau = bandeau,
                    preparation = (regen as? Regen.Lot)?.preparation,
                    onChoix = { ouvrirPorte(it) },
                )

                Etape.Fil -> porteFil?.let { porte ->
                    EcranFil(
                        porte = porte,
                        attente = (regen as? Regen.Suite)?.takeIf { it.porteId == porte.id },
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
                        onLireAncienne = {
                            journal.note("regeneration_annulee", "type" to "suite")
                            tache?.cancel()
                            regen = null
                        },
                        onFin = {
                            progression.marquerVus(porte)
                            progression.marquerLotConsomme()
                            onConceptsExplores(porte.concepts)
                            conceptsDuSoir = porte.concepts
                            nouvellesFeuilles = porte.concepts.map { it.id }.toSet()
                            journal.note("fil_termine", "porte" to porte.id)
                            etape = Etape.Recap
                        },
                    )
                }

                Etape.Recap -> EcranRecap(
                    concepts = conceptsDuSoir,
                    teaser = lot.teaserDemain,
                    terrier = progression.tous(),
                    aime = coeurs::conceptAime,
                    nouvelles = nouvellesFeuilles,
                    autrePorte = autrePorteLibelle(peutAutrePorte, ouvertes.size),
                    onAutrePorte = allerAutrePorte,
                    onBonneNuit = direBonneNuit,
                )

                Etape.AutrePorte -> EcranAutrePorte(
                    restantes = restantes,
                    ouvertes = ouvertes.size,
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
                    autrePorte = autrePorteLibelle(peutAutrePorte, ouvertes.size),
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
                        else -> "${restantes.size} restante${if (restantes.size > 1) "s" else ""}, ou 3 neuves · ${ouvertes.size}/$PLAFOND_PORTES ce soir"
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
    bandeau: String?,
    preparation: Preparation?,
    onChoix: (Porte) -> Unit,
) {
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
        Etiquette("Ce soir")
        Spacer(Modifier.height(6.dp))
        Text("Trois portes. Laquelle tu pousses ?", color = Encre, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        // Les portes bonus d'un soir précédent de la journée s'ajoutent au lot : on garde les 3 du matin.
        portes.take(3).forEach { porte ->
            CartePorte(porte.titre, porte.accroche, porte.mystere, porte.indice) { onChoix(porte) }
        }
    }
}

@Composable
private fun CartePorte(titre: String, accroche: String, mystere: Boolean, indice: String?, onClick: () -> Unit) {
    Panneau(
        Modifier
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        if (mystere) {
            Etiquette("Porte mystère")
            Spacer(Modifier.height(6.dp))
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
            CartePorte(porte.titre, porte.accroche, porte.mystere, porte.indice) { onChoix(porte) }
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

// ---------------------------------------------------------------- Fil de cartes

private sealed interface Page {
    val concept: Concept

    data class Question(override val concept: Concept) : Page
    data class Texte(override val concept: Concept, val carte: Carte, val numero: Int) : Page
    data class ARetenir(override val concept: Concept) : Page

    /** La suite se réécrit : page d'attente après le dernier concept gardé. */
    data class Attente(override val concept: Concept) : Page
}

private enum class Action(val libelle: String, val icone: ImageVector, val code: String, val retour: String) {
    Jaime("J'aime", Icons.Filled.FavoriteBorder, "like", "Noté : plus de ce genre"),
    Bof("Bof", Icons.Filled.Close, "pas_pour_moi", "Noté : moins de ce genre"),
    Creuser("Creuser", Icons.Filled.Search, "creuser", ""),
    JeSavais("Je savais", Icons.Filled.Check, "je_savais", "Noté : on montera d'un cran"),
    Dense("Trop dense", Icons.Filled.Info, "trop_dense", "Noté : on simplifiera"),
    Signaler("Signaler", Icons.Filled.Warning, "signaler", "Carte signalée"),
    Partager("Partager", Icons.Filled.Share, "partager", ""),
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EcranFil(
    porte: Porte,
    attente: Regen.Suite?,
    journal: Journal,
    coeurs: Coeurs,
    creusagesRestants: Int,
    creusagesParSoir: Int,
    creuser: suspend (Concept, String) -> String,
    onCreuser: () -> Unit,
    onCoeur: (Concept, Int, String) -> Unit,
    onTropDense: () -> Unit,
    onPartager: (String) -> Unit,
    onProgres: (Float) -> Unit,
    onConcept: (Int) -> Unit,
    onLireAncienne: () -> Unit,
    onFin: () -> Unit,
) {
    // Pendant la réécriture de la suite, seuls les concepts gardés restent, suivis d'une page d'attente.
    val pages = remember(porte, attente?.gardes) {
        val concepts = if (attente != null) porte.concepts.take(attente.gardes) else porte.concepts
        val base = concepts.flatMap { c ->
            listOf<Page>(Page.Question(c)) +
                c.cartes.mapIndexed { i, carte -> Page.Texte(c, carte, i) } +
                Page.ARetenir(c)
        }
        if (attente != null && concepts.isNotEmpty()) base + Page.Attente(concepts.last()) else base
    }
    val pagesActuelles by rememberUpdatedState(pages)
    val porteActuelle by rememberUpdatedState(porte)
    val etatPager = rememberPagerState(pageCount = { pages.size })
    var message by remember { mutableStateOf<String?>(null) }
    var voletCreuser by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    // Journal : temps passé sur chaque carte, progression de la nuit et concept en cours.
    LaunchedEffect(etatPager) {
        var precedente = etatPager.currentPage
        var debut = System.currentTimeMillis()
        snapshotFlow { etatPager.currentPage }.collect { page ->
            val liste = pagesActuelles
            val maintenant = System.currentTimeMillis()
            if (page != precedente && precedente in liste.indices) {
                journal.note(
                    "carte_vue",
                    "concept" to liste[precedente].concept.id,
                    "page" to precedente,
                    "duree_ms" to (maintenant - debut),
                )
            }
            precedente = page
            debut = maintenant
            onProgres((page + 1).toFloat() / liste.size.coerceAtLeast(1))
            liste.getOrNull(page)?.let { p ->
                val index = porteActuelle.concepts.indexOfFirst { it.id == p.concept.id }
                if (index >= 0) onConcept(index)
            }
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(1600)
            message = null
        }
    }

    val pageCourante = pages[etatPager.currentPage.coerceIn(0, pages.lastIndex)]
    val concept = pageCourante.concept
    // Le cœur se pose sur la page affichée : une carte, la phrase à retenir (-1) ou la question (-2).
    val cibleCoeur: Pair<Int, String>? = when (pageCourante) {
        is Page.Texte -> pageCourante.numero to pageCourante.carte.texte
        is Page.ARetenir -> -1 to concept.aRetenir
        is Page.Question -> -2 to concept.question
        is Page.Attente -> null
    }
    val aimee = cibleCoeur != null && coeurs.aime(concept.id, cibleCoeur.first)

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            VerticalPager(state = etatPager, modifier = Modifier.fillMaxSize()) { i ->
                val page = pages.getOrNull(i)
                when {
                    page == null -> {}
                    page is Page.Attente -> PageAttente(porte.titre, attente?.preparation, onLireAncienne)
                    else -> PageCarte(
                        page = page,
                        estDerniere = i == pages.lastIndex,
                        onFin = onFin,
                        onSource = { url -> runCatching { uri.openUri(url) } },
                    )
                }
            }
            message?.let { texte ->
                Text(
                    texte,
                    color = Encre,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Voile, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        BarreActions(aimee = aimee) { action ->
            when (action) {
                Action.Jaime -> {
                    // Le journal note « like » ou « unlike » au moment de basculer.
                    val (carte, texte) = cibleCoeur ?: return@BarreActions
                    onCoeur(concept, carte, texte)
                    if (!aimee) message = action.retour
                }
                Action.Creuser -> {
                    journal.note(action.code, "concept" to concept.id, "page" to etatPager.currentPage)
                    voletCreuser = true
                }
                Action.Partager -> {
                    journal.note(action.code, "concept" to concept.id, "page" to etatPager.currentPage)
                    onPartager("${concept.aRaconter}\n\n(via Chevet)")
                }
                Action.Dense -> {
                    journal.note(action.code, "concept" to concept.id, "page" to etatPager.currentPage)
                    message = action.retour
                    onTropDense()
                }
                else -> {
                    journal.note(action.code, "concept" to concept.id, "page" to etatPager.currentPage)
                    message = action.retour
                }
            }
        }
    }

    if (voletCreuser) {
        ModalBottomSheet(
            onDismissRequest = { voletCreuser = false },
            containerColor = Color(0xFF1A1630),
        ) {
            VoletCreuser(
                concept = concept,
                restants = creusagesRestants,
                parSoir = creusagesParSoir,
                creuser = creuser,
                onCreuser = { question ->
                    journal.note("creuser_question", "concept" to concept.id, "question" to question)
                    onCreuser()
                },
                onFermer = { voletCreuser = false },
            )
        }
    }
}

@Composable
private fun PageCarte(page: Page, estDerniere: Boolean, onFin: () -> Unit, onSource: (String) -> Unit) {
    val c = page.concept
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Panneau {
            when (page) {
                is Page.Question -> {
                    Etiquette(c.theme)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        c.titre,
                        color = Encre,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        c.question,
                        color = Encre,
                        style = MaterialTheme.typography.headlineSmall,
                        fontStyle = FontStyle.Italic,
                    )
                }

                is Page.Texte -> {
                    Etiquette("${c.titre} · ${page.numero + 1}/${c.cartes.size}")
                    Spacer(Modifier.height(12.dp))
                    Text(page.carte.texte, color = Encre, style = MaterialTheme.typography.titleLarge)
                }

                is Page.Attente -> Unit

                is Page.ARetenir -> {
                    if (c.aRetenir.isNotBlank()) {
                        Etiquette("À retenir")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            c.aRetenir,
                            color = Encre,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    c.ordreDeGrandeur?.takeIf { it.isNotBlank() }?.let { chiffre ->
                        Spacer(Modifier.height(12.dp))
                        Text(chiffre, color = Ambre, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (c.aRaconter.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Etiquette("À raconter")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            c.aRaconter,
                            color = EncreDouce,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Source : ${c.source.titre}",
                color = EncreDouce.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.clickable { onSource(c.source.url) },
            )
        }

        if (estDerniere) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onFin, modifier = Modifier.fillMaxWidth()) { Text("Voir le récap") }
        } else if (page is Page.Question) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Glisse vers le haut",
                color = EncreDouce.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun PageAttente(titrePorte: String, preparation: Preparation?, onLireAncienne: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Panneau {
            Etiquette("La suite se réécrit")
            Spacer(Modifier.height(12.dp))
            Text(
                "Le conteur réécrit la suite de « $titrePorte » avec tes nouveaux réglages.",
                color = Encre,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(20.dp))
            val p = preparation ?: Preparation(0, 4)
            LinearProgressIndicator(
                progress = { p.etape.toFloat() / p.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = Ambre,
                trackColor = Voile,
            )
            Spacer(Modifier.height(8.dp))
            Text("Étape ${p.etape} sur ${p.total}", color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onLireAncienne, modifier = Modifier.fillMaxWidth()) {
            Text("Lire l'ancienne version", color = Encre)
        }
    }
}

@Composable
private fun BarreActions(aimee: Boolean, onAction: (Action) -> Unit) {
    // Le cœur se remplit avec un petit rebond.
    val rebond by animateFloatAsState(if (aimee) 1f else 0f, label = "coeur")
    Row(
        Modifier
            .fillMaxWidth()
            .background(Voile)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Action.entries.forEach { action ->
            val coeur = action == Action.Jaime
            Column(
                Modifier
                    .clickable { onAction(action) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (coeur) {
                    val echelle = 1f + 0.3f * kotlin.math.sin(rebond * Math.PI.toFloat())
                    Icon(
                        if (aimee) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (aimee) "Retirer le cœur" else action.libelle,
                        tint = if (aimee) Ambre else Encre,
                        modifier = Modifier.size((24 * echelle).dp),
                    )
                } else {
                    Icon(action.icone, contentDescription = action.libelle, tint = Encre)
                }
                Text(action.libelle, color = if (coeur && aimee) Ambre else EncreDouce, fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun VoletCreuser(
    concept: Concept,
    restants: Int,
    parSoir: Int,
    creuser: suspend (Concept, String) -> String,
    onCreuser: (String) -> Unit,
    onFermer: () -> Unit,
) {
    var question by remember { mutableStateOf<String?>(null) }
    var reponseIa by remember { mutableStateOf<String?>(null) }
    val portee = rememberCoroutineScope()
    val suggestions = concept.creuser.ifEmpty { questionsSuggerees(concept) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Etiquette("Creuser · $restants/$parSoir ce soir")
        Spacer(Modifier.height(12.dp))
        val choisie = question
        when {
            choisie != null -> {
                Text(choisie, color = Encre, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    reponseIa ?: "Le conteur réfléchit…",
                    color = if (reponseIa == null) EncreDouce else Encre,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            restants <= 0 -> Text(
                "Quota du soir atteint. Ta question attendra demain : elle pourra devenir une porte.",
                color = EncreDouce,
            )

            else -> suggestions.forEach { q ->
                OutlinedButton(
                    onClick = {
                        question = q
                        onCreuser(q)
                        portee.launch { reponseIa = creuser(concept, q) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(q, color = Encre)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onFermer) { Text("Fermer", color = EncreDouce) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Questions de secours quand le lot n'en propose pas. */
internal fun questionsSuggerees(c: Concept) = listOf(
    "D'où vient « ${c.titre} » ?",
    "Un exemple concret, près de chez moi ?",
    "Et aujourd'hui, où en est-on ?",
)

// ---------------------------------------------------------------- Récap et fin

@Composable
private fun EcranRecap(
    concepts: List<Concept>,
    teaser: String,
    terrier: List<ConceptVu>,
    aime: (String) -> Boolean,
    nouvelles: Set<String>,
    autrePorte: String?,
    onAutrePorte: () -> Unit,
    onBonneNuit: () -> Unit,
) {
    Colonne {
        Spacer(Modifier.height(32.dp))
        Etiquette("Ce soir, tu as appris")
        Spacer(Modifier.height(12.dp))
        concepts.filter { it.aRetenir.isNotBlank() }.forEach { c ->
            Text(
                "• ${c.aRetenir}",
                color = Encre,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        FinDeSoiree(teaser, terrier, aime, nouvelles, autrePorte, onAutrePorte, onBonneNuit)
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
