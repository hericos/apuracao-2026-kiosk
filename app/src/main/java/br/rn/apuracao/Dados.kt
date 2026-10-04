package br.rn.apuracao

import org.json.JSONArray
import org.json.JSONObject

private const val BASE = "https://resultados.tse.jus.br/oficial/ele2026"

/** Códigos oficiais do TSE para 2026 (1º turno): 6257 = federal, 6259 = estadual. */
enum class Cargo(
    val titulo: String,
    val abrangencia: String,
    val url: String,
    val vagas: Int,
    val textoVagas: String,
    val proporcional: Boolean,
    val cor: Int,
) {
    PRESIDENTE(
        "Presidente", "Brasil",
        "$BASE/6257/dados-simplificados/br/br-c0001-e006257-r.json",
        1, "1 vaga", false, 0xFF2D7FF9.toInt()
    ),
    GOVERNADOR(
        "Governador", "Rio Grande do Norte",
        "$BASE/6259/dados-simplificados/rn/rn-c0003-e006259-r.json",
        1, "1 vaga", false, 0xFF1E9E5A.toInt()
    ),
    SENADOR(
        "Senador", "Rio Grande do Norte",
        "$BASE/6259/dados-simplificados/rn/rn-c0005-e006259-r.json",
        2, "2 vagas", false, 0xFF8E44AD.toInt()
    ),
    DEP_FEDERAL(
        "Deputado Federal", "Rio Grande do Norte",
        "$BASE/6259/dados-simplificados/rn/rn-c0006-e006259-r.json",
        8, "8 vagas", true, 0xFFE67E22.toInt()
    ),
    DEP_ESTADUAL(
        "Deputado Estadual", "Rio Grande do Norte",
        "$BASE/6259/dados-simplificados/rn/rn-c0007-e006259-r.json",
        24, "24 vagas", true, 0xFFD6336C.toInt()
    );
}

data class Candidato(
    val numero: String,
    val nome: String,
    val partido: String,
    val votos: Long,
    val pct: Double,
    val eleito: Boolean,
    val situacao: String,
)

data class Resultado(
    val pctApurado: Double,
    val secoesTotalizadas: Long,
    val secoesTotal: Long,
    val atualizado: String,
    val validos: Long, val pctValidos: Double,
    val brancos: Long, val pctBrancos: Double,
    val nulos: Long, val pctNulos: Double,
    val abstencao: Long, val pctAbstencao: Double,
    val candidatos: List<Candidato>,
)

object Parser {
    private fun JSONObject.num(k: String): Long =
        optString(k).filter { it.isDigit() }.toLongOrNull() ?: 0L

    private fun JSONObject.pct(k: String): Double {
        val s = optString(k).trim()
        val norm = if (s.contains(',')) s.replace(".", "").replace(',', '.') else s
        return norm.toDoubleOrNull() ?: 0.0
    }

    /** Procura o array de candidatos mesmo que o TSE mude o aninhamento. */
    private fun acharCand(o: JSONObject): JSONArray? {
        o.optJSONArray("cand")?.let { return it }
        val keys = o.keys()
        while (keys.hasNext()) {
            when (val v = o.opt(keys.next())) {
                is JSONObject -> acharCand(v)?.let { return it }
                is JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? JSONObject)?.let { acharCand(it)?.let { r -> return r } }
                }
            }
        }
        return null
    }

    private fun partido(cc: String): String {
        // "cc" vem como "PT" ou "PT - Federação ..." ; mostramos só a sigla
        val s = cc.substringBefore(" - ").trim()
        return s.ifEmpty { cc.trim() }
    }

    fun parse(json: String): Resultado {
        val o = JSONObject(json)
        val arr = acharCand(o) ?: JSONArray()
        val cands = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val st = c.optString("st").trim()
            Candidato(
                numero = c.optString("n"),
                nome = c.optString("nmu").ifBlank { c.optString("nm") }.trim(),
                partido = partido(c.optString("cc")),
                votos = c.num("vap"),
                pct = c.pct("pvap"),
                eleito = c.optString("e").equals("s", true) || st.startsWith("Eleito", true),
                situacao = st,
            )
        }.sortedWith(compareByDescending<Candidato> { it.votos }.thenBy { it.nome })

        val dg = o.optString("dg"); val hg = o.optString("hg")
        return Resultado(
            pctApurado = o.pct("pst"),
            secoesTotalizadas = o.num("st"),
            secoesTotal = o.num("s"),
            atualizado = listOf(dg, hg).filter { it.isNotBlank() }.joinToString(" "),
            validos = o.num("vv"), pctValidos = o.pct("pvv"),
            brancos = o.num("vb"), pctBrancos = o.pct("pvb"),
            nulos = o.num("tvn").takeIf { it > 0 } ?: o.num("vn"),
            pctNulos = o.pct("ptvn").takeIf { it > 0 } ?: o.pct("pvn"),
            abstencao = o.num("a"), pctAbstencao = o.pct("pa"),
            candidatos = cands,
        )
    }
}
