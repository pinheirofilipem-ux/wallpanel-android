# WallPanel Lite

App Android mínima (sem dependências externas) para usar um tablet como painel de parede do Home Assistant:

- Mostra o teu dashboard em ecrã inteiro (WebView).
- A câmara frontal analisa movimento continuamente (Camera2, frames minúsculos, só luminância, ~15 análises/s).
- Com movimento → o ecrã acorda. **X segundos sem movimento (por omissão 15) → o ecrã desliga.**
- Tocar no ecrã também conta como atividade.

## Como desliga o ecrã "sem luz"

| Modo | Efeito |
|---|---|
| **Administrador de dispositivo ativo** (recomendado) | `lockNow()` — o ecrã desliga mesmo (backlight a zero). A deteção continua com o ecrã desligado. |
| Sem administrador (fallback) | Ecrã preto + brilho no mínimo absoluto. Em OLED fica sem luz; em LCD pode ficar um brilho residual. |

## Instalação

1. Faz o build (`./gradlew :app:assembleRelease`) ou descarrega o APK dos *Actions* do GitHub (artefacto `wallpanel-lite-apk`).
2. Abre a app → indica o URL do dashboard → permite a câmara.
3. Em **Definições** (abre sozinho no 1.º arranque; depois: 5 toques no canto superior esquerdo):
   - *Ativar desligar ecrã total (administrador)*
   - *Ignorar otimização de bateria*
   - Define os segundos e a sensibilidade.
4. No Android: **Bloqueio de ecrã = Nenhum ou Deslizar** (não PIN/padrão), senão o ecrã acorda mas fica no bloqueio.
5. Deixa a app sempre aberta (é a atividade que aparece por cima do bloqueio ao acordar).

## Limitações conhecidas

- Alguns fabricantes (Samsung, Xiaomi…) são agressivos a matar câmaras com o ecrã desligado: desativa a otimização de bateria e bloqueia a app na lista de recentes.
- Não arranca sozinha após reiniciar (o Android 14+ não permite iniciar um serviço de câmara a partir do boot); abre a app uma vez.
