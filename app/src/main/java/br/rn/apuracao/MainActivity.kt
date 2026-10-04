package br.rn.apuracao

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    companion object {
        const val CONSULTA_MS = 5_000L
    }

    /** Ordem do rodízio, tempo por tela e modo de teste vêm das configurações (toque longo). */
    private var telas: List<Tela> = Config.TELAS_PADRAO
    private var trocaTelaMs = Config.SEGUNDOS_PADRAO * 1000L

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var painel: PainelView
    private lateinit var coletor: Coletor
    private var idx = 0
    private var inicioTela = 0L

    private val tick = object : Runnable {
        override fun run() {
            val agora = SystemClock.uptimeMillis()
            if (agora - inicioTela >= trocaTelaMs) irPara(idx + 1)
            painel.progressoTela = (agora - inicioTela) / trocaTelaMs.toFloat()
            painel.invalidate()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        painel = PainelView(this)
        setContentView(painel)

        coletor = Coletor(CONSULTA_MS) { cargo ->
            ui.post { if (telas[idx].cargo == cargo) atualizarPainel() }
        }

        // Toque: avança a tela. Toque longo: abre as configurações.
        painel.setOnClickListener { irPara(idx + 1) }
        painel.setOnLongClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
            true
        }
    }

    private fun aplicarConfig() {
        val cfg = Config.carregar(this)
        val atual = telas.getOrNull(idx)
        telas = cfg.telasAtivas
        trocaTelaMs = cfg.segundos * 1000L
        if (coletor.demo != cfg.demo) coletor.demo = cfg.demo
        painel.demo = cfg.demo
        // continua na mesma tela se ela ainda estiver visível; senão volta ao início
        irPara(telas.indexOf(atual).coerceAtLeast(0))
    }

    private fun irPara(novo: Int) {
        idx = ((novo % telas.size) + telas.size) % telas.size
        inicioTela = SystemClock.uptimeMillis()
        atualizarPainel()
        painel.alpha = 0f
        painel.animate().alpha(1f).setDuration(350).start()
    }

    private fun atualizarPainel() {
        val t = telas[idx]
        painel.tela = t
        painel.indice = idx
        painel.total = telas.size
        painel.estado = coletor.estados[t.cargo] ?: Estado.Carregando
        painel.invalidate()
    }

    override fun onResume() {
        super.onResume()
        telaCheia()
        aplicarConfig()
        // consulta apenas os cargos que têm alguma tela visível
        coletor.iniciar(telas.map { it.cargo }.toSet())
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        coletor.parar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) telaCheia()
    }

    @Suppress("DEPRECATION")
    private fun telaCheia() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }
}
