# Apuração 2026 — Kiosk

App Android para deixar um tablet **em pé** acompanhando a apuração das Eleições Gerais de 2026
(1º turno, 04/10/2026), com dados oficiais do TSE (`resultados.tse.jus.br`).

## Telas (rodízio padrão a cada 10 s, configurável)

| # | Cargo | Abrangência | Vagas |
|---|-------|-------------|-------|
| 1 | Presidente | Brasil | 1 |
| 2 | Governador | RN | 1 |
| 3 | Senador | RN | 2 |
| 4 | Deputado Federal — 1º ao 15º | RN | 8 |
| 5 | Deputado Federal — 16º ao 30º | RN | 8 |
| 6 | Deputado Estadual — 1º ao 15º | RN | 24 |
| 7 | Deputado Estadual — 16º ao 30º | RN | 24 |

Todas as telas usam a mesma grade de **15 candidatos**. Cada tela mostra o percentual de seções totalizadas,
votos e percentual de cada candidato e, em destaque no rodapé, válidos, brancos, nulos e abstenção. Nas telas proporcionais, uma linha dourada marca a quantidade de vagas
e os eleitos confirmados pelo TSE aparecem em verde com ✔ (a eleição proporcional segue o quociente
partidário, não apenas a ordem de votos).

## Atualização

- 1 requisição a cada **5 s** para **cada** cargo, em paralelo (usa `If-Modified-Since`).
- Endpoints (`dados-simplificados`):
  - Presidente: `ele2026/6257/dados-simplificados/br/br-c0001-e006257-r.json`
  - RN: `ele2026/6259/dados-simplificados/rn/rn-c000{3,5,6,7}-e006259-r.json`
- Antes da divulgação (HTTP 403/404) mostra "Aguardando divulgação do TSE".

## Uso

- Tela sempre ligada, tela cheia, orientação retrato.
- **Toque**: avança para a próxima tela.
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
- `PainelView.kt` — renderização da tela (Canvas, escala para qualquer tamanho)
- `MainActivity.kt` — rodízio de telas e modo kiosk
- `Config.kt` / `ConfigActivity.kt` — configurações (tempo, ordem, telas ocultas, dados de teste)
