package fr.dash.chevet.data

import android.content.Context
import fr.dash.chevet.model.Lot
import java.time.LocalDate
import java.time.LocalDateTime

/** Heure de coucher et nombre de soirs sur lesquels elle a été apprise (null : valeur par défaut). */
data class Coucher(val minutes: Int, val forcee: Boolean, val soirsAppris: Int?)

/**
 * Réglages de la soirée, gardés sur le téléphone : heure de coucher apprise ou forcée,
 * notif du soir, déclencheur d'habitude, quota de « creuser » et outils de test.
 */
class Soiree(context: Context) {

    private val prefs = context.getSharedPreferences("soiree", Context.MODE_PRIVATE)

    var notifActive: Boolean
        get() = prefs.getBoolean("notif_active", true)
        set(v) = prefs.edit().putBoolean("notif_active", v).apply()

    /** Heure forcée en minutes depuis minuit, ou null pour l'heure apprise. */
    var heureForcee: Int?
        get() = prefs.getInt("heure_forcee", -1).takeIf { it >= 0 }
        set(v) = prefs.edit().putInt("heure_forcee", v ?: -1).apply()

    var declencheur: String
        get() = prefs.getString("declencheur", null) ?: ""
        set(v) = prefs.edit().putString("declencheur", v).apply()

    var creusagesParSoir: Int
        get() = prefs.getInt("creusages_par_soir", 3)
        set(v) = prefs.edit().putInt("creusages_par_soir", v.coerceIn(1, 10)).apply()

    var outilsTest: Boolean
        get() = prefs.getBoolean("outils_test", false)
        set(v) = prefs.edit().putBoolean("outils_test", v).apply()

    // ------------------------------------------------------------ « Trop dense »

