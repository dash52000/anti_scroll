package fr.dash.chevet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import fr.dash.chevet.data.ConceptVu
import fr.dash.chevet.data.Voisin
import fr.dash.chevet.model.Sens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------- Thèmes

/** Les 4 thèmes, dans l'ordre des branches de l'arbre (de gauche à droite). */
internal val THEMES = listOf("Sciences & techno", "Histoire & sociétés", "Stats, maths, data", "Philo & idées")

internal fun couleurTheme(theme: String): Color = when {
    theme.startsWith("Sciences") -> Color(0xFF7FC8B8)
    theme.startsWith("Histoire") -> Color(0xFFF2B36B)
    theme.startsWith("Stats") -> Color(0xFFA9A2F0)
    theme.startsWith("Philo") -> Color(0xFFE79AB0)
    else -> EncreDouce
}

internal fun nomCourt(theme: String): String = theme.substringBefore(" &").substringBefore(",")

private fun indexTheme(theme: String): Int =
    THEMES.indexOfFirst { theme.startsWith(it.substringBefore(" ")) }.takeIf { it >= 0 } ?: 1

// ---------------------------------------------------------------- Arbre qui pousse

internal enum class Saison { Printemps, Ete, Automne, Hiver }

internal fun saison(jour: LocalDate = LocalDate.now()): Saison = when (jour.monthValue) {
    3, 4, 5 -> Saison.Printemps
    6, 7, 8 -> Saison.Ete
    9, 10, 11 -> Saison.Automne
    else -> Saison.Hiver
}

private class Rameau(val p0: Offset, val p1: Offset, val p2: Offset, val epaisseur: Float) {
    fun point(t: Float): Offset {
        val u = 1 - t
        return Offset(
            u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x,
            u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y,
        )
    }

    /** Direction de la tangente en t, en radians. */
    fun angle(t: Float): Float {
        val dx = 2 * (1 - t) * (p1.x - p0.x) + 2 * t * (p2.x - p1.x)
        val dy = 2 * (1 - t) * (p1.y - p0.y) + 2 * t * (p2.y - p1.y)
        return kotlin.math.atan2(dy, dx)
    }
}

private class Feuille(
    val id: String,
    val attache: Offset,
    val centre: Offset,
    val angle: Float,
    val teinte: Int,
    val fleur: Boolean,
    val fruit: Boolean,
    val aimee: Boolean,
)

/** Un cran (« plus loin », « à côté ») dessiné en racine sous l'arbre : de son départ jusqu'à lui. */
private class Racine(
    val id: String,
    val de: Offset,
    val vers: Offset,
    val theme: String,
    val plusLoin: Boolean,
)

private class DessinArbre(
    val tronc: Rameau,
    val rameaux: List<Rameau>,
    val feuilles: List<Feuille>,
    val etiquettes: List<Pair<String, Offset>>,
    /** Hauteur du sol, en part de la hauteur : plus haut quand des racines poussent dessous. */
    val sol: Float,
    val racines: List<Racine>,
)

/**
 * Dispose l'arbre : un tronc, une branche par thème qui s'allonge avec ses concepts, des rameaux
 * quand une branche se charge, une feuille par concept. Tout est déterministe : une feuille garde
 * sa place d'un soir à l'autre.
 */
