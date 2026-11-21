package fr.dash.chevet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dash.chevet.data.Coeurs
import fr.dash.chevet.data.ConceptVu
import fr.dash.chevet.data.Curiosite
import fr.dash.chevet.data.NotePerso
import fr.dash.chevet.data.Soiree
import fr.dash.chevet.data.Distant
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.NotePorte
import fr.dash.chevet.data.Progression
import fr.dash.chevet.data.Voisin
import fr.dash.chevet.model.Concept
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Les pages du menu. */
internal enum class PageMenu { Accueil, Terrier, CoupsDeCoeur, Carnet, Soirees, Recherche, Stats, Curiosites, Notes, Onboarding, Reviser }

/** Ce que le menu montre de la soirée en cours. */
internal data class EtatSoiree(
    val ceSoir: String,
    val detail: String,
    val autrePorte: String,
    val autrePortePossible: Boolean,
)

/** Ce dont les pages du menu ont besoin pour agir sur la soirée et le terrier. */
internal class ActionsMenu(
    val onPage: (PageMenu) -> Unit,
    val onCeSoir: () -> Unit,
    val onAutrePorte: () -> Unit,
    val onReglages: (fiche: Boolean) -> Unit,
    /** Concept, numéro de carte (-1 : à retenir, -2 : question), texte, jour du concept. */
    val onCoeur: (Concept, Int, String, String?) -> Unit,
    val onSource: (String) -> Unit,
    val onPartager: (String) -> Unit,
    val onAnnonce: (String) -> Unit,
    val onCreuse: () -> Unit,
    val onQuitter: () -> Unit,
)

private val FondMenu = Color(0xF20B1026)

private val formatJour = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

internal fun jourLisible(jour: String): String =
    runCatching { LocalDate.parse(jour).format(formatJour).replaceFirstChar { it.uppercase() } }.getOrDefault(jour)

/**
 * Le menu : l'arbre du terrier sous le ciel du soir, le bouton « Ce soir », puis « Relire » et
 * « Faire ». Il s'ouvre par-dessus la soirée, qui reste gardée en dessous.
 */
@Composable
internal fun EcranMenu(
    page: PageMenu,
    nuit: Float,
    soiree: EtatSoiree,
    soireePrefs: Soiree,
    progression: Progression,
    coeurs: Coeurs,
    distant: Distant,
    journal: Journal,
    creusagesRestants: Int,
    creusagesParSoir: Int,
    partage: String?,
    actions: ActionsMenu,
) {
    var relire by remember { mutableStateOf<ConceptVu?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf<String?>(null) }
    val concepts = remember(version, page) { progression.tous() }

    BackHandler {
        when {
            relire != null -> relire = null
            page != PageMenu.Accueil -> actions.onPage(PageMenu.Accueil)
            else -> actions.onQuitter()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        CielDuSoir(nuit = nuit, etoiles = false)
        val retour = { actions.onPage(PageMenu.Accueil) }
        val ouvrir: (ConceptVu) -> Unit = { relire = it }
        when (page) {
            PageMenu.Accueil -> AccueilMenu(concepts, coeurs, progression, soiree, actions) { id ->
                selection = id
                actions.onPage(PageMenu.Terrier)
            }
            PageMenu.Terrier -> PageTerrier(
                concepts = concepts,
                coeurs = coeurs,
                progression = progression,
                distant = distant,
                journal = journal,
                selectionInitiale = selection,
                creusagesRestants = creusagesRestants,
                creusagesParSoir = creusagesParSoir,
                actions = actions,
                onRelire = ouvrir,
                onChange = { version += 1 },
                onRetour = retour,
            )
            PageMenu.CoupsDeCoeur -> PageCoupsDeCoeur(concepts, coeurs, actions, ouvrir, retour)
            PageMenu.Carnet -> PageCarnet(concepts, ouvrir, retour)
            PageMenu.Soirees -> PageSoirees(concepts, progression.notes(), ouvrir, retour)
            PageMenu.Recherche -> PageRecherche(concepts, ouvrir, retour)
            PageMenu.Stats -> PageStats(concepts, coeurs, progression.notes(), retour)
            PageMenu.Curiosites -> PageCuriosites(distant, journal, partage, actions, retour)
            PageMenu.Notes -> PageNotes(distant, journal, actions, retour)
            PageMenu.Onboarding -> PageOnboarding(distant, soireePrefs, journal, actions, retour)
            PageMenu.Reviser -> PageReviser(progression, coeurs, journal, onFin = { version += 1 }, onRetour = retour)
        }

        relire?.let { vu ->
            EcranRelire(vu, coeurs, actions) { relire = null }
        }
    }
}

// ---------------------------------------------------------------- Briques du menu

@Composable
internal fun PageMenuColonne(titre: String, onRetour: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(FondMenu)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        EnTete(titre, onRetour)
        content()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun EnTete(titre: String, onRetour: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRetour) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Encre)
        }
        Text(titre, color = Encre, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LigneMenu(icone: ImageVector, titre: String, detail: String, actif: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = actif, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icone, contentDescription = null, tint = if (actif) Ambre else EncreDouce.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titre, color = if (actif) Encre else EncreDouce.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) {
                Text(detail, color = EncreDouce.copy(alpha = if (actif) 1f else 0.6f), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (actif) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EncreDouce)
    }
}

