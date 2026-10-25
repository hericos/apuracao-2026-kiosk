package br.rn.apuracao

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Uma "tela" do rodízio: um cargo e uma página (0 = 1º ao 15º, 1 = 16º ao 30º). */
data class Tela(val cargo: Cargo, val pagina: Int)

class PainelView(ctx: Context) : View(ctx) {

    companion object {
        /** Todas as telas de lista usam a mesma grade de linhas, para manter as proporções iguais. */
        const val POR_PAGINA = 15

        /** Linhas da tabela abaixo do mapa (27 UFs + exterior; ou os maiores municípios do RN). */
        const val LINHAS_TABELA = 28
    }

    /** Ações disparadas por toques na tela. */
    interface Acoes {
        fun avancar()
        fun alternarPausa()
        fun selecionar(regiao: String?)
        fun configurar()
    }

    var acoes: Acoes? = null
    var tela: Tela = Tela(Cargo.PRESIDENTE, 0)
    var estado: Estado = Estado.Carregando
    var indice = 0
    var total = 1
    var progressoTela = 0f      // 0..1 até a próxima troca
    var demo = false
    var turno = 1
    var pausado = false

    // telas com mapa
    var andamento: List<Andamento> = emptyList()
    var selecao: String? = null
    var detalhe: Estado? = null

    private var insetTopo = 0   // recorte da câmera/notch, quando houver

    // áreas tocáveis do último desenho
    private var unidade = 1f
    private val areaPausa = RectF()
    private val areaLimpar = RectF()
    private var caixaMapa: RectF? = null
    private var malhaAtual: Malha? = null
    private val linhasTabela = mutableListOf<Pair<RectF, String>>()

    private val br = Locale("pt", "BR")
    private val fmtInt = NumberFormat.getIntegerInstance(br)
    private val fmtPct = NumberFormat.getNumberInstance(br).apply {
        minimumFractionDigits = 2; maximumFractionDigits = 2
    }
    private val fmtHora = SimpleDateFormat("HH:mm:ss", br)

    private val BG = Color.parseColor("#0B1320")
    private val CARD = Color.parseColor("#15212F")
    private val CARD2 = Color.parseColor("#1B2A3B")
    private val LINHA = Color.parseColor("#22344A")
    private val TXT = Color.parseColor("#F3F6FA")
    private val TXT2 = Color.parseColor("#93A4B8")
    private val OURO = Color.parseColor("#F2B705")
    private val VERDE = Color.parseColor("#2ECC71")
    private val VERMELHO = Color.parseColor("#FF6B6B")
    private val AZUL = Color.parseColor("#4DA3FF")
    private val CINZA = Color.parseColor("#B8C4D2")

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedB = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun pct(v: Double) = fmtPct.format(v) + "%"
    private fun int(v: Long) = fmtInt.format(v)

    // ---------------------------------------------------------------- toques