private fun disposerArbre(tous: List<ConceptVu>, taille: Size, unite: Float, aime: (String) -> Boolean): DessinArbre {
    val l = taille.width
    val h = taille.height
    // Les concepts des portes sont des feuilles ; les crans poussent en racines sous le sol.
    val concepts = tous.filter { !it.concept.estCran }
    val avecRacines = tous.any { it.concept.estCran }
    val sol = if (avecRacines) 0.80f else 0.97f
    val pied = Offset(l / 2, h * sol)
    val cime = Offset(l / 2, h * (sol - 0.41f))
    val epaisseurTronc = unite * (5f + min(9f, concepts.size / 6f))
    val tronc = Rameau(pied, Offset(l / 2 + unite * 4, (pied.y + cime.y) / 2), cime, epaisseurTronc)

    val parTheme = concepts.groupBy { indexTheme(it.concept.theme) }
    val angles = listOf(-64f, -24f, 22f, 60f)
    val departs = listOf(0.80f, 0.97f, 1f, 0.84f)
    val rameaux = mutableListOf<Rameau>()
    val feuilles = mutableListOf<Feuille>()
    val etiquettes = mutableListOf<Pair<String, Offset>>()

    THEMES.forEachIndexed { i, theme ->
        val liste = parTheme[i].orEmpty().sortedBy { it.vuLe }
        val n = liste.size
        val angle = (angles[i] - 90f) * (PI / 180f).toFloat()
        val depart = tronc.point(departs[i])
        var longueur = h * (0.16f + 0.24f * min(1f, sqrt(n / 16f)))
        // La branche reste dans le cadre.
        while (longueur > unite * 20) {
            val bout = Offset(depart.x + cos(angle) * longueur, depart.y + sin(angle) * longueur)
            if (bout.x in l * 0.05f..l * 0.95f && bout.y > h * 0.06f) break
            longueur *= 0.92f
        }
        val bout = Offset(depart.x + cos(angle) * longueur, depart.y + sin(angle) * longueur)
        val milieu = Offset((depart.x + bout.x) / 2, (depart.y + bout.y) / 2 - longueur * 0.12f)
        val branche = Rameau(depart, milieu, bout, epaisseurTronc * 0.55f)
        rameaux += branche
        etiquettes += nomCourt(theme) to bout

        // Un rameau de plus tous les 6 concepts, 4 au plus.
        val nbRameaux = min(4, max(0, ceil(n / 6f).toInt() - 1))
        val supports = mutableListOf(branche)
        for (j in 0 until nbRameaux) {
            val t = 0.35f + 0.5f * (j + 1) / (nbRameaux + 1)
            val attache = branche.point(t)
            val cote = if (j % 2 == 0) -1f else 1f
            val a = branche.angle(t) + cote * 0.62f
            val lr = longueur * 0.45f * (1 - 0.3f * t)
            val fin = Offset(attache.x + cos(a) * lr, attache.y + sin(a) * lr)
            val ctrl = Offset((attache.x + fin.x) / 2, (attache.y + fin.y) / 2 - lr * 0.1f)
            val rameau = Rameau(attache, ctrl, fin, branche.epaisseur * 0.5f)
            rameaux += rameau
            supports += rameau
        }

        liste.forEachIndexed { k, vu ->
            val r = Random(vu.concept.id.hashCode().toLong())
            val support = supports[k % supports.size]
            val t = 0.3f + 0.7f * r.nextFloat()
            val attache = support.point(t)
            val cote = if (r.nextBoolean()) 1f else -1f
            val a = support.angle(t) + cote * (0.7f + 0.5f * r.nextFloat())
            val distance = unite * (7f + 6f * r.nextFloat())
            feuilles += Feuille(
                id = vu.concept.id,
                attache = attache,
                centre = Offset(attache.x + cos(a) * distance, attache.y + sin(a) * distance),
                angle = a,
                teinte = r.nextInt(4),
                fleur = r.nextInt(3) == 0,
                fruit = vu.maitrise,
                aimee = aime(vu.concept.id),
            )
        }
    }
    return DessinArbre(tronc, rameaux, feuilles, etiquettes, sol, disposerRacines(tous, pied, taille, unite))
}

/**
 * Les racines : chaque cran part de son concept de départ. Un cran de premier niveau part du pied
 * de l'arbre, décalé selon le thème ; « plus loin » descend, « à côté » s'écarte sur le côté.
 */