@Composable
private fun Bloc(titre: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Etiquette(titre)
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(Voile, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun Vide(texte: String) {
    Spacer(Modifier.height(24.dp))
    Text(texte, color = EncreDouce, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
}

// ---------------------------------------------------------------- Accueil

@Composable
private fun AccueilMenu(
    concepts: List<ConceptVu>,
    coeurs: Coeurs,
    progression: Progression,
    soiree: EtatSoiree,
    actions: ActionsMenu,
    onFeuille: (String) -> Unit,
) {
    val fruits = concepts.count { it.maitrise }
    val aReviser = remember(concepts) { progression.aReviser().count { it.du() } }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiquette("Chevet")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { actions.onReglages(false) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Réglages", tint = Encre.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            }
        }
        Arbre(
            concepts = concepts,
            aime = coeurs::conceptAime,
            modifier = Modifier.fillMaxWidth().height(250.dp),
            onFeuille = onFeuille,
        )
        Text(
            when {
                concepts.isEmpty() -> "Ton arbre pousse avec chaque concept lu."
                else -> "Ton terrier : ${concepts.size} concept${if (concepts.size > 1) "s" else ""}" +
                    if (fruits > 0) " · $fruits fruit${if (fruits > 1) "s" else ""}" else ""
            },
            color = EncreDouce,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = actions.onCeSoir, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(soiree.ceSoir, style = MaterialTheme.typography.titleMedium)
                if (soiree.detail.isNotBlank()) Text(soiree.detail, fontSize = 12.sp)
            }
        }

        Bloc("Relire") {
            LigneMenu(Icons.Filled.Star, "Terrier", "L'arbre et le graphe de tes concepts") { actions.onPage(PageMenu.Terrier) }
            LigneMenu(Icons.Filled.Favorite, "Coups de cœur", "${coeurs.tous.size} carte${if (coeurs.tous.size > 1) "s" else ""} aimée${if (coeurs.tous.size > 1) "s" else ""}") {
                actions.onPage(PageMenu.CoupsDeCoeur)
            }
            LigneMenu(Icons.Filled.Edit, "Carnet", "Les phrases à retenir, par thème") { actions.onPage(PageMenu.Carnet) }
            LigneMenu(Icons.Filled.DateRange, "Soirées passées", "Relire ce que tu as lu, soir par soir") { actions.onPage(PageMenu.Soirees) }
            LigneMenu(Icons.Filled.Search, "Recherche", "Un mot dans tout ce que tu as lu") { actions.onPage(PageMenu.Recherche) }
            LigneMenu(Icons.AutoMirrored.Filled.List, "Stats", "Ce que ton terrier a dans le ventre") { actions.onPage(PageMenu.Stats) }
        }
        Bloc("Faire") {
            LigneMenu(Icons.Filled.Add, "Boîte à curiosités", "Une question, un lien : ça nourrit tes prochaines portes") {
                actions.onPage(PageMenu.Curiosites)
            }
            LigneMenu(Icons.Filled.Create, "Notes perso", "Tes idées et réflexions : en faire une porte, ou « réponds-moi »") {
                actions.onPage(PageMenu.Notes)
            }
            LigneMenu(Icons.Filled.PlayArrow, "Autre porte", soiree.autrePorte, actif = soiree.autrePortePossible, onClick = actions.onAutrePorte)
            LigneMenu(
                Icons.Filled.Refresh,
                "Réviser",
                if (aReviser > 0) "$aReviser révision${if (aReviser > 1) "s" else ""} due${if (aReviser > 1) "s" else ""}, et autant de QCM libres que tu veux" else "QCM libres, tes concepts aimés d'abord",
            ) { actions.onPage(PageMenu.Reviser) }
            LigneMenu(Icons.Filled.Face, "Ce que Chevet sait de moi", "Ta fiche, à corriger quand tu veux") { actions.onReglages(true) }
            LigneMenu(Icons.Filled.Settings, "Faire connaissance", "Sujets à swiper, historique YouTube, niveaux : 5 minutes") {
                actions.onPage(PageMenu.Onboarding)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- Relire un concept

/** Un concept relu en entier : ses cartes, chacune avec son cœur, puis la phrase à retenir. */
@Composable
private fun EcranRelire(vu: ConceptVu, coeurs: Coeurs, actions: ActionsMenu, onRetour: () -> Unit) {
    val c = vu.concept
    PageMenuColonne(c.titre, onRetour) {
        Etiquette("${nomCourt(c.theme)} · ${jourLisible(vu.vuLe)}")
        vu.porteTitre?.let {
            Text("Porte « $it »", color = EncreDouce, style = MaterialTheme.typography.bodySmall)
        }
        if (c.estCran) {
            Text(
                if (c.sens == fr.dash.chevet.model.Sens.PLUS_LOIN) "Plus loin depuis « ${c.depuisTitre.orEmpty()} »"
                else "À côté de « ${c.depuisTitre.orEmpty()} »",
                color = EncreDouce.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(c.question, color = Encre, style = MaterialTheme.typography.titleLarge, fontStyle = FontStyle.Italic)
        c.cartes.forEachIndexed { i, carte ->
            CarteRelue(carte.texte, coeurs.aime(c.id, i)) { actions.onCoeur(c, i, carte.texte, vu.vuLe) }
        }
        if (c.aRetenir.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Etiquette("À retenir")
            CarteRelue(c.aRetenir, coeurs.aime(c.id, -1), gras = true) { actions.onCoeur(c, -1, c.aRetenir, vu.vuLe) }
        }
        c.contrePoint?.let { cp ->
            Spacer(Modifier.height(8.dp))
            Etiquette("L'autre camp répondrait")
            CarteRelue(cp.texte, coeurs.aime(c.id, -3)) { actions.onCoeur(c, -3, cp.texte, vu.vuLe) }
        }
        c.pont?.let { pont ->
            Spacer(Modifier.height(8.dp))
            Etiquette("Pont avec « ${pont.avecTitre} »")
            CarteRelue(pont.texte, coeurs.aime(c.id, -4)) { actions.onCoeur(c, -4, pont.texte, vu.vuLe) }
        }
        c.ordreDeGrandeur?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Ambre, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (c.aRaconter.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Etiquette("À raconter")
            Text(c.aRaconter, color = EncreDouce, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            TextButton(onClick = { actions.onPartager("${c.aRaconter}\n\n(via Chevet)") }) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = Ambre, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Partager", color = Ambre)
            }
        }
        Text(
            "Source : ${c.source.titre}",
            color = EncreDouce.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp).clickable { actions.onSource(c.source.url) },
        )
    }
}

@Composable
private fun CarteRelue(texte: String, aimee: Boolean, gras: Boolean = false, onCoeur: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Voile, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            texte,
            color = Encre,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (gras) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCoeur) {
            Icon(
                if (aimee) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (aimee) "Retirer le cœur" else "J'aime",
                tint = if (aimee) Ambre else EncreDouce,
            )
        }
    }
}

// ---------------------------------------------------------------- Terrier : arbre et graphe

@Composable
private fun PageTerrier(
    concepts: List<ConceptVu>,
    coeurs: Coeurs,
    progression: Progression,
    distant: Distant,
    journal: Journal,
    selectionInitiale: String?,
    creusagesRestants: Int,
    creusagesParSoir: Int,
    actions: ActionsMenu,
    onRelire: (ConceptVu) -> Unit,
    onChange: () -> Unit,
    onRetour: () -> Unit,
) {
    var graphe by remember { mutableStateOf(concepts.size >= 2) }
    var choisiId by remember { mutableStateOf(selectionInitiale) }
    val choisi = concepts.firstOrNull { it.concept.id == choisiId }
    // Voisins chargés, par concept : ils restent affichés quand on revient sur le même concept.
    val voisinsCharges = remember { mutableStateOf<Map<String, List<Voisin>?>>(emptyMap()) }
    var chargement by remember { mutableStateOf(false) }
    val portee = rememberCoroutineScope()
    val fruits = concepts.count { it.maitrise }
    val aimes = concepts.count { coeurs.conceptAime(it.concept.id) }

    fun chargerVoisins(vu: ConceptVu) {
        if (vu.concept.id in voisinsCharges.value || chargement) return
        chargement = true
        journal.note("voisins", "concept" to vu.concept.id)
        portee.launch {
            val v = distant.voisins(vu)
            voisinsCharges.value = voisinsCharges.value + (vu.concept.id to v)
            chargement = false
            if (v == null) actions.onAnnonce("Pas de connexion : les voisins viendront plus tard.")
        }
    }

    fun prolonger(vu: ConceptVu, actif: Boolean, vers: String?) {
        progression.prolonger(vu.concept.id, actif, vers)
        onChange()
        journal.note("prolonger", "concept" to vu.concept.id, "actif" to actif, "vers" to vers)
        portee.launch {
            val ok = distant.majConcept(vu.concept, vu.vuLe, prolonger = actif, vers = vers)
            if (!ok) {
                progression.prolonger(vu.concept.id, !actif, if (actif) null else vu.prolongerVers)
                onChange()
                actions.onAnnonce("Pas de connexion : le lot de demain n'a pas pu être prévenu. Réessaie plus tard.")
            } else {
                actions.onAnnonce(
                    if (actif) "Demain, une porte partira de « ${vers ?: vu.concept.titre} »." else "C'est annulé.",
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FondMenu.copy(alpha = 0.55f))
            .systemBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        EnTete("Terrier", onRetour)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !graphe, onClick = { graphe = false }, label = { Text("Arbre") })
            FilterChip(selected = graphe, onClick = { graphe = true }, label = { Text("Graphe") })
            Spacer(Modifier.width(8.dp))
            Text(
                "${concepts.size} concepts · $aimes aimés · $fruits fruits",
                color = EncreDouce,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (concepts.isEmpty()) {
                Text(
                    "Ton terrier est encore vide : il pousse avec chaque concept lu.",
                    color = EncreDouce,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else if (graphe) {
                Graphe(
                    concepts = concepts,
                    aime = coeurs::conceptAime,
                    choisi = choisiId,
                    voisins = choisiId?.let { voisinsCharges.value[it] }.orEmpty(),
                    onChoix = { choisiId = it?.concept?.id },
                    onVoisin = { v ->
                        choisi?.let { prolonger(it, true, v.titre) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "Pince pour zoomer, glisse pour te déplacer, touche un concept.",
                    color = EncreDouce.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                )
            } else {
                Arbre(
                    concepts = concepts,
                    aime = coeurs::conceptAime,
                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                    onFeuille = { choisiId = it },
                )
            }
        }
        if (choisi != null) {
            FicheConcept(
                vu = choisi,
                aime = coeurs.conceptAime(choisi.concept.id),
                voisins = voisinsCharges.value[choisi.concept.id],
                voisinsDemandes = choisi.concept.id in voisinsCharges.value,
                chargement = chargement,
                creusagesRestants = creusagesRestants,
                creusagesParSoir = creusagesParSoir,
                distant = distant,
                journal = journal,
                onRelire = { onRelire(choisi) },
                onVoisins = { chargerVoisins(choisi) },
                onProlonger = { actif, vers -> prolonger(choisi, actif, vers) },
                onCreuse = actions.onCreuse,
                onFermer = { choisiId = null },
            )
        }
    }
}

/** La fiche d'un concept touché dans l'arbre ou le graphe. */
@Composable
private fun FicheConcept(
    vu: ConceptVu,
    aime: Boolean,
    voisins: List<Voisin>?,
    voisinsDemandes: Boolean,
    chargement: Boolean,
    creusagesRestants: Int,
    creusagesParSoir: Int,
    distant: Distant,
    journal: Journal,
    onRelire: () -> Unit,
    onVoisins: () -> Unit,
    onProlonger: (Boolean, String?) -> Unit,
    onCreuse: () -> Unit,
    onFermer: () -> Unit,
) {
    val c = vu.concept
    var creuser by remember(c.id) { mutableStateOf(false) }
    var question by remember(c.id) { mutableStateOf<String?>(null) }
    var reponse by remember(c.id) { mutableStateOf<String?>(null) }
    val portee = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .padding(vertical = 8.dp)
            .background(Color(0xF21A1630), RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(couleurTheme(c.theme), RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Etiquette(nomCourt(c.theme))
            if (aime) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Favorite, contentDescription = "Aimé", tint = Ambre, modifier = Modifier.size(14.dp))
            }
            if (vu.maitrise) {
                Spacer(Modifier.width(6.dp))
                Text("fruit", color = Color(0xFFF2C14E), fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onFermer, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = EncreDouce)
            }
        }
        Text(c.titre, color = Encre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (c.aRetenir.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(c.aRetenir, color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            jourLisible(vu.vuLe) + (vu.porteTitre?.let { " · « $it »" } ?: ""),
            color = EncreDouce.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val serre = PaddingValues(horizontal = 4.dp)
            OutlinedButton(onClick = onRelire, contentPadding = serre, modifier = Modifier.weight(1f)) { Text("Relire", color = Encre) }
            OutlinedButton(onClick = onVoisins, enabled = !voisinsDemandes, contentPadding = serre, modifier = Modifier.weight(1f)) {
                Text("Voisins", color = Encre)
            }
            OutlinedButton(onClick = { creuser = !creuser }, contentPadding = serre, modifier = Modifier.weight(1f)) {
                Text("Creuser", color = Encre)
            }
        }

        // Prolonger demain
        Spacer(Modifier.height(8.dp))
        if (vu.prolonger) {
            Text(
                "Demain, une porte partira d'ici${vu.prolongerVers?.let { ", vers « $it »" } ?: ""}.",
                color = Ambre,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onProlonger(false, null) }) { Text("Annuler", color = EncreDouce) }
        } else {
            Button(onClick = { onProlonger(true, null) }, modifier = Modifier.fillMaxWidth()) { Text("Prolonger demain") }
        }

        // Voisins
        when {
            chargement && !voisinsDemandes -> {
                Spacer(Modifier.height(8.dp))
                Text("Le conteur cherche les voisins…", color = EncreDouce, fontStyle = FontStyle.Italic)
            }
            voisinsDemandes && voisins.isNullOrEmpty() -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (voisins == null) "Pas de voisins pour l'instant." else "Aucun voisin inexploré : tu as fait le tour.",
                    color = EncreDouce,
                )
            }
            !voisins.isNullOrEmpty() -> {
                Spacer(Modifier.height(10.dp))
                Etiquette("Voisins inexplorés")
                voisins.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.titre, color = Encre, style = MaterialTheme.typography.titleSmall)
                            if (v.accroche.isNotBlank()) Text(v.accroche, color = EncreDouce, style = MaterialTheme.typography.bodySmall)
                        }
                        val vise = vu.prolonger && vu.prolongerVers == v.titre
                        TextButton(onClick = { onProlonger(!vise, if (vise) null else v.titre) }) {
                            Text(if (vise) "Demain ✓" else "Demain", color = Ambre)
                        }
                    }
                }
            }
        }

        // Creuser à nouveau, dans le quota du soir
        if (creuser) {
            Spacer(Modifier.height(10.dp))
            Etiquette("Creuser · $creusagesRestants/$creusagesParSoir ce soir")
            val choisie = question
            when {
                choisie != null -> {
                    Text(choisie, color = Encre, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        reponse ?: "Le conteur réfléchit…",
                        color = if (reponse == null) EncreDouce else Encre,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                creusagesRestants <= 0 -> Text(
                    "Quota du soir atteint. Ta question attendra demain : elle pourra devenir une porte.",
                    color = EncreDouce,
                )
                else -> c.creuser.ifEmpty { questionsSuggerees(c) }.forEach { q ->
                    OutlinedButton(
                        onClick = {
                            question = q
                            onCreuse()
                            journal.note("creuser_question", "concept" to c.id, "question" to q, "depuis" to "terrier")
                            portee.launch { reponse = distant.creuser(c, q) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) { Text(q, color = Encre) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Coups de cœur

@Composable
private fun PageCoupsDeCoeur(
    concepts: List<ConceptVu>,
    coeurs: Coeurs,
    actions: ActionsMenu,
    onRelire: (ConceptVu) -> Unit,
    onRetour: () -> Unit,
) {
    val parId = concepts.associateBy { it.concept.id }
    PageMenuColonne("Coups de cœur", onRetour) {
        val liste = coeurs.tous
        if (liste.isEmpty()) Vide("Pas encore de cœur. Touche le cœur sous une carte qui te plaît : elle t'attendra ici.")
        liste.forEach { coeur ->
            val vu = parId[coeur.concept]
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(Voile, RoundedCornerShape(14.dp))
                    .clickable(enabled = vu != null) { vu?.let(onRelire) }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(couleurTheme(coeur.theme), RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${coeur.conceptTitre} · ${jourLisible(coeur.le)}",
                        color = EncreDouce,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            vu?.concept?.let { actions.onCoeur(it, coeur.carte, coeur.texte, vu.vuLe) }
                        },
                        enabled = vu != null,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Retirer le cœur", tint = Ambre, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    coeur.texte,
                    color = Encre,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (coeur.carte == -1) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Carnet

@Composable
private fun PageCarnet(concepts: List<ConceptVu>, onRelire: (ConceptVu) -> Unit, onRetour: () -> Unit) {
    PageMenuColonne("Carnet", onRetour) {
        val avecPhrase = concepts.filter { it.concept.aRetenir.isNotBlank() }
        if (avecPhrase.isEmpty()) Vide("Ton carnet se remplit d'une phrase à retenir par concept lu.")
        val parTheme = avecPhrase.groupBy { it.concept.theme }
        val ordre = THEMES.filter { it in parTheme } + parTheme.keys.filter { it !in THEMES }
        ordre.forEach { theme ->
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(couleurTheme(theme), RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Etiquette(theme)
            }
            parTheme[theme].orEmpty().asReversed().forEach { vu ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onRelire(vu) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(vu.concept.aRetenir, color = Encre, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(vu.concept.titre, color = EncreDouce, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Soirées passées

@Composable
private fun PageSoirees(concepts: List<ConceptVu>, notes: List<NotePorte>, onRelire: (ConceptVu) -> Unit, onRetour: () -> Unit) {
    PageMenuColonne("Soirées passées", onRetour) {
        if (concepts.isEmpty()) Vide("Rien encore : tes soirées s'empileront ici.")
        concepts.groupBy { it.vuLe }.toSortedMap(compareByDescending { it }).forEach { (jour, duSoir) ->
            Spacer(Modifier.height(16.dp))
            Etiquette(jourLisible(jour))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(Voile, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                duSoir.groupBy { it.porteTitre }.forEach { (porte, liste) ->
                    if (porte != null) {
                        val note = notes.lastOrNull { n -> n.jour == jour && liste.any { it.porte == n.porte } }?.note
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("« $porte »", color = EncreDouce, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
                            if (note != null) Text("$note/10", color = Ambre, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    liste.sortedBy { it.rang }.forEach { vu ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onRelire(vu) }
                                .padding(start = if (vu.concept.estCran) 18.dp else 0.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (vu.concept.estCran) {
                                Text(if (vu.concept.sens == fr.dash.chevet.model.Sens.PLUS_LOIN) "↓ " else "→ ", color = Ambre, fontSize = 12.sp)
                            }
                            Box(Modifier.size(8.dp).background(couleurTheme(vu.concept.theme), RoundedCornerShape(50)))
                            Spacer(Modifier.width(10.dp))
                            Text(vu.concept.titre, color = Encre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EncreDouce)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Recherche

private fun sansAccents(texte: String): String =
    Normalizer.normalize(texte, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Le passage où le mot apparaît, avec un peu de contexte. */
private fun extraitAutour(texte: String, cherche: String): String? {
    val i = sansAccents(texte).indexOf(cherche)
    if (i < 0) return null
    val debut = (i - 50).coerceAtLeast(0)
    val fin = (i + cherche.length + 70).coerceAtMost(texte.length)
    return (if (debut > 0) "…" else "") + texte.substring(debut, fin).trim() + (if (fin < texte.length) "…" else "")
}

@Composable
private fun PageRecherche(concepts: List<ConceptVu>, onRelire: (ConceptVu) -> Unit, onRetour: () -> Unit) {
    var requete by remember { mutableStateOf("") }
    PageMenuColonne("Recherche", onRetour) {
        OutlinedTextField(
            value = requete,
            onValueChange = { requete = it },
            placeholder = { Text("Gramsci, 1789, hôpital…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = EncreDouce) },
            modifier = Modifier.fillMaxWidth(),
        )
        val cherche = sansAccents(requete.trim())
        if (cherche.length >= 2) {
            val resultats = concepts.mapNotNull { vu ->
                val c = vu.concept
                val textes = listOf(c.titre, c.question) + c.cartes.map { it.texte } + listOf(c.aRetenir, c.aRaconter)
                textes.firstNotNullOfOrNull { extraitAutour(it, cherche) }?.let { vu to it }
            }.asReversed()
            Spacer(Modifier.height(12.dp))
            Text(
                if (resultats.isEmpty()) "Rien trouvé." else "${resultats.size} concept${if (resultats.size > 1) "s" else ""}",
                color = EncreDouce,
                style = MaterialTheme.typography.bodySmall,
            )
            resultats.forEach { (vu, extrait) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .background(Voile, RoundedCornerShape(14.dp))
                        .clickable { onRelire(vu) }
                        .padding(14.dp),
                ) {
                    Text(vu.concept.titre, color = Encre, style = MaterialTheme.typography.titleMedium)
                    Text(extrait, color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
                    Text(jourLisible(vu.vuLe), color = EncreDouce.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Stats

@Composable
private fun PageStats(concepts: List<ConceptVu>, coeurs: Coeurs, notes: List<NotePorte>, onRetour: () -> Unit) {
    PageMenuColonne("Stats", onRetour) {
        val soirees = concepts.map { it.vuLe }.distinct().size
        val fruits = concepts.count { it.maitrise }
        val dues = concepts.count { it.qcmValide && it.du() }
        val aimes = concepts.count { coeurs.conceptAime(it.concept.id) }
        val lignes = listOf(
            "Concepts explorés" to "${concepts.size}",
            "Soirées" to "$soirees",
            "Portes lues" to "${concepts.map { it.porte ?: "soir:${it.vuLe}" }.distinct().size}",
            "Fruits (concepts maîtrisés)" to "$fruits",
            "Révisions dues aujourd'hui" to "$dues",
            "Cœurs posés" to "${coeurs.tous.size} sur $aimes concept${if (aimes > 1) "s" else ""}",
            "Crans explorés (plus loin, à côté)" to "${concepts.count { it.concept.estCran }}",
            "Ponts croisés" to "${concepts.count { it.concept.pont != null }}",
            "Note moyenne des portes" to if (notes.isEmpty()) "pas encore" else "%.1f/10 sur %d".format(notes.map { it.note }.average(), notes.size),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Voile, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            lignes.forEach { (cle, valeur) ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text(cle, color = EncreDouce, modifier = Modifier.weight(1f))
                    Text(valeur, color = Ambre, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Etiquette("Note moyenne par thème")
            notes.groupBy { it.theme ?: "Autre" }.toSortedMap().forEach { (theme, liste) ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text(theme, color = Encre, modifier = Modifier.weight(1f))
                    Text("%.1f/10 · %d".format(liste.map { it.note }.average(), liste.size), color = Ambre)
                }
            }
            val parType = notes.filter { !it.type.isNullOrBlank() }.groupBy { it.type!! }
            if (parType.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Etiquette("Note moyenne par type de porte")
                parType.toSortedMap().forEach { (type, liste) ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(type.replaceFirstChar { it.uppercase() }, color = Encre, modifier = Modifier.weight(1f))
                        Text("%.1f/10 · %d".format(liste.map { it.note }.average(), liste.size), color = Ambre)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Etiquette("Par thème")
        val parTheme = concepts.groupingBy { it.concept.theme }.eachCount()
        val maximum = (parTheme.values.maxOrNull() ?: 1).coerceAtLeast(1)
        (THEMES + parTheme.keys.filter { it !in THEMES }).forEach { theme ->
            val n = parTheme[theme] ?: 0
            Column(Modifier.padding(vertical = 6.dp)) {
                Row {
                    Text(theme, color = Encre, modifier = Modifier.weight(1f))
                    Text("$n", color = EncreDouce)
                }
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(fraction = (n.toFloat() / maximum).coerceAtLeast(0.02f))
                        .height(8.dp)
                        .background(couleurTheme(theme), RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Boîte à curiosités

private val regexUrl = Regex("https?://\\S+")

@Composable
private fun PageCuriosites(
    distant: Distant,
    journal: Journal,
    partage: String?,
    actions: ActionsMenu,
    onRetour: () -> Unit,
) {
    var texte by remember { mutableStateOf(partage.orEmpty()) }
    var envoi by remember { mutableStateOf(false) }
    var liste by remember { mutableStateOf<List<Curiosite>?>(null) }
    var charge by remember { mutableStateOf(false) }
    val portee = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        liste = distant.curiosites()
        charge = true
    }

    PageMenuColonne("Boîte à curiosités", onRetour) {
        Text(
            "Une question qui te trotte, un lien vu dans la journée : le conteur en fera une porte, " +
                "ce soir dans « d'autres portes » ou demain dans ton lot.",
            color = EncreDouce,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = texte,
            onValueChange = { texte = it },
            placeholder = { Text("Pourquoi les trains belges roulent-ils à gauche ?") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val propre = texte.trim()
                if (propre.isEmpty() || envoi) return@Button
                envoi = true
                val url = regexUrl.find(propre)?.value
                val origine = if (partage != null && propre == partage.trim()) "partage" else "saisie"
                portee.launch {
                    val ok = distant.envoyerCuriosite(propre, url, origine)
                    envoi = false
                    journal.note("curiosite", "origine" to origine, "ok" to ok)
                    if (ok) {
                        texte = ""
                        actions.onAnnonce("Notée : elle nourrira tes prochaines portes.")
                        liste = distant.curiosites()
                    } else {
                        actions.onAnnonce("Pas de connexion : garde-la ici et réessaie plus tard.")
                    }
                }
            },
            enabled = texte.isNotBlank() && !envoi,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (envoi) "Envoi…" else "Noter") }

        Spacer(Modifier.height(20.dp))
        Etiquette("Dans la boîte")
        val l = liste
        when {
            !charge -> Text("Chargement…", color = EncreDouce)
            l == null -> Text("Pas de connexion : la boîte se consulte en ligne.", color = EncreDouce)
            l.isEmpty() -> Text("La boîte est vide.", color = EncreDouce)
            else -> l.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(c.texte ?: c.url ?: "", color = Encre, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    Text(
                        when (c.statut) {
                            "en_attente" -> "En attente d'une porte"
                            "porte_generee" -> "Devenue une porte"
                            "utilisee" -> "Lue"
                            else -> "Écartée"
                        } + c.creeLe.take(10).takeIf { it.isNotBlank() }?.let { " · ${jourLisible(it)}" }.orEmpty(),
                        color = if (c.statut == "porte_generee") Ambre else EncreDouce,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Notes perso

@Composable
private fun PageNotes(distant: Distant, journal: Journal, actions: ActionsMenu, onRetour: () -> Unit) {
    var texte by remember { mutableStateOf("") }
    var envoi by remember { mutableStateOf(false) }
    var liste by remember { mutableStateOf<List<NotePerso>?>(null) }
    var charge by remember { mutableStateOf(false) }
    var occupee by remember { mutableStateOf<String?>(null) }
    val portee = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        liste = distant.notesPerso()
        charge = true
    }

    PageMenuColonne("Notes perso", onRetour) {
        Text(
            "Une idée, une réflexion à toi, écrite comme elle vient. Chaque note peut devenir une porte " +
                "ou recevoir une réponse tout de suite. De temps en temps, le récap en ressort une ancienne.",
            color = EncreDouce,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = texte,
            onValueChange = { texte = it },
            placeholder = { Text("Ce qui te trotte dans la tête…") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val propre = texte.trim()
                if (propre.isEmpty() || envoi) return@Button
                envoi = true
                portee.launch {
                    val ok = distant.ajouterNote(propre)
                    envoi = false
                    journal.note("note_perso", "ok" to ok)
                    if (ok) {
                        texte = ""
                        actions.onAnnonce("Note gardée.")
                        liste = distant.notesPerso()
                    } else {
                        actions.onAnnonce("Pas de connexion : garde-la ici et réessaie plus tard.")
                    }
                }
            },
            enabled = texte.isNotBlank() && !envoi,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (envoi) "Envoi…" else "Garder la note") }

        Spacer(Modifier.height(20.dp))
        Etiquette("Tes notes")
        val l = liste
        when {
            !charge -> Text("Chargement…", color = EncreDouce)
            l == null -> Text("Pas de connexion : les notes se consultent en ligne.", color = EncreDouce)
            l.isEmpty() -> Text("Aucune note pour l'instant.", color = EncreDouce)
            else -> l.forEach { n ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(n.texte, color = Encre, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        jourLisible(n.creeLe.take(10)) + if (n.suite == "porte") " · devenue une porte" else "",
                        color = if (n.suite == "porte") Ambre else EncreDouce,
                        fontSize = 11.sp,
                    )
                    n.reponse?.let { r ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            r,
                            color = Encre,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Voile, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (n.suite != "porte") {
                            TextButton(
                                enabled = occupee == null,
                                onClick = {
                                    occupee = n.id
                                    portee.launch {
                                        val ok = distant.noteEnPorte(n)
                                        journal.note("note_en_porte", "ok" to ok)
                                        actions.onAnnonce(
                                            if (ok) "Elle nourrira tes prochaines portes." else "Pas de connexion : réessaie plus tard.",
                                        )
                                        if (ok) liste = distant.notesPerso()
                                        occupee = null
                                    }
                                },
                            ) { Text("En faire une porte", color = Ambre) }
                        }
                        if (n.reponse == null) {
                            TextButton(
                                enabled = occupee == null,
                                onClick = {
                                    occupee = n.id
                                    portee.launch {
                                        val r = distant.repondreNote(n)
                                        journal.note("note_reponds_moi", "ok" to (r.texte != null), "quota" to r.quota)
                                        when {
                                            r.texte != null -> liste = distant.notesPerso()
                                            r.quota -> actions.onAnnonce("3 réponses par jour : la suivante attendra demain.")
                                            else -> actions.onAnnonce(r.erreur ?: "Pas de réponse pour l'instant.")
                                        }
                                        occupee = null
                                    }
                                },
                            ) { Text(if (occupee == n.id) "Il réfléchit…" else "Réponds-moi", color = Ambre) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Réviser hors soirée

@Composable
private fun PageReviser(
    progression: Progression,
    coeurs: Coeurs,
    journal: Journal,
    onFin: () -> Unit,
    onRetour: () -> Unit,
) {
    val liste = remember { progression.aReviser(coeurs::conceptAime) }
    var index by remember { mutableIntStateOf(0) }
    var choix by remember { mutableStateOf<Int?>(null) }
    var faites by remember { mutableIntStateOf(0) }
    var justes by remember { mutableIntStateOf(0) }

    PageMenuColonne("Réviser", onRetour) {
        when {
            liste.isEmpty() -> Vide("Rien à réviser pour l'instant : le terrier doit d'abord pousser.")
            index >= liste.size -> {
                Vide("Tu as fait le tour : $justes juste${if (justes > 1) "s" else ""} sur $faites.")
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    index = 0
                    choix = null
                }) { Text("Recommencer", color = Encre) }
            }
            else -> QcmLibre(
                vu = liste[index],
                aimee = coeurs.conceptAime(liste[index].concept.id),
                choix = choix,
                justes = justes,
                faites = faites,
                progres = (index + 1f) / liste.size,
                onChoix = { i, reussi ->
                    choix = i
                    faites += 1
                    if (reussi) justes += 1
                    progression.resultatRevisionLibre(liste[index].concept.id, reussi)
                    journal.note("qcm_libre", "concept" to liste[index].concept.id, "reussi" to reussi)
                },
                onSuivante = {
                    index += 1
                    choix = null
                },
                onStop = {
                    onFin()
                    onRetour()
                },
            )
        }
    }
}

@Composable
private fun QcmLibre(
    vu: ConceptVu,
    aimee: Boolean,
    choix: Int?,
    justes: Int,
    faites: Int,
    progres: Float,
    onChoix: (Int, Boolean) -> Unit,
    onSuivante: () -> Unit,
    onStop: () -> Unit,
) {
    Column {
        val qcm = vu.concept.qcm
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiquette(
                buildString {
                    append(nomCourt(vu.concept.theme))
                    if (aimee) append(" · aimé")
                    if (vu.du()) append(" · dû")
                },
            )
            Spacer(Modifier.weight(1f))
            Text("$justes/$faites", color = EncreDouce, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(qcm.question, color = Encre, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        qcm.choix.forEachIndexed { i, libelle ->
            val bord = when {
                choix == null -> Encre.copy(alpha = 0.35f)
                i == qcm.bonne -> Color(0xFF8FD18B)
                i == choix -> Color(0xFFE38B7A)
                else -> Encre.copy(alpha = 0.15f)
            }
            OutlinedButton(
                onClick = { if (choix == null) onChoix(i, i == qcm.bonne) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                border = BorderStroke(1.dp, bord),
            ) { Text(libelle, color = Encre) }
        }
        if (choix != null) {
            Spacer(Modifier.height(12.dp))
            if (choix != qcm.bonne) {
                Text(qcm.autreAngle, color = EncreDouce, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSuivante, modifier = Modifier.weight(1f)) { Text("Suivante") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("J'arrête", color = Encre) }
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progres },
            modifier = Modifier.fillMaxWidth(),
            color = Ambre,
            trackColor = Voile,
        )
    }
}
