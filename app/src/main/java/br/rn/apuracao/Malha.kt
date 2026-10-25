package br.rn.apuracao

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Contornos de regiões (malha do IBGE, qualidade mínima) guardados em `assets/` como
 * `{"escala": N, "regioes": {código: {"n": nome, "p": [[lon·N, lat·N, ...], ...]}}}`.
 * Projeta numa caixa de tela mantendo a proporção e identifica a região tocada.
 */
class Malha(ctx: Context, arquivo: String, private val rotulos: Map<String, Pair<Float, Float>> = emptyMap()) {

    val nomes: Map<String, String>
    private val formas: Map<String, List<FloatArray>>
    private val minX: Float
    private val maxX: Float
    private val minY: Float
    private val maxY: Float
    private val cosLat: Float

    init {
        val json = JSONObject(ctx.assets.open(arquivo).bufferedReader().use { it.readText() })
        val escala = json.getDouble("escala").toFloat()
        val regioes = json.getJSONObject("regioes")
        val n = mutableMapOf<String, String>()
        val f = mutableMapOf<String, List<FloatArray>>()
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (cod in regioes.keys()) {
            val r = regioes.getJSONObject(cod)
            n[cod] = r.getString("n")
            val aneis = r.getJSONArray("p")
            f[cod] = (0 until aneis.length()).map { i ->
                val a = aneis.getJSONArray(i)
                FloatArray(a.length()) { k -> a.getInt(k) / escala }.also { pts ->
                    for (k in pts.indices step 2) {
                        x0 = min(x0, pts[k]); x1 = maxOf(x1, pts[k])
                        y0 = min(y0, pts[k + 1]); y1 = maxOf(y1, pts[k + 1])
                    }
                }
            }
        }
        // as maiores primeiro: regiões pequenas (ex.: DF dentro de GO) ficam por cima
        formas = f.toList().sortedByDescending { (_, aneis) -> area(aneis) }.toMap()
        nomes = n
        minX = x0; maxX = x1; minY = y0; maxY = y1
        cosLat = cos(Math.toRadians(((minY + maxY) / 2).toDouble())).toFloat()
    }

    private fun area(aneis: List<FloatArray>): Float = aneis.sumOf { pts ->
        var xa = Float.MAX_VALUE; var xb = -Float.MAX_VALUE; var ya = Float.MAX_VALUE; var yb = -Float.MAX_VALUE
        for (k in pts.indices step 2) {
            xa = min(xa, pts[k]); xb = maxOf(xb, pts[k]); ya = min(ya, pts[k + 1]); yb = maxOf(yb, pts[k + 1])
        }
        ((xb - xa) * (yb - ya)).toDouble()
    }.toFloat()

    val codigos: Set<String> get() = formas.keys

    /** Largura/altura do desenho projetado, para dimensionar a caixa. */
    val proporcao: Float get() = (maxX - minX) * cosLat / (maxY - minY)

    private var cacheCaixa: RectF? = null
    private var cachePaths: Map<String, Path> = emptyMap()
    private var cacheRegioes: Map<String, Region> = emptyMap()
    private var cacheCentros: Map<String, PointF> = emptyMap()
    private var escala = 1f
    private var offX = 0f
    private var offY = 0f