private fun disposerRacines(tous: List<ConceptVu>, pied: Offset, taille: Size, unite: Float): List<Racine> {
    val crans = tous.filter { it.concept.estCran }
    if (crans.isEmpty()) return emptyList()
    val parId = tous.associateBy { it.concept.id }
    val bas = taille.height * 0.985f
    val pas = ((bas - pied.y) / 4f).coerceAtLeast(unite * 6)
    val positions = mutableMapOf<String, Offset>()
    val racines = mutableListOf<Racine>()
    val enCours = mutableSetOf<String>()

    fun position(vu: ConceptVu): Offset {
        val id = vu.concept.id
        positions[id]?.let { return it }
        val r = Random(id.hashCode().toLong())
        val parent = vu.concept.depuis?.let { parId[it] }
        val depart = if (parent != null && parent.concept.estCran && enCours.add(id)) {
            position(parent)
        } else {
            val t = indexTheme(parent?.concept?.theme ?: vu.concept.theme)
            Offset(pied.x + (t - 1.5f) * unite * 16 + (r.nextFloat() - 0.5f) * unite * 8, pied.y)
        }
        val brut = if (vu.concept.sens == Sens.PLUS_LOIN) {
            Offset(depart.x + (r.nextFloat() - 0.5f) * unite * 10, depart.y + pas)
        } else {
            val cote = if (r.nextBoolean()) 1f else -1f
            Offset(depart.x + cote * unite * (16f + 8f * r.nextFloat()), depart.y + pas * 0.45f)
        }
        val p = Offset(brut.x.coerceIn(unite * 6, taille.width - unite * 6), brut.y.coerceAtMost(bas))
        positions[id] = p
        racines += Racine(id, depart, p, vu.concept.theme, vu.concept.sens == Sens.PLUS_LOIN)
        return p
    }
    crans.forEach { position(it) }
    return racines
}

private fun couleursFeuilles(s: Saison): List<Color> = when (s) {
    Saison.Printemps -> listOf(Color(0xFF9BD08F), Color(0xFFB5DDA0), Color(0xFF86C27D), Color(0xFFA8D49A))
    Saison.Ete -> listOf(Color(0xFF5FA35A), Color(0xFF7DBB6B), Color(0xFF4E8F4A), Color(0xFF6AAE5E))
    Saison.Automne -> listOf(Color(0xFFD9822B), Color(0xFFC0582E), Color(0xFFE5B04A), Color(0xFFA9442A))
    Saison.Hiver -> listOf(Color(0xFFCFE3EE), Color(0xFFE8F1F6), Color(0xFFB9D4E4), Color(0xFFDDEBF3))
}

/**
 * L'arbre du terrier, dessiné par le code : une branche par thème, une feuille par concept,
 * le feuillage de la saison, une mirabelle par concept maîtrisé. Les [nouvelles] feuilles
 * poussent sous les yeux. Un tap sur une feuille appelle [onFeuille].
 */
