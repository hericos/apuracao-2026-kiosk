package br.rn.apuracao

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Tela de configurações, aberta com toque longo no painel. Salva a cada alteração. */
class ConfigActivity : Activity() {

    private lateinit var ajustes: Config.Ajustes
    private lateinit var lista: LinearLayout
    private lateinit var lblTempo: TextView
    private lateinit var seek: SeekBar
    private lateinit var swDemo: Switch

    private val BG = Color.parseColor("#0B1320")
    private val CARD = Color.parseColor("#15212F")
    private val TXT = Color.parseColor("#F3F6FA")
    private val TXT2 = Color.parseColor("#93A4B8")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ajustes = Config.carregar(this)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        raiz.addView(titulo("Configurações", 30f))
        raiz.addView(texto("Alterações são salvas automaticamente.", 15f, TXT2), lp(bottom = 20))

        // ---- Tempo por tela ----
        raiz.addView(secao())
        val cardTempo = card()
        lblTempo = texto("", 20f, TXT).apply { typeface = Typeface.DEFAULT_BOLD }
        cardTempo.addView(lblTempo)
        val linhaTempo = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val menos = botao("−") { mudarTempo(ajustes.segundos - 1) }
        seek = SeekBar(this).apply {
            max = Config.SEGUNDOS_MAX - Config.SEGUNDOS_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) mudarTempo(p + Config.SEGUNDOS_MIN)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val mais = botao("+") { mudarTempo(ajustes.segundos + 1) }
        linhaTempo.addView(menos)
        linhaTempo.addView(seek, LinearLayout.LayoutParams(0, dp(56), 1f))
        linhaTempo.addView(mais)
        cardTempo.addView(linhaTempo, lp(top = 8))
        raiz.addView(cardTempo, lp(bottom = 24))

        // ---- Telas: ordem e visibilidade ----
        raiz.addView(titulo("Telas — ordem e exibição", 22f), lp(bottom = 4))
        raiz.addView(texto("Desmarque para ocultar. Use ▲ ▼ para mudar a ordem.", 15f, TXT2), lp(bottom = 8))
        lista = card()
        raiz.addView(lista, lp(bottom = 24))

        // ---- Dados de teste ----
        val cardDemo = card()
        swDemo = Switch(this).apply {
            text = "Usar dados de teste (demonstração)"
            textSize = 20f
            setTextColor(TXT)
            setOnCheckedChangeListener { _, v -> ajustes = ajustes.copy(demo = v); salvar() }
        }
        cardDemo.addView(swDemo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        cardDemo.addView(texto("Gera resultados fictícios para testar o visual. Desligue para ver os dados reais do TSE.", 14f, TXT2))
        raiz.addView(cardDemo, lp(bottom = 24))

        // ---- Ações ----
        val acoes = LinearLayout(this)
        acoes.addView(botao("Restaurar padrão") {
            ajustes = Config.PADRAO; salvar(); preencher()
        }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(12) })
        acoes.addView(botao("Voltar ao painel") { finish() }, LinearLayout.LayoutParams(0, dp(64), 1f))
        raiz.addView(acoes)

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(raiz) })
        preencher()
    }

    private fun preencher() {
        lblTempo.text = "Tempo em cada tela: ${ajustes.segundos} s"
        seek.progress = ajustes.segundos - Config.SEGUNDOS_MIN
        swDemo.isChecked = ajustes.demo
        montarLista()
    }

    private fun mudarTempo(s: Int) {
        ajustes = ajustes.copy(segundos = s.coerceIn(Config.SEGUNDOS_MIN, Config.SEGUNDOS_MAX))
        lblTempo.text = "Tempo em cada tela: ${ajustes.segundos} s"
        seek.progress = ajustes.segundos - Config.SEGUNDOS_MIN
        salvar()
    }

    private fun montarLista() {
        lista.removeAllViews()
        val itens = ajustes.itens
        itens.forEachIndexed { i, item ->
            val linha = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val faixa = View(this).apply {
                background = GradientDrawable().apply { setColor(item.tela.cargo.cor); cornerRadius = dp(3).toFloat() }
            }
            linha.addView(faixa, LinearLayout.LayoutParams(dp(6), dp(40)).apply { marginEnd = dp(10) })
            val cb = CheckBox(this).apply {
                text = "${i + 1}. ${Config.nome(item.tela)}"
                textSize = 19f
                setTextColor(if (item.visivel) TXT else TXT2)
                isChecked = item.visivel
                setOnCheckedChangeListener { v, marcado ->
                    val novos = itens.toMutableList().also { it[i] = item.copy(visivel = marcado) }
                    if (novos.none { it.visivel }) {
                        v.isChecked = true
                        Toast.makeText(this@ConfigActivity, "Deixe ao menos uma tela visível", Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }
                    ajustes = ajustes.copy(itens = novos); salvar(); montarLista()
                }
            }
            linha.addView(cb, LinearLayout.LayoutParams(0, dp(56), 1f))
            linha.addView(botao("▲", habilitado = i > 0) { mover(i, i - 1) }, LinearLayout.LayoutParams(dp(64), dp(56)))
            linha.addView(botao("▼", habilitado = i < itens.lastIndex) { mover(i, i + 1) },
                LinearLayout.LayoutParams(dp(64), dp(56)).apply { marginStart = dp(8) })
            lista.addView(linha)
        }
    }

    private fun mover(de: Int, para: Int) {
        val l = ajustes.itens.toMutableList()
        l.add(para, l.removeAt(de))
        ajustes = ajustes.copy(itens = l); salvar(); montarLista()
    }

    private fun salvar() = Config.salvar(this, ajustes)

    // ---- helpers de layout ----
    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun texto(s: String, size: Float, cor: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(cor)
    }

    private fun titulo(s: String, size: Float) = texto(s, size, TXT).apply { typeface = Typeface.DEFAULT_BOLD }

    private fun secao() = titulo("Rodízio", 22f).apply { setPadding(0, 0, 0, dp(8)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(12).toFloat() }
    }

    private fun botao(s: String, habilitado: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 18f; isAllCaps = false
        isEnabled = habilitado
        alpha = if (habilitado) 1f else 0.3f
        setOnClickListener { onClick() }
    }
}
