package fr.dash.chevet.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dash.chevet.data.Coeurs
import fr.dash.chevet.data.Crans
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.Preparation
import fr.dash.chevet.model.Carte
import fr.dash.chevet.model.Concept
import fr.dash.chevet.model.Porte
import fr.dash.chevet.model.Sens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Couleur du contre-point : un bleu de nuit, pour qu'on voie tout de suite que c'est l'autre camp. */
private val BleuContre = Color(0xFF9DB7E0)

/** Couleur des ponts : le fil doré du graphe. */
internal val Dore = Color(0xFFF2C14E)

/** Seuil du geste horizontal, en part de la largeur de l'écran. */
private const val SEUIL_GESTE = 0.26f

// ---------------------------------------------------------------- Ordre du fil

/**
 * Le fil de la soirée : les concepts de la porte, chacun suivi des crans ouverts depuis son carrefour.
 * Le dernier cran ouvert se lit juste après le carrefour d'où il part.
 */
internal fun ordreDuFil(principaux: List<Concept>, inseres: List<Concept>): List<Concept> {
    val enfants = inseres.groupBy { it.depuis }
    val vus = mutableSetOf<String>()
    val resultat = mutableListOf<Concept>()
    fun ajouter(c: Concept) {
        if (!vus.add(c.id)) return
        resultat += c
        enfants[c.id].orEmpty().asReversed().forEach { ajouter(it) }
    }
    principaux.forEach { ajouter(it) }
    return resultat
}

/** Le chemin de titres qui mène à [c] depuis la porte : sert à ne pas reproposer ce qui est déjà lu. */
internal fun cheminVers(c: Concept, fil: List<Concept>): List<String> {
    val parId = fil.associateBy { it.id }
    val chemin = mutableListOf<String>()
    var courant = c.depuis?.let { parId[it] }
    while (courant != null && chemin.size < 8) {
        chemin.add(0, courant.titre)
        courant = courant.depuis?.let { parId[it] }
    }
    return chemin
}

// ---------------------------------------------------------------- Pages et actions

private sealed interface Page {
    val concept: Concept
    val cle: String

    data class Question(override val concept: Concept) : Page {
        override val cle get() = "${concept.id}:q"
    }

    data class Texte(override val concept: Concept, val carte: Carte, val numero: Int) : Page {
        override val cle get() = "${concept.id}:t$numero"
    }

    data class ContrePoint(override val concept: Concept) : Page {
        override val cle get() = "${concept.id}:cp"
    }

    data class PagePont(override val concept: Concept) : Page {
        override val cle get() = "${concept.id}:pont"
    }

    /** La page « À retenir » : le carrefour d'où partent « plus loin » et « à côté ». */
    data class Carrefour(override val concept: Concept) : Page {
        override val cle get() = "${concept.id}:car"
    }

    /** La suite de la porte se réécrit (réglages changés) : page d'attente après le dernier concept gardé. */
    data class Attente(override val concept: Concept) : Page {
        override val cle get() = "${concept.id}:att"
    }

    /** Un cran choisi n'est pas encore prêt : page d'attente qui se remplit seule. */
    data class AttenteCran(override val concept: Concept, val sens: String) : Page {
        override val cle get() = "${concept.id}:attente-$sens"
    }
}

/** Les actions de la carte : les quatre premières en pied de carte, les autres derrière « ⋯ ». */
internal enum class Action(
    val libelle: String,
    val icone: ImageVector,
    val code: String,
    val retour: String,
    val principale: Boolean,
) {
    Jaime("J'aime", Icons.Filled.FavoriteBorder, "like", "Noté : plus de ce genre", true),
    Creuser("Creuser", Icons.Filled.Search, "creuser", "", true),
    JeSavais("Je savais", Icons.Filled.Check, "je_savais", "Noté : on montera d'un cran", true),
    Dense("Trop dense", Icons.Filled.Info, "trop_dense", "Noté : on simplifiera", true),
    Bof("Pas pour moi", Icons.Filled.Close, "pas_pour_moi", "Noté : moins de ce genre", false),
    Partager("Partager", Icons.Filled.Share, "partager", "", false),
    Signaler("Signaler", Icons.Filled.Warning, "signaler", "Carte signalée", false),
}

