package br.rn.apuracao

/** Partido isolado ou federação que disputa as vagas proporcionais. */
data class Agremiacao(
    val id: String,
    val sigla: String,
    val votos: Long,     // nominais + legenda (0 = desconhecido; usa a soma dos candidatos)
    val vagasTse: Int,   // vagas obtidas segundo o TSE (0 enquanto não divulgado)
)

/**
 * Distribuição das vagas proporcionais (Código Eleitoral, arts. 106–109, com a decisão do STF de 2024
 * que libera a 3ª fase das sobras para todos):
 *  1. Quociente partidário: cada agremiação leva floor(votos / QE) vagas, ocupadas por candidatos com
 *     pelo menos 10% do QE.
 *  2. Sobras: maiores médias votos / (vagas + 1) entre agremiações com ≥ 80% do QE e candidatos com
 *     ≥ 20% do QE.
 *  3. Sobras restantes: maiores médias entre todas as agremiações, sem exigência mínima.
 *
 * Quando o TSE já informa as vagas por agremiação, usa os números oficiais e apenas ordena os candidatos.
 */
object Projecao {

    data class Saida(
        val qe: Long,
        val vagasPorAgr: List<Pair<String, Int>>,   // sigla → vagas, só quem tem vaga
        val naVaga: Set<String>,                     // números dos candidatos dentro das vagas
        val oficial: Boolean,
    )

    /** Quociente eleitoral: válidos / vagas, desprezando fração ≤ 0,5 e arredondando acima disso. */
    fun quociente(validos: Long, vagas: Int): Long {
        if (validos <= 0 || vagas <= 0) return 0
        val q = validos / vagas
        val resto = validos % vagas
        return if (resto * 2 > vagas) q + 1 else q
    }

    fun calcular(
        cands: List<Candidato>,
        agrs: Map<String, Agremiacao>,
        vagas: Int,
        qeTse: Long,
        validos: Long,
    ): Saida? {
        // candidatos com votos, agrupados por agremiação e ordenados pelos mais votados
        val porAgr = cands.filter { it.votos > 0 && it.agr.isNotEmpty() }
            .groupBy { it.agr }
            .mapValues { (_, l) -> l.sortedByDescending { it.votos } }
        if (porAgr.isEmpty()) return null

        val ids = (porAgr.keys + agrs.keys).toSet()
        val votos = ids.associateWith { id ->
            agrs[id]?.votos?.takeIf { it > 0 } ?: porAgr[id].orEmpty().sumOf { it.votos }
        }
        val totalValidos = validos.takeIf { it > 0 } ?: votos.values.sum()
        val qe = qeTse.takeIf { it > 0 } ?: quociente(totalValidos, vagas)
        if (qe <= 0) return null

        val sigla = { id: String -> agrs[id]?.sigla?.takeIf { it.isNotBlank() } ?: id }
        val vagasAgr = ids.associateWith { 0 }.toMutableMap()
        val eleitos = mutableSetOf<String>()
        val proximo = ids.associateWith { 0 }.toMutableMap()   // índice do próximo candidato da lista

        fun eleger(id: String) {
            val c = porAgr[id]!![proximo[id]!!]
            eleitos += c.numero
            proximo[id] = proximo[id]!! + 1
            vagasAgr[id] = vagasAgr[id]!! + 1
        }
        fun candidatoSeguinte(id: String) = porAgr[id]?.getOrNull(proximo[id]!!)
        // maior média; no empate, a agremiação mais votada
        val maiorMedia = compareBy<String>({ votos[it]!!.toDouble() / (vagasAgr[it]!! + 1) }, { votos[it]!! })

        val oficiais = agrs.values.filter { it.vagasTse > 0 }
        if (oficiais.isNotEmpty()) {
            // distribuição oficial do TSE: só preenche com os mais votados de cada agremiação
            for (a in oficiais) repeat(a.vagasTse) { if (candidatoSeguinte(a.id) != null) eleger(a.id) }
        } else {
            // 1ª fase: quociente partidário
            for (id in ids) {
                val qp = (votos[id]!! / qe).toInt()
                while (eleitos.size < vagas && vagasAgr[id]!! < qp && (candidatoSeguinte(id)?.votos ?: 0) * 10 >= qe) eleger(id)
            }
            // 2ª fase: sobras com 80% / 20%
            while (eleitos.size < vagas) {
                val id = ids.filter { votos[it]!! * 10 >= qe * 8 && (candidatoSeguinte(it)?.votos ?: 0) * 5 >= qe }
                    .maxWithOrNull(maiorMedia) ?: break
                eleger(id)
            }
            // 3ª fase: sobras restantes entre todos
            while (eleitos.size < vagas) {
                val id = ids.filter { candidatoSeguinte(it) != null }
                    .maxWithOrNull(maiorMedia) ?: break
                eleger(id)
            }
        }

        val resumo = vagasAgr.filterValues { it > 0 }.toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { votos[it.first] })
            .map { (id, n) -> sigla(id) to n }
        return Saida(qe, resumo, eleitos, oficiais.isNotEmpty())
    }
}
