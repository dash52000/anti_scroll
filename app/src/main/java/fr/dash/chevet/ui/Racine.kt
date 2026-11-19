package fr.dash.chevet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.dash.chevet.data.Distant
import fr.dash.chevet.data.Journal
import fr.dash.chevet.data.LotCharge
import fr.dash.chevet.data.Preparation
import fr.dash.chevet.data.Progression
import fr.dash.chevet.model.Lot
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Charge le lot du jour (Supabase, sinon lot d'exemple), puis lance le rituel. */
@Composable
fun Racine(
    distant: Distant,
    progression: Progression,
    journal: Journal,
    lotExemple: () -> Lot,
    onPartager: (String) -> Unit,
    onRejouer: () -> Unit,
    onFermer: () -> Unit,
) {
    var charge by remember { mutableStateOf<LotCharge?>(null) }
    var preparation by remember { mutableStateOf(Preparation(0, 8)) }
    val portee = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val resultat = distant.lotDuJour(lotExemple) { preparation = it }
        journal.note(
            "lot_charge",
            "lot" to resultat.lot.jour,
            "exemple" to resultat.exemple,
            "message" to resultat.message,
        )
        charge = resultat
        distant.envoyerJournal(journal)
    }

    val pret = charge
    if (pret == null) {
        EcranPreparation(preparation)
        return
    }

    ChevetApp(
        lot = pret.lot,
        progression = progression,
        journal = journal,
        onPartager = onPartager,
        onRejouer = onRejouer,
        onBonneNuit = {
            portee.launch {
                withTimeoutOrNull(4_000) { distant.envoyerJournal(journal) }
                onFermer()
            }
        },
        bandeau = pret.message,
        creuser = { concept, question -> distant.creuser(concept, question) },
        onConceptsExplores = { concepts ->
            if (!pret.exemple) portee.launch { distant.enregistrerConcepts(concepts, pret.lot.jour) }
        },
    )
}

@Composable
private fun EcranPreparation(preparation: Preparation) {
    Box(Modifier.fillMaxSize()) {
        CielDuSoir(nuit = 0f, etoiles = false)
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Etiquette("Chevet")
            Spacer(Modifier.height(12.dp))
            Text(
                "Ton lot du soir se prépare…",
                color = Encre,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { preparation.etape.toFloat() / preparation.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = Ambre,
                trackColor = Voile,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Étape ${preparation.etape} sur ${preparation.total}",
                color = EncreDouce,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