/** Ce que le fil remonte de la lecture : le concept en cours et si son carrefour est atteint. */
internal data class Position(val concept: Concept, val carrefourAtteint: Boolean)

// ---------------------------------------------------------------- Écran du fil

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun EcranFil(
    porte: Porte,
    inseres: List<Concept>,
    enAttente: Pair<String, String>?,
    attente: Regen.Suite?,
    crans: Map<String, Crans>,
    exploration: Boolean,
    apresCoucher: Boolean,
    pontAffiche: String?,
    fremir: Boolean,
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
    onPosition: (Position) -> Unit,
    onSortie: (Concept, String) -> String?,
    onGarder: () -> Unit,
    onPontVu: (String) -> Unit,
    onCarrefourVu: () -> Unit,
    onLireAncienne: () -> Unit,
    onFin: () -> Unit,
) {
    // Pendant la réécriture de la suite, seuls les concepts gardés restent, suivis d'une page d'attente.
    val pages = remember(porte, inseres, enAttente, attente?.gardes, pontAffiche) {
        val principaux = if (attente != null) porte.concepts.take(attente.gardes) else porte.concepts
        val fil = ordreDuFil(principaux, inseres)
        buildList {
            fil.forEach { c ->
                add(Page.Question(c))
                c.cartes.forEachIndexed { i, carte -> add(Page.Texte(c, carte, i)) }
                if (c.contrePoint != null) add(Page.ContrePoint(c))
                if (c.pont != null && c.id == pontAffiche) add(Page.PagePont(c))
                add(Page.Carrefour(c))
                if (enAttente?.first == c.id) add(Page.AttenteCran(c, enAttente.second))
            }
            if (attente != null && fil.isNotEmpty()) add(Page.Attente(fil.last()))
        }
    }
    val pagesActuelles by rememberUpdatedState(pages)
    val porteActuelle by rememberUpdatedState(porte)
    val etatPager = rememberPagerState(pageCount = { pages.size })
    var message by remember { mutableStateOf<String?>(null) }
    var voletCreuser by remember { mutableStateOf(false) }
    var voletPreuve by remember { mutableStateOf<Pair<Concept, Carte?>?>(null) }
    // Page à rejoindre dès qu'elle existe, et page qui entre par le côté.
    var cible by remember { mutableStateOf<String?>(null) }
    var entree by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var attenteVue by remember { mutableStateOf<Pair<String, String>?>(null) }
    val uri = LocalUriHandler.current
    val portee = rememberCoroutineScope()

    // Journal : temps passé sur chaque carte, progression de la nuit, concept et carrefour en cours.
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
                val atteint = p is Page.Carrefour || p is Page.AttenteCran ||
                    liste.indexOfFirst { it is Page.Carrefour && it.concept.id == p.concept.id }.let { it in 0 until page }
                onPosition(Position(p.concept, atteint))
                when (p) {
                    is Page.Carrefour -> {
                        journal.note("carrefour", "concept" to p.concept.id)
                        onCarrefourVu()
                    }
                    is Page.PagePont -> onPontVu(p.concept.id)
                    is Page.AttenteCran -> attenteVue = p.concept.id to p.sens
                    else -> Unit
                }
                // Parti de la page d'attente (elle est encore là) : pas de saut quand le cran arrivera.
                if (p !is Page.AttenteCran) {
                    attenteVue?.let { (d, s) ->
                        if (liste.any { it is Page.AttenteCran && it.concept.id == d && it.sens == s }) attenteVue = null
                    }
                }
            }
        }
    }

    // Rejoint la page visée dès qu'elle apparaît dans le fil.
    LaunchedEffect(pages, cible) {
        val visee = cible ?: return@LaunchedEffect
        val index = pages.indexOfFirst { it.cle == visee }
        if (index >= 0) {
            etatPager.scrollToPage(index)
            cible = null
        }
    }

    // Un cran attendu vient d'arriver : on passe de la page d'attente à sa question.
    LaunchedEffect(pages) {
        val (depuis, sens) = attenteVue ?: return@LaunchedEffect
        if (pages.any { it is Page.AttenteCran && it.concept.id == depuis && it.sens == sens }) return@LaunchedEffect
        attenteVue = null
        val arrive = inseres.lastOrNull { it.depuis == depuis && it.sens == sens }
        if (arrive != null) {
            entree = "${arrive.id}:q" to 0f
            cible = "${arrive.id}:q"
        } else {
            message = "Pas de suite de ce côté ce soir"
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(1800)
            message = null
        }
    }

    /** Une sortie choisie au carrefour (geste ou languette). */
    fun sortir(depuis: Concept, sens: String) {
        journal.note("sortie", "concept" to depuis.id, "sens" to sens)
        val cleCible = onSortie(depuis, sens)
        val direction = if (sens == Sens.PLUS_LOIN) 1f else -1f
        if (cleCible != null) {
            entree = cleCible to direction
            cible = cleCible
        } else {
            val attenteCle = "${depuis.id}:attente-$sens"
            entree = attenteCle to direction
            cible = attenteCle
        }
    }

    fun agir(action: Action, page: Page) {
        val concept = page.concept
        journal.note(action.code, "concept" to concept.id, "page" to etatPager.currentPage)
        when (action) {
            Action.Jaime -> {
                val (carte, texte) = cibleCoeur(page) ?: return
                val deja = coeurs.aime(concept.id, carte)
                onCoeur(concept, carte, texte)
                if (!deja) message = action.retour
            }
            Action.Creuser -> voletCreuser = true
            Action.Partager -> onPartager("${concept.aRaconter}\n\n(via Chevet)")
            Action.Dense -> {
                message = action.retour
                onTropDense()
            }
            else -> message = action.retour
        }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        VerticalPager(
            state = etatPager,
            modifier = Modifier.fillMaxSize(),
            key = { i -> pages.getOrNull(i)?.cle ?: "vide-$i" },
        ) { i ->
            val page = pages.getOrNull(i) ?: return@VerticalPager
            val courante = i == etatPager.currentPage
            val depuisCote = entree?.takeIf { it.first == page.cle }?.second
            if (depuisCote != null) {
                LaunchedEffect(page.cle) {
                    delay(400)
                    if (entree?.first == page.cle) entree = null
                }
            }
            val aimee = cibleCoeur(page)?.let { coeurs.aime(page.concept.id, it.first) } ?: false
            val estDerniere = i == pages.lastIndex
            when (page) {
                is Page.Attente -> PageAttente(porte.titre, attente?.preparation, onLireAncienne)
                is Page.AttenteCran -> PageAttenteCran(page.concept, page.sens, crans[page.concept.id], depuisCote)
                is Page.Carrefour -> {
                    val suivante = pages.getOrNull(i + 1)
                    PageCarrefour(
                        concept = page.concept,
                        crans = crans[page.concept.id],
                        ouverts = inseres.filter { it.depuis == page.concept.id },
                        enAttente = enAttente?.takeIf { it.first == page.concept.id }?.second,
                        exploration = exploration,
                        suivant = (suivante as? Page.Question)?.concept?.titre,
                        estDerniere = estDerniere,
                        apresCoucher = apresCoucher,
                        fremir = fremir && courante,
                        depuisCote = depuisCote,
                        aimee = aimee,
                        onAction = { agir(it, page) },
                        onPreuve = { voletPreuve = page.concept to null },
                        onSortie = { sens -> sortir(page.concept, sens) },
                        onDejaOuvert = { c ->
                            entree = null
                            cible = "${c.id}:q"
                        },
                        onImpasse = { message = "Pas de suite de ce côté ce soir" },
                        onGarder = onGarder,
                        onFin = onFin,
                    )
                }
                else -> PageCarte(
                    page = page,
                    depuisCote = depuisCote,
                    aimee = aimee,
                    onAction = { agir(it, page) },
                    onPreuve = { carte -> voletPreuve = page.concept to carte },
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
                    .background(Color(0xEE1A1630), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (voletCreuser) {
        val concept = pages.getOrNull(etatPager.currentPage)?.concept
        if (concept != null) {
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

    voletPreuve?.let { (concept, carte) ->
        ModalBottomSheet(
            onDismissRequest = { voletPreuve = null },
            containerColor = Color(0xFF1A1630),
        ) {
            VoletPreuve(
                concept = concept,
                carte = carte,
                onOuvrir = { url ->
                    journal.note("preuve_article", "concept" to concept.id)
                    runCatching { uri.openUri(url) }
                },
                onFermer = { voletPreuve = null },
            )
        }
        LaunchedEffect(concept.id, carte) {
            journal.note("preuve", "concept" to concept.id, "carte" to (carte != null))
        }
    }
}

/** Le cœur se pose sur la page affichée : une carte, l'à-retenir (-1), la question (-2), le contre-point (-3), le pont (-4). */
private fun cibleCoeur(page: Page): Pair<Int, String>? = when (page) {
    is Page.Texte -> page.numero to page.carte.texte
    is Page.Carrefour -> -1 to page.concept.aRetenir
    is Page.Question -> -2 to page.concept.question
    is Page.ContrePoint -> page.concept.contrePoint?.let { -3 to it.texte }
    is Page.PagePont -> page.concept.pont?.let { -4 to it.texte }
    is Page.Attente, is Page.AttenteCran -> null
}

// ---------------------------------------------------------------- Briques des pages

/** Entrée par le côté : la carte arrive du côté opposé à celui où le doigt a envoyé la précédente. */
@Composable
private fun rememberEntree(depuisCote: Float?): Float {
    val largeurEcran = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val depart = if (depuisCote != null && depuisCote != 0f) depuisCote * largeurEcran else 0f
    var decalage by remember { mutableFloatStateOf(depart) }
    LaunchedEffect(depuisCote) {
        if (depart != 0f) animate(depart, 0f, animationSpec = tween(280)) { v, _ -> decalage = v }
    }
    return decalage
}

/** Une ligne discrète au-dessus de la carte : où l'on est dans le terrier. */
@Composable
private fun LigneProfondeur(c: Concept) {
    if (!c.estCran) return
    val texte = if (c.sens == Sens.PLUS_LOIN) {
        "↓ Plus loin · niveau ${c.profondeur.coerceAtLeast(1)}"
    } else {
        "→ À côté de « ${c.depuisTitre ?: "ton concept"} »"
    }
    Text(
        texte,
        color = Ambre.copy(alpha = 0.75f),
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun EtiquetteCouleur(texte: String, couleur: Color) {
    Text(
        texte.uppercase(),
        color = couleur,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Le panneau d'une carte : le texte, la source avec sa preuve, puis les actions en pied de carte. */
@Composable
private fun PanneauCarte(
    bordure: Color?,
    source: String,
    aPreuve: Boolean,
    aimee: Boolean,
    onPreuve: () -> Unit,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
    contenu: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Voile, RoundedCornerShape(16.dp))
            .then(if (bordure != null) Modifier.border(1.dp, bordure, RoundedCornerShape(16.dp)) else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    ) {
        contenu()
        Spacer(Modifier.height(14.dp))
        Text(
            if (aPreuve) "Source · voir la preuve" else "Source : $source",
            color = if (aPreuve) Ambre.copy(alpha = 0.8f) else EncreDouce.copy(alpha = 0.7f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(onClick = onPreuve)
                .padding(vertical = 6.dp),
        )
        HorizontalDivider(color = Encre.copy(alpha = 0.08f))
        ActionsCarte(aimee = aimee, onAction = onAction)
    }
}

/** Les actions en pied de carte : cœur, creuser, je savais, trop dense, et « ⋯ » pour le reste. */
@Composable
private fun ActionsCarte(aimee: Boolean, onAction: (Action) -> Unit) {
    val rebond by animateFloatAsState(if (aimee) 1f else 0f, label = "coeur")
    var plus by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Action.entries.filter { it.principale }.forEach { action ->
            val coeur = action == Action.Jaime
            Column(
                Modifier
                    .clickable { onAction(action) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (coeur) {
                    val echelle = 1f + 0.3f * kotlin.math.sin(rebond * Math.PI.toFloat())
                    Icon(
                        if (aimee) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (aimee) "Retirer le cœur" else action.libelle,
                        tint = if (aimee) Ambre else Encre.copy(alpha = 0.85f),
                        modifier = Modifier.size((20 * echelle).dp),
                    )
                } else {
                    Icon(action.icone, contentDescription = action.libelle, tint = Encre.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                }
                Text(action.libelle, color = if (coeur && aimee) Ambre else EncreDouce, fontSize = 9.sp, maxLines = 1)
            }
        }
        Box {
            Column(
                Modifier
                    .clickable { plus = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Autres actions", tint = Encre.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                Text("Plus", color = EncreDouce, fontSize = 9.sp)
            }
            DropdownMenu(expanded = plus, onDismissRequest = { plus = false }) {
                Action.entries.filter { !it.principale }.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.libelle) },
                        leadingIcon = { Icon(action.icone, contentDescription = null) },
                        onClick = {
                            plus = false
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Cartes

@Composable
private fun PageCarte(
    page: Page,
    depuisCote: Float?,
    aimee: Boolean,
    onAction: (Action) -> Unit,
    onPreuve: (Carte?) -> Unit,
) {
    val c = page.concept
    val decalage = rememberEntree(depuisCote)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        LigneProfondeur(c)
        val carte: Carte? = when (page) {
            is Page.Texte -> page.carte
            is Page.ContrePoint -> c.contrePoint
            is Page.PagePont -> c.pont?.commeCarte()
            else -> null
        }
        val bordure = when (page) {
            is Page.ContrePoint -> BleuContre.copy(alpha = 0.6f)
            is Page.PagePont -> Dore.copy(alpha = 0.8f)
            else -> null
        }
        PanneauCarte(
            bordure = bordure,
            source = c.source.titre,
            aPreuve = carte?.preuve != null || (page is Page.Question && c.cartes.any { it.preuve != null }),
            aimee = aimee,
            onPreuve = { onPreuve(carte) },
            onAction = onAction,
            modifier = Modifier.graphicsLayer { translationX = decalage },
        ) {
            when (page) {
                is Page.Question -> {
                    Etiquette(c.theme)
                    Spacer(Modifier.height(8.dp))
                    Text(c.titre, color = Encre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    Text(c.question, color = Encre, style = MaterialTheme.typography.headlineSmall, fontStyle = FontStyle.Italic)
                }

                is Page.Texte -> {
                    Etiquette("${c.titre} · ${page.numero + 1}/${c.cartes.size}")
                    Spacer(Modifier.height(12.dp))
                    Text(page.carte.texte, color = Encre, style = MaterialTheme.typography.titleLarge)
                }

                is Page.ContrePoint -> {
                    EtiquetteCouleur("L'autre camp répondrait", BleuContre)
                    Spacer(Modifier.height(12.dp))
                    Text(c.contrePoint?.texte.orEmpty(), color = Encre, style = MaterialTheme.typography.titleLarge)
                }

                is Page.PagePont -> {
                    val pont = c.pont
                    val ilYa = pont?.avecLe?.let { joursDepuis(it) }
                    EtiquetteCouleur(if (ilYa != null) "Pont · il y a $ilYa jours" else "Pont", Dore)
                    Spacer(Modifier.height(4.dp))
                    Text("« ${pont?.avecTitre.orEmpty()} » ↔ « ${c.titre} »", color = EncreDouce, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(pont?.texte.orEmpty(), color = Encre, style = MaterialTheme.typography.titleLarge)
                }

                else -> Unit
            }
        }
        if (page is Page.Question && !c.estCran) {
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

private fun joursDepuis(date: String): Long? =
    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date.take(10)), LocalDate.now()).coerceAtLeast(1) }.getOrNull()

// ---------------------------------------------------------------- Carrefour

/** Ce qu'une languette montre d'une sortie. */
private sealed interface Sortie {
    data class Prete(val titre: String, val accroche: String) : Sortie
    data class EnPreparation(val titre: String?) : Sortie
    data class DejaOuverte(val concept: Concept) : Sortie
    data object Aucune : Sortie
}

private fun sortie(sens: String, crans: Crans?, ouverts: List<Concept>, exploration: Boolean): Sortie {
    ouverts.lastOrNull { it.sens == sens }?.let { return Sortie.DejaOuverte(it) }
    if (!exploration) return Sortie.Aucune
    if (crans == null) return Sortie.EnPreparation(null)
    if (crans.impasse(sens)) return Sortie.Aucune
    val pret = crans.concept(sens)
    val titre = crans.titre(sens)
    return when {
        pret != null -> Sortie.Prete(titre?.titre ?: pret.titre, titre?.accroche.orEmpty())
        crans.fini -> Sortie.Aucune
        else -> Sortie.EnPreparation(titre?.titre)
    }
}

@Composable
private fun PageCarrefour(
    concept: Concept,
    crans: Crans?,
    ouverts: List<Concept>,
    enAttente: String?,
    exploration: Boolean,
    suivant: String?,
    estDerniere: Boolean,
    apresCoucher: Boolean,
    fremir: Boolean,
    depuisCote: Float?,
    aimee: Boolean,
    onAction: (Action) -> Unit,
    onPreuve: () -> Unit,
    onSortie: (String) -> Unit,
    onDejaOuvert: (Concept) -> Unit,
    onImpasse: () -> Unit,
    onGarder: () -> Unit,
    onFin: () -> Unit,
) {
    val c = concept
    val gauche = sortie(Sens.A_COTE, crans, ouverts, exploration)
    val droite = sortie(Sens.PLUS_LOIN, crans, ouverts, exploration)
    val possible = { sens: String ->
        val s = if (sens == Sens.PLUS_LOIN) droite else gauche
        s !is Sortie.Aucune
    }
    val entreeDecalage = rememberEntree(depuisCote)
    var largeur by remember { mutableFloatStateOf(1f) }
    var decalage by remember { mutableFloatStateOf(0f) }
    var seuilPasse by remember { mutableStateOf(false) }
    val haptique = LocalHapticFeedback.current
    val portee = rememberCoroutineScope()
    val possibleActuel by rememberUpdatedState(possible)
    val gaucheActuelle by rememberUpdatedState(gauche)
    val droiteActuelle by rememberUpdatedState(droite)

    /** Lâcher : au-delà du seuil, la carte part du côté choisi ; sinon elle revient. */
    fun choisir(sens: String) {
        val s = if (sens == Sens.PLUS_LOIN) droiteActuelle else gaucheActuelle
        when (s) {
            is Sortie.DejaOuverte -> onDejaOuvert(s.concept)
            is Sortie.Aucune -> onImpasse()
            else -> onSortie(sens)
        }
    }

    // Les premiers carrefours, la carte frémit d'un côté puis de l'autre pour montrer qu'elle bouge.
    LaunchedEffect(fremir) {
        if (!fremir || !exploration) return@LaunchedEffect
        delay(900)
        animate(0f, -36f, animationSpec = tween(220)) { v, _ -> decalage = v }
        animate(-36f, 36f, animationSpec = tween(320)) { v, _ -> decalage = v }
        animate(36f, 0f, animationSpec = spring()) { v, _ -> decalage = v }
    }

    val geste = Modifier.pointerInput(c.id) {
        detectHorizontalDragGestures(
            onDragEnd = {
                val seuil = largeur * SEUIL_GESTE
                val v = decalage
                val sens = when {
                    v <= -seuil -> Sens.PLUS_LOIN
                    v >= seuil -> Sens.A_COTE
                    else -> null
                }
                seuilPasse = false
                portee.launch {
                    if (sens != null && possibleActuel(sens)) {
                        animate(v, if (sens == Sens.PLUS_LOIN) -largeur else largeur, animationSpec = tween(170)) { x, _ -> decalage = x }
                        choisir(sens)
                        delay(350)
                        decalage = 0f
                    } else {
                        if (sens != null) choisir(sens)
                        animate(v, 0f, animationSpec = spring()) { x, _ -> decalage = x }
                    }
                }
            },
            onDragCancel = {
                seuilPasse = false
                portee.launch { animate(decalage, 0f, animationSpec = spring()) { x, _ -> decalage = x } }
            },
        ) { change, dx ->
            change.consume()
            val sensVise = if (decalage + dx < 0) Sens.PLUS_LOIN else Sens.A_COTE
            // Un côté sans suite résiste sous le doigt.
            val frein = if (possibleActuel(sensVise)) 0.9f else 0.25f
            decalage += dx * frein
            val passe = abs(decalage) >= largeur * SEUIL_GESTE && possibleActuel(sensVise)
            if (passe && !seuilPasse) haptique.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            seuilPasse = passe
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { largeur = it.width.toFloat().coerceAtLeast(1f) }
            .then(if (exploration || ouverts.isNotEmpty()) geste else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            LigneProfondeur(c)
            PanneauCarte(
                bordure = null,
                source = c.source.titre,
                aPreuve = c.cartes.any { it.preuve != null },
                aimee = aimee,
                onPreuve = onPreuve,
                onAction = onAction,
                modifier = Modifier.graphicsLayer {
                    translationX = decalage + entreeDecalage
                    alpha = 1f - (abs(decalage) / largeur * 0.7f).coerceIn(0f, 0.7f)
                },
            ) {
                if (c.aRetenir.isNotBlank()) {
                    Etiquette("À retenir")
                    Spacer(Modifier.height(8.dp))
                    Text(c.aRetenir, color = Encre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                c.ordreDeGrandeur?.takeIf { it.isNotBlank() }?.let { chiffre ->
                    Spacer(Modifier.height(12.dp))
                    Text(chiffre, color = Ambre, style = MaterialTheme.typography.bodyLarge)
                }
                if (c.aRaconter.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Etiquette("À raconter")
                    Spacer(Modifier.height(6.dp))
                    Text(c.aRaconter, color = EncreDouce, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                }
            }

            // Les deux sorties, chacune de son côté, avec ce qui attend.
            if (gauche !is Sortie.Aucune || droite !is Sortie.Aucune) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        Languette(Sens.A_COTE, gauche, enAttente == Sens.A_COTE) { choisir(Sens.A_COTE) }
                    }
                    Box(Modifier.weight(1f)) {
                        Languette(Sens.PLUS_LOIN, droite, enAttente == Sens.PLUS_LOIN) { choisir(Sens.PLUS_LOIN) }
                    }
                }
            }

            if (apresCoucher) {
                TextButton(onClick = onGarder, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("☾  Garder pour demain", color = EncreDouce)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (estDerniere) {
                Button(onClick = onFin, modifier = Modifier.fillMaxWidth()) { Text("Voir le récap") }
            } else if (suivant != null) {
                Text(
                    "↑ Glisse vers le haut : $suivant",
                    color = EncreDouce.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Chevrons de bord, qui respirent doucement quand une sortie existe de ce côté.
        val respiration = rememberInfiniteTransition(label = "chevrons")
        val pouls by respiration.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "pouls",
        )
        if (gauche !is Sortie.Aucune) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = Ambre,
                modifier = Modifier.align(Alignment.CenterStart).alpha(pouls).size(28.dp),
            )
        }
        if (droite !is Sortie.Aucune) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Ambre,
                modifier = Modifier.align(Alignment.CenterEnd).alpha(pouls).size(28.dp),
            )
        }
    }
}

/** Une languette : de quel côté part la sortie, et ce qui attend. */
@Composable
private fun Languette(sens: String, sortie: Sortie, attendue: Boolean, onClick: () -> Unit) {
    if (sortie is Sortie.Aucune) return
    val gauche = sens == Sens.A_COTE
    val libelle = if (gauche) "À côté" else "Plus loin"
    val (titre, detail) = when (sortie) {
        is Sortie.Prete -> sortie.titre to sortie.accroche
        is Sortie.EnPreparation -> (sortie.titre ?: "…") to (if (attendue) "on t'attend là-bas…" else "se prépare…")
        is Sortie.DejaOuverte -> sortie.concept.titre to "déjà ouvert : y retourner"
        Sortie.Aucune -> "" to ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(Voile, RoundedCornerShape(14.dp))
            .border(1.dp, Ambre.copy(alpha = if (sortie is Sortie.Prete) 0.45f else 0.18f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = if (gauche) Alignment.Start else Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (gauche) Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Ambre, modifier = Modifier.size(16.dp))
            Text(libelle.uppercase(), color = Ambre, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
            if (!gauche) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ambre, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            titre,
            color = Encre,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (gauche) TextAlign.Start else TextAlign.End,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                color = EncreDouce.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontStyle = if (sortie is Sortie.Prete) FontStyle.Normal else FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (gauche) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------- Attentes

@Composable
private fun PageAttenteCran(depuis: Concept, sens: String, crans: Crans?, depuisCote: Float?) {
    val decalage = rememberEntree(depuisCote)
    val titre = crans?.titre(sens)?.titre
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .graphicsLayer { translationX = decalage },
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Voile, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Etiquette(if (sens == Sens.PLUS_LOIN) "Plus loin" else "À côté")
            Spacer(Modifier.height(12.dp))
            Text(
                if (titre != null) "Le conteur écrit « $titre »…" else "Le conteur cherche la suite de « ${depuis.titre} »…",
                color = Encre,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Ambre, trackColor = Voile)
            Spacer(Modifier.height(8.dp))
            Text(
                "Elle arrive toute seule ici, d'ici une minute environ. Tu peux remonter en attendant.",
                color = EncreDouce,
                style = MaterialTheme.typography.bodyMedium,
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
        Column(
            Modifier
                .fillMaxWidth()
                .background(Voile, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
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

// ---------------------------------------------------------------- Preuve en un tap

/**
 * Le volet de la preuve : pour une carte, la phrase de l'article qui la prouve, surlignée dans son
 * paragraphe ; pour la question ou l'à-retenir, les preuves de toutes les cartes du concept.
 */
@Composable
internal fun VoletPreuve(concept: Concept, carte: Carte?, onOuvrir: (String) -> Unit, onFermer: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Etiquette("La preuve")
        Spacer(Modifier.height(10.dp))
        val avecPreuve = if (carte != null) listOf(carte) else concept.cartes.filter { it.preuve != null }
        if (avecPreuve.none { it.preuve != null }) {
            Text(
                "Pas de citation gardée pour cette carte : l'article entier reste la source.",
                color = EncreDouce,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            if (carte == null) {
                Text(
                    "Ce que dit l'article, carte par carte :",
                    color = EncreDouce,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
            }
            avecPreuve.forEach { c ->
                val preuve = c.preuve ?: return@forEach
                val contexte = c.contexte?.takeIf { it.contains(preuve) }
                val texte = buildAnnotatedString {
                    if (contexte == null) {
                        withStyle(SpanStyle(background = Ambre.copy(alpha = 0.25f))) { append(preuve) }
                    } else {
                        val i = contexte.indexOf(preuve)
                        append(contexte.substring(0, i))
                        withStyle(SpanStyle(background = Ambre.copy(alpha = 0.25f), color = Encre)) { append(preuve) }
                        append(contexte.substring(i + preuve.length))
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(Voile, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                ) {
                    if (carte == null) {
                        Text(c.texte, color = EncreDouce, fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(texte, color = EncreDouce, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Article : ${concept.source.titre}", color = EncreDouce.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onOuvrir(concept.source.url) }, modifier = Modifier.fillMaxWidth()) {
            Text("Ouvrir l'article", color = Encre)
        }
        TextButton(onClick = onFermer) { Text("Fermer", color = EncreDouce) }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- Creuser

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
