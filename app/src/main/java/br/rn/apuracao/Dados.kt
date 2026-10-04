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
        "$BASE/6257/dados/br/br-c0001-e006257-u.json",
        1, "1 vaga", false, 0xFF2D7FF9.toInt()
    ),
    GOVERNADOR(
        "Governador", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0003-e006259-u.json",
        1, "1 vaga", false, 0xFF1E9E5A.toInt()
    ),
    SENADOR(
        "Senador", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0005-e006259-u.json",
        2, "2 vagas", false, 0xFF8E44AD.toInt()
    ),
    DEP_FEDERAL(
        "Deputado Federal", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0006-e006259-u.json",
        8, "8 vagas", true, 0xFFE67E22.toInt()
    ),
    DEP_ESTADUAL(
        "Deputado Estadual", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0007-e006259-u.json",
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
    private val ordemAlfabetica = java.text.Collator.getInstance(java.util.Locale("pt", "BR"))

    private fun JSONObject.num(k: String): Long =
        optString(k).filter { it.isDigit() }.toLongOrNull() ?: 0L

    private fun JSONObject.pct(k: String): Double {
        val s = optString(k).trim()
        val norm = if (s.contains(',')) s.replace(".", "").replace(',', '.') else s
        return norm.toDoubleOrNull() ?: 0.0
    }

    private fun partido(cc: String): String {
        // "cc" vem como "PT" ou "PT - Federação ..." ; mostramos só a sigla
        val s = cc.substringBefore(" - ").trim()
        return s.ifEmpty { cc.trim() }
    }

    /**
     * Junta os candidatos de qualquer aninhamento. No arquivo completo do TSE (`-u.json`) eles ficam em
     * carg → agr (agremiação) → par (partido, com a sigla em "sg") → cand; no simplificado, direto em "cand".
     */
    private fun coletar(o: JSONObject, sigla: String, out: MutableList<Candidato>) {
        val sg = o.optString("sg").ifBlank { sigla }
        o.optJSONArray("cand")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val st = c.optString("st").trim()
                out += Candidato(
                    numero = c.optString("n"),
                    nome = c.optString("nmu").ifBlank { c.optString("nm") }.trim(),
                    partido = c.optString("cc").takeIf { it.isNotBlank() }?.let(::partido) ?: sg,
                    votos = c.num("vap"),
                    pct = c.pct("pvap"),
                    eleito = c.optString("e").equals("s", true) || st.startsWith("Eleito", true),
                    situacao = st,
                )
            }
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "cand") continue
            when (val v = o.opt(k)) {
                is JSONObject -> coletar(v, sg, out)
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let { coletar(it, sg, out) }
            }
        }
    }

    fun parse(json: String): Resultado {
        val o = JSONObject(json)
        val cands = mutableListOf<Candidato>().also { coletar(o, "", it) }
            .sortedWith(compareByDescending<Candidato> { it.votos }.thenBy(ordemAlfabetica) { it.nome })

        // No arquivo completo os totais ficam em "s" (seções), "e" (eleitorado) e "v" (votos);
        // no simplificado, na raiz.
        val s = o.optJSONObject("s") ?: o
        val e = o.optJSONObject("e") ?: o
        val v = o.optJSONObject("v") ?: o
        val dg = o.optString("dg"); val hg = o.optString("hg")
        return Resultado(
            pctApurado = s.pct("pst"),
            secoesTotalizadas = s.num("st"),
            secoesTotal = if (s !== o) s.num("ts") else o.num("s"),
            atualizado = listOf(dg, hg).filter { it.isNotBlank() }.joinToString(" "),
            validos = v.num("vv"), pctValidos = v.pct("pvv"),
            brancos = v.num("vb"), pctBrancos = v.pct("pvb"),
            nulos = v.num("tvn").takeIf { it > 0 } ?: v.num("vn"),
            pctNulos = v.pct("ptvn").takeIf { it > 0 } ?: v.pct("pvn"),
            abstencao = e.num("a"), pctAbstencao = e.pct("pa"),
            candidatos = cands,
        )
    }
}
