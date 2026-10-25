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
    var turno = 1

    private var insetTopo = 0
    private val mapa by lazy { MapaBrasil(context) }   // recorte da câmera/notch, quando houver

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
        texto(c, cargo.abrangencia, pad + 3f * u, y + 10.6f * u, 3f * u, TXT2, condensed)

        // selo de vagas
        val vagasLabel = cargo.textoVagas.uppercase()
        tp.textSize = 3.6f * u; tp.typeface = condensedB
        val seloW = tp.measureText(vagasLabel) + 4f * u
        rect(c, w - pad - seloW, y + 0.8f * u, w - pad, y + 6.6f * u, cargo.cor, 1.2f * u)
        texto(c, vagasLabel, w - pad - seloW / 2, y + 5f * u, 3.6f * u, Color.WHITE, condensedB, Paint.Align.CENTER)
        val sub = when {
            cargo.mapa -> "+ exterior"
            cargo == Cargo.PRESIDENTE -> "em disputa"
            else -> "em disputa no RN"
        }
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
        val (linha1, linha2) = when {
            cargo.mapa -> "Cor de cada estado = % de seções totalizadas: branco (sem apuração) até verde (100%)." to
                resumoMapa(res)
            turno == 2 -> "2º turno: eleito quem tiver a maioria dos votos válidos." to ""
            else -> when (cargo) {
            Cargo.PRESIDENTE, Cargo.GOVERNADOR ->
                "Eleito no 1º turno quem tiver mais da metade dos votos válidos;" to
                    "senão, os 2 mais votados disputam o 2º turno."
            Cargo.SENADOR -> "Os 2 mais votados são eleitos (maioria simples)." to ""
            else -> regraProporcional(res, cargo)
            }
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

        if (cargo.mapa && res != null && res.ufs.isNotEmpty()) {
            desenharMapa(c, res, u, pad, w, corpoTop, corpoBot)
        } else if (res == null || res.candidatos.isEmpty()) {
            val (l1, l2) = when (estado) {
                Estado.Aguardando -> "Aguardando divulgação do TSE" to "Os resultados começam a ser publicados após as 17h (horário de Brasília)."
                is Estado.Erro -> "Sem conexão com o TSE" to "Tentando novamente a cada 5 segundos…"
                else -> "Conectando ao TSE…" to "resultados.tse.jus.br"
            }
            val cy = (corpoTop + corpoBot) / 2
            texto(c, l1, w / 2, cy, 4.5f * u, TXT, condensedB, Paint.Align.CENTER, w - 2 * pad)
            texto(c, l2, w / 2, cy + 5f * u, 2.6f * u, TXT2, condensed, Paint.Align.CENTER, w - 2 * pad)
        } else {
            if (turno == 2) desenharDuelo(c, res, cargo, u, pad, w, corpoTop, corpoBot)
            else desenharLista(c, res, cargo, u, pad, w, corpoTop, corpoBot)
        }

        desenharTotais(c, if (cargo.mapa) blocosMapa(res) else blocosVotos(res), u, pad, w, totaisTop, totaisTop + totaisH)
        desenharIndicadores(c, cargo, u, pad, w, bottom - indicH, bottom)
    }

    /** 4 blocos grandes: válidos, brancos, nulos e abstenção. */
    private data class Bloco(val rotulo: String, val valor: String, val sub: String, val cor: Int)

    private fun blocosVotos(res: Resultado?): List<Bloco> {
        fun b(r: String, p: Double?, v: Long?, cor: Int) =
            Bloco(r, p?.let { pct(it) } ?: "—", v?.let { "${int(it)} votos" } ?: "", cor)
        return listOf(
            b("VÁLIDOS", res?.pctValidos, res?.validos, VERDE),
            b("BRANCOS", res?.pctBrancos, res?.brancos, CINZA),
            b("NULOS", res?.pctNulos, res?.nulos, VERMELHO),
            b("ABSTENÇÃO", res?.pctAbstencao, res?.abstencao, AZUL),
        )
    }

    private fun blocosMapa(res: Resultado?): List<Bloco> {
        val estados = res?.ufs.orEmpty().filter { it.uf != "zz" }
        val concluidos = estados.count { it.pct >= 100.0 }
        val munCom = estados.sumOf { it.municipiosComVotos }
        val munTot = estados.sumOf { it.municipiosTotal }
        return listOf(
            Bloco("COMPARECIMENTO", res?.let { pct(it.pctComparecimento) } ?: "—",
                res?.let { "${int(it.comparecimento)} eleitores" } ?: "", VERDE),
            Bloco("ABSTENÇÃO", res?.let { pct(it.pctAbstencao) } ?: "—",
                res?.let { "${int(it.abstencao)} eleitores" } ?: "", AZUL),
            Bloco("ESTADOS 100%", if (res != null) "$concluidos / ${estados.size}" else "—",
                "com apuração concluída", OURO),
            Bloco("MUNICÍPIOS COM VOTOS", if (munTot > 0) pct(munCom * 100.0 / munTot) else "—",
                if (munTot > 0) "${int(munCom)} de ${int(munTot)}" else "", CINZA),
        )
    }

    private fun resumoMapa(res: Resultado?): String {
        val estados = res?.ufs.orEmpty().filter { it.uf != "zz" }
        if (estados.isEmpty()) return ""
        val iniciados = estados.count { it.pct > 0 }
        val concluidos = estados.count { it.pct >= 100.0 }
        val ext = res?.ufs?.firstOrNull { it.uf == "zz" }?.let { " · Exterior ${pct(it.pct)}" } ?: ""
        return "Com apuração: $iniciados de ${estados.size} · Concluídos: $concluidos$ext"
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
            tp.typeface = bold; tp.textSize = 2.3f * u
            val tamRot = minOf(2.3f * u, 2.2f * u * (bw - 2 * u) / tp.measureText(b.rotulo).coerceAtLeast(1f))
            texto(c, b.rotulo, cx, top + bh * 0.27f, tamRot, TXT2, bold, Paint.Align.CENTER, bw - 2 * u)
            texto(c, b.valor, cx, top + bh * 0.66f, 5.2f * u, b.cor, condensedB, Paint.Align.CENTER, bw - 1.5f * u)
            texto(c, b.sub, cx, top + bh * 0.88f, 2.1f * u, TXT, condensed, Paint.Align.CENTER, bw - 1.5f * u)
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

    /** 2º turno: dois cartões grandes, frente a frente, e uma barra dividida com os percentuais. */
    private fun desenharDuelo(
        c: Canvas, res: Resultado, cargo: Cargo, u: Float, pad: Float, w: Float, top: Float, bot: Float,
    ) {
        val dois = res.candidatos.take(2)
        val temVotos = dois.any { it.votos > 0 }
        val cores = listOf(cargo.cor, Color.parseColor("#E67E22"))

        // barra dividida no topo
        val barH = 7f * u
        val bl = pad; val brr = w - pad
        val p0 = if (temVotos) (dois[0].pct / (dois.sumOf { it.pct }.coerceAtLeast(0.01))).toFloat() else 0.5f
        val meio = bl + (brr - bl) * p0
        rect(c, bl, top, brr, top + barH, LINHA, 1.5f * u)
        if (temVotos) {
            rect(c, bl, top, meio, top + barH, cores[0], 1.5f * u)
            if (dois.size > 1) rect(c, meio, top, brr, top + barH, cores[1], 1.5f * u)
            texto(c, pct(dois[0].pct), bl + 2f * u, top + barH * 0.7f, 4f * u, Color.WHITE, condensedB)
            if (dois.size > 1) texto(c, pct(dois[1].pct), brr - 2f * u, top + barH * 0.7f, 4f * u, Color.WHITE,
                condensedB, Paint.Align.RIGHT)
        }
        // marca dos 50%
        fill.color = Color.WHITE
        c.drawRect((bl + brr) / 2 - 0.2f * u, top - 1f * u, (bl + brr) / 2 + 0.2f * u, top + barH + 1f * u, fill)
        texto(c, "50%", (bl + brr) / 2, top + barH + 3.5f * u, 2f * u, TXT2, bold, Paint.Align.CENTER)

        // cartões
        val gap = 2f * u
        val cTop = top + barH + 5.5f * u
        val ch = (bot - cTop - gap) / 2
        dois.forEachIndexed { i, cand ->
            val t = cTop + i * (ch + gap); val b = t + ch
            val lider = temVotos && i == 0
            val cor = cores[i]
            rect(c, pad, t, w - pad, b, if (lider || cand.eleito) CARD2 else CARD, 2f * u)
            rect(c, pad, t, pad + 1.4f * u, b, if (cand.eleito) VERDE else cor, 0.7f * u)

            val x = pad + 5f * u
            texto(c, if (cand.eleito) "✔ ${cand.nome}" else cand.nome, x, t + ch * 0.26f, 7f * u,
                if (cand.eleito) VERDE else TXT, condensedB, maxW = w - 2 * pad - 8f * u)
            texto(c, "${cand.numero} · ${cand.partido}", x, t + ch * 0.38f, 3.2f * u, TXT2, condensed)
            texto(c, pct(cand.pct), x, t + ch * 0.72f, 15f * u, if (lider || cand.eleito) clarear(cor) else TXT, condensedB)
            texto(c, "${int(cand.votos)} votos", x, t + ch * 0.88f, 3.6f * u, TXT, condensed)

            val chip = when {
                cand.eleito -> "ELEITO" to VERDE
                lider -> "LIDERANDO" to OURO
                else -> null
            }
            chip?.let { (txt, cc) ->
                tp.textSize = 3f * u; tp.typeface = bold
                val cw = tp.measureText(txt) + 4f * u
                rect(c, w - pad - 3f * u - cw, t + 3f * u, w - pad - 3f * u, t + 8f * u, cc, 1.2f * u)
                texto(c, txt, w - pad - 3f * u - cw / 2, t + 6.6f * u, 3f * u, BG, bold, Paint.Align.CENTER)
            }
        }
    }

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

    private fun textoSobre(p: Double) = if (p >= 45.0) Color.WHITE else Color.parseColor("#0B1320")

    private fun fmtPct1(v: Double): String = when {
        v <= 0.0 -> "0%"
        v >= 100.0 -> "100%"
        v < 10.0 -> String.format(br, "%.2f%%", v)
        else -> String.format(br, "%.1f%%", v)
    }

    /** Tela do mapa: Brasil colorido pelo andamento, destaques à direita e tabela de todas as UFs. */
    private fun desenharMapa(c: Canvas, res: Resultado, u: Float, pad: Float, w: Float, top: Float, bot: Float) {
        val porUf = res.ufs.associateBy { it.uf }
        val h = bot - top
        val altoTopo = h * 0.56f

        // ---- mapa ----
        val mapaW = min(altoTopo * mapa.proporcao, (w - 2 * pad) * 0.6f)
        val caixa = RectF(pad, top, pad + mapaW, top + altoTopo)
        val bordas = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = BG; strokeWidth = 0.25f * u; strokeJoin = Paint.Join.ROUND
        }
        for ((uf, path) in mapa.caminhos(caixa)) {
            fill.color = corApuracao(porUf[uf]?.pct ?: 0.0)
            c.drawPath(path, fill)
            c.drawPath(path, bordas)
        }
        for (uf in mapa.ufs) {
            val p = porUf[uf]?.pct ?: 0.0
            val pt = mapa.rotulo(uf, caixa)
            val cor = textoSobre(p)
            val halo = if (cor == Color.WHITE) Color.parseColor("#0B4D2B") else Color.WHITE
            if (uf in MapaBrasil.PEQUENAS) {
                texto(c, uf.uppercase(), pt.x, pt.y + 0.6f * u, 1.7f * u, cor, bold, Paint.Align.CENTER, halo = halo)
            } else {
                texto(c, uf.uppercase(), pt.x, pt.y, 2.2f * u, cor, bold, Paint.Align.CENTER, halo = halo)
                texto(c, fmtPct1(p), pt.x, pt.y + 2.3f * u, 1.8f * u, cor, condensedB, Paint.Align.CENTER, halo = halo)
            }
        }

        // ---- painel à direita: legenda e destaques ----
        val x0 = caixa.right + 2.5f * u
        val x1 = w - pad
        var y = top + 1f * u
        texto(c, "LEGENDA", x0, y + 2f * u, 2f * u, TXT2, bold)
        y += 3f * u
        val passos = 20
        val lw = (x1 - x0) / passos
        for (i in 0 until passos) {
            rect(c, x0 + i * lw, y, x0 + (i + 1) * lw + 1, y + 2.4f * u, corApuracao(i * 100.0 / (passos - 1)))
        }
        fill.color = LINHA; fill.style = Paint.Style.STROKE; fill.strokeWidth = 0.15f * u
        c.drawRect(x0, y, x1, y + 2.4f * u, fill)
        fill.style = Paint.Style.FILL
        texto(c, "0%", x0, y + 4.8f * u, 1.8f * u, TXT2, condensed)
        texto(c, "50%", (x0 + x1) / 2, y + 4.8f * u, 1.8f * u, TXT2, condensed, Paint.Align.CENTER)
        texto(c, "100%", x1, y + 4.8f * u, 1.8f * u, TXT2, condensed, Paint.Align.RIGHT)
        y += 8f * u

        val estados = res.ufs.filter { it.uf != "zz" }
        texto(c, "MAIS ADIANTADOS", x0, y, 2f * u, TXT2, bold)
        y += 1.2f * u
        estados.sortedByDescending { it.pct }.take(4).forEach { s -> y = linhaRanking(c, s, x0, x1, y, u) }
        y += 2.2f * u
        texto(c, "MAIS ATRASADOS", x0, y, 2f * u, TXT2, bold)
        y += 1.2f * u
        estados.sortedBy { it.pct }.take(4).forEach { s -> y = linhaRanking(c, s, x0, x1, y, u) }
        y += 2.2f * u
        porUf["zz"]?.let { ext ->
            texto(c, "EXTERIOR", x0, y, 2f * u, TXT2, bold)
            linhaRanking(c, ext, x0, x1, y + 1.2f * u, u)
        }

        // ---- tabela de todas as UFs (2 colunas, ordem alfabética) ----
        val tTop = top + altoTopo + 2f * u
        val lista = res.ufs.sortedBy { MapaBrasil.NOMES[it.uf] ?: it.uf }
        val porColuna = (lista.size + 1) / 2
        val gap = 1.5f * u
        val colW = (w - 2 * pad - gap) / 2
        val rowH = (bot - tTop) / porColuna
        lista.forEachIndexed { i, s ->
            val col = i / porColuna
            val lin = i % porColuna
            val l = pad + col * (colW + gap)
            val r = l + colW
            val t = tTop + lin * rowH
            val b = t + rowH - 0.35f * u
            val my = (t + b) / 2
            rect(c, l, t, r, b, if (lin % 2 == 0) CARD else CARD2, 0.6f * u)
            // selo da UF na cor do mapa
            rect(c, l + 0.5f * u, t + 0.35f * u, l + 5.3f * u, b - 0.35f * u, corApuracao(s.pct), 0.5f * u)
            texto(c, if (s.uf == "zz") "EXT" else s.uf.uppercase(), l + 2.9f * u, my + 0.75f * u, 1.9f * u,
                textoSobre(s.pct), bold, Paint.Align.CENTER)
            texto(c, MapaBrasil.NOMES[s.uf] ?: s.uf, l + 6.3f * u, my + 0.75f * u, 2f * u, TXT, condensed,
                maxW = colW * 0.42f)
            val bl = l + colW * 0.52f
            val br2 = l + colW * 0.74f
            rect(c, bl, my - 0.5f * u, br2, my + 0.5f * u, LINHA, 0.5f * u)
            if (s.pct > 0) {
                rect(c, bl, my - 0.5f * u, bl + (br2 - bl) * (s.pct / 100).toFloat().coerceIn(0f, 1f),
                    my + 0.5f * u, corApuracao(maxOf(s.pct, 30.0)), 0.5f * u)
            }
            texto(c, pct(s.pct), r - 0.8f * u, my + 0.75f * u, 2.1f * u, if (s.pct >= 100) VERDE else TXT,
                condensedB, Paint.Align.RIGHT)
        }
    }

    private fun linhaRanking(c: Canvas, s: UfStatus, x0: Float, x1: Float, y: Float, u: Float): Float {
        val b = y + 3f * u
        val my = (y + b) / 2
        texto(c, if (s.uf == "zz") "EXT" else s.uf.uppercase(), x0, my + 0.8f * u, 2.2f * u, TXT, bold)
        val bl = x0 + 6f * u
        val br2 = x1 - 11f * u
        rect(c, bl, my - 0.6f * u, br2, my + 0.6f * u, LINHA, 0.6f * u)
        if (s.pct > 0) {
            rect(c, bl, my - 0.6f * u, bl + (br2 - bl) * (s.pct / 100).toFloat().coerceIn(0f, 1f),
                my + 0.6f * u, corApuracao(maxOf(s.pct, 30.0)), 0.6f * u)
        }
        texto(c, pct(s.pct), x1, my + 0.8f * u, 2.2f * u, TXT, condensedB, Paint.Align.RIGHT)
        return b + 0.4f * u
    }

    private fun clarear(cor: Int): Int =
        Color.rgb((Color.red(cor) + 255) / 2, (Color.green(cor) + 255) / 2, (Color.blue(cor) + 255) / 2)
}