    private val gestos = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tocar(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) { acoes?.configurar() }
    })

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestos.onTouchEvent(event) || super.onTouchEvent(event)

    private fun tocar(x: Float, y: Float) {
        val a = acoes ?: return
        if (areaPausa.contains(x, y)) { a.alternarPausa(); return }
        if (!areaLimpar.isEmpty && areaLimpar.contains(x, y)) { a.selecionar(null); return }
        linhasTabela.firstOrNull { it.first.contains(x, y) }?.let { (_, cod) ->
            a.selecionar(if (cod == selecao) null else cod); return
        }
        val caixa = caixaMapa
        val malha = malhaAtual
        if (caixa != null && malha != null && caixa.contains(x, y)) {
            malha.regiaoEm(x, y, caixa, 2.5f * unidade)?.let { cod -> a.selecionar(if (cod == selecao) null else cod) }
            return
        }
        a.avancar()
    }

    // ---------------------------------------------------------------- desenho

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        insetTopo = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    private fun texto(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        tf: Typeface = Typeface.SANS_SERIF, align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f,
        halo: Int? = null,
    ) {
        tp.textSize = size; tp.typeface = tf; tp.textAlign = align
        val t = if (maxW > 0) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        if (halo != null) {
            tp.style = Paint.Style.STROKE; tp.strokeWidth = size * 0.22f; tp.strokeJoin = Paint.Join.ROUND
            tp.color = halo
            c.drawText(t, x, y, tp)
            tp.style = Paint.Style.FILL
        }
        tp.color = color
        c.drawText(t, x, y, tp)
    }

    /** Tamanho de fonte que faz [s] caber em [largura], sem passar de [max]. */
    private fun caber(s: String, tf: Typeface, max: Float, largura: Float): Float {
        tp.typeface = tf; tp.textSize = max
        return min(max, max * 0.97f * largura / tp.measureText(s).coerceAtLeast(1f))
    }

    private fun rect(c: Canvas, l: Float, t: Float, rr: Float, b: Float, color: Int, rad: Float = 0f) {
        fill.color = color; r.set(l, t, rr, b)
        if (rad > 0) c.drawRoundRect(r, rad, rad, fill) else c.drawRect(r, fill)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = min(w, h * 0.65f) / 100f       // unidade base, escala com a tela
        unidade = u
        val pad = 3.5f * u
        val cargo = tela.cargo
        c.drawColor(BG)
        areaLimpar.setEmpty(); linhasTabela.clear(); caixaMapa = null; malhaAtual = null

        val res: Resultado? = estado.resultado

        // ---------- Cabeçalho (com folga extra no topo) ----------
        var y = max(pad + 3f * u, insetTopo + 2f * u)
        val titulo = "ELEIÇÕES 2026 · ${turno}º TURNO"
        texto(c, if (demo) "$titulo · DADOS DE TESTE" else titulo,
            pad, y + 2.6f * u, 2.4f * u, if (demo) OURO else TXT2, bold)
        val statusTxt = when (val e = estado) {
            is Estado.Ok -> "● AO VIVO  ·  consulta ${fmtHora.format(Date(e.recebidoEm))}"
            is Estado.Erro -> "● SEM CONEXÃO (${e.msg})"
            Estado.Aguardando -> "● AGUARDANDO TSE"
            Estado.Carregando -> "● CONECTANDO…"
        }
        val statusCor = when (estado) {
            is Estado.Ok -> VERDE; is Estado.Erro -> VERMELHO; else -> OURO
        }
        texto(c, statusTxt, w - pad, y + 2.6f * u, 2.2f * u, statusCor, bold, Paint.Align.RIGHT)
        y += 4.5f * u

        // faixa colorida do cargo
        rect(c, pad, y, pad + 1.2f * u, y + 11f * u, cargo.cor, 0.6f * u)
        texto(c, cargo.titulo.uppercase(), pad + 3f * u, y + 6.6f * u, 7f * u, TXT, condensedB)
        val abrang = when (cargo) {
            Cargo.PRESIDENTE -> "Brasil · toque num estado para ver os votos"
            Cargo.GOVERNADOR -> "Rio Grande do Norte · toque num município"
            else -> cargo.abrangencia
        }
        texto(c, abrang, pad + 3f * u, y + 10.6f * u, 3f * u, TXT2, condensed, maxW = w - 2 * pad - 25f * u)

        // selo de vagas
        val vagasLabel = cargo.textoVagas.uppercase()
        tp.textSize = 3.6f * u; tp.typeface = condensedB
        val seloW = tp.measureText(vagasLabel) + 4f * u
        rect(c, w - pad - seloW, y + 0.8f * u, w - pad, y + 6.6f * u, cargo.cor, 1.2f * u)
        texto(c, vagasLabel, w - pad - seloW / 2, y + 5f * u, 3.6f * u, Color.WHITE, condensedB, Paint.Align.CENTER)
        val sub = if (cargo == Cargo.PRESIDENTE) "em disputa" else "em disputa no RN"
        texto(c, sub, w - pad - seloW / 2, y + 9.4f * u, 2.3f * u, TXT2, condensed, Paint.Align.CENTER)
        y += 13.5f * u

        // ---------- Apuração ----------
        rect(c, pad, y, w - pad, y + 12f * u, CARD, 1.5f * u)
        val ap = res?.pctApurado ?: 0.0
        texto(c, "SEÇÕES TOTALIZADAS", pad + 2.5f * u, y + 3.8f * u, 2.3f * u, TXT2, bold)
        texto(c, pct(ap), pad + 2.5f * u, y + 9.6f * u, 6f * u, OURO, condensedB)
        val secTxt = if (res != null && res.secoesTotal > 0)
            "${int(res.secoesTotalizadas)} de ${int(res.secoesTotal)} seções" else "—"
        texto(c, secTxt, w - pad - 2.5f * u, y + 3.8f * u, 2.3f * u, TXT2, condensed, Paint.Align.RIGHT)
        val bl = pad + 28f * u; val br2 = w - pad - 2.5f * u
        val bt = y + 6.3f * u; val bb = y + 8.8f * u
        rect(c, bl, bt, br2, bb, LINHA, 1.25f * u)
        if (ap > 0) rect(c, bl, bt, bl + (br2 - bl) * (ap / 100.0).toFloat().coerceIn(0f, 1f), bb, OURO, 1.25f * u)
        res?.atualizado?.takeIf { it.isNotBlank() }?.let {
            texto(c, "Atualização TSE: $it", w - pad - 2.5f * u, y + 11.3f * u, 1.9f * u, TXT2, condensed, Paint.Align.RIGHT)
        }
        y += 13f * u

        // ---------- Regra do cargo / andamento ou vagas por partido (2 linhas em todas as telas) ----------
        val regra = when {
            turno == 2 -> "2º turno: eleito quem tiver a maioria dos votos válidos."
            cargo == Cargo.PRESIDENTE || cargo == Cargo.GOVERNADOR ->
                "Eleito no 1º turno quem tiver mais da metade dos votos válidos; senão, os 2 mais votados vão ao 2º turno."
            cargo == Cargo.SENADOR -> "Os 2 mais votados são eleitos (maioria simples)."
            else -> null
        }
        val (linha1, linha2) = when {
            cargo.temMapa -> (regra ?: "") to resumoAndamento(cargo)
            regra != null -> regra to ""
            else -> regraProporcional(res, cargo)
        }
        texto(c, linha1, w / 2, y + 2.2f * u, caber(linha1, condensed, 2f * u, w - 2 * pad), TXT2, condensed, Paint.Align.CENTER)
        // a lista de vagas por partido encolhe até caber numa linha
        val tam2 = caber(linha2, condensedB, 2.1f * u, w - 2 * pad).coerceAtLeast(1.5f * u)
        texto(c, linha2, w / 2, y + 5f * u, tam2, clarear(cargo.cor), condensedB, Paint.Align.CENTER, w - 2 * pad)
        y += 6.6f * u

        // ---------- Rodapé: totais (maiores) + indicadores ----------
        val indicH = 5f * u
        val totaisH = 13f * u
        val bottom = h - pad
        val totaisTop = bottom - indicH - totaisH
        val corpoTop = y
        val corpoBot = totaisTop - 1.5f * u

        if (cargo.temMapa) {
            desenharMapa(c, res, cargo, u, pad, w, corpoTop, corpoBot)
        } else if (res == null || res.candidatos.isEmpty()) {
            val (l1, l2) = mensagemEstado(estado)
            val cy = (corpoTop + corpoBot) / 2
            texto(c, l1, w / 2, cy, 4.5f * u, TXT, condensedB, Paint.Align.CENTER, w - 2 * pad)
            texto(c, l2, w / 2, cy + 5f * u, 2.6f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        } else {
            desenharLista(c, res, cargo, u, pad, w, corpoTop, corpoBot)
        }

        // totais da região selecionada (quando já carregada) ou do total
        val sel = selecao.takeIf { cargo.temMapa }
        val resSel = sel?.let { detalhe?.resultado }
        val regiaoTotais = if (sel != null && resSel != null) Malhas.nome(context, cargo, sel) else null
        desenharTotais(c, blocosVotos(resSel ?: res, regiaoTotais), u, pad, w, totaisTop, totaisTop + totaisH)
        desenharIndicadores(c, cargo, u, pad, w, bottom - indicH, bottom)
    }

    private fun mensagemEstado(e: Estado?): Pair<String, String> = when (e) {
        Estado.Aguardando -> "Aguardando divulgação do TSE" to "Os resultados começam a ser publicados após as 17h (horário de Brasília)."
        is Estado.Erro -> "Sem conexão com o TSE" to "Tentando novamente a cada 5 segundos…"
        else -> "Conectando ao TSE…" to "resultados.tse.jus.br"
    }

    private fun resumoAndamento(cargo: Cargo): String {
        val regioes = andamento.filter { it.codigo != "zz" }
        if (regioes.isEmpty()) return ""
        val iniciados = regioes.count { it.pct > 0 }
        val concluidos = regioes.count { it.pct >= 100.0 }
        return if (cargo == Cargo.PRESIDENTE) {
            val ext = andamento.firstOrNull { it.codigo == "zz" }?.let { " · Exterior ${pct(it.pct)}" } ?: ""
            "Estados com apuração: $iniciados de ${regioes.size} · Concluídos: $concluidos$ext"
        } else {
            "Municípios com apuração: $iniciados de ${regioes.size} · Concluídos: $concluidos"
        }
    }

    /** 4 blocos grandes: válidos, brancos, nulos e abstenção. */
    private data class Bloco(val rotulo: String, val valor: String, val sub: String, val cor: Int)

    private fun blocosVotos(res: Resultado?, regiao: String?): List<Bloco> {
        fun b(r: String, p: Double?, v: Long?, cor: Int) = Bloco(
            r, p?.let { pct(it) } ?: "—",
            v?.let { "${int(it)} votos" + (regiao?.let { reg -> " · $reg" } ?: "") } ?: "", cor,
        )
        return listOf(
            b("VÁLIDOS", res?.pctValidos, res?.validos, VERDE),
            b("BRANCOS", res?.pctBrancos, res?.brancos, CINZA),
            b("NULOS", res?.pctNulos, res?.nulos, VERMELHO),
            b("ABSTENÇÃO", res?.pctAbstencao, res?.abstencao, AZUL),
        )
    }

    private fun desenharTotais(c: Canvas, blocos: List<Bloco>, u: Float, pad: Float, w: Float, top: Float, bot: Float) {
        val gap = 1.2f * u
        val bw = (w - 2 * pad - gap * (blocos.size - 1)) / blocos.size
        val bh = bot - top
        blocos.forEachIndexed { i, b ->
            val l = pad + i * (bw + gap)
            rect(c, l, top, l + bw, bot, CARD, 1.5f * u)
            rect(c, l, top, l + bw, top + 0.6f * u, b.cor, 0.3f * u)
            val cx = l + bw / 2
            texto(c, b.rotulo, cx, top + bh * 0.27f, caber(b.rotulo, bold, 2.3f * u, bw - 2 * u), TXT2, bold,
                Paint.Align.CENTER, bw - 2 * u)
            texto(c, b.valor, cx, top + bh * 0.66f, 5.2f * u, b.cor, condensedB, Paint.Align.CENTER, bw - 1.5f * u)
            texto(c, b.sub, cx, top + bh * 0.88f, caber(b.sub, condensed, 2.1f * u, bw - 1.5f * u).coerceAtLeast(1.5f * u),
                TXT, condensed, Paint.Align.CENTER, bw - 1.5f * u)
        }
    }

    /** Indicadores de tela, fonte e, no canto inferior direito, o botão de pausa. */
    private fun desenharIndicadores(c: Canvas, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float) {
        val dot = 1.1f * u; val gap = 1.2f * u
        val pillW = 6f * u
        val cy = (top + bot) / 2
        val fy = cy - dot
        val totW = (total - 1) * (2 * dot + gap) + pillW
        var dx = w / 2 - totW / 2
        for (i in 0 until total) {
            if (i == indice) {
                rect(c, dx, fy, dx + pillW, fy + 2 * dot, LINHA, dot)
                rect(c, dx, fy, dx + pillW * progressoTela.coerceIn(0f, 1f), fy + 2 * dot,
                    if (pausado) TXT2 else cargo.cor, dot)
                dx += pillW + gap
            } else {
                fill.color = LINHA; c.drawCircle(dx + dot, fy + dot, dot, fill)
                dx += 2 * dot + gap
            }
        }
        texto(c, "Fonte: TSE · resultados.tse.jus.br", pad, fy + 2 * dot, 1.8f * u, TXT2, condensed)

        // botão de pausa / continuar
        val raio = 2.4f * u
        val bx = w - pad - raio
        fill.color = if (pausado) OURO else CARD2
        c.drawCircle(bx, cy, raio, fill)
        fill.color = if (pausado) BG else TXT
        if (pausado) {
            val p = Path().apply {
                moveTo(bx - 0.7f * u, cy - 1.1f * u); lineTo(bx + 1.1f * u, cy); lineTo(bx - 0.7f * u, cy + 1.1f * u); close()
            }
            c.drawPath(p, fill)
        } else {
            rect(c, bx - 0.85f * u, cy - 1f * u, bx - 0.3f * u, cy + 1f * u, TXT, 0.15f * u)
            rect(c, bx + 0.3f * u, cy - 1f * u, bx + 0.85f * u, cy + 1f * u, TXT, 0.15f * u)
        }
        areaPausa.set(bx - raio * 1.8f, cy - raio * 1.8f, w, bot + pad)   // área de toque generosa

        val dir = when {
            pausado -> "PAUSADO"
            cargo.proporcional -> "Página ${tela.pagina + 1}/2"
            else -> ""
        }
        texto(c, dir, bx - raio - 1.2f * u, fy + 2 * dot, 1.9f * u, if (pausado) OURO else TXT2,
            if (pausado) bold else condensed, Paint.Align.RIGHT)
    }

    private fun regraProporcional(res: Resultado?, cargo: Cargo): Pair<String, String> {
        if (res == null || res.qe <= 0)
            return "Proporcional: ${cargo.vagas} vagas distribuídas pelo quociente partidário" to
                "Quociente eleitoral e vagas por partido aparecem quando houver votos apurados"
        val fonte = if (res.vagasOficiais) "distribuição oficial do TSE" else "projeção pelo quociente partidário e sobras"
        val vagas = res.vagasPorAgr.joinToString(" · ") { (sg, n) -> "$sg $n" }
        return "Quociente eleitoral ${int(res.qe)} votos  ·  em destaque: dentro das vagas ($fonte)" to
            "VAGAS: $vagas"
    }

    private fun chipSituacao(cand: Candidato): Pair<String, Int>? {
        val s = cand.situacao.lowercase(br)
        return when {
            cand.eleito -> "ELEITO" to VERDE
            s.contains("2º turno") || s.contains("2o turno") || s.contains("segundo turno") -> "2º TURNO" to OURO
            cand.naVaga -> "NA VAGA" to OURO
            s.contains("suplente") -> "SUPLENTE" to TXT2
            else -> null
        }
    }

    /** Lista para os cargos sem mapa: 15 linhas de mesma altura por tela. */
    private fun desenharLista(
        c: Canvas, res: Resultado, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float,
    ) {
        val ini = tela.pagina * POR_PAGINA
        val lista = res.candidatos.drop(ini).take(POR_PAGINA)
        val gap = 0.6f * u
        val rowH = (bot - top + gap) / POR_PAGINA
        if (lista.isEmpty()) {
            texto(c, "Sem candidatos nesta faixa (${ini + 1}º ao ${ini + POR_PAGINA}º)", w / 2, (top + bot) / 2,
                3f * u, TXT2, condensed, Paint.Align.CENTER)
            return
        }
        val maxPct = (res.candidatos.firstOrNull()?.pct ?: 1.0).coerceAtLeast(0.01)
        var y = top
        lista.forEachIndexed { i, cand ->
            val pos = ini + i + 1
            val t = y; val b = y + rowH - gap
            val hh = b - t
            // majoritários: os N primeiros; proporcionais: distribuição do TSE ou projeção do QP
            val dentro = if (cargo.proporcional) cand.naVaga else pos <= cargo.vagas && cand.votos > 0
            rect(c, pad, t, w - pad, b, if (dentro) CARD2 else CARD, 1.2f * u)
            when {
                cand.eleito -> rect(c, pad, t, pad + 0.9f * u, b, VERDE, 0.45f * u)
                dentro -> rect(c, pad, t, pad + 0.9f * u, b, cargo.cor, 0.45f * u)
            }

            texto(c, "$pos", pad + 6f * u, t + hh * 0.66f, hh * 0.46f,
                if (dentro || cand.eleito) TXT else TXT2, condensedB, Paint.Align.RIGHT)

            val nx = pad + 8.5f * u
            val pctX = w - pad - 2f * u
            val nomeMax = pctX - nx - 30f * u
            val nome = if (cand.eleito) "✔ ${cand.nome}" else cand.nome
            texto(c, nome, nx, t + hh * 0.50f, hh * 0.42f, if (cand.eleito) VERDE else TXT, condensedB, maxW = nomeMax)
            texto(c, "${cand.numero} · ${cand.partido}", nx, t + hh * 0.82f, hh * 0.24f, TXT2, condensed, maxW = nomeMax)

            texto(c, pct(cand.pct), pctX, t + hh * 0.52f, hh * 0.44f,
                if (dentro) clarear(cargo.cor) else TXT, condensedB, Paint.Align.RIGHT)
            texto(c, "${int(cand.votos)} votos", pctX, t + hh * 0.83f, hh * 0.24f, TXT2, condensed, Paint.Align.RIGHT)

            chipSituacao(cand)?.let { (txt, cor) ->
                tp.textSize = hh * 0.22f; tp.typeface = bold
                val cw = tp.measureText(txt) + 2f * u
                val cx = pctX - 19f * u - cw
                rect(c, cx, t + hh * 0.22f, cx + cw, t + hh * 0.52f, cor, 0.8f * u)
                texto(c, txt, cx + cw / 2, t + hh * 0.45f, hh * 0.22f, BG, bold, Paint.Align.CENTER)
            }

            val bl = nx; val brr = w - pad - 2f * u
            val by = b - hh * 0.08f
            rect(c, bl, by, brr, by + hh * 0.05f, LINHA, hh * 0.025f)
            rect(c, bl, by, bl + (brr - bl) * (cand.pct / maxPct).toFloat().coerceIn(0f, 1f), by + hh * 0.05f,
                if (dentro || cand.eleito) cargo.cor else TXT2, hh * 0.025f)

            y += rowH
        }
    }

    // ---------------------------------------------------------------- telas com mapa

    /** Branco sem apuração → verde com 100%; qualquer seção apurada já dá um leve tom. */
    private fun corApuracao(p: Double): Int {
        if (p <= 0.0) return Color.WHITE
        val t = (0.08 + 0.92 * (p / 100.0).coerceIn(0.0, 1.0)).toFloat()
        val fim = Color.parseColor("#12844A")
        return Color.rgb(
            (255 + (Color.red(fim) - 255) * t).toInt(),
            (255 + (Color.green(fim) - 255) * t).toInt(),
            (255 + (Color.blue(fim) - 255) * t).toInt(),
        )
    }

    private fun textoSobre(p: Double) = if (p >= 45.0) Color.WHITE else BG

    private fun fmtPct1(v: Double): String = when {
        v <= 0.0 -> "0%"
        v >= 100.0 -> "100%"
        v < 10.0 -> String.format(br, "%.2f%%", v)
        else -> String.format(br, "%.1f%%", v)
    }

    /**
     * Mapa colorido pelo andamento (UFs ou municípios do RN), painel com os votos dos candidatos
     * (total ou da região tocada) e tabela tocável das regiões.
     *  - Mapa "alto" (Brasil): mapa à esquerda e painel à direita; tabela de 2 colunas embaixo.
     *  - Mapa "largo" (RN): mapa na largura toda em cima; painel e tabela de 1 coluna lado a lado embaixo.
     */
    private fun desenharMapa(
        c: Canvas, res: Resultado?, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float,
    ) {
        val malha = Malhas.doCargo(context, cargo) ?: return
        malhaAtual = malha
        val porRegiao = andamento.associateBy { it.codigo }
        val gap = 1.5f * u

        if (malha.proporcao > 1.3f) {
            val mapaH = min((bot - top) * 0.46f, (w - 2 * pad) / malha.proporcao)
            val mapaW = mapaH * malha.proporcao
            val caixa = RectF((w - mapaW) / 2, top, (w + mapaW) / 2, top + mapaH)
            desenharRegioes(c, malha, caixa, cargo, porRegiao, u)
            val baixo = top + mapaH + gap
            val meio = pad + (w - 2 * pad - gap) / 2
            desenharCandidatos(c, res, cargo, u, pad, baixo, meio, bot)
            desenharTabela(c, cargo, malha, porRegiao, u, meio + gap, w - pad, baixo, bot, colunas = 1)
        } else {
            val altoTopo = (bot - top) * 0.56f
            val mapaW = min(altoTopo * malha.proporcao, (w - 2 * pad) * 0.58f)
            val mapaH = min(altoTopo, mapaW / malha.proporcao)
            val caixa = RectF(pad, top + (altoTopo - mapaH) / 2, pad + mapaW, top + (altoTopo + mapaH) / 2)
            desenharRegioes(c, malha, caixa, cargo, porRegiao, u)
            desenharCandidatos(c, res, cargo, u, pad + mapaW + 2.5f * u, top, w - pad, top + altoTopo)
            desenharTabela(c, cargo, malha, porRegiao, u, pad, w - pad, top + altoTopo + gap, bot, colunas = 2)
        }
    }

    private fun desenharRegioes(
        c: Canvas, malha: Malha, caixa: RectF, cargo: Cargo, porRegiao: Map<String, Andamento>, u: Float,
    ) {
        caixaMapa = caixa
        val paths = malha.caminhos(caixa)
        traco.color = BG
        traco.strokeWidth = if (cargo == Cargo.PRESIDENTE) 0.25f * u else 0.12f * u
        for ((cod, path) in paths) {
            fill.color = corApuracao(porRegiao[cod]?.pct ?: 0.0)
            c.drawPath(path, fill)
            c.drawPath(path, traco)
        }
        selecao?.let { paths[it] }?.let { p ->
            traco.color = OURO; traco.strokeWidth = 0.6f * u
            c.drawPath(p, traco)
        }
        if (cargo == Cargo.PRESIDENTE) {
            for (cod in malha.codigos) {
                val p = porRegiao[cod]?.pct ?: 0.0
                val pt = malha.centro(cod, caixa) ?: continue
                val cor = textoSobre(p)
                val halo = if (cor == Color.WHITE) Color.parseColor("#0B4D2B") else Color.WHITE
                if (cod in Malhas.UFS_PEQUENAS) {
                    texto(c, cod.uppercase(), pt.x, pt.y + 0.6f * u, 1.7f * u, cor, bold, Paint.Align.CENTER, halo = halo)
                } else {
                    texto(c, cod.uppercase(), pt.x, pt.y, 2.2f * u, cor, bold, Paint.Align.CENTER, halo = halo)
                    texto(c, fmtPct1(p), pt.x, pt.y + 2.3f * u, 1.8f * u, cor, condensedB, Paint.Align.CENTER, halo = halo)
                }
            }
        } else {
            // municípios: sem rótulos (são 167); só o nome do selecionado, sempre dentro do mapa
            selecao?.let { cod ->
                malha.centro(cod, caixa)?.let { pt ->
                    val nome = malha.nomes[cod] ?: cod
                    val fs = 2.4f * u
                    tp.textSize = fs; tp.typeface = bold
                    val meia = tp.measureText(nome) / 2 + 0.5f * u
                    val x = pt.x.coerceIn(caixa.left + meia, caixa.right - meia)
                    val y = (pt.y - 1.4f * u).coerceAtLeast(caixa.top + fs)
                    texto(c, nome, x, y, fs, BG, bold, Paint.Align.CENTER, halo = Color.WHITE)
                }
            }
        }
    }

    private fun desenharCandidatos(
        c: Canvas, total: Resultado?, cargo: Cargo, u: Float, x0: Float, top: Float, x1: Float, bot: Float,
    ) {
        val sel = selecao
        val fonte = if (sel != null) detalhe?.resultado else total
        val nome = (if (sel != null) Malhas.nome(context, cargo, sel) else cargo.abrangencia).uppercase()
        rect(c, x0, top, x1, bot, CARD, 1.5f * u)
        val pl = x0 + 2f * u
        val pr = x1 - 2f * u

        // cabeçalho do painel: nome da região, andamento e "ver total" (ou dica)
        texto(c, nome, pl, top + 4.6f * u, caber(nome, condensedB, 3.8f * u, pr - pl).coerceAtLeast(2.8f * u),
            if (sel != null) OURO else TXT, condensedB, maxW = pr - pl)
        val subt = when {
            fonte != null -> "${pct(fonte.pctApurado)} das seções totalizadas" +
                (fonte.secoesTotal.takeIf { it > 0 }?.let { " · ${int(fonte.secoesTotalizadas)} de ${int(it)}" } ?: "")
            else -> mensagemEstado(if (sel != null) detalhe else estado).first
        }
        texto(c, subt, pl, top + 7.6f * u, caber(subt, condensed, 2f * u, pr - pl), TXT2, condensed)
        if (sel != null) {
            val rotulo = "✕  VER TOTAL " + if (cargo == Cargo.PRESIDENTE) "DO BRASIL" else "DO RN"
            tp.textSize = 1.9f * u; tp.typeface = bold
            val bw = tp.measureText(rotulo) + 3f * u
            rect(c, pl, top + 8.8f * u, pl + bw, top + 12f * u, OURO, 1f * u)
            texto(c, rotulo, pl + bw / 2, top + 11f * u, 1.9f * u, BG, bold, Paint.Align.CENTER)
            areaLimpar.set(x0, top + 7.8f * u, pl + bw + 2f * u, top + 13f * u)
        } else {
            texto(c, if (cargo == Cargo.PRESIDENTE) "Total do Brasil · toque num estado para filtrar"
                else "Total do RN · toque num município para filtrar",
                pl, top + 10.6f * u, 1.8f * u, clarear(cargo.cor), condensed, maxW = pr - pl)
        }

        // candidatos
        val listaTop = top + 13.5f * u
        val cands = fonte?.candidatos.orEmpty()
        if (cands.isEmpty()) {
            val msg = if (sel != null && detalhe == Estado.Carregando) "Carregando…" else mensagemEstado(if (sel != null) detalhe else estado).second
            texto(c, msg, (x0 + x1) / 2, (listaTop + bot) / 2, 2.4f * u, TXT2, condensed, Paint.Align.CENTER, x1 - x0 - 4f * u)
            return
        }
        val disp = bot - listaTop - 1.5f * u
        val maxLinhas = max(1, (disp / (4f * u)).toInt())
        val lista = cands.take(maxLinhas)
        val rowH = min(disp / lista.size, 18f * u)
        val compacto = rowH < 6.5f * u
        val temVotos = lista.any { it.votos > 0 }
        lista.forEachIndexed { i, cand ->
            val t = listaTop + i * rowH
            val b = t + rowH - 0.5f * u
            val hh = b - t
            val lider = temVotos && i == 0
            if (lider) rect(c, x0 + 0.8f * u, t, x1 - 0.8f * u, b, CARD2, 1f * u)
            rect(c, x0 + 0.8f * u, t, x0 + 1.5f * u, b, if (cand.eleito) VERDE else if (lider) cargo.cor else LINHA, 0.35f * u)
            val nomeTxt = if (cand.eleito) "✔ ${cand.nome}" else cand.nome
            val corNome = if (cand.eleito) VERDE else TXT
            val corPct = if (lider || cand.eleito) clarear(cargo.cor) else TXT
            val barT: Float
            if (compacto) {
                val fs = hh * 0.42f
                texto(c, pct(cand.pct), pr, t + hh * 0.55f, fs, corPct, condensedB, Paint.Align.RIGHT)
                tp.textSize = fs; tp.typeface = condensedB
                val pctW = tp.measureText("00,00%") + 1.5f * u
                texto(c, nomeTxt, pl, t + hh * 0.55f, fs, corNome, condensedB, maxW = pr - pl - pctW)
                barT = t + hh * 0.72f
            } else {
                // nome numa linha inteira; abaixo, percentual grande à esquerda e número/partido/votos à direita
                val fsNome = min(hh * 0.24f, 4.2f * u)
                val fsPct = min(hh * 0.34f, 7f * u)
                val fsInfo = fsNome * 0.6f
                var nomeMax = pr - pl
                val chip = when {
                    cand.eleito -> "ELEITO" to VERDE
                    lider && turno == 2 -> "LIDERANDO" to OURO
                    else -> null
                }
                chip?.let { (txt, cor) ->
                    tp.textSize = fsInfo; tp.typeface = bold
                    val cw = tp.measureText(txt) + 2f * u
                    rect(c, pr - cw, t + hh * 0.1f, pr, t + hh * 0.1f + fsInfo * 1.6f, cor, 0.8f * u)
                    texto(c, txt, pr - cw / 2, t + hh * 0.1f + fsInfo * 1.15f, fsInfo, BG, bold, Paint.Align.CENTER)
                    nomeMax -= cw + 1.5f * u
                }
                texto(c, nomeTxt, pl, t + hh * 0.3f, caber(nomeTxt, condensedB, fsNome, nomeMax), corNome, condensedB)
                texto(c, pct(cand.pct), pl, t + hh * 0.66f, fsPct, corPct, condensedB)
                texto(c, "${cand.numero} · ${cand.partido}", pr, t + hh * 0.47f, fsInfo, TXT2, condensed, Paint.Align.RIGHT)
                texto(c, "${int(cand.votos)} votos", pr, t + hh * 0.65f, fsInfo, TXT, condensed, Paint.Align.RIGHT)
                barT = t + hh * 0.78f
            }
            val barH = if (compacto) 0.6f * u else min(hh * 0.1f, 1.4f * u)
            rect(c, pl, barT, pr, barT + barH, LINHA, barH / 2)
            rect(c, pl, barT, pl + (pr - pl) * (cand.pct / 100).toFloat().coerceIn(0f, 1f), barT + barH,
                if (lider || cand.eleito) cargo.cor else TXT2, barH / 2)
        }
    }

    /** Tabela tocável das regiões: UFs + exterior (Presidente) ou os maiores municípios (Governador). */
    private fun desenharTabela(
        c: Canvas, cargo: Cargo, malha: Malha, porRegiao: Map<String, Andamento>,
        u: Float, x0: Float, x1: Float, top: Float, bot: Float, colunas: Int,
    ) {
        val presidente = cargo == Cargo.PRESIDENTE
        val linhasPorColuna = LINHAS_TABELA / 2
        val qtd = linhasPorColuna * colunas
        val titulo = if (presidente) "ESTADOS E EXTERIOR · toque para ver os votos"
        else "$qtd MAIORES MUNICÍPIOS · toque para ver os votos"
        texto(c, titulo, x0, top + 1.6f * u, caber(titulo, bold, 1.8f * u, x1 - x0), TXT2, bold)
        val tTop = top + 2.6f * u

        val codigos: List<String> = if (presidente) {
            (malha.codigos + "zz").sortedBy { Malhas.nome(context, cargo, it) }
        } else if (andamento.isNotEmpty()) {
            andamento.sortedByDescending { it.eleitores }.take(qtd).map { it.codigo }
        } else {
            malha.codigos.sortedBy { malha.nomes[it] }.take(qtd)
        }
        val porColuna = (codigos.size + colunas - 1) / colunas
        val gap = 1.5f * u
        val colW = (x1 - x0 - gap * (colunas - 1)) / colunas
        val rowH = (bot - tTop) / porColuna
        codigos.forEachIndexed { i, cod ->
            val p = porRegiao[cod]?.pct ?: 0.0
            val col = i / porColuna
            val lin = i % porColuna
            val l = x0 + col * (colW + gap)
            val rr = l + colW
            val t = tTop + lin * rowH
            val b = t + rowH - 0.35f * u
            val my = (t + b) / 2
            linhasTabela += RectF(l, t, rr, b) to cod
            rect(c, l, t, rr, b, if (lin % 2 == 0) CARD else CARD2, 0.6f * u)
            if (cod == selecao) {
                traco.color = OURO; traco.strokeWidth = 0.35f * u
                r.set(l, t, rr, b); c.drawRoundRect(r, 0.6f * u, 0.6f * u, traco)
            }
            // selo na cor do mapa (com a sigla, no caso das UFs)
            val seloW = if (presidente) 4.8f * u else 2.4f * u
            rect(c, l + 0.5f * u, t + 0.35f * u, l + 0.5f * u + seloW, b - 0.35f * u, corApuracao(p), 0.5f * u)
            if (presidente) {
                texto(c, if (cod == "zz") "EXT" else cod.uppercase(), l + 0.5f * u + seloW / 2, my + 0.75f * u, 1.9f * u,
                    textoSobre(p), bold, Paint.Align.CENTER)
            }
            val nx = l + seloW + 1.5f * u
            texto(c, Malhas.nome(context, cargo, cod), nx, my + 0.75f * u, 2f * u,
                if (cod == selecao) OURO else TXT, condensed, maxW = l + colW * 0.5f - nx)
            val bl = l + colW * 0.52f
            val br2 = l + colW * 0.74f
            rect(c, bl, my - 0.5f * u, br2, my + 0.5f * u, LINHA, 0.5f * u)
            if (p > 0) {
                rect(c, bl, my - 0.5f * u, bl + (br2 - bl) * (p / 100).toFloat().coerceIn(0f, 1f),
                    my + 0.5f * u, corApuracao(maxOf(p, 30.0)), 0.5f * u)
            }
            texto(c, pct(p), rr - 0.8f * u, my + 0.75f * u, 2.1f * u, if (p >= 100) VERDE else TXT,
                condensedB, Paint.Align.RIGHT)
        }
    }

    private fun clarear(cor: Int): Int =
        Color.rgb((Color.red(cor) + 255) / 2, (Color.green(cor) + 255) / 2, (Color.blue(cor) + 255) / 2)
}
