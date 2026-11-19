package fr.dash.chevet.data

import android.content.Context
import fr.dash.chevet.model.Lot
import kotlinx.serialization.json.Json

interface LotRepository {
    fun lotDuJour(): Lot
}

/** Lot d'exemple embarqué dans l'APK, en attendant la génération par Supabase + Groq. */
class LotExemple(private val context: Context) : LotRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun lotDuJour(): Lot =
        context.assets.open("lot_exemple.json").bufferedReader().use { lecteur ->
            json.decodeFromString(Lot.serializer(), lecteur.readText())
        }
}
