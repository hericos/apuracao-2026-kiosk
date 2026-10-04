package br.rn.apuracao

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
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
        /** Todas as telas usam a mesma grade de linhas, para manter as proporções iguais. */
        const val POR_PAGINA = 15
    }

    var tela: Tela = Tela(Cargo.PRESIDENTE, 0)
    var estado: Estado = Estado.Carregando
    var indice = 0
    var total = 1
    var progressoTela = 0f      // 0..1 até a próxima troca
    var demo = false

    private var insetTopo = 0   // recorte da câmera/notch, quando houver

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
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun pct(v: Double) = fmtPct.format(v) + "%"
    private fun int(v: Long) = fmtInt.format(v)

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        insetTopo = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    private fun texto(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        tf: Typeface = Typeface.SANS_SERIF, align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f,
    ) {
        tp.textSize = size; tp.color = color; tp.typeface = tf; tp.textAlign = align
        val t = if (maxW > 0) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        c.drawText(t, x, y, tp)
    }

    private fun rect(c: Canvas, l: Float, t: Float, rr: Float, b: Float, color: Int, rad: Float = 0f) {
        fill.color = color; r.set(l, t, rr, b)
        if (rad > 0) c.drawRoundRect(r, rad, rad, fill) else c.drawRect(r, fill)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = min(w, h * 0.65f) / 100f       // unidade base, escala com a tela
        val pad = 3.5f * u
        val cargo = tela.cargo
        c.drawColor(BG)

        val res: Resultado? = when (val e = estado) {
            is Estado.Ok -> e.r
            is Estado.Erro -> e.ultimo
            else -> null
        }

        // ---------- Cabeçalho (com folga extra no topo) ----------
        var y = max(pad + 3f * u, insetTopo + 2f * u)
        texto(c, if (demo) "ELEIÇÕES 2026 · 1º TURNO · DADOS DE TESTE" else "ELEIÇÕES 2026 · 1º TURNO",
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
        texto(c, cargo.abrangencia, pad + 3f * u, y + 10.6f * u, 3f * u, TXT2, condensed)

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

        // ---------- Regra do cargo / vagas por partido (2 linhas em todas as telas) ----------
        val (linha1, linha2) = when (cargo) {
            Cargo.PRESIDENTE, Cargo.GOVERNADOR ->
                "Eleito no 1º turno quem tiver mais da metade dos votos válidos;" to
                    "senão, os 2 mais votados disputam o 2º turno."
            Cargo.SENADOR -> "Os 2 mais votados são eleitos (maioria simples)." to ""
            else -> regraProporcional(res, cargo)
        }
        texto(c, linha1, w / 2, y + 2.2f * u, 2f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        // a lista de vagas por partido encolhe até caber numa linha
        tp.typeface = condensedB; tp.textSize = 2.1f * u
        val tam2 = (2.1f * u * (w - 2 * pad) / tp.measureText(linha2).coerceAtLeast(1f)).coerceIn(1.5f * u, 2.1f * u)
        texto(c, linha2, w / 2, y + 5f * u, tam2, clarear(cargo.cor), condensedB, Paint.Align.CENTER, w - 2 * pad)
        y += 6.6f * u

        // ---------- Rodapé: totais (maiores) + indicadores ----------
        val indicH = 5f * u
        val totaisH = 13f * u
        val bottom = h - pad
        val totaisTop = bottom - indicH - totaisH
        val corpoTop = y
        val corpoBot = totaisTop - 1.5f * u

        if (res == null || res.candidatos.isEmpty()) {
            val (l1, l2) = when (estado) {
                Estado.Aguardando -> "Aguardando divulgação do TSE" to "Os resultados começam a ser publicados após as 17h (horário de Brasília)."
                is Estado.Erro -> "Sem conexão com o TSE" to "Tentando novamente a cada 5 segundos…"
                else -> "Conectando ao TSE…" to "resultados.tse.jus.br"
            }
            val cy = (corpoTop + corpoBot) / 2
            texto(c, l1, w / 2, cy, 4.5f * u, TXT, condensedB, Paint.Align.CENTER, w - 2 * pad)
            texto(c, l2, w / 2, cy + 5f * u, 2.6f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        } else {
            desenharLista(c, res, cargo, u, pad, w, corpoTop, corpoBot)
        }

        desenharTotais(c, res, u, pad, w, totaisTop, totaisTop + totaisH)
        desenharIndicadores(c, cargo, u, pad, w, bottom - indicH, bottom)
    }

    /** 4 blocos grandes: válidos, brancos, nulos e abstenção. */
    private fun desenharTotais(c: Canvas, res: Resultado?, u: Float, pad: Float, w: Float, top: Float, bot: Float) {
        data class Bloco(val rotulo: String, val pct: Double?, val votos: Long?, val cor: Int)
        val blocos = listOf(
            Bloco("VÁLIDOS", res?.pctValidos, res?.validos, VERDE),
            Bloco("BRANCOS", res?.pctBrancos, res?.brancos, CINZA),
            Bloco("NULOS", res?.pctNulos, res?.nulos, VERMELHO),
            Bloco("ABSTENÇÃO", res?.pctAbstencao, res?.abstencao, AZUL),
        )
        val gap = 1.2f * u
        val bw = (w - 2 * pad - gap * (blocos.size - 1)) / blocos.size
        val bh = bot - top
        blocos.forEachIndexed { i, b ->
            val l = pad + i * (bw + gap)
            rect(c, l, top, l + bw, bot, CARD, 1.5f * u)
            rect(c, l, top, l + bw, top + 0.6f * u, b.cor, 0.3f * u)
            val cx = l + bw / 2
            texto(c, b.rotulo, cx, top + bh * 0.27f, 2.3f * u, TXT2, bold, Paint.Align.CENTER, bw - 2 * u)
            texto(c, b.pct?.let { pct(it) } ?: "—", cx, top + bh * 0.66f, 5.2f * u, b.cor, condensedB,
                Paint.Align.CENTER, bw - 1.5f * u)
            texto(c, b.votos?.let { "${int(it)} votos" } ?: "", cx, top + bh * 0.88f, 2.3f * u, TXT, condensed,
                Paint.Align.CENTER, bw - 1.5f * u)
        }
    }

    private fun desenharIndicadores(c: Canvas, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float) {
        val dot = 1.1f * u; val gap = 1.2f * u
        val pillW = 6f * u
        val fy = (top + bot) / 2 - dot
        val totW = (total - 1) * (2 * dot + gap) + pillW
        var dx = w / 2 - totW / 2
        for (i in 0 until total) {
            if (i == indice) {
                rect(c, dx, fy, dx + pillW, fy + 2 * dot, LINHA, dot)
                rect(c, dx, fy, dx + pillW * progressoTela.coerceIn(0f, 1f), fy + 2 * dot, cargo.cor, dot)
                dx += pillW + gap
            } else {
                fill.color = LINHA; c.drawCircle(dx + dot, fy + dot, dot, fill)
                dx += 2 * dot + gap
            }
        }
        texto(c, "Fonte: TSE · resultados.tse.jus.br", pad, fy + 2 * dot, 1.8f * u, TXT2, condensed)
        val pagTxt = if (cargo.proporcional) "Página ${tela.pagina + 1}/2" else ""
        texto(c, pagTxt, w - pad, fy + 2 * dot, 1.8f * u, TXT2, condensed, Paint.Align.RIGHT)
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

    /** Lista única para todos os cargos: 15 linhas de mesma altura por tela. */
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

            // posição
            texto(c, "$pos", pad + 6f * u, t + hh * 0.66f, hh * 0.46f,
                if (dentro || cand.eleito) TXT else TXT2, condensedB, Paint.Align.RIGHT)

            // nome e número · partido
            val nx = pad + 8.5f * u
            val pctX = w - pad - 2f * u
            val nomeMax = pctX - nx - 30f * u
            val nome = if (cand.eleito) "✔ ${cand.nome}" else cand.nome
            texto(c, nome, nx, t + hh * 0.50f, hh * 0.42f, if (cand.eleito) VERDE else TXT, condensedB, maxW = nomeMax)
            texto(c, "${cand.numero} · ${cand.partido}", nx, t + hh * 0.82f, hh * 0.24f, TXT2, condensed, maxW = nomeMax)

            // percentual e votos
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

            // barra de votos (relativa ao 1º colocado)
            val bl = nx; val brr = w - pad - 2f * u
            val by = b - hh * 0.08f
            rect(c, bl, by, brr, by + hh * 0.05f, LINHA, hh * 0.025f)
            rect(c, bl, by, bl + (brr - bl) * (cand.pct / maxPct).toFloat().coerceIn(0f, 1f), by + hh * 0.05f,
                if (dentro || cand.eleito) cargo.cor else TXT2, hh * 0.025f)

            y += rowH
        }
    }

    private fun clarear(cor: Int): Int =
        Color.rgb((Color.red(cor) + 255) / 2, (Color.green(cor) + 255) / 2, (Color.blue(cor) + 255) / 2)
}