    /** Note un « trop dense » et renvoie le nombre de ceux des 7 derniers jours. */
    fun noterTropDense(maintenant: LocalDateTime = LocalDateTime.now()): Int {
        val limite = maintenant.minusDays(7)
        val recents = (prefs.getString("trop_dense", null) ?: "")
            .split(';')
            .mapNotNull { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            .filter { it.isAfter(limite) } + maintenant
        prefs.edit().putString("trop_dense", recents.joinToString(";")).apply()
        return recents.size
    }

    /** Après un ajustement de la longueur, le compteur repart de zéro. */
    fun oublierTropDense() {
        prefs.edit().remove("trop_dense").apply()
    }

    // ------------------------------------------------------------ Heure de coucher

    /**
     * Une ouverture entre 18 h et 3 h compte pour la soirée : on retient la première de chaque soir.
     * Après minuit, l'heure est comptée au-delà de 24 h (0 h 30 = 24 h 30) pour garder un ordre juste.
     */
    fun noterOuverture(maintenant: LocalDateTime = LocalDateTime.now()) {
        prefs.edit().putString("derniere_ouverture", maintenant.toString()).apply()
        val heure = maintenant.hour
        if (heure in 3 until 18) return
        val soir = if (heure < 3) maintenant.toLocalDate().minusDays(1) else maintenant.toLocalDate()
        val minutes = heure * 60 + maintenant.minute + if (heure < 3) 1440 else 0
        val ouvertures = lireOuvertures().toMutableMap()
        if (soir.toString() in ouvertures) return
        ouvertures[soir.toString()] = minutes
        val gardees = ouvertures.toSortedMap().entries.toList().takeLast(21)
        prefs.edit()
            .putString("ouvertures", gardees.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    private fun lireOuvertures(): Map<String, Int> =
        (prefs.getString("ouvertures", null) ?: "")
            .split(';')
            .mapNotNull { e ->
                val (jour, minutes) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                minutes.toIntOrNull()?.let { jour to it }
            }
            .toMap()

    /** Médiane des premières ouvertures du soir, arrondie au quart d'heure ; null avant 5 soirs. */
    fun heureApprise(): Pair<Int, Int>? {
        val valeurs = lireOuvertures().values.sorted()
        if (valeurs.size < SOIRS_MINIMUM) return null
        val mediane = if (valeurs.size % 2 == 1) {
            valeurs[valeurs.size / 2]
        } else {
            (valeurs[valeurs.size / 2 - 1] + valeurs[valeurs.size / 2]) / 2
        }
        val arrondie = ((mediane + 7) / 15) * 15
        return (arrondie % 1440) to valeurs.size
    }

    fun soirsObserves(): Int = lireOuvertures().size

    fun coucher(): Coucher {
        heureForcee?.let { return Coucher(it, forcee = true, soirsAppris = null) }
        val apprise = heureApprise()
        return if (apprise != null) {
            Coucher(apprise.first, forcee = false, soirsAppris = apprise.second)
        } else {
            Coucher(COUCHER_PAR_DEFAUT, forcee = false, soirsAppris = null)
        }
    }

    /** La notif part une heure avant le coucher. */
    fun minutesNotif(): Int = (coucher().minutes - 60 + 1440) % 1440

    /**
     * Vrai une fois l'heure de coucher passée (et jusqu'au matin) : le carrefour propose alors
     * « garder pour demain », les crans s'écrivent courts et le ciel fonce plus vite.
     */
    fun apresCoucher(maintenant: LocalDateTime = LocalDateTime.now()): Boolean {
        val heure = maintenant.hour
        if (heure in 5 until 17) return false
        var actuel = heure * 60 + maintenant.minute
        if (heure < 5) actuel += 1440
        var coucher = coucher().minutes
        if (coucher < 12 * 60) coucher += 1440
        return actuel >= coucher
    }

    // ------------------------------------------------------------ Exploration

    /** La soirée en cours : après minuit, on est encore la veille. */
    private fun soir(maintenant: LocalDateTime = LocalDateTime.now()): String =
        (if (maintenant.hour < 5) maintenant.toLocalDate().minusDays(1) else maintenant.toLocalDate()).toString()

    /** Nombre de carrefours déjà vus : les trois premiers, la carte frémit pour montrer qu'elle bouge. */
    var carrefoursVus: Int
        get() = prefs.getInt("carrefours_vus", 0)
        set(v) = prefs.edit().putInt("carrefours_vus", v).apply()

    /** Le concept dont le pont s'affiche ce soir (un pont par soir au plus). */
    fun pontDuSoir(): String? {
        val brut = prefs.getString("pont_du_soir", null) ?: return null
        val (jour, id) = brut.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        return id.takeIf { jour == soir() }
    }

    fun montrerPont(conceptId: String) {
        prefs.edit().putString("pont_du_soir", "${soir()}|$conceptId").apply()
    }

    /** Garde le titre de ce qui attend après le marque-page, pour la notif de demain. */
    fun memoriserMarquePage(titre: String?) {
        prefs.edit().apply {
            if (titre.isNullOrBlank()) {
                remove("marque_page")
                remove("marque_page_jour")
            } else {
                putString("marque_page", titre)
                putString("marque_page_jour", soir())
            }
        }.apply()
    }

    // ------------------------------------------------------------ Contenu de la notif

    /** Garde le teaser de demain et la pépite du lot chargé, pour la notif du soir. */
    fun memoriserLot(lot: Lot) {
        prefs.edit()
            .putString("teaser", lot.teaserDemain)
            .putString("teaser_jour", lot.jour)
            .putString("pepite", lot.pepite.texte)
            .putString("pepite_jour", lot.jour)
            .apply()
    }

    fun marquerPepiteVue() {
        prefs.edit().putString("pepite_vue", prefs.getString("pepite_jour", null)).apply()
    }

    /**
     * Note l'envoi d'une notif. Si la précédente n'a pas été suivie d'une ouverture, elle compte
     * comme ignorée ; ignorée deux soirs de suite, la notif tend la pépite en attente.
     */
    fun noterEnvoi(maintenant: LocalDateTime = LocalDateTime.now()) {
        val precedent = prefs.getString("dernier_envoi", null)?.let { LocalDateTime.parse(it) }
        val ouverture = prefs.getString("derniere_ouverture", null)?.let { LocalDateTime.parse(it) }
        val ignoree = precedent != null && (ouverture == null || ouverture.isBefore(precedent))
        val ignorees = if (ignoree) prefs.getInt("notifs_ignorees", 0) + 1 else 0
        prefs.edit()
            .putString("dernier_envoi", maintenant.toString())
            .putInt("notifs_ignorees", ignorees)
            .apply()
    }

    fun texteNotif(aujourdhui: LocalDate = LocalDate.now()): String {
        val ignorees = prefs.getInt("notifs_ignorees", 0)
        val pepite = prefs.getString("pepite", null)
        val pepiteJour = prefs.getString("pepite_jour", null)
        val pepiteEnAttente = !pepite.isNullOrBlank() && pepiteJour != prefs.getString("pepite_vue", null)
        if (ignorees >= 2 && pepiteEnAttente) return "Une pépite t'attend : $pepite"

        // Marque-page d'hier soir : la notif repart de là.
        val marque = prefs.getString("marque_page", null)
        if (!marque.isNullOrBlank() && prefs.getString("marque_page_jour", null) == aujourdhui.minusDays(1).toString()) {
            return "Hier, tu t'es arrêté juste avant « $marque ». La suite t'attend, déjà prête."
        }

        val teaser = prefs.getString("teaser", null)
        val teaserJour = prefs.getString("teaser_jour", null)
        if (!teaser.isNullOrBlank() && teaserJour == aujourdhui.minusDays(1).toString()) return teaser
        return "Ton lot du soir est prêt : trois portes, et une bonne nuit au bout."
    }

    companion object {
        const val COUCHER_PAR_DEFAUT = 22 * 60 + 30
        const val SOIRS_MINIMUM = 5

        fun format(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
    }
}
