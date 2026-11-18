package fr.dash.chevet.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import java.util.Random

/**
 * Ciel du soir dessiné par le code : couchant chaud au début, nuit profonde à la fin.
 * [nuit] va de 0 (couchant) à 1 (nuit noire) ; les étoiles apparaissent au « bonne nuit ».
 */
@Composable
fun CielDuSoir(nuit: Float, etoiles: Boolean, modifier: Modifier = Modifier) {
    val progres by animateFloatAsState(nuit.coerceIn(0f, 1f), tween(1500), label = "nuit")
    val alphaEtoiles by animateFloatAsState(if (etoiles) 1f else 0f, tween(2500), label = "etoiles")
    val positions = remember {
        val r = Random(42)
        List(80) { Offset(r.nextFloat(), r.nextFloat() * 0.6f) to (0.6f + r.nextFloat() * 1.4f) }
    }

    Canvas(modifier.fillMaxSize()) {
        val haut = lerp(Color(0xFF5E3F72), Color(0xFF070A1C), progres)
        val milieu = lerp(Color(0xFFD9805A), Color(0xFF141A3A), progres)
        val bas = lerp(Color(0xFFF2C27B), Color(0xFF232646), progres)
        drawRect(Brush.verticalGradient(listOf(haut, milieu, bas), endY = size.height * 0.78f))

        if (alphaEtoiles > 0f) {
            positions.forEach { (p, rayon) ->
                drawCircle(
                    color = Color.White.copy(alpha = alphaEtoiles * 0.8f),
                    radius = rayon.dp.toPx(),
                    center = Offset(p.x * size.width, p.y * size.height),
                )
            }
        }

        colline(base = 0.80f, amplitude = 0.07f, decalage = 0, couleur = lerp(Color(0xFF6B4A6E), Color(0xFF151832), progres))
        colline(base = 0.86f, amplitude = 0.05f, decalage = 1, couleur = lerp(Color(0xFF4A3352), Color(0xFF0D1026), progres))
        colline(base = 0.93f, amplitude = 0.04f, decalage = 0, couleur = lerp(Color(0xFF2E2238), Color(0xFF070918), progres))
    }
}

/** Une ligne de collines douces, à la gaumaise. */
private fun DrawScope.colline(base: Float, amplitude: Float, decalage: Int, couleur: Color) {
    val largeur = size.width
    val hauteur = size.height
    val pas = largeur / 3f
    val chemin = Path().apply {
        moveTo(0f, hauteur)
        lineTo(0f, hauteur * base)
        var x = 0f
        var i = decalage
        while (x < largeur) {
            val sommet = if (i % 2 == 0) amplitude else amplitude * 0.45f
            quadraticBezierTo(x + pas / 2f, hauteur * (base - sommet), x + pas, hauteur * base)
            x += pas
            i += 1
        }
        lineTo(largeur, hauteur)
        close()
    }
    drawPath(chemin, couleur)
}