@Composable
internal fun Arbre(
    concepts: List<ConceptVu>,
    aime: (String) -> Boolean,
    modifier: Modifier = Modifier,
    nouvelles: Set<String> = emptySet(),
    etiquettes: Boolean = true,
    onFeuille: ((String) -> Unit)? = null,
) {
    val mesure = rememberTextMeasurer()
    val pousse = remember { Animatable(if (nouvelles.isEmpty()) 1f else 0f) }
    LaunchedEffect(nouvelles) {
        if (nouvelles.isNotEmpty()) {
            pousse.snapTo(0f)
            pousse.animateTo(1f, tween(2600, delayMillis = 400, easing = FastOutSlowInEasing))
        }
    }
    val s = remember { saison() }
    val ordreNouvelles = remember(nouvelles) { nouvelles.toList() }
    val dernier = remember { Memo<DessinArbre?>(null) }

    val tap = if (onFeuille != null) {
        Modifier.pointerInput(concepts) {
            detectTapGestures { p ->
                val d = dernier.valeur ?: return@detectTapGestures
                val points = d.feuilles.map { it.id to it.centre } + d.racines.map { it.id to it.vers }
                val proche = points.minByOrNull { (it.second - p).getDistance() }
                if (proche != null && (proche.second - p).getDistance() < 22 * density) onFeuille(proche.first)
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier.then(tap)) {
        val unite = density
        val dessin = disposerArbre(concepts, size, unite, aime)
        dernier.valeur = dessin
        val ecorce = Color(0xFF3B2A26)

        // Sous le sol, la terre où poussent les racines (les crans).
        if (dessin.racines.isNotEmpty()) {
            drawRect(
                Color(0xFF120E1E).copy(alpha = 0.55f),
                topLeft = Offset(0f, size.height * dessin.sol),
                size = Size(size.width, size.height * (1f - dessin.sol)),
            )
        }

        // Butte au pied de l'arbre.
        drawOval(
            Color(0xFF1B1530).copy(alpha = 0.85f),
            topLeft = Offset(size.width * 0.18f, size.height * (dessin.sol - 0.04f)),
            size = Size(size.width * 0.64f, size.height * 0.12f),
        )

        // Les racines : puits vers le bas (« plus loin »), galeries sur les côtés (« à côté »).
        dessin.racines.forEach { r ->
            val rang = ordreNouvelles.indexOf(r.id)
            val echelle = if (rang < 0) 1f else (pousse.value * (ordreNouvelles.size + 1) - rang).coerceIn(0f, 1f)
            if (echelle <= 0f) return@forEach
            val bout = Offset(r.de.x + (r.vers.x - r.de.x) * echelle, r.de.y + (r.vers.y - r.de.y) * echelle)
            val chemin = Path().apply {
                moveTo(r.de.x, r.de.y)
                quadraticTo(if (r.plusLoin) r.de.x else bout.x, if (r.plusLoin) (r.de.y + bout.y) / 2 else r.de.y, bout.x, bout.y)
            }
            drawPath(chemin, Color(0xFF6B4C40).copy(alpha = 0.9f), style = Stroke(width = unite * 1.6f, cap = StrokeCap.Round))
            drawCircle(couleurTheme(r.theme), radius = unite * 3.4f * echelle, center = bout)
            if (aime(r.id)) drawCircle(Ambre, radius = unite * 4.8f * echelle, center = bout, style = Stroke(unite * 1.1f))
        }

        rameau(dessin.tronc, ecorce)
        dessin.rameaux.forEach { rameau(it, ecorce) }
        if (s == Saison.Hiver) {
            // Un liseré de neige sur le dessus des branches.
            dessin.rameaux.forEach { r ->
                val decale = Rameau(
                    r.p0 + Offset(0f, -r.epaisseur * 0.45f),
                    r.p1 + Offset(0f, -r.epaisseur * 0.45f),
                    r.p2 + Offset(0f, -r.epaisseur * 0.45f),
                    r.epaisseur * 0.35f,
                )
                rameau(decale, Color(0xFFEAF2F7).copy(alpha = 0.8f))
            }
        }

        val teintes = couleursFeuilles(s)
        dessin.feuilles.forEach { f ->
            val rangNouvelle = ordreNouvelles.indexOf(f.id)
            val echelle = if (rangNouvelle < 0) {
                1f
            } else {
                (pousse.value * (ordreNouvelles.size + 1) - rangNouvelle).coerceIn(0f, 1f)
            }
            if (echelle <= 0f) return@forEach
            val centre = Offset(
                f.attache.x + (f.centre.x - f.attache.x) * echelle,
                f.attache.y + (f.centre.y - f.attache.y) * echelle,
            )
            drawLine(ecorce, f.attache, centre, strokeWidth = unite * 1.2f, cap = StrokeCap.Round)

            if (rangNouvelle >= 0 && echelle < 1f) {
                drawCircle(Ambre.copy(alpha = 0.35f * (1 - echelle)), radius = unite * 16 * echelle, center = centre)
            }

            if (s == Saison.Printemps && f.fleur) {
                fleur(centre, unite * echelle)
            } else {
                val lf = unite * (if (f.aimee) 13f else 11f) * echelle
                val hf = lf * 0.5f
                rotate(Math.toDegrees(f.angle.toDouble()).toFloat(), pivot = centre) {
                    drawOval(teintes[f.teinte], topLeft = Offset(centre.x - lf / 2, centre.y - hf / 2), size = Size(lf, hf))
                    if (f.aimee) {
                        drawOval(
                            Ambre,
                            topLeft = Offset(centre.x - lf / 2, centre.y - hf / 2),
                            size = Size(lf, hf),
                            style = Stroke(width = unite * 1.2f),
                        )
                    }
                }
            }
            if (f.fruit) {
                val fruit = centre + Offset(0f, unite * 7 * echelle)
                drawLine(ecorce, centre, fruit, strokeWidth = unite)
                drawCircle(Color(0xFFF2C14E), radius = unite * 4.2f * echelle, center = fruit)
                drawCircle(Color(0xFFFFE7A3), radius = unite * 1.3f * echelle, center = fruit + Offset(-unite * 1.3f, -unite * 1.3f))
            }
        }

        if (etiquettes) {
            dessin.etiquettes.forEachIndexed { i, (nom, bout) ->
                val texte = mesure.measure(nom, TextStyle(color = couleurTheme(THEMES[i]).copy(alpha = 0.8f), fontSize = 10.sp))
                val x = (bout.x - texte.size.width / 2f).coerceIn(0f, size.width - texte.size.width)
                val y = (bout.y - texte.size.height - unite * 14).coerceAtLeast(0f)
                drawText(texte, topLeft = Offset(x, y))
            }
        }
    }
}

/** Mémoire simple, hors état Compose : ce qu'on dessine sert au tap sans redéclencher le dessin. */
private class Memo<T>(var valeur: T)

private fun DrawScope.rameau(r: Rameau, couleur: Color) {
    val chemin = Path().apply {
        moveTo(r.p0.x, r.p0.y)
        quadraticTo(r.p1.x, r.p1.y, r.p2.x, r.p2.y)
    }
    drawPath(chemin, couleur, style = Stroke(width = r.epaisseur, cap = StrokeCap.Round))
}

private fun DrawScope.fleur(centre: Offset, unite: Float) {
    for (k in 0 until 5) {
        val a = k * 2 * PI.toFloat() / 5
        drawCircle(Color(0xFFF4B6C8), radius = unite * 2.6f, center = centre + Offset(cos(a) * unite * 2.8f, sin(a) * unite * 2.8f))
    }
    drawCircle(Color(0xFFF2D06B), radius = unite * 1.6f, center = centre)
}

// ---------------------------------------------------------------- Graphe du terrier

/** Un lien du graphe ; [pont] : un fil doré vers un concept vu plus d'une semaine plus tôt. */
internal data class Lien(val a: String, val b: String, val pont: Boolean = false)

/**
 * Les liens du graphe : chaque concept d'une porte est relié au précédent de sa porte, chaque cran
 * à son concept de départ, et chaque pont au concept qu'il rappelle.
 */
internal fun liensDuTerrier(concepts: List<ConceptVu>): List<Lien> {
    val liens = mutableListOf<Lien>()
    val ids = concepts.map { it.concept.id }.toSet()
    // Les concepts d'avant le menu n'ont pas de porte : ceux d'un même soir formaient un seul tunnel.
    val groupes = concepts.filter { !it.concept.estCran }.groupBy { it.porte ?: "soir:${it.vuLe}" }
    groupes.values.forEach { groupe ->
        val ordonne = if (groupe.first().porte != null) groupe.sortedBy { it.rang } else groupe
        ordonne.zipWithNext { a, b -> liens += Lien(a.concept.id, b.concept.id) }
    }
    concepts.filter { it.concept.estCran }.forEach { vu ->
        val depart = vu.concept.depuis
        if (depart != null && depart in ids) liens += Lien(depart, vu.concept.id)
    }
    concepts.forEach { vu ->
        val pont = vu.concept.pont ?: return@forEach
        if (pont.avecId in ids && pont.avecId != vu.concept.id) liens += Lien(pont.avecId, vu.concept.id, pont = true)
    }
    return liens
}

/**
 * Disposition en toile libre (Fruchterman-Reingold) : les concepts liés s'attirent, tous se
 * repoussent, et une légère gravité vers le coin de leur thème regroupe les thèmes.
 * Coordonnées autour de 0, dans un carré de côté 2 environ.
 */
private fun disposerGraphe(concepts: List<ConceptVu>, liens: List<Lien>): Map<String, Offset> {
    val n = concepts.size
    if (n == 0) return emptyMap()
    val index = concepts.withIndex().associate { (i, c) -> c.concept.id to i }
    val ancres = listOf(Offset(-0.6f, -0.6f), Offset(0.6f, -0.6f), Offset(-0.6f, 0.6f), Offset(0.6f, 0.6f))
    val r = Random(7)
    val x = FloatArray(n)
    val y = FloatArray(n)
    val ax = FloatArray(n)
    val ay = FloatArray(n)
    concepts.forEachIndexed { i, c ->
        val a = ancres[indexTheme(c.concept.theme)]
        ax[i] = a.x
        ay[i] = a.y
        x[i] = a.x + (r.nextFloat() - 0.5f) * 0.6f
        y[i] = a.y + (r.nextFloat() - 0.5f) * 0.6f
    }
    val aretes = liens.mapNotNull { l -> index[l.a]?.let { i -> index[l.b]?.let { j -> i to j } } }
    val k = sqrt(4f / n).coerceAtMost(0.6f)
    var temperature = 0.25f
    val iterations = if (n > 200) 160 else 300
    val dx = FloatArray(n)
    val dy = FloatArray(n)
    repeat(iterations) {
        dx.fill(0f)
        dy.fill(0f)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val ex = x[i] - x[j]
                val ey = y[i] - y[j]
                val d = max(0.01f, sqrt(ex * ex + ey * ey))
                val f = k * k / d
                dx[i] += ex / d * f
                dy[i] += ey / d * f
                dx[j] -= ex / d * f
                dy[j] -= ey / d * f
            }
        }
        for ((i, j) in aretes) {
            val ex = x[i] - x[j]
            val ey = y[i] - y[j]
            val d = max(0.01f, sqrt(ex * ex + ey * ey))
            val f = d * d / k
            dx[i] -= ex / d * f
            dy[i] -= ey / d * f
            dx[j] += ex / d * f
            dy[j] += ey / d * f
        }
        for (i in 0 until n) {
            dx[i] += (ax[i] - x[i]) * 0.6f * k
            dy[i] += (ay[i] - y[i]) * 0.6f * k
            val d = max(0.0001f, sqrt(dx[i] * dx[i] + dy[i] * dy[i]))
            val pas = min(d, temperature)
            x[i] += dx[i] / d * pas
            y[i] += dy[i] / d * pas
        }
        temperature = max(0.004f, temperature * 0.985f)
    }
    return concepts.withIndex().associate { (i, c) -> c.concept.id to Offset(x[i], y[i]) }
}

