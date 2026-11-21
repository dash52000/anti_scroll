package fr.dash.chevet.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dash.chevet.data.Checkin
import fr.dash.chevet.data.Distant
import fr.dash.chevet.data.FicheOnboarding
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.SUJETS_DEPART
import fr.dash.chevet.data.Soiree
import fr.dash.chevet.data.lireHistoriqueYoutube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ETAPES = 5
private const val TAILLE_MAX_HISTORIQUE = 60L * 1024 * 1024

/**
 * L'onboarding, environ 5 minutes en une fois : relire la fiche, swiper des sujets, importer l'historique
 * YouTube, régler les niveaux, puis le déclencheur et l'heure de coucher. Les étapes 1, 4 et 5 ouvrent les
 * réglages existants ; le swipe et l'import écrivent dans la fiche « Ce que Chevet sait de moi ».
 */
@Composable
internal fun PageOnboarding(
    distant: Distant,
    soiree: Soiree,
    journal: Journal,
    actions: ActionsMenu,
    onRetour: () -> Unit,
) {
    var etape by remember { mutableIntStateOf(0) }
    val suivant = { etape = (etape + 1).coerceAtMost(ETAPES - 1) }

    PageMenuColonne("Faire connaissance", onRetour) {
        Text("Étape ${etape + 1} sur $ETAPES", color = Ambre, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        when (etape) {
            0 -> Etape(
                "Ta fiche",
                "Chevet part d'un brouillon de ce qu'il sait de toi. Relis-le et corrige ce qui est faux : " +
                    "c'est lui qui guide les histoires, les exemples et le niveau des cartes.",
            ) {
                Button(onClick = { actions.onReglages(true) }, modifier = Modifier.fillMaxWidth()) { Text("Relire et corriger ma fiche") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = suivant, modifier = Modifier.fillMaxWidth()) { Text("C'est bon, suivant", color = Encre) }
            }
            1 -> EtapeSwipe(distant, journal, suivant)
            2 -> EtapeYoutube(distant, journal, suivant)
            3 -> Etape(
                "Ton niveau, domaine par domaine",
                "Expert, curieux ou débutant : Chevet adapte la profondeur des cartes. Les niveaux se règlent " +
                    "dans les réglages, section Contenu.",
            ) {
                Button(onClick = { actions.onReglages(false) }, modifier = Modifier.fillMaxWidth()) { Text("Régler mes niveaux") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = suivant, modifier = Modifier.fillMaxWidth()) { Text("Suivant", color = Encre) }
            }
            else -> Etape(
                "Ton déclencheur et ton heure de coucher",
                "Une phrase qui t'attache à l'habitude (« Quand je me mets au lit, j'ouvre Chevet ») et l'heure à " +
                    "laquelle tu te couches : la notif du soir part une heure avant. Tout se règle dans les réglages, " +
                    "section Soirée.",
            ) {
                Button(onClick = { actions.onReglages(false) }, modifier = Modifier.fillMaxWidth()) { Text("Régler ma soirée") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        soiree.onboardingFait = true
                        journal.note("onboarding_fini")
                        actions.onAnnonce("C'est noté : tes prochains lots en tiendront compte.")
                        actions.onCeSoir()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Terminer", color = Encre) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = {
                journal.note("onboarding_plus_tard", "etape" to etape)
                actions.onCeSoir()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Plus tard, depuis le menu", color = EncreDouce) }
    }
}

@Composable
private fun Etape(titre: String, texte: String, contenu: @Composable () -> Unit) {
    Text(titre, color = Encre, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(texte, color = EncreDouce, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(20.dp))
    contenu()
}

// ---------------------------------------------------------------- Swipe de sujets

@Composable
private fun EtapeSwipe(distant: Distant, journal: Journal, suivant: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var gardes by remember { mutableStateOf(listOf<String>()) }
    var rejetes by remember { mutableStateOf(listOf<String>()) }
    var enregistre by remember { mutableStateOf<Boolean?>(null) }
    val portee = rememberCoroutineScope()

    fun enregistrer(g: List<String>, r: List<String>) {
        portee.launch {
            val profil = distant.profil()
            enregistre = if (profil == null) {
                false
            } else {
                var fiche = FicheOnboarding.avecPuces(profil.fiche, FicheOnboarding.GARDES, g, remplacer = true)
                fiche = FicheOnboarding.avecPuces(fiche, FicheOnboarding.REJETES, r, remplacer = true)
                distant.enregistrerProfil(profil.copy(fiche = fiche))
            }
            journal.note("onboarding_swipe", "gardes" to g.size, "rejetes" to r.size, "ok" to enregistre)
        }
    }

    val choisir = { garde: Boolean ->
        val sujet = SUJETS_DEPART[index]
        val g = if (garde) gardes + sujet else gardes
        val r = if (garde) rejetes else rejetes + sujet
        gardes = g
        rejetes = r
        index += 1
        if (index >= SUJETS_DEPART.size) enregistrer(g, r)
    }

    Text("Des sujets, d'un geste", color = Encre, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    if (index >= SUJETS_DEPART.size) {
        Text(
            "${gardes.size} sujet${if (gardes.size > 1) "s" else ""} gardé${if (gardes.size > 1) "s" else ""}, ${rejetes.size} écarté${if (rejetes.size > 1) "s" else ""}. " +
                when (enregistre) {
                    true -> "C'est dans ta fiche."
                    false -> "Pas de connexion : réessaie depuis le menu."
                    null -> "Enregistrement…"
                },
            color = EncreDouce,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = suivant, modifier = Modifier.fillMaxWidth()) { Text("Suivant") }
        return
    }
    Text(
        "Glisse la carte à droite pour garder, à gauche pour écarter. ${index + 1} sur ${SUJETS_DEPART.size}.",
        color = EncreDouce,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    var glisse by remember(index) { mutableStateOf(0f) }
    Panneau(
        Modifier.pointerInput(index) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (glisse > 140f) choisir(true) else if (glisse < -140f) choisir(false)
                    glisse = 0f
                },
                onDragCancel = { glisse = 0f },
            ) { _, dx -> glisse += dx }
        },
    ) {
        Text(
            SUJETS_DEPART[index],
            color = Encre,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { choisir(false) }, modifier = Modifier.weight(1f)) { Text("Pas pour moi", color = Encre) }
        Button(onClick = { choisir(true) }, modifier = Modifier.weight(1f)) { Text("Garder") }
    }
    TextButton(onClick = suivant, modifier = Modifier.fillMaxWidth()) { Text("Passer cette étape", color = EncreDouce) }
}

// ---------------------------------------------------------------- Import de l'historique YouTube

@Composable
private fun EtapeYoutube(distant: Distant, journal: Journal, suivant: () -> Unit) {
    val contexte = LocalContext.current
    val portee = rememberCoroutineScope()
    var etat by remember { mutableStateOf<String?>(null) }
    var propositions by remember { mutableStateOf(listOf<String>()) }
    var choisies by remember { mutableStateOf(setOf<String>()) }
    var ajoute by remember { mutableStateOf(false) }

    val ouvrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        etat = "Lecture de l'historique…"
        portee.launch {
            val taille = withContext(Dispatchers.IO) {
                runCatching { contexte.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
            }
            if (taille > TAILLE_MAX_HISTORIQUE) {
                etat = "Ce fichier est trop gros (plus de 60 Mo). Exporte une période plus courte depuis Takeout."
                return@launch
            }
            val resume = withContext(Dispatchers.IO) {
                runCatching {
                    contexte.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()?.let(::lireHistoriqueYoutube)
            }
            if (resume == null) {
                etat = "Je ne reconnais pas ce fichier : il me faut « watch-history.json » (Takeout, format JSON)."
                return@launch
            }
            etat = "${resume.videos} vidéos lues, ${resume.chaines.size} chaînes. Chevet en tire tes centres d'intérêt…"
            val puces = distant.interetsYoutube(resume)
            journal.note("onboarding_youtube", "videos" to resume.videos, "puces" to puces.size)
            if (puces.isEmpty()) {
                etat = "Pas de réponse du serveur pour l'instant : réessaie depuis le menu."
            } else {
                propositions = puces
                choisies = puces.toSet()
                etat = "Voilà ce que j'y vois. Décoche ce qui n'est pas toi."
            }
        }
    }

    Text("Ton historique YouTube", color = Encre, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        "Sur ordinateur : takeout.google.com, ne coche que YouTube, « historique », format JSON. " +
            "Mets le fichier watch-history.json sur ton téléphone, puis choisis-le ici. Il est lu sur le téléphone ; " +
            "seuls tes 40 premières chaînes et 120 titres partent au serveur, pour en tirer des centres d'intérêt.",
        color = EncreDouce,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    if (propositions.isEmpty()) {
        Button(onClick = { ouvrir.launch(arrayOf("application/json", "text/*", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Choisir watch-history.json")
        }
    }
    etat?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Ambre, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
    }
    if (propositions.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            propositions.forEach { p ->
                FilterChip(
                    selected = p in choisies,
                    onClick = { choisies = if (p in choisies) choisies - p else choisies + p },
                    label = { Text(p) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !ajoute && choisies.isNotEmpty(),
            onClick = {
                ajoute = true
                portee.launch {
                    val profil = distant.profil()
                    val ok = profil != null && distant.enregistrerProfil(
                        profil.copy(
                            fiche = FicheOnboarding.avecPuces(
                                profil.fiche,
                                FicheOnboarding.YOUTUBE,
                                propositions.filter { it in choisies },
                                remplacer = false,
                            ),
                        ),
                    )
                    etat = if (ok) "Ajouté à ta fiche." else "Pas de connexion : réessaie depuis le menu."
                    ajoute = ok
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Ajouter à ma fiche") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = suivant, modifier = Modifier.fillMaxWidth()) {
        Text(if (ajoute) "Suivant" else "Passer cette étape", color = Encre)
    }
}

// ---------------------------------------------------------------- Check-in du dimanche

/**
 * Le dimanche, au récap : « Ta semaine » en trois mots, puis pourquoi en un tap. Facultatif, sans reproche :
 * « passer » le range jusqu'à dimanche prochain.
 */
@Composable
internal fun CarteCheckin(distant: Distant, soiree: Soiree, journal: Journal) {
    val semaine = remember { Checkin.semaine() }
    var visible by remember { mutableStateOf(Checkin.estDimanche() && soiree.checkinSemaine != semaine) }
    var ressenti by remember { mutableStateOf<String?>(null) }
    val portee = rememberCoroutineScope()
    if (!visible) return

    Spacer(Modifier.height(24.dp))
    Etiquette("Ta semaine avec Chevet")
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(Voile, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        val r = ressenti
        if (r == null) {
            Text("Comment c'était, cette semaine ?", color = Encre, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkin.RESSENTIS.forEach { (cle, libelle) ->
                    OutlinedButton(onClick = { ressenti = cle }, modifier = Modifier.weight(1f)) { Text(libelle, color = Encre) }
                }
            }
        } else {
            Text("Pourquoi, en un tap ?", color = Encre, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkin.raisonsPour(r).forEach { raison ->
                    OutlinedButton(
                        onClick = {
                            visible = false
                            soiree.checkinSemaine = semaine
                            journal.note("checkin", "ressenti" to r, "raison" to raison)
                            portee.launch { distant.enregistrerCheckin(semaine, r, listOf(raison)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(raison, color = Encre) }
                }
            }
        }
        TextButton(
            onClick = {
                visible = false
                soiree.checkinSemaine = semaine
                val r2 = ressenti
                journal.note("checkin_passe", "ressenti" to r2)
                if (r2 != null) portee.launch { distant.enregistrerCheckin(semaine, r2, emptyList()) }
            },
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),
        ) { Text("Passer", color = EncreDouce) }
    }
}