    private fun ajustar(caixa: RectF) {
        if (cacheCaixa == caixa) return
        val larg = (maxX - minX) * cosLat
        val alt = maxY - minY
        escala = min(caixa.width() / larg, caixa.height() / alt)
        offX = caixa.left + (caixa.width() - larg * escala) / 2
        offY = caixa.top + (caixa.height() - alt * escala) / 2
        cachePaths = formas.mapValues { (_, aneis) ->
            Path().apply {
                for (pts in aneis) {
                    for (k in pts.indices step 2) {
                        val x = px(pts[k]); val y = py(pts[k + 1])
                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
            }
        }
        val clip = Region(Rect(caixa.left.toInt() - 2, caixa.top.toInt() - 2, caixa.right.toInt() + 2, caixa.bottom.toInt() + 2))
        cacheRegioes = cachePaths.mapValues { (_, p) -> Region().apply { setPath(p, clip) } }
        cacheCentros = cachePaths.mapValues { (cod, p) ->
            rotulos[cod]?.let { (lon, lat) -> PointF(px(lon), py(lat)) }
                ?: RectF().also { p.computeBounds(it, true) }.let { PointF(it.centerX(), it.centerY()) }
        }
        cacheCaixa = RectF(caixa)
    }

    private fun px(lon: Float) = offX + (lon - minX) * cosLat * escala
    private fun py(lat: Float) = offY + (maxY - lat) * escala

    fun caminhos(caixa: RectF): Map<String, Path> { ajustar(caixa); return cachePaths }

    fun centro(cod: String, caixa: RectF): PointF? { ajustar(caixa); return cacheCentros[cod] }

    /**
     * Região sob o toque; se nenhuma contém o ponto (regiões muito pequenas), a de centro mais
     * próximo dentro de [tolerancia] pixels.
     */
    fun regiaoEm(x: Float, y: Float, caixa: RectF, tolerancia: Float): String? {
        ajustar(caixa)
        // percorre das menores para as maiores, para acertar o DF e municípios encravados
        cacheRegioes.entries.reversed().firstOrNull { it.value.contains(x.toInt(), y.toInt()) }?.let { return it.key }
        return cacheCentros.minByOrNull { (_, p) -> hypot(p.x - x, p.y - y) }
            ?.takeIf { (_, p) -> hypot(p.x - x, p.y - y) <= tolerancia }?.key
    }
}

/** Malhas usadas pelo app, carregadas uma vez. */
object Malhas {
    /** Posição do rótulo (lon, lat) das UFs onde o centro da caixa não fica bom. */
    private val ROTULOS_UF = mapOf(
        "go" to (-50.6f to -16.4f), "df" to (-47.8f to -15.8f), "rn" to (-36.7f to -5.8f),
        "pb" to (-36.8f to -7.1f), "pe" to (-38.0f to -8.4f), "al" to (-36.6f to -9.6f),
        "se" to (-37.4f to -10.6f), "es" to (-40.6f to -19.6f), "rj" to (-42.7f to -22.3f),
        "sc" to (-50.0f to -27.6f), "ma" to (-45.6f to -4.6f), "pi" to (-42.6f to -8.4f),
        "ce" to (-39.5f to -4.7f), "ba" to (-41.7f to -12.3f), "mg" to (-44.7f to -18.4f),
        "pa" to (-52.5f to -4.0f), "am" to (-64.5f to -4.3f), "to" to (-48.3f to -10.2f),
        "mt" to (-55.8f to -12.8f), "ms" to (-54.6f to -20.6f), "sp" to (-48.6f to -22.3f),
        "pr" to (-51.9f to -24.3f), "rs" to (-53.3f to -29.6f), "ro" to (-62.8f to -10.9f),
        "ac" to (-70.0f to -9.4f), "rr" to (-61.4f to 2.0f), "ap" to (-51.8f to 1.4f),
    )

    /** UFs pequenas demais para mostrar o percentual dentro do mapa. */
    val UFS_PEQUENAS = setOf("df", "se", "al", "pb", "rn", "pe", "es", "rj")

    private var brasil: Malha? = null
    private var rn: Malha? = null

    fun brasil(ctx: Context): Malha = brasil ?: Malha(ctx.applicationContext, "malha_br.json", ROTULOS_UF).also { brasil = it }
    fun rn(ctx: Context): Malha = rn ?: Malha(ctx.applicationContext, "malha_rn.json").also { rn = it }

    /** Malha do mapa de cada cargo (Presidente: UFs; Governador: municípios do RN). */
    fun doCargo(ctx: Context, cargo: Cargo): Malha? = when (cargo) {
        Cargo.PRESIDENTE -> brasil(ctx)
        Cargo.GOVERNADOR -> rn(ctx)
        else -> null
    }

    fun nome(ctx: Context, cargo: Cargo, cod: String): String =
        if (cod == "zz") "Exterior" else doCargo(ctx, cargo)?.nomes?.get(cod) ?: cod.uppercase()
}
