# Emrys for Android

## Build

Needs JDK 17+ and the Android SDK (platform 36, build-tools 36).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleRelease
# app/build/outputs/apk/release/app-release.apk (minified, signed with your local debug key)
```

`app/src/main/assets/emrys/index.html` is generated from `robot/Emrys_the_Robot.html` and `robot/bridge.js`:
`python3 tools/build_robot_asset.py` rebuilds it, and `--check` fails if it is stale or if `bridge.js` fails
TypeScript's strict type check (`robot/tsconfig.json`, TypeScript 7.0.2 through `npx`, so it needs Node).

### The stage layer (ElevenLabs UI)

The robot page carries a voice layer from `stage-ui/`, a small Vite + React + Tailwind v4 project set up for shadcn.
The ElevenLabs UI components `orb`, `shimmering-text` and `matrix` are installed verbatim with the official CLI and
never edited; `npm run verify:components` checks them byte for byte against upstream `elevenlabs/ui@3a28ebab`. Our own
code composes them: the Orb as a soft backlight behind Emrys, a shimmering caption while Emrys works, and the Matrix
light language in the robot's armor and floor slots and on the mini band (`src/matrix/`, `docs/design/EMRYS_MATRIX.md`).
It reads `window.emrys.stageInfo()` and `speech()` from `robot/bridge.js`, which also takes the real voice signals the
app sends (`emrys.voice` and `emrys.levels`, from `voice/EmrysAgent.kt`).

The layer is built into `app/src/main/assets/emrys/vendor/stage-ui.{js,css}` and checked in, so the asset builder
needs no Node. After changing it:

```bash
cd stage-ui && npm ci && npm run build && npm test   # Playwright on the real page
cd .. && python3 tools/build_robot_asset.py
```

The Orb's noise texture is bundled (`assets/emrys/vendor/perlin-noise.png`, provenance in `SOURCE.txt`) and
`ui/RobotView.kt` answers its one URL from it; every other request outside the app's assets stays blocked.

## Install (sideload)

1. Copy the APK to the phone and open it, allowing installs from that source; or `adb install app-release.apk`.
2. Open Emrys › Settings. Enter the brain URL (`https://<your-brain-host>`), the device token (the brain's
   `EMRYS_DEVICE_TOKEN`) and keep or change the session id. Tap **Save & connect**.
3. Grant permissions under Settings › Permissions:
   - Texts, Contacts, Calendar, Phone, Notifications: tap **Allow**.
   - **Android 13+ first**: App info › ⋮ › *Allow restricted settings* (sideloaded apps need this for the next two).
   - Notification access: turn Emrys on (reading and replying to messages in any app).
   - App operator: Accessibility › Emrys app operator › on.
   - Call screening: accept the role prompt.
   - Default assistant: Default apps › Digital assistant app › Emrys (long-press Home/Power opens Emrys's overlay,
     listening, over the app you are in). The first time, the overlay's **Allow mic** key opens Emrys to grant the
     microphone.

## On-device checklist

There is no emulator in the build environment, so these are checked on a real phone:

- [ ] Robot renders, follows light/dark, waves through states while a turn runs; mouth moves while replies are read aloud.
- [ ] Emrys stays in view on Activity and Settings (a short band under the header); tapping the band on Activity or Settings opens Home.
- [ ] Switching tabs never reloads the robot (no stage flash on Home after the first load); the mouth keeps moving with read-aloud replies and ElevenLabs speech on every tab.
- [ ] With Remove animations on, the band appears and disappears without motion and the robot's idle sway stays off.
- [ ] The stage layer: a soft Orb glows behind Emrys in the mood colour (also in airplane mode), the caption reads
      "Thinking", "Working", … while it works, and the Matrix lights the chest, visor, gauntlets and floor; in an
      ElevenLabs voice session the Orb follows the agent's voice.
- [ ] "What's on my calendar tomorrow?" lists events (calendar_list).
- [ ] "Text <contact> I'm running late": contacts_search, then an approval card; Approve sends; the text appears in Messages.
- [ ] Deny the same request: nothing is sent and Emrys says so.
- [ ] With notification access, "Reply to <name>'s WhatsApp: on my way" uses notification_reply after approval.
- [ ] "Remind me in 2 minutes to stretch": a reminder notification arrives; it survives killing the app.
- [ ] "Open Spotify and play my liked songs": app_open, screen_read, screen_act; the red **Stop Emrys** button
      floats while it works and stops further actions when pressed.
- [ ] Turn on airplane mode mid-turn, then off: the app reconnects, pending approvals come back, the turn finishes.
- [ ] Lock the phone while an approval is pending: the notification's Approve asks for unlock; a high-risk
      approval only offers Review, and approving it in the app asks for biometrics.
- [ ] Long-press Home with Emrys as the digital assistant, from another app: the overlay slides up over that app
      and listens; your words appear as you speak; the reply streams in and is read aloud; tapping the scrim or Back
      closes it and stops speech; **Open Emrys** shows the same conversation in the app; **Type** opens a field
      above the keyboard. With the phone locked, it only offers to unlock.
- [ ] Call answering (brain needs `EMRYS_PUBLIC_URL`): "Set up call answering" ends with the number saved in
      Settings › Call answering; **Turn on** shows the carrier's confirmation; a call you let ring goes to
      Emrys, which takes a message; the phone shows "Emrys is answering…" and then the message; **Turn off** restores normal ringing.
- [ ] Live call (after setting up call answering again, and with your own number in Settings): the ongoing
      notification shows what the caller and Emrys say; **Take over** (after unlocking) rings your phone and
      connects you to the caller; when the call ends, the summary replaces the notification and appears in Activity.
- [ ] Approvals (0.4.0): "Text Dana …" shows the contact's name and "You asked: …"; tick "Don't ask again" and
      **Always deny**: the next identical request is declined without a sheet and Activity says "Declined by your
      rule"; **Always allow** asks for biometrics; Settings › Approval rules lists both and **Remove** works while
      connected (disabled offline). A purchase offers only Deny.
- [ ] Notifications (0.4.0): two pending approvals show a "2 things need your OK" summary that goes away at one; a
      call keeps one notification from "answering" to its summary, with the caller's message in it; opening Emrys
      clears approval notifications. Grouping and alert-once behaviour are verified only under Robolectric.
- [ ] Recommended settings (0.4.0): from the power-on screen and Settings, the sheet lists only what would change;
      Apply runs the permission prompt and then Android's default apps screen; Settings › **Undo recommended
      settings** puts the settings back.
