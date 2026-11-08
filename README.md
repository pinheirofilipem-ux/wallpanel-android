# Pine3 Wallpanel

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

## Extras

- **Ligação perdida**: se o Wi-Fi/HA cair, a página volta a carregar sozinha (de 10 em 10 s e logo que a rede regressa).
- **Definições**: dois dedos premidos durante mais de 2 segundos em qualquer ponto do ecrã.
- **Arranque automático**: ao ligar o tablet a app abre-se sozinha. Requer ativar uma vez, em Definições, *Aparecer por cima* (e ter aberto a app pelo menos uma vez).

## Samsung Galaxy Tab A7 (SM-T505)

O ecrã é LCD, por isso usa o **administrador de dispositivo** (desliga o backlight a sério; o modo "ecrã preto" deixa luz residual).

No tablet, para a app não ser morta pela One UI:
1. Definições → Aplicações → Pine3 Wallpanel → **Bateria → Sem restrições**.
2. Definições → Assistência do dispositivo → Bateria → Limites de utilização em segundo plano: desativar *Colocar apps não usadas em suspensão* e adicionar a app a **Apps nunca em suspensão**.
3. Definições → Aplicações → Pine3 Wallpanel → **Aparecer por cima de outras apps: ativar**.
4. Ecrã de bloqueio: **Nenhum** (ou Deslizar).
5. Na lista de recentes, toca no ícone da app e escolhe **Manter aberta**.
6. Com o tablet sempre ligado ao carregador, em Definições → Bateria podes limitar a carga a 85 % (Proteger bateria) se existir.

Nota: se o tablet ficar completamente sem bateria, o Tab A7 não liga sozinho quando volta a energia — é preciso carregar no botão. Depois de ligado, a app arranca sozinha.

## Limitações conhecidas

- Alguns fabricantes matam câmaras com o ecrã desligado; os passos acima minimizam isso.
- Se a câmara parar, a app tenta reabri-la sozinha a cada 4 s.
