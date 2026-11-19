package fr.dash.chevet.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dash.chevet.Rappel
import fr.dash.chevet.data.Cran
import fr.dash.chevet.data.Distant
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.Niveaux
import fr.dash.chevet.data.Profil
import fr.dash.chevet.data.ResultatFlux
import fr.dash.chevet.data.Rubrique
import fr.dash.chevet.data.Soiree
import fr.dash.chevet.data.SourceRss
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FondReglages = Color(0xFF0E0B1C)
private val Erreur = Color(0xFFE38B7A)

private enum class PageReglages { Principal, Fiche }

/**
 * Réglages, ouverts par-dessus la soirée en cours. Le contenu (profil) n'est envoyé qu'à la
 * fermeture : [onFermer] reçoit le profil d'avant et celui d'après (null hors ligne sans copie).
 * La soirée (heure, notif, déclencheur) est gardée sur le téléphone au fil des changements.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EcranReglages(
    distant: Distant,
    soiree: Soiree,
    journal: Journal,
    etatLot: String,
    onFermer: (avant: Profil?, apres: Profil?) -> Unit,
    onRejouer: () -> Unit,
    onRegenererLot: () -> Unit,
    onAnnonce: (String) -> Unit,
) {
    val contexte = LocalContext.current
    val portee = rememberCoroutineScope()

    var original by remember { mutableStateOf<Profil?>(null) }
    var brouillon by remember { mutableStateOf<Profil?>(null) }
    var chargement by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(PageReglages.Principal) }

    var coucher by remember { mutableStateOf(soiree.coucher()) }
    var notif by remember { mutableStateOf(soiree.notifActive) }
    var declencheur by remember { mutableStateOf(soiree.declencheur) }
    var autorisee by remember { mutableStateOf(Rappel.autorisee(contexte)) }
    var outils by remember { mutableStateOf(soiree.outilsTest) }

    val demandePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        autorisee = ok
    }

    LaunchedEffect(Unit) {
        val p = distant.profil()?.let { it.copy(heureCoucher = it.heureCoucher?.take(5)) }
        if (p != null && declencheur.isBlank() && !p.declencheur.isNullOrBlank()) {
            declencheur = p.declencheur
            soiree.declencheur = p.declencheur
        }
        original = p?.copy(declencheur = declencheur.ifBlank { null }, heureCoucher = Soiree.format(coucher.minutes))
        brouillon = original
        chargement = false
    }

    fun maj(f: (Profil) -> Profil) {
        brouillon = brouillon?.let(f)
    }

    val fermer: () -> Unit = {
        if (page == PageReglages.Fiche) {
            page = PageReglages.Principal
        } else {
            soiree.declencheur = declencheur
            Rappel.programmer(contexte)
            val apres = brouillon?.copy(
                declencheur = declencheur.ifBlank { null },
                heureCoucher = Soiree.format(soiree.coucher().minutes),
            )
            onFermer(original, apres)
        }
    }
    BackHandler(onBack = fermer)

    Box(
        Modifier
            .fillMaxSize()
            .background(FondReglages)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val b = brouillon
        if (page == PageReglages.Fiche && b != null) {
            EcranFiche(
                fiche = b.fiche,
                onChange = { fiche -> maj { it.copy(fiche = fiche) } },
                onRetour = fermer,
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                BarreTitre("Réglages", onRetour = fermer)

                // ------------------------------------------------ Soirée
                Section("Soirée") {
                    Text("Heure de coucher", color = Encre, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Soiree.format(coucher.minutes),
                            color = Ambre,
                            fontSize = 32.sp,
                            fontFamily = FontFamily.Serif,
                        )
                        if (coucher.forcee) {
                            Spacer(Modifier.width(16.dp))
                            PetitBouton("−") {
                                soiree.heureForcee = Math.floorMod(coucher.minutes - 15, 1440)
                                coucher = soiree.coucher()
                            }
                            Spacer(Modifier.width(8.dp))
                            PetitBouton("+") {
                                soiree.heureForcee = Math.floorMod(coucher.minutes + 15, 1440)
                                coucher = soiree.coucher()
                            }
                        }
                    }
                    Text(
                        when {
                            coucher.forcee -> "Fixée par toi."
                            coucher.soirsAppris != null -> "Apprise sur tes ${coucher.soirsAppris} derniers soirs."
                            else -> "Par défaut, le temps d'apprendre : ${soiree.soirsObserves()} soir(s) observé(s) sur ${Soiree.SOIRS_MINIMUM}."
                        },
                        color = EncreDouce,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            soiree.heureForcee = if (coucher.forcee) null else coucher.minutes
                            coucher = soiree.coucher()
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (coucher.forcee) "Revenir à l'heure apprise" else "La fixer moi-même", color = Ambre)
                    }

                    Spacer(Modifier.height(8.dp))
                    LigneInterrupteur(
                        titre = "Notif du soir",
                        detail = "À ${Soiree.format(Math.floorMod(coucher.minutes - 60, 1440))}, une heure avant le coucher.",
                        actif = notif,
                    ) { v ->
                        notif = v
                        soiree.notifActive = v
                        if (v && !autorisee && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            demandePermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (notif && !autorisee) {
                        Text("Android bloque les notifs de Chevet.", color = Erreur, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    demandePermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }) { Text("Autoriser", color = Ambre) }
                            TextButton(onClick = {
                                contexte.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, contexte.packageName),
                                )
                            }) { Text("Réglages Android", color = EncreDouce) }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = declencheur,
                        onValueChange = {
                            declencheur = it
                            soiree.declencheur = it
                        },
                        label = { Text("Déclencheur") },
                        supportingText = { Text("Ta phrase d'habitude ; elle sert de titre à la notif.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ------------------------------------------------ Contenu
                Section("Contenu") {
                    when {
                        chargement -> Text("Chargement…", color = EncreDouce)
                        b == null -> Text(
                            "Pas de connexion : le contenu se règle quand Chevet joint le serveur.",
                            color = EncreDouce,
                        )
                        else -> {
                            SousTitre("Poids des thèmes")
                            b.poidsThemes.forEach { (theme, poids) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(theme, color = Encre, modifier = Modifier.weight(1f))
                                    Text("$poids", color = Ambre)
                                }
                                Slider(
                                    value = poids.toFloat(),
                                    onValueChange = { v -> maj { it.copy(poidsThemes = it.poidsThemes + (theme to v.roundToInt())) } },
                                    valueRange = 0f..100f,
                                )
                            }
                            Text(
                                "À 0, un thème disparaît ; à 100, il revient le plus souvent.",
                                color = EncreDouce,
                                style = MaterialTheme.typography.bodySmall,
                            )

                            Spacer(Modifier.height(20.dp))
                            SousTitre("Niveau par domaine")
                            b.niveaux.forEach { (cle, valeur) ->
                                Spacer(Modifier.height(8.dp))
                                Text(Niveaux.libelle(cle), color = Encre)
                                val precision = Niveaux.precision(valeur)
                                if (precision.isNotBlank()) {
                                    Text(precision, color = EncreDouce, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val actuel = Niveaux.cran(valeur)
                                    Cran.entries.forEach { cran ->
                                        FilterChip(
                                            selected = actuel == cran,
                                            onClick = {
                                                if (actuel != cran) {
                                                    maj { it.copy(niveaux = it.niveaux + (cle to Niveaux.avecCran(valeur, cran))) }
                                                }
                                            },
                                            label = { Text(cran.libelle) },
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(20.dp))
                            SousTitre("Sujets à éviter")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                b.aEviter.forEach { sujet ->
                                    InputChip(
                                        selected = false,
                                        onClick = { maj { it.copy(aEviter = it.aEviter - sujet) } },
                                        label = { Text(sujet) },
                                        trailingIcon = {
                                            Icon(Icons.Filled.Close, contentDescription = "Retirer", modifier = Modifier.size(16.dp))
                                        },
                                    )
                                }
                            }
                            var nouveauSujet by remember { mutableStateOf("") }
                            ChampAjout(
                                valeur = nouveauSujet,
                                onValeur = { nouveauSujet = it },
                                indication = "Ajouter un sujet",
                            ) {
                                val sujet = nouveauSujet.trim()
                                if (sujet.isNotEmpty()) maj { it.copy(aEviter = (it.aEviter + sujet).distinct()) }
                                nouveauSujet = ""
                            }

                            Spacer(Modifier.height(20.dp))
                            SousTitre("Sources d'actu")
                            b.sources.forEachIndexed { i, source ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(source.nom, color = Encre)
                                        Text(domaine(source.url), color = EncreDouce, fontSize = 12.sp)
                                    }
                                    if (source.ajoutee) {
                                        IconButton(onClick = {
                                            maj { it.copy(sources = it.sources.filterIndexed { j, _ -> j != i }) }
                                        }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Supprimer", tint = EncreDouce)
                                        }
                                    }
                                    Switch(
                                        checked = source.actif,
                                        onCheckedChange = { v ->
                                            maj { p -> p.copy(sources = p.sources.mapIndexed { j, s -> if (j == i) s.copy(actif = v) else s }) }
                                        },
                                    )
                                }
                            }
                            AjoutSource(
                                tester = { url -> distant.testerFlux(url) },
                                dejaLa = { url -> b.sources.any { it.url.trimEnd('/') == url.trimEnd('/') } },
                                onAjouter = { source -> maj { it.copy(sources = it.sources + source) } },
                            )
                        }
                    }
                }

                // ------------------------------------------------ Creuser
                Section("Creuser") {
                    val parSoir = b?.creusagesParSoir ?: soiree.creusagesParSoir
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Questions par soir", color = Encre, modifier = Modifier.weight(1f))
                        Text("$parSoir", color = Ambre)
                    }
                    Slider(
                        value = parSoir.toFloat(),
                        onValueChange = { v -> maj { it.copy(creusagesParSoir = v.roundToInt().coerceIn(1, 10)) } },
                        valueRange = 1f..10f,
                        steps = 8,
                        enabled = b != null,
                    )
                    Text(
                        "Les 3 concepts du soir, eux, ne bougent pas.",
                        color = EncreDouce,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // ------------------------------------------------ Profil
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Voile, RoundedCornerShape(16.dp))
                        .clickable(enabled = b != null) { page = PageReglages.Fiche }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ce que Chevet sait de moi", color = Encre, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (b == null) "Disponible avec une connexion" else "${b.fiche.sumOf { it.puces.size }} puces, ${b.fiche.size} rubriques",
                            color = EncreDouce,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EncreDouce)
                }

                // ------------------------------------------------ Outils de test
                if (outils) {
                    var rafraichir by remember { mutableIntStateOf(0) }
                    Section("Outils de test") {
                        val lignes = remember(rafraichir) { distant.etatSynchro(journal) + ("Lot du jour" to etatLot) }
                        lignes.forEach { (cle, valeur) ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(cle, color = EncreDouce, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(valeur, color = Encre, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        BoutonOutil("Rejouer le lot", onRejouer)
                        BoutonOutil("Forcer la régénération du lot", onRegenererLot)
                        BoutonOutil("Envoyer le journal maintenant") {
                            portee.launch {
                                distant.envoyerJournal(journal)
                                rafraichir += 1
                            }
                        }
                        BoutonOutil("Notif de test") {
                            if (Rappel.autorisee(contexte)) {
                                Rappel.notifier(contexte, test = true)
                            } else {
                                onAnnonce("Autorise d'abord les notifs (section Soirée).")
                            }
                        }
                        BoutonOutil("Masquer les outils de test") {
                            outils = false
                            soiree.outilsTest = false
                        }
                    }
                }

                // ------------------------------------------------ Version (7 taps : outils de test)
                val version = remember {
                    runCatching { contexte.packageManager.getPackageInfo(contexte.packageName, 0).versionName }.getOrNull() ?: "?"
                }
                var taps by remember { mutableIntStateOf(0) }
                Text(
                    "Chevet $version",
                    color = EncreDouce.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (outils) return@clickable
                            taps += 1
                            when {
                                taps >= 7 -> {
                                    outils = true
                                    soiree.outilsTest = true
                                    onAnnonce("Outils de test activés")
                                }
                                taps >= 4 -> onAnnonce("Encore ${7 - taps} tap(s)")
                            }
                        }
                        .padding(24.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- « Ce que Chevet sait de moi »

@Composable
private fun EcranFiche(fiche: List<Rubrique>, onChange: (List<Rubrique>) -> Unit, onRetour: () -> Unit) {
    // Puce en cours d'édition : (rubrique, puce), puce = -1 pour une nouvelle.
    var edition by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var texte by remember { mutableStateOf("") }

    fun valider() {
        val (r, p) = edition ?: return
        val propre = texte.trim()
        val nouvelles = fiche.mapIndexed { i, rubrique ->
            if (i != r) {
                rubrique
            } else {
                val puces = when {
                    p == -1 && propre.isNotEmpty() -> rubrique.puces + propre
                    p == -1 -> rubrique.puces
                    propre.isEmpty() -> rubrique.puces.filterIndexed { j, _ -> j != p }
                    else -> rubrique.puces.mapIndexed { j, puce -> if (j == p) propre else puce }
                }
                rubrique.copy(puces = puces)
            }
        }
        onChange(nouvelles)
        edition = null
        texte = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        BarreTitre("Ce que Chevet sait de moi", onRetour = onRetour)
        Text(
            "Chevet s'en sert pour composer tes portes. Touche une puce pour la modifier.",
            color = EncreDouce,
            style = MaterialTheme.typography.bodyMedium,
        )

        fiche.forEachIndexed { r, rubrique ->
            Spacer(Modifier.height(20.dp))
            Etiquette(rubrique.titre.ifBlank { "Sans titre" })
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Voile, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                rubrique.puces.forEachIndexed { p, puce ->
                    if (edition == (r to p)) {
                        ChampEdition(texte, onValeur = { texte = it }, onValider = ::valider, onAnnuler = { edition = null })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "• $puce",
                                color = Encre,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        edition = r to p
                                        texte = puce
                                    }
                                    .padding(vertical = 6.dp),
                            )
                            IconButton(
                                onClick = { onChange(fiche.mapIndexed { i, x -> if (i == r) x.copy(puces = x.puces.filterIndexed { j, _ -> j != p }) else x }) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Supprimer", tint = EncreDouce, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                if (edition == (r to -1)) {
                    ChampEdition(texte, onValeur = { texte = it }, onValider = ::valider, onAnnuler = { edition = null })
                } else {
                    Row {
                        TextButton(onClick = {
                            edition = r to -1
                            texte = ""
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Ambre, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ajouter", color = Ambre)
                        }
                        if (rubrique.puces.isEmpty()) {
                            TextButton(onClick = { onChange(fiche.filterIndexed { i, _ -> i != r }) }) {
                                Text("Retirer la rubrique", color = EncreDouce)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        var nouvelleRubrique by remember { mutableStateOf("") }
        ChampAjout(
            valeur = nouvelleRubrique,
            onValeur = { nouvelleRubrique = it },
            indication = "Nouvelle rubrique",
        ) {
            val titre = nouvelleRubrique.trim()
            if (titre.isNotEmpty()) {
                onChange(fiche + Rubrique(titre))
                edition = fiche.size to -1
                texte = ""
            }
            nouvelleRubrique = ""
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- Nouvelle source d'actu

@Composable
private fun AjoutSource(
    tester: suspend (String) -> ResultatFlux,
    dejaLa: (String) -> Boolean,
    onAjouter: (SourceRss) -> Unit,
) {
    val portee = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var resultat by remember { mutableStateOf<ResultatFlux?>(null) }
    var enTest by remember { mutableStateOf(false) }

    Spacer(Modifier.height(8.dp))
    ChampAjout(
        valeur = url,
        onValeur = {
            url = it
            resultat = null
        },
        indication = "Adresse d'un flux RSS",
        icone = { Icon(Icons.Filled.Search, contentDescription = "Tester", tint = Ambre) },
    ) {
        val adresse = url.trim()
        if (adresse.isEmpty() || enTest) return@ChampAjout
        enTest = true
        portee.launch {
            resultat = tester(if (adresse.startsWith("http")) adresse else "https://$adresse")
            enTest = false
        }
    }
    if (enTest) Text("Vérification du flux…", color = EncreDouce, style = MaterialTheme.typography.bodySmall)
    resultat?.let { r ->
        val adresse = r.url ?: url.trim()
        when {
            !r.ok -> Text(r.erreur ?: "Flux illisible", color = Erreur, style = MaterialTheme.typography.bodySmall)
            dejaLa(adresse) -> Text("Cette source est déjà dans la liste.", color = EncreDouce, style = MaterialTheme.typography.bodySmall)
            else -> {
                Text("${r.nom ?: domaine(adresse)} · ${r.articles} articles", color = Encre)
                r.dernier?.let { Text("Dernier : $it", color = EncreDouce, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    onAjouter(SourceRss(nom = r.nom ?: domaine(adresse), url = adresse, actif = true, ajoutee = true))
                    url = ""
                    resultat = null
                }) { Text("Ajouter cette source") }
            }
        }
    }
}

// ---------------------------------------------------------------- Briques

@Composable
private fun BarreTitre(titre: String, onRetour: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRetour) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Encre)
        }
        Text(titre, color = Encre, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Section(titre: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(24.dp))
    Etiquette(titre)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .background(Voile, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SousTitre(texte: String) {
    Text(texte, color = Encre, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LigneInterrupteur(titre: String, detail: String, actif: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titre, color = Encre, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = EncreDouce, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = actif, onCheckedChange = onChange)
    }
}

@Composable
private fun PetitBouton(libelle: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) {
        Text(libelle, color = Encre, fontSize = 18.sp)
    }
}

@Composable
private fun BoutonOutil(libelle: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(libelle, color = Encre)
    }
}

@Composable
private fun ChampAjout(
    valeur: String,
    onValeur: (String) -> Unit,
    indication: String,
    icone: @Composable () -> Unit = { Icon(Icons.Filled.Add, contentDescription = "Ajouter", tint = Ambre) },
    onAjouter: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = valeur,
            onValueChange = onValeur,
            placeholder = { Text(indication) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAjouter() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAjouter, content = icone)
    }
}

@Composable
private fun ChampEdition(valeur: String, onValeur: (String) -> Unit, onValider: () -> Unit, onAnnuler: () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = valeur,
            onValueChange = onValeur,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onValider() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = onValider) { Text("OK", color = Ambre) }
            TextButton(onClick = onAnnuler) { Text("Annuler", color = EncreDouce) }
        }
    }
}

private fun domaine(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
