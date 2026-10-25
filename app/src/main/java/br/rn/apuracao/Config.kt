package br.rn.apuracao

import android.content.Context

/** Ajustes do painel, persistidos em SharedPreferences. */
object Config {
    private const val PREFS = "config"
    private const val K_SEGUNDOS = "segundos"
    private const val K_ORDEM = "ordem"
    private const val K_OCULTAS = "ocultas"
    private const val K_DEMO = "demo"
    private const val K_TURNO = "turno"

    /** Data do 2º turno de 2026: a partir dela o turno padrão passa a ser o 2º. */
    private const val DATA_2_TURNO = "2026-10-25"

    const val SEGUNDOS_PADRAO = 10
    const val SEGUNDOS_MIN = 3
    const val SEGUNDOS_MAX = 120

    val TELAS_PADRAO = listOf(
        Tela(Cargo.PRESIDENTE, 0),
        Tela(Cargo.GOVERNADOR, 0),
        Tela(Cargo.SENADOR, 0),
        Tela(Cargo.DEP_FEDERAL, 0),
        Tela(Cargo.DEP_FEDERAL, 1),
        Tela(Cargo.DEP_ESTADUAL, 0),
        Tela(Cargo.DEP_ESTADUAL, 1),
    )

    data class Item(val tela: Tela, val visivel: Boolean)

    data class Ajustes(val segundos: Int, val itens: List<Item>, val demo: Boolean, val turno: Int) {
        /** Telas visíveis que existem no turno escolhido (no 2º, só Presidente e Governador). */
        val telasAtivas: List<Tela>
            get() = itens.filter { it.visivel && it.tela.cargo.temTurno(turno) }.map { it.tela }
                .ifEmpty { TELAS_PADRAO.filter { it.cargo.temTurno(turno) } }
    }

    fun turnoPadrao(): Int =
        if (java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) >= DATA_2_TURNO) 2 else 1

    val PADRAO get() = Ajustes(SEGUNDOS_PADRAO, TELAS_PADRAO.map { Item(it, true) }, false, turnoPadrao())

    fun nome(t: Tela): String = when {
        !t.cargo.proporcional -> t.cargo.titulo
        t.pagina == 0 -> "${t.cargo.titulo} — 1º ao 15º"
        else -> "${t.cargo.titulo} — 16º ao 30º"
    }

    private fun chave(t: Tela) = "${t.cargo.name}:${t.pagina}"
    private fun deChave(s: String): Tela? = TELAS_PADRAO.firstOrNull { chave(it) == s }

    fun carregar(ctx: Context): Ajustes {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salvas = p.getString(K_ORDEM, null)?.split(",")?.mapNotNull(::deChave).orEmpty()
        // telas novas (ausentes da ordem salva) entram logo após a tela que as precede no padrão
        val ordem = salvas.distinct().toMutableList()
        TELAS_PADRAO.forEachIndexed { i, t ->
            if (t !in ordem) {
                val anterior = TELAS_PADRAO.getOrNull(i - 1)
                ordem.add(if (anterior == null) 0 else ordem.indexOf(anterior) + 1, t)
            }
        }
        val ocultas = p.getStringSet(K_OCULTAS, emptySet()).orEmpty()
        val itens = ordem.map { Item(it, chave(it) !in ocultas) }
        return Ajustes(
            segundos = p.getInt(K_SEGUNDOS, SEGUNDOS_PADRAO).coerceIn(SEGUNDOS_MIN, SEGUNDOS_MAX),
            itens = if (itens.any { it.visivel }) itens else PADRAO.itens,
            demo = p.getBoolean(K_DEMO, false),
            turno = p.getInt(K_TURNO, turnoPadrao()).coerceIn(1, 2),
        )
    }

    fun salvar(ctx: Context, a: Ajustes) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_SEGUNDOS, a.segundos)
            .putString(K_ORDEM, a.itens.joinToString(",") { chave(it.tela) })
            .putStringSet(K_OCULTAS, a.itens.filter { !it.visivel }.map { chave(it.tela) }.toSet())
            .putBoolean(K_DEMO, a.demo)
            .putInt(K_TURNO, a.turno)
            .apply()
    }
}
