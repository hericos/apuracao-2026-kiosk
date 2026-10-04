package br.rn.apuracao

import android.content.Context

/** Ajustes do painel, persistidos em SharedPreferences. */
object Config {
    private const val PREFS = "config"
    private const val K_SEGUNDOS = "segundos"
    private const val K_ORDEM = "ordem"
    private const val K_OCULTAS = "ocultas"
    private const val K_DEMO = "demo"

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

    data class Ajustes(val segundos: Int, val itens: List<Item>, val demo: Boolean) {
        val telasAtivas: List<Tela> get() = itens.filter { it.visivel }.map { it.tela }
    }

    val PADRAO = Ajustes(SEGUNDOS_PADRAO, TELAS_PADRAO.map { Item(it, true) }, false)

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
        // telas não presentes na ordem salva entram no fim, na ordem padrão
        val ordem = (salvas + TELAS_PADRAO).distinct()
        val ocultas = p.getStringSet(K_OCULTAS, emptySet()).orEmpty()
        val itens = ordem.map { Item(it, chave(it) !in ocultas) }
        return Ajustes(
            segundos = p.getInt(K_SEGUNDOS, SEGUNDOS_PADRAO).coerceIn(SEGUNDOS_MIN, SEGUNDOS_MAX),
            itens = if (itens.any { it.visivel }) itens else PADRAO.itens,
            demo = p.getBoolean(K_DEMO, false),
        )
    }

    fun salvar(ctx: Context, a: Ajustes) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_SEGUNDOS, a.segundos)
            .putString(K_ORDEM, a.itens.joinToString(",") { chave(it.tela) })
            .putStringSet(K_OCULTAS, a.itens.filter { !it.visivel }.map { chave(it.tela) }.toSet())
            .putBoolean(K_DEMO, a.demo)
            .apply()
    }
}
