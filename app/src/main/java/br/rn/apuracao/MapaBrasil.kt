package br.rn.apuracao

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min

/**
 * Contornos das UFs (malha do IBGE, qualidade mínima) guardados em `assets/ufs.json` como
 * longitude/latitude × 100. Projeta numa caixa de tela mantendo a proporção.
 */
class MapaBrasil(ctx: Context) {

    companion object {
        val NOMES = mapOf(
            "ac" to "Acre", "al" to "Alagoas", "am" to "Amazonas", "ap" to "Amapá", "ba" to "Bahia",
            "ce" to "Ceará", "df" to "Distrito Federal", "es" to "Espírito Santo", "go" to "Goiás",
            "ma" to "Maranhão", "mg" to "Minas Gerais", "ms" to "Mato Grosso do Sul", "mt" to "Mato Grosso",
            "pa" to "Pará", "pb" to "Paraíba", "pe" to "Pernambuco", "pi" to "Piauí", "pr" to "Paraná",
            "rj" to "Rio de Janeiro", "rn" to "Rio Grande do Norte", "ro" to "Rondônia", "rr" to "Roraima",
            "rs" to "Rio Grande do Sul", "sc" to "Santa Catarina", "se" to "Sergipe", "sp" to "São Paulo",
            "to" to "Tocantins", "zz" to "Exterior",
        )

        /** Posição do rótulo (lon, lat) onde o centro da caixa não fica bom. */
        private val ROTULOS = mapOf(
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
        val PEQUENAS = setOf("df", "se", "al", "pb", "rn", "pe", "es", "rj")

        private val COS_LAT = cos(Math.toRadians(15.0)).toFloat()
    }

    private val formas: Map<String, List<FloatArray>>
    private val minX: Float; private val maxX: Float
    private val minY: Float; private val maxY: Float

    init {
        val json = JSONObject(ctx.assets.open("ufs.json").bufferedReader().use { it.readText() })
        val m = mutableMapOf<String, List<FloatArray>>()
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (uf in json.keys()) {
            val aneis = json.getJSONArray(uf)
            m[uf] = (0 until aneis.length()).map { i ->
                val a = aneis.getJSONArray(i)
                FloatArray(a.length()) { k -> a.getInt(k) / 100f }.also { pts ->
                    for (k in pts.indices step 2) {
                        x0 = min(x0, pts[k]); x1 = maxOf(x1, pts[k])
                        y0 = min(y0, pts[k + 1]); y1 = maxOf(y1, pts[k + 1])
                    }
                }
            }
        }
        // DF por último: fica por cima de Goiás
        formas = m.toList().sortedBy { it.first == "df" }.toMap()
        minX = x0; maxX = x1; minY = y0; maxY = y1
    }

    val ufs: Set<String> get() = formas.keys

    private var cacheCaixa: RectF? = null
    private var cachePaths: Map<String, Path> = emptyMap()
    private var escala = 1f; private var offX = 0f; private var offY = 0f

    /** Largura/altura do Brasil projetado, para dimensionar a caixa. */
    val proporcao: Float get() = (maxX - minX) * COS_LAT / (maxY - minY)

    private fun ajustar(caixa: RectF) {
        if (cacheCaixa == caixa) return
        val larg = (maxX - minX) * COS_LAT; val alt = maxY - minY
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
        cacheCaixa = RectF(caixa)
    }

    private fun px(lon: Float) = offX + (lon - minX) * COS_LAT * escala
    private fun py(lat: Float) = offY + (maxY - lat) * escala

    fun caminhos(caixa: RectF): Map<String, Path> { ajustar(caixa); return cachePaths }

    fun rotulo(uf: String, caixa: RectF): PointF {
        ajustar(caixa)
        val (lon, lat) = ROTULOS[uf] ?: return PointF(caixa.centerX(), caixa.centerY())
        return PointF(px(lon), py(lat))
    }
}
