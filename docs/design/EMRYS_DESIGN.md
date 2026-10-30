# Emrys — the Noir design system

Emrys is renamed **Emrys** and re-dressed from an industrial "test bay" (graphite, safety orange, keycaps, condensed
uppercase type, telemetry everywhere) into an editorial, high-end interface: quiet, confident, warm and a little
heroic. Every action, state, setting and flow stays exactly as it is. Only how it looks, moves and reads changes.

The tokens live in `android/app/src/main/java/com/hiron/emrys/ui/Theme.kt` (Compose) and are mirrored as CSS
custom properties in the robot page. This document is the brief every screen, the robot and the stage follow.

## 1. Name and voice

- **Emrys** (EM-riss), a Welsh given name meaning "immortal" and the true name of Merlin, the counsel behind the
  hero. Two syllables, human, rare. Say it in full; never "EMRYS" in caps except where a launcher or OS forces it.
- The wordmark is "Emrys" set in Instrument Serif, regular, sentence case. A small halo dot may sit after it
  (`Emrys·`), replacing the orange full stop. No other punctuation tricks.
- **Rename the user-visible name only.** App label, copy, notification titles, content descriptions, the robot's
  labels and the widget text say Emrys. Package names (`com.hiron.emrys`), class and file names (`EmrysRuntime`,
  `EmrysTheme`), the bridge (`window.emrys`, `EmrysHost`), asset paths (`assets/emrys/`), test tags, settings keys,
  stored file names and protocol fields keep their names: changing them breaks installs, stored data and the brain.
- Copy is plain, warm and short, in sentence case. "Emrys is waiting on your OK", not "QUEUE: 1 PENDING".
  Never invent new claims; reword only to match the new voice, and keep every piece of information that was shown.

## 2. Principles

1. **Editorial, not industrial.** A magazine spread on near-black paper: big serif moments, small precise sans,
   generous space, hairline rules. No keycap lips, bevels, LEDs, degree ticks or uppercase mono labels.
2. **Porcelain leads, halo whispers.** The primary call to action is a porcelain pill with noir ink. The halo
   accent (pale periwinkle) is for focus, links, the user's own words and the live voice, at most one halo element
   per region of the screen.
3. **Status is a dot and a sentence.** A 6 dp status dot in its colour, then words. Replace tag plates and
   telemetry readouts with quiet status lines, keeping every value they showed.
4. **Glass over boxes.** Cards are soft, translucent surfaces with a 1 dp hairline and large radii; elevation comes
   from tone, not shadows.
5. **Slow and soft motion.** Things settle; nothing snaps or pings. Respect reduced motion everywhere.
6. **Accessible by default.** Text contrast ≥ 4.5:1 (≥ 3:1 for 18 sp+), touch targets ≥ 48 dp, every existing
   content description and semantics role kept (and renamed to Emrys where they say Emrys).

## 3. Colour (Noir)

| Token | Value | Use |
|---|---|---|
| `bg` | `#0A0A0C` | the page |
| `card` / `cardHi` | `#111114` / `#17171B` | cards, pressed cards |
| `field` | `#131316` | composer, chips |
| `raised` | `#141417` | sheets, dock |
| `key` | `#18181C` | soft secondary pills, icon keys |
| `tile` | `#1A1A1E` | inputs, Emrys's bubbles, wells |
| `high` | `#242429` | the active segment |
| `hud` | `#121215` at 85% | glass cards over the stage |
| `glass` | `#0A0A0C` at 85% | sticky headers, the tab bar |
| `ink` | `#F3F2EE` porcelain | primary text, the primary pill's fill |
| `ink2` | `#9E9DA4` | secondary text |
| `line` / `lineHi` | porcelain at 7% / 14% | hairlines; pressed or focused borders |
| `edge` / `edgeSoft` / `dot` | white at 9% / 4% / 18% | frames, wells, step dots |
| `accent` (halo) | `#B7C2FF` | focus, links, the user's words, the live voice |
| `accentHi` / `accentDeep` / `accentSoft` | `#CDD5FF` / `#7F8EF0` / halo at 12% | halo gradients, the user's bubble |
| `accentInk` | `#0A0A0C` | ink on porcelain and on halo |
| `cy` (glacier) | `#8FDBE8` | Emrys working, streaming |
| `ok` (sage) | `#97D4A6` | connected, granted, done |
| `amb` (honey) | `#E8C07C` | waiting on your OK |
| `rec` (coral) | `#F07B6C` | live, failed, destructive |

