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
- **Vários painéis**: guarda quantos URLs quiseres (nome + endereço) em Definições → Painéis. Toca num para o ativar; **deslizar 3 dedos na horizontal** no painel alterna para o seguinte/anterior, com transição lateral.
- **Definições**: dois dedos premidos durante mais de 2 segundos em qualquer ponto do ecrã.
- **Arranque automático**: ao ligar o tablet a app abre-se sozinha. Requer ativar uma vez, em Definições, *Aparecer por cima* (e ter aberto a app pelo menos uma vez).

## Desempenho do WebView

A app afina o WebView para ficar perto do Chrome:

- **Taxa de atualização máxima** do ecrã (ex.: 90 Hz no OPPO Pad SE); sem pedir, uma app fica muitas vezes a 60 Hz.
- **Renderer com prioridade alta**: o Android não o despromove nem o mata quando falta memória (`largeHeap` também ajuda).
- **Rasterização fora do ecrã**, sem "flashes" ao deslizar entre painéis; sem zoom nem barras de scroll.
- **Câmara mais leve com o ecrã ligado**: 4 análises/s (antes ~15), o CPU fica para a página; com o ecrã desligado continua rápida para acordar.

**Texto a 100%** (Definições → Movimento e ecrã, ligado por omissão): o WebView aplica o tamanho de letra do Android e o painel parece "ampliado"; assim fica como no Chrome.

Em **Definições** aparece a versão do WebView, a área do ecrã em px CSS e a taxa máxima. O WebView é o do sistema:
mantém **Android System WebView** (ou o Chrome, se for o fornecedor) atualizado na Play Store, que é o que mais pesa.

## Ponte com a página (Pine3 Homeview)

A página sabe que está nesta app: o user agent acaba em `Pine3Wallpanel/1` e existe `window.Pine3Wallpanel`.
O [Pine3 Homeview](https://github.com/pinheirofilipem-ux/Pine3-Homeview) usa isto para sair do face ambiente
quando há movimento e para baixar o brilho no ambiente; noutros painéis não muda nada.

Métodos (`window.Pine3Wallpanel.…`):

| Método | O que faz |
|---|---|
| `version()` | Versão da ponte (1). |
| `state()` | JSON com `sleeping`, `timeoutSec`, `secondsToSleep`, `lastMotionAgoMs`, `brightness`, `admin`. |
| `setBrightness(n)` | Brilho do ecrã de 0 a 1; um valor negativo volta ao brilho do sistema. |
| `wake()` | Conta como atividade: acorda o ecrã e reinicia a contagem. |
| `keepAwake(s)` | Mantém o ecrã ligado pelo menos `s` segundos (0 cancela). |
| `sleepNow()` | Desliga o ecrã já. |

Eventos: `window.addEventListener("pine3wallpanel", (e) => e.detail.type)`, com `motion` (no máximo um por segundo),
`sleep` e `wake`.

## Acordar com entidades do Home Assistant (ex.: campainha)

A app liga-se ao Home Assistant (API WebSocket) e acorda o ecrã quando uma entidade mapeada muda de estado.
Funciona com o ecrã desligado: a ligação vive no serviço da app e reconecta sozinha se o Wi-Fi ou o HA caírem.

1. No HA, cria um **token de acesso de longa duração** (o teu perfil, separador Segurança).
2. Na app: Definições → **Acordar com entidades do Home Assistant…**
3. Cola o token (o endereço é o do painel ativo, a não ser que indiques outro) e define quantos segundos o ecrã fica ligado.
4. Adiciona entidades. Para a integração [comelit_homeassistant_intercom](https://github.com/pinheirofilipem-ux/comelit_homeassistant_intercom) usa o atalho
   **+ Campainha Comelit** (`event.doorbell`, só quando `ring`). Confirma o id exato em Ferramentas de programador, Estados.
5. Opcional: escolhe um painel para mostrar ao disparar (por exemplo, o da câmara da porta).

Regras: "Só quando" compara com o novo estado (`on`, `home`…) ou com o `event_type` das entidades `event.*`;
vazio = qualquer mudança de estado. O token fica guardado só no tablet.

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

## OPPO Pad SE (11", 90 Hz, ColorOS)

- **90 Hz**: a app pede a taxa de atualização máxima do ecrã (no Tab A7 era 60 Hz).
- O ColorOS é agressivo com apps em segundo plano. Os nomes dos menus variam com a versão, mas procura nas definições da app e da bateria:
  1. Bateria da app: **sem restrições** / permitir atividade em segundo plano.
  2. **Arranque automático** (auto-launch) permitido, para a app abrir sozinha depois de reiniciar.
  3. **Aparecer por cima de outras apps**: ativar (também necessário para o arranque automático).
  4. Nos recentes, **bloquear** a app (puxar o cartão para baixo ou menu do cartão).
  5. Ecrã de bloqueio: **Nenhum** ou Deslizar, não PIN.
- Se o ecrã não acordar com a câmara, ou a ligação ao Home Assistant cair com o ecrã desligado, é quase sempre a gestão de bateria do ColorOS: repete os passos 1, 2 e 4.

## Limitações conhecidas

- Alguns fabricantes matam câmaras com o ecrã desligado; os passos acima minimizam isso.
- Se a câmara parar, a app tenta reabri-la sozinha a cada 4 s.
