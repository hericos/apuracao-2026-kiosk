package br.rn.apuracao

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

class MainActivity : Activity(), PainelView.Acoes {

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

    /** Rodízio pausado pelo botão do canto inferior direito; guarda quanto da tela já tinha passado. */
    private var pausado = false
    private var decorridoNaPausa = 0L

    private val tick = object : Runnable {
        override fun run() {
            val agora = SystemClock.uptimeMillis()
            if (pausado) inicioTela = agora - decorridoNaPausa
            else if (agora - inicioTela >= trocaTelaMs) irPara(idx + 1)
            painel.progressoTela = (agora - inicioTela) / trocaTelaMs.toFloat()
            painel.invalidate()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        painel = PainelView(this)
        painel.acoes = this
        setContentView(painel)

        coletor = Coletor(
            CONSULTA_MS,
            regioesDoCargo = { cargo ->
                val malha = Malhas.doCargo(this, cargo)?.codigos.orEmpty().toList()
                if (cargo == Cargo.PRESIDENTE) malha + "zz" else malha
            },
        ) { cargo ->
            ui.post { if (telas[idx].cargo == cargo) atualizarPainel() }
        }
    }

    // ---- ações da tela (PainelView.Acoes) ----

    /** Toque fora das áreas interativas: próxima tela. */
    override fun avancar() = irPara(idx + 1)

    override fun alternarPausa() {
        pausado = !pausado
        decorridoNaPausa = SystemClock.uptimeMillis() - inicioTela
        painel.pausado = pausado
        painel.invalidate()
    }

    /** Estado/município tocado no mapa ou na tabela (null = voltar ao total). */
    override fun selecionar(regiao: String?) {
        val cargo = telas[idx].cargo
        if (!cargo.temMapa) return
        coletor.selecionar(cargo, regiao)
        // dá o tempo inteiro da tela para ver a região escolhida
        if (!pausado) inicioTela = SystemClock.uptimeMillis()
        atualizarPainel()
    }

    override fun configurar() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    // ---- rodízio ----

    private fun aplicarConfig() {
        val cfg = Config.carregar(this)
        val atual = telas.getOrNull(idx)
        telas = cfg.telasAtivas
        trocaTelaMs = cfg.segundos * 1000L
        coletor.demo = cfg.demo
        coletor.turno = cfg.turno
        painel.demo = cfg.demo
        painel.turno = cfg.turno
        // continua na mesma tela se ela ainda estiver visível; senão volta ao início
        irPara(telas.indexOf(atual).coerceAtLeast(0))
    }

    private fun irPara(novo: Int) {
        val anterior = telas.getOrNull(idx)?.cargo
        idx = ((novo % telas.size) + telas.size) % telas.size
        // ao sair de uma tela com mapa, ela volta a mostrar o total na próxima vez
        if (anterior != null && anterior != telas[idx].cargo && anterior.temMapa) coletor.selecionar(anterior, null)
        inicioTela = SystemClock.uptimeMillis()
        decorridoNaPausa = 0
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
        painel.andamento = coletor.andamentos[t.cargo].orEmpty()
        painel.selecao = coletor.selecao[t.cargo]
        painel.detalhe = coletor.detalhes[t.cargo]
        painel.pausado = pausado
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
