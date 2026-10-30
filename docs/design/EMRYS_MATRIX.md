# The Emrys Matrix: light language, armor slots and light shows

The ElevenLabs UI `Matrix` (a dot-matrix display of circular SVG cells, brightness 0..1 per cell) is Emrys's second
signature visual after the robot itself. It is how Emrys *shows* what it is doing: an app-specific light language
built into the robot's armor, the stage floor and the mini band, so it is on screen wherever the stage is (the big
stage on Home and onboarding, the band on every other tab and the voice setup).

The component is installed verbatim with the ElevenLabs CLI and never edited. Everything here is our own frame data,
composition and choreography (`android/stage-ui/src/matrix/`), fed by real state only.

## 1. What the component can do (from its source, upstream commit 3a28ebab)

- `rows × cols` grid, `size` (cell px), `gap` (px); the SVG is a fixed `cols*(size+gap)-gap` × `rows*(size+gap)-gap`.
  Scale it by choosing `size`/`gap`, or with a transform on a wrapper.
- `pattern` (one Frame, static; disables autoplay), `frames` + `fps` + `loop` + `onFrame` (rAF with a time
  accumulator; a new `frames` reference restarts at 0, so build frames once), or `mode="vu"` + `levels` (7 rows).
- Cell opacity = `clamp(brightness × value)`. Above 0.05 a cell is lit with the `matrix-pixel-on` gradient; above
  0.5 it also gets the `matrix-glow` blur and `scale(1.1)`. Opacity transitions over 300 ms, so frame changes glow
  softly into each other — design patterns for that, not against it.
- Lit colour comes from `--matrix-on` (default `currentColor`). Unlit cells use `var(--muted-foreground)` at 20 %
  (10 % under `.dark`), not `palette.off`.
- **Every instance shares the same gradient and filter ids**, so all matrices on the page take the first one's
  lit colour. We embrace this: there is one global mood colour, set as `color` on a common ancestor (the stage), and
  every Matrix keeps the default `palette.on = currentColor`. Unlit dots get `--muted-foreground` set on the stage to
  a faint porcelain so an unlit panel reads as a field of tiny glass beads.

## 2. Where the Matrix lives

| Slot | Grid | Where | Job |
|---|---|---|---|
| `chest` | 13×13, circular | The hero emblem on the chest plate, a recessed dark-glass disc | The sigil at rest; one glyph per state; the heart of every light show |
| `mouth` | 3×15 | A slim dark-glass band across the lower visor, under the eyes | Emrys's voice: dot-matrix mouth shapes from the real visemes while it speaks |
| `gauntlet-l`, `gauntlet-r` | 3×11 each | Outer forearm plates | Ambient chases that echo the chest; a beat-synced VU during music moves |
| `floor` | 25×25, circular | A light floor under the robot's feet, in the floor plane | Ripples, radar, celebrations and an LED dance floor |
| `band` | 7×7 (plus an optional 7×N ticker) | Beside the robot in the mini band (`stage.clientHeight < 200`) | The current state's glyph on every screen that shows the band |

The robot's eyes stay the renderer's own soft luminous capsules (they carry gaze and blinks in 3D). The Matrix
gives Emrys a voice, a heart and a floor.

## 3. Slot contract between the robot page and the stage layer

The **robot page** (`android/robot/Emrys_the_Robot.html`) owns geometry, placement, depth and fallbacks. The
**stage layer** (`android/stage-ui`) owns content and choreography. Neither reaches into the other's internals.

### Armor slots (`chest`, `mouth`, `gauntlet-l`, `gauntlet-r`)

The robot page creates, once, inside its mesh group (`gMesh`):
```html
<g class="emrys-slot" id="slot-chest" data-slot="chest">
  <foreignObject x="0" y="0" width="130" height="130">
    <div xmlns="http://www.w3.org/1999/xhtml" class="emrys-slot-root" style="width:130px;height:130px"></div>
  </foreignObject>
</g>
```
Local boxes (CSS px): `chest` 130×130, `mouth` 150×30, `gauntlet-l` / `gauntlet-r` 110×30. Every frame the robot:
- sets the `<g>`'s `transform="matrix(a b c d e f)"` mapping the local box onto the projected armor surface (the
  emblem disc, the visor band, the forearm plate), so the dots move, turn and foreshorten with the body;
- keeps the `<g>` in the painter's order immediately after the face it's pinned to (move it in the `gMesh` child
  list only when its position changes), so arms, hands, the phone, the guitar and every prop pass in front of it;
- sets `opacity` from how squarely the surface faces the camera (fade out between a facing cosine of 0.4 and 0.15)
  and `display="none"` when it faces away or the current scene hides that armor part.

