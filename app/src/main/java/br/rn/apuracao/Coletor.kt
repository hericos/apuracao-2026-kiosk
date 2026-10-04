package br.rn.apuracao

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed class Estado {
    object Carregando : Estado()
    object Aguardando : Estado()          // TSE ainda não publicou o arquivo (HTTP 403/404)
    data class Ok(val r: Resultado, val recebidoEm: Long) : Estado()
    data class Erro(val msg: String, val ultimo: Resultado?) : Estado()
}

/** Faz 1 requisição a cada [intervaloMs] para cada cargo, em paralelo. */
class Coletor(
    private val intervaloMs: Long = 5_000,
    private val aoAtualizar: (Cargo) -> Unit,
) {
    val estados = ConcurrentHashMap<Cargo, Estado>().apply {
        Cargo.entries.forEach { put(it, Estado.Carregando) }
    }
    private val lastModified = ConcurrentHashMap<Cargo, String>()
    private var exec: ScheduledExecutorService? = null

    @Volatile var demo = false
        set(v) {
            field = v; demoTick = 0; lastModified.clear()
            // descarta resultados do modo anterior (teste x real)
            Cargo.entries.forEach { estados[it] = Estado.Carregando }
        }
    private var demoTick = 0

    fun iniciar(cargos: Set<Cargo> = Cargo.entries.toSet()) {
        if (exec != null) return
        exec = Executors.newScheduledThreadPool(cargos.size.coerceAtLeast(1)).also { ex ->
            cargos.forEachIndexed { i, cargo ->
                // escalona levemente o início para não disparar tudo no mesmo milissegundo
                ex.scheduleAtFixedRate({ buscar(cargo) }, i * 300L, intervaloMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun parar() {
        exec?.shutdownNow()
        exec = null
    }

    private fun ultimo(cargo: Cargo): Resultado? = when (val e = estados[cargo]) {
        is Estado.Ok -> e.r
        is Estado.Erro -> e.ultimo
        else -> null
    }

    private fun buscar(cargo: Cargo) {
        try {
            if (demo) {
                estados[cargo] = Estado.Ok(Demo.gerar(cargo, demoTick++), System.currentTimeMillis())
                aoAtualizar(cargo)
                return
            }
            val con = URL(cargo.url).openConnection() as HttpURLConnection
            con.connectTimeout = 4_000
            con.readTimeout = 4_500
            con.useCaches = false
            con.setRequestProperty("Cache-Control", "no-cache")
            con.setRequestProperty("Accept", "application/json")
            lastModified[cargo]?.let { con.setRequestProperty("If-Modified-Since", it) }
            try {
                when (val code = con.responseCode) {
                    200 -> {
                        val body = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val r = Parser.parse(body, cargo)
                        con.getHeaderField("Last-Modified")?.let { lastModified[cargo] = it }
                        estados[cargo] = Estado.Ok(r, System.currentTimeMillis())
                    }
                    304 -> {
                        // sem mudanças: mantém o último resultado, só marca que a consulta ocorreu
                        ultimo(cargo)?.let { estados[cargo] = Estado.Ok(it, System.currentTimeMillis()) }
                    }
                    403, 404 -> if (ultimo(cargo) == null) estados[cargo] = Estado.Aguardando
                    else -> estados[cargo] = Estado.Erro("HTTP $code", ultimo(cargo))
                }
            } finally {
                con.disconnect()
            }
        } catch (t: Throwable) {
            android.util.Log.w("Apuracao", "Falha ao consultar ${cargo.name}", t)
            estados[cargo] = Estado.Erro(t.javaClass.simpleName, ultimo(cargo))
        }
        aoAtualizar(cargo)
    }
}

/** Dados fictícios para testar o layout antes da divulgação (pressione e segure a tela). */
object Demo {
    private val nomes = listOf(
        "ANA", "BRUNO", "CARLA", "DIEGO", "ELISA", "FÁBIO", "GABRIELA", "HENRIQUE", "ISABEL", "JOÃO",
        "KARINA", "LUCAS", "MARIANA", "NELSON", "OLÍVIA", "PAULO", "QUITÉRIA", "RAFAEL", "SABRINA", "TIAGO"
    )
    private val sobrenomes = listOf(
        "SILVA", "SOUZA", "OLIVEIRA", "LIMA", "PEREIRA", "COSTA", "ALVES", "MEDEIROS", "DANTAS", "BEZERRA",
        "FERNANDES", "MAIA", "QUEIROZ", "ROCHA", "TAVARES"
    )
    private val partidos = listOf("PT", "PL", "UNIÃO", "MDB", "PSD", "PP", "PSDB", "PSB", "REPUBLICANOS", "PDT", "PSOL", "NOVO")

    fun gerar(cargo: Cargo, tick: Int): Resultado {
        val n = when (cargo) {
            Cargo.PRESIDENTE -> 9; Cargo.GOVERNADOR -> 6; Cargo.SENADOR -> 8
            Cargo.DEP_FEDERAL -> 95; Cargo.DEP_ESTADUAL -> 160
        }
        val rnd = Random(cargo.ordinal * 7919)
        val pesos = DoubleArray(n) { i -> (1.0 / (i + 1.3)) * (0.6 + rnd.nextDouble()) }
        val apurado = ((tick + 1) * 1.7).coerceAtMost(100.0)
        val eleitorado = if (cargo == Cargo.PRESIDENTE) 156_000_000L else 2_600_000L
        val validos = (eleitorado * 0.78 * apurado / 100).toLong()
        val jitter = Random(tick * 31 + cargo.ordinal)
        val w = pesos.map { it * (0.97 + jitter.nextDouble() * 0.06) }
        val soma = w.sum()
        val fim = apurado >= 100.0
        val cands = w.mapIndexed { i, p ->
            val v = (validos * p / soma).toLong()
            val num = if (cargo.proporcional) "${10 + i % 80}${100 + i}" else "${10 + i * 5}"
            Candidato(num, "${nomes[(i * 7) % nomes.size]} ${sobrenomes[(i * 3) % sobrenomes.size]}",
                partidos[i % partidos.size], v, if (validos > 0) v * 100.0 / validos else 0.0,
                fim && !cargo.proporcional && i < cargo.vagas,
                if (fim && !cargo.proporcional && i < cargo.vagas) "Eleito" else "",
                agr = partidos[i % partidos.size])
        }.sortedByDescending { it.votos }
        val secoes = if (cargo == Cargo.PRESIDENTE) 472_000L else 7_900L
        return Resultado(
            apurado, (secoes * apurado / 100).toLong(), secoes, "DEMONSTRAÇÃO",
            validos, 91.2, (validos * 0.03).toLong(), 2.9, (validos * 0.06).toLong(), 5.9,
            (eleitorado * 0.2 * apurado / 100).toLong(), 20.1, cands
        ).comVagas(cargo, demoAgremiacoes(cands), 0)
    }
}

/** Agremiações fictícias: votos nominais dos candidatos + 5% de votos de legenda. */
private fun demoAgremiacoes(cands: List<Candidato>): Map<String, Agremiacao> =
    cands.groupBy { it.agr }.mapValues { (id, l) ->
        Agremiacao(id, id, (l.sumOf { it.votos } * 1.05).toLong(), 0)
    }
