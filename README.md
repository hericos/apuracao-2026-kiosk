# Apuração 2026 — Kiosk

App Android para deixar um tablet **em pé** acompanhando a apuração das Eleições Gerais de 2026
(1º turno, 04/10/2026), com dados oficiais do TSE (`resultados.tse.jus.br`).

## Telas (rodízio padrão a cada 10 s, configurável)

| # | Cargo | Abrangência | Vagas |
|---|-------|-------------|-------|
| 1 | Presidente (com mapa das UFs) | Brasil | 1 |
| 2 | Governador (com mapa dos municípios) | RN | 1 |
| 3 | Senador | RN | 2 |
| 4 | Deputado Federal — 1º ao 15º | RN | 8 |
| 5 | Deputado Federal — 16º ao 30º | RN | 8 |
| 6 | Deputado Estadual — 1º ao 15º | RN | 24 |
| 7 | Deputado Estadual — 16º ao 30º | RN | 24 |

As telas de Senador e deputados usam a mesma grade de **15 candidatos**. Cada tela mostra o percentual de seções totalizadas,
votos e percentual de cada candidato e, em destaque no rodapé, válidos, brancos, nulos e abstenção. Nas telas de deputado aparecem o **quociente eleitoral**, as **vagas por partido/federação** e os candidatos
**dentro das vagas** em destaque ("NA VAGA"). Usa a distribuição oficial do TSE (campos `qe` e `vag`) quando
publicada; até lá, projeta pelo quociente partidário (10% do QE), sobras com 80%/20% e sobras finais entre
todos (decisão do STF de 2024) — ver `Projecao.kt` e `ProjecaoTest.kt`. Eleitos confirmados pelo TSE aparecem
em verde com ✔.

## Telas com mapa (Presidente e Governador)

- **Presidente**: mapa do Brasil (malha do IBGE em `assets/malha_br.json`) com cada UF colorida pelo % de seções
  totalizadas — branco sem apuração, verde em 100% — e tabela das 27 UFs + exterior.
- **Governador**: mapa dos 167 municípios do RN (`assets/malha_rn.json`) com a mesma escala de cores e tabela dos
  14 maiores municípios por eleitorado.
- O painel ao lado do mapa mostra o **percentual e os votos de cada candidato**: o total (Brasil ou RN) ou, ao
  **tocar num estado/município** no mapa ou na tabela, os votos daquela região. "✕ Ver total" volta ao total.
  Os blocos de válidos/brancos/nulos/abstenção acompanham a região selecionada.
- Ao trocar de tela, a seleção é desfeita; ao selecionar, o tempo da tela recomeça.
- Fontes: andamento por região no arquivo de abrangências (`br-e006258-ab.json`, `rn-e006260-ab.json`) e
  resultado da região selecionada (`sp/sp-c0001-e006258-u.json`, `rn/rn17612-c0003-e006260-u.json`), a cada 5 s.

## 2º turno

Nas configurações (toque longo) há a escolha do **turno**; a partir de 25/10/2026 o padrão é o 2º. No 2º turno só existem
Presidente (`6258`) e Governador do RN (`6260`), ambos nas telas com mapa.

## Atualização

- A cada **5 s**, para **cada** cargo e em paralelo (usa `If-Modified-Since`): o resultado total; nas telas com
  mapa, também o andamento por região e o resultado da região selecionada.
- Endpoints (arquivo completo `-u.json`, publicado desde a véspera com votos zerados):
  - Presidente: `ele2026/6257/dados/br/br-c0001-e006257-u.json`
  - RN: `ele2026/6259/dados/rn/rn-c000{3,5,6,7}-e006259-u.json`
- Antes da divulgação (HTTP 403/404) mostra "Aguardando divulgação do TSE".

## Uso

- Tela sempre ligada, tela cheia, orientação retrato.
- **Toque**: avança para a próxima tela (no mapa e na tabela, seleciona a região).
- **Botão no canto inferior direito**: pausa / continua a troca de telas.
- **Toque longo**: abre as **Configurações** (salvas automaticamente):
  - tempo em cada tela (3 a 120 s, padrão 10 s);
  - ordem das telas (▲ ▼);
  - ocultar telas (só os cargos com tela visível são consultados no TSE);
  - usar dados de teste (modo demonstração) ou dados reais do TSE.

## Build

Requer JDK 17 e Android SDK (compileSdk 35). Sem dependências externas.

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (assinado com a chave de debug para instalação direta).

Código principal em `app/src/main/java/br/rn/apuracao/`:

- `Dados.kt` — cargos, URLs, vagas e parser do JSON do TSE
- `Coletor.kt` — consultas periódicas e modo demonstração
- `Projecao.kt` — distribuição das vagas proporcionais (oficial do TSE ou projeção)
- `Malha.kt` — contornos (UFs e municípios do RN), projeção do mapa e região tocada
- `PainelView.kt` — renderização da tela (Canvas, escala para qualquer tamanho)
- `MainActivity.kt` — rodízio de telas e modo kiosk
- `Config.kt` / `ConfigActivity.kt` — configurações (tempo, ordem, telas ocultas, dados de teste)