Fallback: when a slot root has `data-filled="1"` (the stage layer set it after mounting a Matrix there), the robot
skips its own native drawing for that part (the emblem's core, the visor mouth). When it isn't filled (no stage
layer, `lowFx`, a WebView without the bundle) the robot draws its native look, so Emrys is always complete.

### The floor slot

The robot page creates `<div class="emrys-floor" id="slot-floor"><div class="emrys-slot-root"
style="width:420px;height:420px"></div></div>` inside `.stage`, after the Orb container and before the `svg`,
absolutely positioned with `transform-origin: 0 0`. Every frame it sets `transform: matrix3d(...)`, the exact
homography that maps the 420×420 square onto a square on the floor plane centred under the robot (side about 2.2×
the robot's shoulder width), from the camera's projection of its four corners. The robot's shadows (in the `svg`)
fall on top of the floor dots. It's hidden on a squeezed stage and when the floor isn't drawn.

### State the stage layer reads

`window.emrys.slots()` → `{ chest: {visible, facing}, mouth: {...}, ... }` and `window.emrys.scene()` →
`{ move: <current move id>, beat: { bpm, phase } | null }` (the beat of the music scenes, from the page's own
clock or `emrys.beat`). Both are additions to the bridge API; nothing existing changes. The stage layer also hooks
`emrys.state`, `emrys.mouth` / `emrys.viseme` / `emrys.quiet` (real speech timing from TTS word boundaries and the
ElevenLabs agent's alignment) and any real levels the app sends.

## 4. The light language (our frame data)

Grids are drawn for the 13×13 chest; the band uses 7×7 versions; the floor uses radial generators.

- **Sigil** (`docs/design/emrys-sigil.json`): an E inside a halo ring of dots, its middle bar ending in a half-lit
  spark. It's the resting chest, the boot finale, the app icon, the notification icon and the brand mark
  (`res/drawable/emrys_sigil.xml`).
- **idle**: the sigil breathing (brightness 0.55 → 0.9 over 4 s), with a slow glint that crosses it every ~12 s.
- **greeting**: the sigil assembles from scattered sparks (they converge over ~1.2 s), then one ripple leaves the
  floor.
- **listening** (coral, a live mic): concentric rings travel outward from the centre like sonar; when the app sends
  real input levels, ring brightness follows them.
- **speaking**: the mouth band shows the viseme shapes (`sil` a thin line, `PP` closed, `FF` lower-lip, `aa` wide
  oval, `O`/`U` round, `E`/`I` wide flat, and so on); the chest shows a radial VU from the same envelope; the floor
  breathes with it at low brightness.
- **thinking / searching** (glacier): a comet orbits the halo ring with a fading tail while inner dots twinkle into
  a constellation; *searching* adds a radar beam sweeping the floor.
- **working** (desk): a weave — diagonal threads crossing, like a loom.
- **texting**: three typing dots that bounce, then resolve into a speech bubble.
- **emailing**: an envelope drawn on dot by dot, its flap closing.
- **calling**: a handset with signal arcs pulsing outward.
- **video**: an aperture iris opening and closing.
- **waiting** (honey, waiting on your OK): a keyhole that breathes; the floor holds one slow honey ring.
- **done** (sage): a check drawn stroke by stroke, a burst of sparks outward across the floor, then back to the sigil.
- **failed** (coral): a soft × that fades, then a dimmed sigil.
- **sleeping**: the sigil at 25 % with a single `z` drifting up every few seconds, at a low frame rate.
- **music moves** (DJ, rock, pop, reggae, country, folk, dance, robot): the floor becomes an LED dance floor locked to
  the scene's beat (checker pulses for pop, rolling waves for reggae, equaliser columns for DJ and rock, a slow
  campfire flicker for folk and country), the gauntlets run a beat VU, the chest pulses on the downbeat.
- **Transitions**: a 180 ms column wipe from one glyph to the next. Never a hard cut.

Colour: the mood colour of the design doc (aurora `#B7C2FF` at rest, glacier `#8FDBE8` working, honey `#E8C07C`
waiting, sage `#97D4A6` done, coral `#F07B6C` listening and failed), applied as the stage's `color`, easing between
moods.

## 5. Light shows

1. **Boot** (page ready, about 2.2 s): the floor ignites outward from the feet in one ripple; sparks converge into
   the chest sigil; the gauntlets sweep from wrist to elbow; the mouth band blinks once. Then idle.
2. **Every state change**: the chest wipes to the new glyph; the floor sends one ring in the new colour.
3. **Done**: the celebration above. **Failed**: one soft coral ring inward, then dim.
4. **Speech**: mouth + chest VU + floor breath for as long as real speech lasts; `quiet` returns to the state glyph.
5. **Music**: the dance floor for as long as the scene plays.

## 6. Budget and fallbacks

- At most about 800 cells on the page and about 150 cells above 0.5 (the glow filter) at any moment. Measure the
  page's own fps readout in Playwright with CPU throttling (CDP `Emulation.setCPUThrottlingRate` 4×) while the robot
  dances; the stage must not trip the page's `degrade()`.
- `lowFx`: unmount the floor and gauntlets (the robot draws its native look); keep the chest with static patterns.
- Reduced motion: static patterns only (each state's resting glyph), no light shows, no floor.
- Squeezed stage (the mini band): no floor, no gauntlets, no mouth; the `band` glyph instead.
- `document.hidden`: everything paused.
