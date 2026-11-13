# ren mobile: Ink & Volt

ren mobile follows ren desktop's design system as its Electron code implements it. The truth is ren desktop's
`tailwind.config.ts`, `src/index.css` and `docs/brand.md` (`ImRonAI/TheBrowser`); this page records how the phone
carries it. The Compose tokens are in `android/app/src/main/java/com/hiron/ren/ui/Theme.kt`; the bundled pages use ren
desktop's own Tailwind config (`android/web/src/ren/tailwind.config.ts`) or the same values as CSS variables
(`android/app/src/main/assets/map/index.html`).

## Colour

One accent, volt, on ink and paper. Dark has no hue but volt.

| Role | Light | Dark | `Palette` |
|---|---|---|---|
| Volt (accent, primary keys, links) | `#5543FF` (volt 600) | `#8D98FE` (volt 400) | `accent` |
| Volt, pressed | `#4533D8` (700) | `#B3BDFF` (300) | `accentHi` |
| Text on volt | `#FFFFFF` | `#0B0C17` | `accentInk` |
| Ink | `#0B0C17` | `#F5F5F5` | `ink` |
| Ink, secondary | `#4A4C58` | `#BDBDBD` | `ink2` |
| Ink, muted | `#72747E` | `#919191` | `muted` |
| Paper (surface 0 / 900) | `#FFFFFF` | `#0C0C0C` | `bg` |
| Browser chrome | `#F1F2F6` | `#070707` | `chrome` |
| Raised and cards | `#FFFFFF`, `#F9FAFC` | `#141414`, `#1E1E1E` | `raised`, `card`, `cardHi` |
| Secondary keys | `#F3F3F7`, pressed `#E6E7ED` | `#1E1E1E`, pressed `#2C2C2C` | `key`, `keyHi` |
| Hairlines | `#E6E7ED`, strong `#D3D4DC` | `#1E1E1E`, strong `#2C2C2C` | `line`, `lineHi` |
| Success, signal, danger, info | `#01B568`, `#E6C103`, `#EF2D4A`, `#676AFE` | the same | `ok`, `amb`, `danger`, `info` |

Light and Dark follow the phone. Glass, ren desktop's third theme, is not built.

## Type

- **Bricolage Grotesque** for display: headings at weight 700 to 800, and the poster cut (`.ren-display`: 800 at 86%
  width) for hero lines such as the greeting. One display heading per view, in sentence case.
- **Inter** for all interface text: 14sp body, 13sp labels, 12sp secondary lines, with ren's features
  (`cv02, cv03, cv04, cv11, ss03`).
- **JetBrains Mono** for code, figures and tag labels (10sp capitals, tracked wide, the one uppercase style).

All three are variable fonts in `res/font/` (hashes and licence in `android/FONTS-OFL.txt`); the bundled pages load the
same files through `/res/font/`.

## Shape and motion

- Corners: 10dp controls, 12dp cards, 20dp panels and sheets; chips are pills.
- Surfaces are flat: a hairline, not a shadow or a glow. Nothing pulses for decoration.
- Agent activity is shimmering text ("Ren is working"), not a spinner.
- Motion is quick: 150, 220 and 400 ms on `ease-out-expo` and `ease-smooth`; with animations off in the system,
  entrances land at once.

## Brand

- The logo is the name: lowercase **ren.** with a volt full stop. Its outlines are `ren-logotype.json`, copied from
  ren desktop's `src/components/brand/logotype.ts`. `android/tools/build_icon.py` generates the launcher icon (the
  white logotype on the volt tile), the themed icon, the R notification icon and the wordmark drawables from it.
- An eyebrow is a tag label after a volt `//`.
- The robot is Cato, recoloured from Cato's blue to volt and otherwise unchanged (`android/web/src/cato`,
  `android/web/scripts/gen-ren-robot.mjs`).