The **aurora** is the voice's signature gradient: `accentHi → accent → accentDeep`, sometimes with glacier at the
edge. It appears only where Emrys is live: the talk key while listening or speaking, the stage orb, the robot's
visor glow. Destructive confirmation uses coral text and a coral hairline, never a coral flood.

## 4. Type

- **Instrument Serif** (`Display`, regular and italic): greetings, screen titles, onboarding and voice-setup
  headlines, the approval headline, quotes (the message Emrys will send), counters and the wordmark. Sentence case,
  tight negative tracking. Italic for one emphasised word at most.
- **Geist** (`Sans`): all interface text. Medium for labels and titles, Regular for body. No SemiBold except where a
  small label must hold its weight on glass.
- **Geist Mono** (`Mono`): ticking figures (times, durations, counts), codes and technical details only.
- **Eyebrows** (`Type.eyebrow`) are the only uppercase text: 11 sp, 0.12 em tracking, `ink2`.
- Remove `.uppercase()` calls on anything that is not an eyebrow; rewrite those source strings in sentence case.

## 5. Shape, space and surfaces

- Radius scale: 10 (chips' inner wells), 14 (inputs, small cards), 20 (cards), 28 (sheets, stage frame), full
  (pills, keys, the talk disc).
- Spacing on a 4 dp grid. Screen gutters 20 dp (16 dp on phones under 360 dp wide). Section gaps 28–36 dp.
- Hairline 1 dp in `line`; a focused or pressed element brightens to `lineHi`. No drop shadows on cards; sheets
  may carry one very soft ambient shadow.
- Glass: `hud`/`glass` fills with a 1 dp `line` border and, on API 31+, an optional background blur where the
  platform supports it; the solid fill is always the fallback.

## 6. Components (old → new)

| Was | Becomes |
|---|---|
| Keycap / KeyBody with a 1 px lip | Soft pill: `key` fill, `line` hairline, no shadow; pressed brightens to `cardHi` + `lineHi` |
| PrimaryKey (orange glow) | Porcelain pill (`ink` fill, `accentInk` label, Geist Medium); pressed: `accentHi` hairline glow; disabled: `edge` fill, `ink2` label |
| SecondaryKey / TextKey (mono caps) | Outline pill / text link in Geist Medium, sentence case, `ink` or `accent` for links |
| TagPlate / StatusTag / Tag | Status dot + label, sentence case, tone colour on the dot only |
| HudCard / HudHeaderRow | Glass card with an eyebrow and a serif or Geist title |
| Odometer / StatTile / Readout | Serif figures (`Type.stat`) with a Geist label under them |
| HudHeader telemetry (MODE / LINK / QUEUE) | A single quiet status line: dot + "Connected · 2 waiting · Voice", same values |
| HaloTalkKey (orange disc) | Porcelain voice disc with a thin aurora ring; listening/speaking: the ring breathes in aurora; mic glyph in noir |
| RecessedField / ComposerCard | Rounded `field` card, 1 dp hairline, halo caret and focus ring |
| Toggle / EmrysSwitch | Slim track (`track` off, `ink` on with a noir thumb) |
| Step dots | `dot` dots, the current one a short porcelain capsule |
| Section numbers on plates ("01 LINK") | Eyebrow + serif section title; numbering may stay as small Geist figures |

Icons: Material Outlined (or Rounded) at 20–22 dp, `ink2` at rest, `ink` when active. No filled heavy glyphs.

## 7. Motion

`Motion.QUICK 140`, `BASE 260`, `SLOW 480`, `STAGGER 50`, breathing at `1800`, pulses at `2400`, easing
`Emphasized (0.2, 0, 0, 1)`. Entrances fade and rise 8 dp; sheets settle with a soft spring. Infinite animations hold
a resting frame when the system turns animations off (`rememberReduceMotion()`).

## 8. Emrys the robot

A hero you'd trust with your phone: elegant, approachable, influential, trendy. Think a couture superhero figure
rendered as a refined collectible, not a toy.

- **Proportions:** taller and leaner than the old toy. About six heads tall, broad shoulders tapering to a narrow
  waist, long legs, slender forearms. Still rounded and soft-edged so it stays friendly.
- **Head:** a smooth ovoid helmet in porcelain with one wraparound smoked-glass visor. No antenna and no ear
  cans; a slim crest line runs from the brow over the crown. The eyes stay expressive (same eye shapes and visemes,
  drawn as soft luminous capsules in halo/glacier with a gentle bloom).
- **Chest:** a recessed dark-glass emblem disc that holds the 13×13 Matrix (the sigil and the state glyphs). This is
  the hero's mark. A slim dark-glass band across the lower visor holds the voice Matrix, and the outer forearm plates
  hold two small ones. Each has a native fallback look (`EMRYS_MATRIX.md` §3).
- **Cape:** a short structured cape (or high-collared mantle) from the shoulders in deep midnight
  (`#15182A`) with a halo-lit inner edge. It sways with the body's motion and must never clip through the body in
  any move; it may fold away (scale to the shoulders) during seated or prop-heavy moves.
- **Finish:** porcelain (`#ECEAE4`) shells, graphite (`#26262C`) joints and under-suit, hairline seams in
  `line`-weight darker strokes, halo piping on the gauntlets and boots. No orange anywhere.
- **Stage:** replace the calibration turntable and its degree ticks/labels with a soft spotlight pool and two faint
  concentric rings. The mood glow stays and follows the same states.
- **HUD cards on the stage** (reasoning trace, email, phone/text, video): glass cards in the Noir style, Geist text,
  sentence case ("Thinking", not "REASONING TRACE"), status dots instead of tag plates.

## 9. The stage, the Matrix and ElevenLabs UI

The robot page is the one HTML surface in the app, so the ElevenLabs UI components run there, installed with the
official CLI into a small React project and bundled offline into the page. They are never edited; they are composed
and styled from our own code. Visual priority on the stage: **the robot first, the Matrix second, the Orb third.**

- **Matrix** (`@elevenlabs` `matrix`): Emrys's light language and second signature visual, built into the armor (the
  chest emblem, a voice band across the lower visor, the gauntlets), a light floor under its feet and the mini
  band's glyph. Its slots, patterns, light shows and budget are in `docs/design/EMRYS_MATRIX.md` (binding). The
  sigil in `docs/design/emrys-sigil.json` is also the app icon, the notification icon and the brand mark
  (`res/drawable/emrys_sigil.xml`, drawn beside the wordmark).
