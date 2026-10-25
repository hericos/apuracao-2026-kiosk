package br.rn.apuracao

import org.json.JSONArray
import org.json.JSONObject

private const val BASE = "https://resultados.tse.jus.br/oficial/ele2026"

/**
 * Códigos oficiais do TSE para 2026: 1º turno 6257 (federal) e 6259 (estadual);
 * 2º turno 6258 (federal) e 6260 (estadual). Só Presidente e Governador têm 2º turno.
 */
enum class Cargo(
    val titulo: String,
    val abrangencia: String,
    private val url1: String,
    private val url2: String?,
    val vagas: Int,
    val textoVagas: String,
    val proporcional: Boolean,
    val cor: Int,
    val mapa: Boolean = false,   // tela do mapa: usa o arquivo de abrangências (% apurado por UF)
) {
    PRESIDENTE(
        "Presidente", "Brasil",
        "$BASE/6257/dados/br/br-c0001-e006257-u.json",
        "$BASE/6258/dados/br/br-c0001-e006258-u.json",
        1, "1 vaga", false, 0xFF2D7FF9.toInt()
    ),
    GOVERNADOR(
        "Governador", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0003-e006259-u.json",
        "$BASE/6260/dados/rn/rn-c0003-e006260-u.json",
        1, "1 vaga", false, 0xFF1E9E5A.toInt()
    ),
    SENADOR(
        "Senador", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0005-e006259-u.json", null,
        2, "2 vagas", false, 0xFF8E44AD.toInt()
    ),
    DEP_FEDERAL(
        "Deputado Federal", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0006-e006259-u.json", null,
        8, "8 vagas", true, 0xFFE67E22.toInt()
    ),
    DEP_ESTADUAL(
        "Deputado Estadual", "Rio Grande do Norte",
        "$BASE/6259/dados/rn/rn-c0007-e006259-u.json", null,
        24, "24 vagas", true, 0xFFD6336C.toInt()
    ),
    MAPA(
        "Apuração por estado", "Brasil e exterior · eleição presidencial",
        "$BASE/6257/dados/br/br-e006257-ab.json",
        "$BASE/6258/dados/br/br-e006258-ab.json",
        0, "27 UFs", false, 0xFF1E9E5A.toInt(), mapa = true
    );

    fun temTurno(turno: Int) = turno == 1 || url2 != null
    fun url(turno: Int): String = if (turno == 2) url2 ?: url1 else url1
}

data class Candidato(
    val numero: String,
    val nome: String,
    val partido: String,
    val votos: Long,
    val pct: Double,
    val eleito: Boolean,
    val situacao: String,
    val agr: String = "",          // agremiação (partido isolado ou federação)
    val naVaga: Boolean = false,   // dentro das vagas proporcionais (oficial ou projeção)
)

