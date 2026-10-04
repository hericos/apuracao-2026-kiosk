package br.rn.apuracao

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val TROCA_TELA_MS = 10_000L
        const val CONSULTA_MS = 5_000L
    }

    /** Ordem do rodízio: deputados têm 2 páginas (1º–30º e 31º–60º). */
    private val telas = listOf(
        Tela(Cargo.PRESIDENTE, 0),
        Tela(Cargo.GOVERNADOR, 0),
        Tela(Cargo.SENADOR, 0),
        Tela(Cargo.DEP_FEDERAL, 0),
        Tela(Cargo.DEP_FEDERAL, 1),
        Tela(Cargo.DEP_ESTADUAL, 0),
        Tela(Cargo.DEP_ESTADUAL, 1),
    )

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var painel: PainelView
    private lateinit var coletor: Coletor
    private var idx = 0
    private var inicioTela = 0L

    private val tick = object : Runnable {
        override fun run() {
            val agora = SystemClock.uptimeMillis()
            if (agora - inicioTela >= TROCA_TELA_MS) irPara(idx + 1)
            painel.progressoTela = (agora - inicioTela) / TROCA_TELA_MS.toFloat()
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

        // Toque: avança a tela. Toque longo: liga/desliga modo demonstração.
        painel.setOnClickListener { irPara(idx + 1) }
        painel.setOnLongClickListener {
            coletor.demo = !coletor.demo
            painel.demo = coletor.demo
            Toast.makeText(this, if (coletor.demo) "Modo demonstração LIGADO" else "Dados reais do TSE", Toast.LENGTH_SHORT).show()
            true
        }
        irPara(0)
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
        coletor.iniciar()
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
