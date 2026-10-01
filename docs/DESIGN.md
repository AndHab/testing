# CubeLens design language

CubeLens should feel like a premium, playful toy-meets-tool: dark, warm, glowing, tactile.
The cube's own sticker colors are the heroes; the chrome around them is quiet ink with warm light.
It must **not** look like a stock Material app (no default blue/purple, no flat grey cards).

## Mood

"Sunset on ink." Near-black backgrounds lit by soft, slowly drifting glows of magenta, tangerine
and a hint of mint, like neon reflecting off a dark table. Surfaces are dark glass with hairline
borders. Primary actions are glowing sunset-gradient pills. Motion is springy and confident.

## Color (see `ui/theme/Color.kt`)

| Token | Hex | Use |
|---|---|---|
| `Brand.Ink` | `#0A0A10` | App background |
| `Brand.Surface` / `SurfaceHigh` | `#17171F` / `#1F1F2A` | Cards, sheets (usually translucent over the aurora) |
| `Brand.Hairline` | white 10% | 1dp borders on glass cards |
| `Brand.TextPrimary` / `Secondary` / `Tertiary` | `#F6F3FF` / `#ADA8C2` / `#6F6A86` | Text hierarchy |
| `Brand.SunsetBrush` | `#FF2E63 → #FF7A18 → #FFC93C` | Primary CTAs, highlights, gradient headline words, progress |
| `Brand.Mint` | `#2EE6A6` | Success / "valid cube" / captured states |
| `Brand.Amber` | `#FFB547` | Warnings |
| `Brand.Danger` | `#FF4D6A` | Errors, flagged stickers |
| `CubePalette` | vivid W/Y/G/B/R/O | Stickers only — never as UI chrome |

Glows: large, very soft radial gradients (alpha 10–22%) of Magenta, Tangerine and Mint behind
content; never hard-edged. Use shadows tinted with the accent (e.g. magenta glow under the main
button) rather than grey drop shadows.

## Typography (see `ui/theme/Type.kt`)

* **Space Grotesk** (Bold/Medium): display & headline text, numbers, move notation (`R'`, `U2`).
  Tight letter-spacing at large sizes. Headlines may highlight one key word with the sunset gradient.
* **Outfit**: body, labels, buttons. Friendly and round.
* Small caps-style overlines (labelSmall, letter-spaced, `TextTertiary` or accent) above titles.

## Shape & surface

* Corner radii: 12dp (small chips), 20dp (cards), 28dp (sheets/hero cards), full pill for buttons.
* Glass cards: `Surface` at ~70–80% alpha + 1dp `Hairline` border + subtle top highlight gradient.
* Cube stickers: rounded squares (≈18% corner radius) on a black body, with a gentle top-left
  highlight so they read as glossy plastic.

## Motion

* Springs (`dampingRatio ≈ 0.7–0.8`, medium-low stiffness) for presses, selection, appearing items.
* Staggered entrance for lists/steps (40–60ms apart), fade + slight upward slide.
* Buttons scale to ~0.96 on press.
* The 3D cube eases each layer turn (`FastOutSlowIn`-like), idles with a slow float/spin on Home.
* Celebrate success (solved cube) with a burst of confetti in the six sticker colors.

## Voice

Short, warm, confident. "Scan my cube", "Looks good — let's solve it", "Hold green toward you,
white on top". Never technical jargon on screen (no "facelets", "Kociemba", "parity" — say
"two pieces look swapped").