- **Orb** (`@elevenlabs` `orb`): a soft backlight behind Emrys, coloured from the mood (aurora at rest, glacier
  working, honey waiting, sage done, coral live/failed), with `agentState` from what Emrys is doing. It stays
  subtle so the crisp dots of the Matrix read in front of it.
- **ShimmeringText**: the big stage's one-line caption while Emrys works ("Thinking", "Searching the web",
  "Drafting a text").
- Others (BarVisualizer) only where they show real state; never with `demo` or invented audio data.

## 10. Invariants (do not break)

- Every action, state, screen, setting, sheet, notification and flow behaves exactly as before.
- Every test tag, semantics role, content description and `contentDescription`-based lookup stays (renamed only
  where its text says Emrys).
- The robot bridge API (`window.emrys.state/trace/email/thread/say/mouth/quiet/viseme/theme/focus/play/beat/moves`,
  the `ready` message, `window.__emrys` test hook) keeps its names, arguments and behaviour; additions are allowed.
- All 40+ robot moves still play and read clearly; all `STATE_MOVE` states and moods still apply.
- No new fakes, stubs or demo data in product code.

## 11. Verification

- Android: `./gradlew testDebugUnitTest lintDebug assembleRelease` passes; screenshots in `android/build/screens/`
  are reviewed by eye; the release APK is installed and walked through on an emulator.
- Robot and stage: the real page served over HTTP and driven with Playwright through every state and move, with
  screenshots reviewed by eye, and checked inside the app's WebView on an emulator.
