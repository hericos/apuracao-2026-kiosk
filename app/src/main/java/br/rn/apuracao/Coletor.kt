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

val Estado.resultado: Resultado?
    get() = when (this) {
        is Estado.Ok -> r
        is Estado.Erro -> ultimo
        else -> null
    }

/**
 * A cada [intervaloMs], para cada cargo e em paralelo: o resultado total; nas telas com mapa, também o
 * andamento por região e, se houver uma região selecionada, o resultado dela.
 */
class Coletor(
    private val intervaloMs: Long = 5_000,
    /** Códigos das regiões do mapa de cada cargo, para os dados de teste. */
    private val regioesDoCargo: (Cargo) -> List<String>,
    private val aoAtualizar: (Cargo) -> Unit,
) {
    val estados = ConcurrentHashMap<Cargo, Estado>().apply {
        Cargo.entries.forEach { put(it, Estado.Carregando) }
    }
    val andamentos = ConcurrentHashMap<Cargo, List<Andamento>>()
    val selecao = ConcurrentHashMap<Cargo, String>()
    val detalhes = ConcurrentHashMap<Cargo, Estado>()

    private val lastModified = ConcurrentHashMap<String, String>()
    private var exec: ScheduledExecutorService? = null

    @Volatile var demo = false
        set(v) { if (field != v) { field = v; reiniciarEstados() } }

    @Volatile var turno = 1
        set(v) { if (field != v) { field = v; reiniciarEstados() } }

    @Volatile private var demoTick = 0

    /** Descarta resultados do modo anterior (teste x real, 1º x 2º turno). */
    private fun reiniciarEstados() {
        demoTick = 0; lastModified.clear()
        Cargo.entries.forEach { estados[it] = Estado.Carregando }
        andamentos.clear(); selecao.clear(); detalhes.clear()
    }

    fun iniciar(cargos: Set<Cargo> = Cargo.entries.toSet()) {
        if (exec != null) return
        exec = Executors.newScheduledThreadPool(cargos.size + 1).also { ex ->
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

    /** Seleciona (ou limpa, com null) a região do mapa e já busca o resultado dela. */
    fun selecionar(cargo: Cargo, regiao: String?) {
        if (regiao == null) {
            selecao.remove(cargo); detalhes.remove(cargo)
            aoAtualizar(cargo)
            return
        }
        selecao[cargo] = regiao
        detalhes[cargo] = Estado.Carregando
        aoAtualizar(cargo)
        exec?.execute { buscarRegiao(cargo, regiao); aoAtualizar(cargo) }
    }

    private fun buscar(cargo: Cargo) {
        if (demo) {
            val tick = demoTick++
            estados[cargo] = Estado.Ok(Demo.gerar(cargo, tick, turno), System.currentTimeMillis())
            if (cargo.temMapa) andamentos[cargo] = Demo.andamentos(regioesDoCargo(cargo), tick)
        } else {
            estados[cargo] = atualizar(estados[cargo], cargo.url(turno)) { Parser.parse(it, cargo) }
            if (cargo.temMapa) baixarAndamento(cargo)
        }
        selecao[cargo]?.let { buscarRegiao(cargo, it) }
        aoAtualizar(cargo)
    }

    private fun baixarAndamento(cargo: Cargo) {
        val url = cargo.urlAndamento(turno)
        val temAnterior = andamentos.containsKey(cargo)
        when (val d = baixar(url, condicional = temAnterior)) {
            is Download.Novo -> runCatching { Parser.parseAndamento(d.corpo) }.onSuccess { andamentos[cargo] = it }
            else -> Unit   // mantém o último andamento conhecido
        }
    }

    private fun buscarRegiao(cargo: Cargo, regiao: String) {
        val novo = if (demo) {
            Estado.Ok(Demo.gerar(cargo, demoTick, turno, regiao), System.currentTimeMillis())
        } else {
            atualizar(detalhes[cargo], cargo.urlRegiao(turno, regiao)) { Parser.parse(it, cargo) }
        }
        // a seleção pode ter mudado enquanto baixava
        if (selecao[cargo] == regiao) detalhes[cargo] = novo
    }

    private fun atualizar(atual: Estado?, url: String, parse: (String) -> Resultado): Estado {
        val ultimo = atual?.resultado
        val agora = System.currentTimeMillis()
        return when (val d = baixar(url, condicional = ultimo != null)) {
            is Download.Novo -> try {
                Estado.Ok(parse(d.corpo), agora)
            } catch (t: Throwable) {
                android.util.Log.w("Apuracao", "Dados inválidos em $url", t)
                Estado.Erro("dados inválidos", ultimo)
            }
            Download.SemMudanca -> ultimo?.let { Estado.Ok(it, agora) } ?: Estado.Carregando
            Download.NaoPublicado -> ultimo?.let { Estado.Ok(it, agora) } ?: Estado.Aguardando
            is Download.Falha -> Estado.Erro(d.msg, ultimo)
        }
    }

    private sealed class Download {
        class Novo(val corpo: String) : Download()
        object SemMudanca : Download()
        object NaoPublicado : Download()
        class Falha(val msg: String) : Download()
    }

    private fun baixar(url: String, condicional: Boolean): Download = try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 4_000
        con.readTimeout = 4_500
        con.useCaches = false
        con.setRequestProperty("Cache-Control", "no-cache")
        con.setRequestProperty("Accept", "application/json")
        if (condicional) lastModified[url]?.let { con.setRequestProperty("If-Modified-Since", it) }
        try {
            when (val code = con.responseCode) {
                200 -> {
                    val corpo = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    con.getHeaderField("Last-Modified")?.let { lastModified[url] = it }
                    Download.Novo(corpo)
                }
                304 -> Download.SemMudanca
                403, 404 -> Download.NaoPublicado
                else -> Download.Falha("HTTP $code")
            }
        } finally {
            con.disconnect()
        }
    } catch (t: Throwable) {
        android.util.Log.w("Apuracao", "Falha ao consultar $url", t)
        Download.Falha(t.javaClass.simpleName)
    }
}

/** Dados fictícios para testar o layout antes da divulgação (configurações → dados de teste). */
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

    /** Andamento fictício por região: cada uma avança num ritmo diferente. */
    fun andamentos(codigos: List<String>, tick: Int): List<Andamento> = codigos.map { cod ->
        val h = cod.hashCode()
        val ritmo = 0.6 + Random(h).nextDouble() * 1.4
        val inicio = Random(h * 17).nextInt(0, 6)
        val pct = ((tick - inicio).coerceAtLeast(0) * 2.2 * ritmo).coerceAtMost(100.0)
        val ts = 20L + Random(h * 3).nextLong(0, 4_000)
        Andamento(cod, pct, (ts * pct / 100).toLong(), ts, ts * 330)
    }

    /** Resultado fictício do cargo; com [regiao], varia os pesos para cada UF/município. */
    fun gerar(cargo: Cargo, tick: Int, turno: Int = 1, regiao: String? = null): Resultado {
        val n = if (turno == 2 && cargo.temTurno(2)) 2 else when (cargo) {
            Cargo.PRESIDENTE -> 9; Cargo.GOVERNADOR -> 6; Cargo.SENADOR -> 8
            Cargo.DEP_FEDERAL -> 95; Cargo.DEP_ESTADUAL -> 160
        }
        val rnd = Random(cargo.ordinal * 7919)
        val local = Random((regiao ?: "").hashCode())
        val pesos = DoubleArray(n) { i -> (1.0 / (i + 1.3)) * (0.6 + rnd.nextDouble()) * (if (regiao != null) 0.5 + local.nextDouble() else 1.0) }
        val apurado = ((tick + 1) * 1.7).coerceAtMost(100.0)
        val eleitorado = when {
            regiao != null -> 400_000L + local.nextLong(0, 4_000_000)
            cargo == Cargo.PRESIDENTE -> 156_000_000L
            else -> 2_600_000L
        }
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
                fim && regiao == null && !cargo.proporcional && i < cargo.vagas,
                if (fim && regiao == null && !cargo.proporcional && i < cargo.vagas) "Eleito" else "",
                agr = partidos[i % partidos.size])
        }.sortedByDescending { it.votos }
        val secoes = eleitorado / 330
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