/**
 * Le graphe du terrier : une toile libre qu'on déplace et qu'on zoome. Un tap sur un concept le
 * choisit ; ses [voisins], s'ils sont chargés, s'affichent autour de lui en pointillé.
 */
@Composable
internal fun Graphe(
    concepts: List<ConceptVu>,
    aime: (String) -> Boolean,
    choisi: String?,
    voisins: List<Voisin>,
    onChoix: (ConceptVu?) -> Unit,
    onVoisin: (Voisin) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mesure = rememberTextMeasurer()
    val liens = remember(concepts) { liensDuTerrier(concepts) }
    var positions by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    LaunchedEffect(concepts) {
        positions = withContext(Dispatchers.Default) { disposerGraphe(concepts, liens) }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var decalage by remember { mutableStateOf(Offset.Zero) }
    val parId = remember(concepts) { concepts.associateBy { it.concept.id } }

    // Passage des coordonnées du graphe à l'écran.
    fun ecran(p: Offset, taille: Size): Offset {
        val etendue = positions.values.maxOfOrNull { max(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }?.coerceAtLeast(0.3f) ?: 1f
        val echelle = min(taille.width, taille.height) * 0.42f / etendue
        return Offset(taille.width / 2, taille.height / 2) + (p * echelle) * zoom + decalage
    }

    fun positionsVoisins(centre: Offset, densite: Float): List<Offset> =
        voisins.indices.map { i ->
            val a = -PI.toFloat() / 2 + i * 2 * PI.toFloat() / max(1, voisins.size)
            centre + Offset(cos(a), sin(a)) * (densite * 78)
        }

    val tailleCanvas = remember { Memo(Size.Zero) }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, facteur, _ ->
                    zoom = (zoom * facteur).coerceIn(0.4f, 4f)
                    decalage += pan
                }
            }
            .pointerInput(concepts, voisins, choisi) {
                detectTapGestures { p ->
                    val c = choisi?.let { positions[it] }?.let { ecran(it, tailleCanvas.valeur) }
                    if (c != null) {
                        val v = positionsVoisins(c, density).withIndex().minByOrNull { (it.value - p).getDistance() }
                        if (v != null && (v.value - p).getDistance() < density * 26) {
                            onVoisin(voisins[v.index])
                            return@detectTapGestures
                        }
                    }
                    val proche = positions.entries.minByOrNull { (ecran(it.value, tailleCanvas.valeur) - p).getDistance() }
                    val touche = proche != null && (ecran(proche.value, tailleCanvas.valeur) - p).getDistance() < density * 24
                    onChoix(if (touche) parId[proche!!.key] else null)
                }
            },
    ) {
        tailleCanvas.valeur = size
        if (positions.isEmpty()) return@Canvas
        val unite = density

        // Les liens ordinaires d'abord, les ponts dorés par-dessus.
        liens.sortedBy { it.pont }.forEach { l ->
            val pa = positions[l.a] ?: return@forEach
            val pb = positions[l.b] ?: return@forEach
            if (l.pont) {
                drawLine(Dore.copy(alpha = 0.85f), ecran(pa, size), ecran(pb, size), strokeWidth = unite * 2.2f, cap = StrokeCap.Round)
            } else {
                drawLine(Encre.copy(alpha = 0.22f), ecran(pa, size), ecran(pb, size), strokeWidth = unite * 1.4f)
            }
        }

        val etiquettesVisibles = concepts.size <= 25 || zoom >= 1.6f
        concepts.forEach { vu ->
            val p = ecran(positions[vu.concept.id] ?: return@forEach, size)
            val aimee = aime(vu.concept.id)
            val rayon = unite * (if (aimee) 8.5f else 7f)
            val estChoisi = vu.concept.id == choisi
            if (estChoisi) drawCircle(Encre.copy(alpha = 0.25f), radius = rayon * 2f, center = p)
            drawCircle(couleurTheme(vu.concept.theme), radius = rayon, center = p)
            if (aimee) drawCircle(Ambre, radius = rayon + unite * 2, center = p, style = Stroke(unite * 1.5f))
            if (vu.maitrise) drawCircle(Color(0xFFF2C14E), radius = unite * 2.6f, center = p)
            if (etiquettesVisibles || estChoisi) {
                val texte = mesure.measure(
                    vu.concept.titre,
                    TextStyle(color = Encre.copy(alpha = if (estChoisi) 1f else 0.75f), fontSize = 11.sp),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = (unite * 140).toInt()),
                )
                drawText(texte, topLeft = Offset(p.x - texte.size.width / 2f, p.y + rayon + unite * 3))
            }
        }

        // Les voisins du concept choisi, en pointillé.
        val centre = choisi?.let { positions[it] }?.let { ecran(it, size) }
        if (centre != null && voisins.isNotEmpty()) {
            val pointille = PathEffect.dashPathEffect(floatArrayOf(unite * 4, unite * 4))
            positionsVoisins(centre, unite).forEachIndexed { i, p ->
                drawLine(Encre.copy(alpha = 0.45f), centre, p, strokeWidth = unite * 1.2f, pathEffect = pointille)
                drawCircle(Color(0xFF1A1630), radius = unite * 6.5f, center = p)
                drawCircle(Encre.copy(alpha = 0.7f), radius = unite * 6.5f, center = p, style = Stroke(unite * 1.2f, pathEffect = pointille))
                val texte = mesure.measure(
                    voisins[i].titre,
                    TextStyle(color = EncreDouce, fontSize = 10.sp),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = (unite * 110).toInt()),
                )
                drawText(texte, topLeft = Offset(p.x - texte.size.width / 2f, p.y + unite * 9))
            }
        }
    }
}