/** Andamento da totalização em uma UF (ou "zz" = exterior). */
data class UfStatus(
    val uf: String,
    val pct: Double,
    val secoesTotalizadas: Long,
    val secoesTotal: Long,
    val eleitores: Long,
    val pctComparecimento: Double,
    val municipiosComVotos: Long,
    val municipiosTotal: Long,
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
    // só para cargos proporcionais
    val qe: Long = 0,
    val vagasPorAgr: List<Pair<String, Int>> = emptyList(),
    val vagasOficiais: Boolean = false,
    // só para a tela do mapa
    val ufs: List<UfStatus> = emptyList(),
    val comparecimento: Long = 0, val pctComparecimento: Double = 0.0,
) {
    /** Aplica a distribuição de vagas (oficial do TSE ou projeção) aos candidatos. */
    fun comVagas(cargo: Cargo, agrs: Map<String, Agremiacao>, qeTse: Long): Resultado {
        if (!cargo.proporcional) return this
        val p = Projecao.calcular(candidatos, agrs, cargo.vagas, qeTse, validos)
            ?: return copy(qe = qeTse)
        return copy(
            candidatos = candidatos.map { it.copy(naVaga = it.numero in p.naVaga) },
            qe = p.qe, vagasPorAgr = p.vagasPorAgr, vagasOficiais = p.oficial,
        )
    }
}

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
     * Também registra cada agremiação com seus votos (nominais + legenda) e vagas oficiais.
     */
    private fun coletar(
        o: JSONObject, sigla: String, agr: String,
        out: MutableList<Candidato>, agrs: MutableMap<String, Agremiacao>,
    ) {
        val sg = o.optString("sg").ifBlank { sigla }
        var agrId = agr
        if (o.has("par") && o.optString("n").isNotBlank()) {
            agrId = o.optString("n")
            agrs[agrId] = Agremiacao(
                id = agrId,
                sigla = o.optString("com").ifBlank { o.optString("nm") }.replace(" / ", "/").trim(),
                votos = o.num("tvtn") + o.num("tvtl"),
                vagasTse = o.num("vag").toInt(),
            )
        }
        o.optJSONArray("cand")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val st = c.optString("st").trim()
                val part = c.optString("cc").takeIf { it.isNotBlank() }?.let(::partido) ?: sg
                out += Candidato(
                    numero = c.optString("n"),
                    nome = c.optString("nmu").ifBlank { c.optString("nm") }.trim(),
                    partido = part,
                    votos = c.num("vap"),
                    pct = c.pct("pvap"),
                    eleito = c.optString("e").equals("s", true) || st.startsWith("Eleito", true),
                    situacao = st,
                    agr = agrId.ifBlank { part },   // formato simplificado: agrupa por partido
                )
            }
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "cand") continue
            when (val v = o.opt(k)) {
                is JSONObject -> coletar(v, sg, agrId, out, agrs)
                is JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? JSONObject)?.let { coletar(it, sg, agrId, out, agrs) }
                }
            }
        }
    }

    private fun acharQe(o: JSONObject): Long =
        o.optJSONArray("carg")?.optJSONObject(0)?.num("qe") ?: o.num("qe")

    /** Arquivo de abrangências (`-ab.json`): andamento por UF, sem votos de candidatos. */
    private fun parseAbrangencias(o: JSONObject): Resultado {
        val arr = o.optJSONArray("abr") ?: JSONArray()
        val itens = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val br = itens.firstOrNull { it.optString("tpabr") == "br" } ?: JSONObject()
        val sBr = br.optJSONObject("s") ?: JSONObject()
        val eBr = br.optJSONObject("e") ?: JSONObject()
        val ufs = itens.filter { it.optString("tpabr") == "uf" }.map { a ->
            val s = a.optJSONObject("s") ?: JSONObject()
            val e = a.optJSONObject("e") ?: JSONObject()
            val comVotos = a.num("munpt") + a.num("munf")
            UfStatus(
                uf = a.optString("cdabr").lowercase(),
                pct = s.pct("pst"),
                secoesTotalizadas = s.num("st"), secoesTotal = s.num("ts"),
                eleitores = e.num("te"), pctComparecimento = e.pct("pc"),
                municipiosComVotos = comVotos, municipiosTotal = comVotos + a.num("munnr"),
            )
        }
        return Resultado(
            pctApurado = sBr.pct("pst"),
            secoesTotalizadas = sBr.num("st"), secoesTotal = sBr.num("ts"),
            atualizado = listOf(o.optString("dg"), o.optString("hg")).filter { it.isNotBlank() }.joinToString(" "),
            validos = 0, pctValidos = 0.0, brancos = 0, pctBrancos = 0.0, nulos = 0, pctNulos = 0.0,
            abstencao = eBr.num("a"), pctAbstencao = eBr.pct("pa"),
            candidatos = emptyList(),
            ufs = ufs,
            comparecimento = eBr.num("c"), pctComparecimento = eBr.pct("pc"),
        )
    }

    fun parse(json: String, cargo: Cargo): Resultado {
        val o = JSONObject(json)
        if (cargo.mapa) return parseAbrangencias(o)
        val agrs = mutableMapOf<String, Agremiacao>()
        val cands = mutableListOf<Candidato>().also { coletar(o, "", "", it, agrs) }
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
            // no arquivo completo "pvv" é relativo aos próprios válidos (100%); calcula sobre o total de votos
            validos = v.num("vv"),
            pctValidos = v.num("tv").takeIf { it > 0 }?.let { v.num("vv") * 100.0 / it } ?: v.pct("pvv"),
            brancos = v.num("vb"), pctBrancos = v.pct("pvb"),
            nulos = v.num("tvn").takeIf { it > 0 } ?: v.num("vn"),
            pctNulos = v.pct("ptvn").takeIf { it > 0 } ?: v.pct("pvn"),
            abstencao = e.num("a"), pctAbstencao = e.pct("pa"),
            candidatos = cands,
        ).comVagas(cargo, agrs, acharQe(o))
    }
}
