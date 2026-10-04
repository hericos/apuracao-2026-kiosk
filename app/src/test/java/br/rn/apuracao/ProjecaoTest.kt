package br.rn.apuracao

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjecaoTest {

    private fun cands(agr: String, vararg votos: Long) =
        votos.mapIndexed { i, v -> Candidato("$agr$i", "$agr$i", agr, v, 0.0, false, "", agr = agr) }

    private fun agr(id: String, votos: Long) = id to Agremiacao(id, id, votos, 0)

    @Test
    fun quocienteArredondaSoAcimaDeMeio() {
        assertEquals(10_000, Projecao.quociente(100_000, 10))
        assertEquals(10_000, Projecao.quociente(100_005, 10))   // fração 0,5 é desprezada
        assertEquals(10_001, Projecao.quociente(100_006, 10))
    }

    @Test
    fun distribuiPorQuocienteESobras() {
        // QE = 100.000 / 10 = 10.000
        val lista = cands("A", 20_000, 10_000, 8_000, 3_000, 2_000, 1_500, 500) +
            cands("B", 12_000, 9_000, 5_000, 4_000) +
            cands("C", 9_000, 6_000) +
            cands("D", 7_000) +
            cands("E", 3_000)
        val agrs = mapOf(agr("A", 45_000), agr("B", 30_000), agr("C", 15_000), agr("D", 7_000), agr("E", 3_000))
        val p = Projecao.calcular(lista, agrs, vagas = 10, qeTse = 0, validos = 100_000)!!

        assertEquals(10_000, p.qe)
        // 1ª fase: A 4, B 3, C 1. Sobras (80/20): A (média 9.000) leva com o candidato de 2.000;
        // depois A não tem candidato com 20% do QE e B e C empatam em 7.500 -> B, mais votado.
        assertEquals(listOf("A" to 5, "B" to 4, "C" to 1), p.vagasPorAgr)
        assertEquals(setOf("A0", "A1", "A2", "A3", "A4", "B0", "B1", "B2", "B3", "C0"), p.naVaga)
    }

    @Test
    fun usaVagasOficiaisDoTse() {
        val lista = cands("A", 50, 40, 30) + cands("B", 45, 5)
        val agrs = mapOf("A" to Agremiacao("A", "A", 120, 1), "B" to Agremiacao("B", "B", 50, 2))
        val p = Projecao.calcular(lista, agrs, vagas = 3, qeTse = 57, validos = 170)!!
        assertEquals(true, p.oficial)
        assertEquals(setOf("A0", "B0", "B1"), p.naVaga)
    }

    @Test
    fun semVotosNaoProjeta() {
        assertEquals(null, Projecao.calcular(cands("A", 0, 0), emptyMap(), 8, 0, 0))
    }
}
