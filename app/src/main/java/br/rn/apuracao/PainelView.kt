package br.rn.apuracao

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Uma "tela" do rodízio: um cargo e uma página (0 = 1º ao 30º, 1 = 31º ao 60º). */
data class Tela(val cargo: Cargo, val pagina: Int)

class PainelView(ctx: Context) : View(ctx) {

    var tela: Tela = Tela(Cargo.PRESIDENTE, 0)
    var estado: Estado = Estado.Carregando
    var indice = 0
    var total = 1
    var progressoTela = 0f      // 0..1 até a próxima troca
    var demo = false

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

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedB = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun pct(v: Double) = fmtPct.format(v) + "%"
    private fun int(v: Long) = fmtInt.format(v)

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

        // ---------- Cabeçalho ----------
        var y = pad
        texto(c, if (demo) "ELEIÇÕES 2026 · 1º TURNO · MODO DEMONSTRAÇÃO" else "ELEIÇÕES 2026 · 1º TURNO",
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
        val sub = if (cargo.proporcional) "em disputa no RN" else if (cargo == Cargo.PRESIDENTE) "em disputa" else "em disputa no RN"
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
        y += 14f * u

        // ---------- Rodapé (reserva espaço) ----------
        val rodapeH = 9f * u
        val corpoTop = y
        val corpoBot = h - pad - rodapeH

        if (res == null || res.candidatos.isEmpty()) {
            val (l1, l2) = when (estado) {
                Estado.Aguardando -> "Aguardando divulgação do TSE" to "Os resultados começam a ser publicados após as 17h (horário de Brasília)."
                is Estado.Erro -> "Sem conexão com o TSE" to "Tentando novamente a cada 5 segundos…"
                else -> "Conectando ao TSE…" to "resultados.tse.jus.br"
            }
            val cy = (corpoTop + corpoBot) / 2
            texto(c, l1, w / 2, cy, 4.5f * u, TXT, condensedB, Paint.Align.CENTER, w - 2 * pad)
            texto(c, l2, w / 2, cy + 5f * u, 2.6f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        } else if (cargo.proporcional) {
            desenharProporcional(c, res, cargo, u, pad, w, corpoTop, corpoBot)
        } else {
            desenharMajoritario(c, res, cargo, u, pad, w, corpoTop, corpoBot)
        }

        // ---------- Rodapé ----------
        var fy = h - pad - rodapeH + 1.5f * u
        if (res != null) {
            val tot = "Válidos ${int(res.validos)} (${pct(res.pctValidos)})   ·   Brancos ${pct(res.pctBrancos)}   ·   " +
                "Nulos ${pct(res.pctNulos)}   ·   Abstenção ${pct(res.pctAbstencao)}"
            texto(c, tot, w / 2, fy + 2f * u, 2.1f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        }
        fy += 4f * u
        // indicadores de tela
        val dot = 1.1f * u; val gap = 1.2f * u
        val pillW = 6f * u
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

    private fun chipSituacao(cand: Candidato): Pair<String, Int>? {
        val s = cand.situacao.lowercase(br)
        return when {
            cand.eleito -> "ELEITO" to VERDE
            s.contains("2º turno") || s.contains("2o turno") || s.contains("segundo turno") -> "2º TURNO" to OURO
            s.contains("suplente") -> "SUPLENTE" to TXT2
            else -> null
        }
    }

    private fun desenharMajoritario(
        c: Canvas, res: Resultado, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float,
    ) {
        val lista = res.candidatos.take(12)
        val gap = 1.2f * u
        val rowH = min((bot - top + gap) / lista.size, 15f * u)
        var y = top
        val maxPct = (lista.maxOfOrNull { it.pct } ?: 1.0).coerceAtLeast(1.0)
        lista.forEachIndexed { i, cand ->
            val t = y; val b = y + rowH - gap
            val dentro = i < cargo.vagas
            rect(c, pad, t, w - pad, b, if (dentro) CARD2 else CARD, 1.5f * u)
            if (dentro) rect(c, pad, t, pad + 0.9f * u, b, cargo.cor, 0.45f * u)
            val hh = b - t
            // posição
            texto(c, "${i + 1}º", pad + 6f * u, t + hh * 0.62f, hh * 0.34f, if (dentro) TXT else TXT2, condensedB, Paint.Align.CENTER)
            // nome e partido
            val nx = pad + 11.5f * u
            val pctX = w - pad - 2.5f * u
            val nomeMax = pctX - nx - 26f * u
            texto(c, cand.nome, nx, t + hh * 0.46f, hh * 0.30f, TXT, condensedB, maxW = nomeMax)
            texto(c, "${cand.numero} · ${cand.partido}", nx, t + hh * 0.74f, hh * 0.18f, TXT2, condensed, maxW = nomeMax)
            // percentual e votos
            texto(c, pct(cand.pct), pctX, t + hh * 0.52f, hh * 0.34f, if (dentro) cargo.cor.clarear() else TXT, condensedB, Paint.Align.RIGHT)
            texto(c, "${int(cand.votos)} votos", pctX, t + hh * 0.78f, hh * 0.17f, TXT2, condensed, Paint.Align.RIGHT)
            chipSituacao(cand)?.let { (txt, cor) ->
                tp.textSize = hh * 0.16f; tp.typeface = bold
                val cw = tp.measureText(txt) + 2f * u
                val cx = pctX - 22f * u - cw
                rect(c, cx, t + hh * 0.30f, cx + cw, t + hh * 0.52f, cor, 0.8f * u)
                texto(c, txt, cx + cw / 2, t + hh * 0.47f, hh * 0.16f, BG, bold, Paint.Align.CENTER)
            }
            // barra
            val bl = nx; val brr = w - pad - 2.5f * u
            val by = b - hh * 0.12f
            rect(c, bl, by, brr, by + hh * 0.06f, LINHA, hh * 0.03f)
            rect(c, bl, by, bl + (brr - bl) * (cand.pct / maxPct).toFloat().coerceIn(0f, 1f), by + hh * 0.06f,
                if (dentro) cargo.cor else TXT2, hh * 0.03f)
            y += rowH
        }
    }

    private fun desenharProporcional(
        c: Canvas, res: Resultado, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float,
    ) {
        val porPagina = 30
        val ini = tela.pagina * porPagina
        val lista = res.candidatos.drop(ini).take(porPagina)

        // aviso sobre vagas proporcionais
        val aviso = "Sistema proporcional: as ${cargo.vagas} vagas seguem o quociente partidário, não só a ordem de votos. ✔ = eleito confirmado pelo TSE"
        texto(c, aviso, w / 2, top + 1.8f * u, 1.75f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        val tTop = top + 3.2f * u

        val headerH = 3.2f * u
        val rowH = (bot - tTop - headerH) / porPagina
        val fs = rowH * 0.56f
        val xPos = pad + 4.5f * u
        val xNum = pad + 6.5f * u
        val xNome = pad + 15f * u
        val xPart = w - pad - 39f * u
        val xVotos = w - pad - 13f * u
        val xPct = w - pad - 1f * u

        rect(c, pad, tTop, w - pad, tTop + headerH, CARD2, 0.8f * u)
        val hy = tTop + headerH * 0.68f
        val hs = 1.9f * u
        texto(c, "#", xPos, hy, hs, TXT2, bold, Paint.Align.RIGHT)
        texto(c, "Nº", xNum, hy, hs, TXT2, bold)
        texto(c, "CANDIDATO", xNome, hy, hs, TXT2, bold)
        texto(c, "PARTIDO", xPart, hy, hs, TXT2, bold)
        texto(c, "VOTOS", xVotos, hy, hs, TXT2, bold, Paint.Align.RIGHT)
        texto(c, "%", xPct, hy, hs, TXT2, bold, Paint.Align.RIGHT)

        if (lista.isEmpty()) {
            texto(c, "Sem candidatos nesta faixa (${ini + 1}º ao ${ini + porPagina}º)", w / 2, (tTop + bot) / 2,
                3f * u, TXT2, condensed, Paint.Align.CENTER)
            return
        }

        val maxPct = (res.candidatos.firstOrNull()?.pct ?: 1.0).coerceAtLeast(0.01)
        var y = tTop + headerH
        lista.forEachIndexed { i, cand ->
            val pos = ini + i + 1
            val t = y; val b = y + rowH
            if (i % 2 == 0) rect(c, pad, t, w - pad, b, CARD)
            // barra de fundo proporcional aos votos
            val barL = xNome - 1f * u
            val barR = w - pad
            fill.color = (cargo.cor and 0x00FFFFFF) or 0x30000000
            r.set(barL, t + rowH * 0.12f, barL + (barR - barL) * (cand.pct / maxPct).toFloat().coerceIn(0f, 1f), b - rowH * 0.12f)
            c.drawRect(r, fill)
            if (cand.eleito) rect(c, pad, t, pad + 0.7f * u, b, VERDE)

            val ty = t + rowH * 0.5f + fs * 0.36f
            texto(c, "$pos", xPos, ty, fs, if (pos <= cargo.vagas) TXT else TXT2, condensedB, Paint.Align.RIGHT)
            texto(c, cand.numero, xNum, ty, fs * 0.9f, TXT2, condensed)
            val nome = if (cand.eleito) "✔ ${cand.nome}" else cand.nome
            texto(c, nome, xNome, ty, fs, if (cand.eleito) VERDE else TXT, condensedB, maxW = xPart - xNome - 1.5f * u)
            texto(c, cand.partido, xPart, ty, fs * 0.9f, TXT2, condensed, maxW = xVotos - xPart - 12f * u)
            texto(c, int(cand.votos), xVotos, ty, fs, TXT, condensed, Paint.Align.RIGHT)
            texto(c, pct(cand.pct), xPct, ty, fs, TXT, condensedB, Paint.Align.RIGHT)

            // linha divisória após a quantidade de vagas
            if (pos == cargo.vagas) {
                fill.color = OURO
                c.drawRect(pad, b - 0.15f * u, w - pad, b + 0.15f * u, fill)
            }
            y += rowH
        }
    }

    private fun Int.clarear(): Int {
        val rr = (Color.red(this) + 255) / 2
        val g = (Color.green(this) + 255) / 2
        val bb = (Color.blue(this) + 255) / 2
        return Color.rgb(rr, g, bb)
    }
}
