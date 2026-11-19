package fr.dash.chevet.ui

import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import fr.dash.chevet.data.ConceptVu
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.Progression
import fr.dash.chevet.model.Carte
import fr.dash.chevet.model.Concept
import fr.dash.chevet.model.Lot
import fr.dash.chevet.model.Porte
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Les étapes du rituel du soir. */
private sealed interface Etape {
    data object Echauffement : Etape
    data object Pepite : Etape
    data object Portes : Etape
    data class Fil(val porte: Porte) : Etape
    data object Recap : Etape
    data object DejaFini : Etape
}

@Composable
fun ChevetApp(
    lot: Lot,
    progression: Progression,
    journal: Journal,
    onPartager: (String) -> Unit,
    onRejouer: () -> Unit,
    onBonneNuit: () -> Unit,
    bandeau: String? = null,
    creuser: suspend (Concept, String) -> String = { _, _ -> "Pas encore branché." },
    onConceptsExplores: (List<Concept>) -> Unit = {},
) {
    val revisions = remember { progression.revisionsDues() }
    var etape by remember {
        mutableStateOf<Etape>(
            when {
                progression.lotConsomme() -> Etape.DejaFini
                revisions.isNotEmpty() -> Etape.Echauffement
                else -> Etape.Portes
            }
        )
    }
    var progresFil by remember { mutableFloatStateOf(0f) }
    var etoiles by remember { mutableStateOf(false) }
    var creusagesRestants by remember { mutableIntStateOf(3) }
    var conceptsDuSoir by remember { mutableStateOf<List<Concept>>(emptyList()) }

    // La nuit tombe au fil du rituel.
    val nuit = when (etape) {
        Etape.Echauffement -> 0.05f
        Etape.Pepite -> 0.12f
        Etape.Portes -> 0.2f
        is Etape.Fil -> 0.25f + 0.6f * progresFil
        Etape.Recap -> 0.9f
        Etape.DejaFini -> 0.95f
    }

    if (etoiles) {
        LaunchedEffect(Unit) {
            delay(2500)
            onBonneNuit()
        }
    }

    val direBonneNuit: () -> Unit = {
        journal.note("bonne_nuit")
        etoiles = true
    }
    val rejouer: () -> Unit = {
        journal.note("rejouer_test")
        progression.reinitialiserPourTest()
        onRejouer()
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

                Etape.Pepite -> EcranPepite(lot.pepite) { etape = Etape.Portes }

                Etape.Portes -> EcranPortes(lot.portes, bandeau) { porte ->
                    journal.note("porte_choisie", "porte" to porte.id)
                    etape = Etape.Fil(porte)
                }

                is Etape.Fil -> EcranFil(
                    porte = e.porte,
                    journal = journal,
                    creusagesRestants = creusagesRestants,
                    creuser = creuser,
                    onCreuser = { creusagesRestants -= 1 },
                    onPartager = onPartager,
                    onProgres = { progresFil = it },
                    onFin = {
                        progression.marquerVus(e.porte.concepts)
                        progression.marquerLotConsomme()
                        onConceptsExplores(e.porte.concepts)
                        conceptsDuSoir = e.porte.concepts
                        journal.note("fil_termine", "porte" to e.porte.id)
                        etape = Etape.Recap
                    },
                )

                Etape.Recap -> EcranRecap(
                    concepts = conceptsDuSoir,
                    teaser = lot.teaserDemain,
                    totalTerrier = progression.nombreDeConcepts(),
                    onBonneNuit = direBonneNuit,
                    onRejouer = rejouer,
                )

                Etape.DejaFini -> EcranDejaFini(
                    teaser = lot.teaserDemain,
                    totalTerrier = progression.nombreDeConcepts(),
                    onBonneNuit = direBonneNuit,
                    onRejouer = rejouer,
                )
            }
        }
    }
}

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
private fun EcranPortes(portes: List<Porte>, bandeau: String?, onChoix: (Porte) -> Unit) {
    Colonne {
        Spacer(Modifier.height(32.dp))
        if (bandeau != null) {
            Text(bandeau, color = EncreDouce.copy(alpha = 0.7f), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
        Etiquette("Ce soir")
        Spacer(Modifier.height(6.dp))
        Text("Trois portes. Laquelle tu pousses ?", color = Encre, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        portes.forEach { porte ->
            Panneau(
                Modifier
                    .padding(vertical = 6.dp)
                    .clickable { onChoix(porte) },
            ) {
                if (porte.mystere) {
                    Etiquette("Porte mystère")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        porte.indice ?: "…",
                        color = Encre,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                    )
                } else {
                    Text(
                        porte.titre,
                        color = Encre,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(porte.accroche, color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Fil de cartes

private sealed interface Page {
    val concept: Concept

    data class Question(override val concept: Concept) : Page
    data class Texte(override val concept: Concept, val carte: Carte, val numero: Int) : Page
    data class ARetenir(override val concept: Concept) : Page
}

private enum class Action(val libelle: String, val icone: ImageVector, val code: String, val retour: String) {
    Jaime("J'aime", Icons.Filled.Favorite, "like", "Noté : plus de ce genre"),
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
    journal: Journal,
    creusagesRestants: Int,
    creuser: suspend (Concept, String) -> String,
    onCreuser: () -> Unit,
    onPartager: (String) -> Unit,
    onProgres: (Float) -> Unit,
    onFin: () -> Unit,
) {
    val pages = remember(porte) {
        porte.concepts.flatMap { c ->
            listOf<Page>(Page.Question(c)) +
                c.cartes.mapIndexed { i, carte -> Page.Texte(c, carte, i) } +
                Page.ARetenir(c)
        }
    }
    val etatPager = rememberPagerState(pageCount = { pages.size })
    var message by remember { mutableStateOf<String?>(null) }
    var voletCreuser by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    // Journal : temps passé sur chaque carte, et progression de la nuit.
    LaunchedEffect(etatPager) {
        var precedente = etatPager.currentPage
        var debut = System.currentTimeMillis()
        snapshotFlow { etatPager.currentPage }.collect { page ->
            val maintenant = System.currentTimeMillis()
            if (page != precedente) {
                journal.note(
                    "carte_vue",
                    "concept" to pages[precedente].concept.id,
                    "page" to precedente,
                    "duree_ms" to (maintenant - debut),
                )
            }
            precedente = page
            debut = maintenant
            onProgres((page + 1).toFloat() / pages.size)
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            delay(1600)
            message = null
        }
    }

    val concept = pages[etatPager.currentPage].concept

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f)) {
            VerticalPager(state = etatPager, modifier = Modifier.fillMaxSize()) { i ->
                PageCarte(
                    page = pages[i],
                    estDerniere = i == pages.lastIndex,
                    onFin = onFin,
                    onSource = { url -> runCatching { uri.openUri(url) } },
                )
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
        BarreActions { action ->
            journal.note(action.code, "concept" to concept.id, "page" to etatPager.currentPage)
            when (action) {
                Action.Creuser -> voletCreuser = true
                Action.Partager -> onPartager("${concept.aRaconter}\n\n(via Chevet)")
                else -> message = action.retour
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
private fun BarreActions(onAction: (Action) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Voile)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Action.entries.forEach { action ->
            Column(
                Modifier
                    .clickable { onAction(action) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(action.icone, contentDescription = action.libelle, tint = Encre)
                Text(action.libelle, color = EncreDouce, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun VoletCreuser(
    concept: Concept,
    restants: Int,
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
        Etiquette("Creuser · $restants/3 ce soir")
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
private fun questionsSuggerees(c: Concept) = listOf(
    "D'où vient « ${c.titre} » ?",
    "Un exemple concret, près de chez moi ?",
    "Et aujourd'hui, où en est-on ?",
)

// ---------------------------------------------------------------- Récap et fin

@Composable
private fun EcranRecap(
    concepts: List<Concept>,
    teaser: String,
    totalTerrier: Int,
    onBonneNuit: () -> Unit,
    onRejouer: () -> Unit,
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
        FinDeSoiree(teaser, totalTerrier, onBonneNuit, onRejouer)
    }
}

@Composable
private fun EcranDejaFini(teaser: String, totalTerrier: Int, onBonneNuit: () -> Unit, onRejouer: () -> Unit) {
    Colonne {
        Spacer(Modifier.height(32.dp))
        Etiquette("C'est tout pour aujourd'hui")
        Spacer(Modifier.height(12.dp))
        Text(
            "Le lot du jour est fini. Le prochain sera prêt demain matin.",
            color = Encre,
            style = MaterialTheme.typography.bodyLarge,
        )
        FinDeSoiree(teaser, totalTerrier, onBonneNuit, onRejouer)
    }
}

@Composable
private fun ColumnScope.FinDeSoiree(teaser: String, totalTerrier: Int, onBonneNuit: () -> Unit, onRejouer: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Etiquette("Demain")
    Spacer(Modifier.height(6.dp))
    Text(teaser, color = EncreDouce, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
    Spacer(Modifier.height(24.dp))
    Text(
        "Ton terrier : $totalTerrier concept${if (totalTerrier > 1) "s" else ""}",
        color = Ambre,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onBonneNuit, modifier = Modifier.fillMaxWidth()) { Text("Bonne nuit") }
    TextButton(onClick = onRejouer, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Rejouer (test)", color = EncreDouce.copy(alpha = 0.6f))
    }
}
