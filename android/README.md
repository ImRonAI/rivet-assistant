# Emrys for Android

## Build

Needs JDK 21 and the Android SDK (platform 36, build-tools 36).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleRelease
# app/build/outputs/apk/release/app-release.apk (minified, arm64-v8a only, signed with your local debug key)
```

`emrys.local.properties` (gitignored, next to this file) bakes defaults into the build: `answeringNumber`,
`braveSearchKey` and `braveAnswersKey`. Without it they start empty and stay editable in Settings.

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
2. Open Emrys and go through the power-on screen: the talk key, the default assistant and phone access. The app
   connects to the ElevenLabs agents by their ids, so there is nothing to sign in to.
3. Grant what Emrys can use under Settings › What Emrys can use:
   - Texts, Contacts, Calendar, Phone, Notifications: tap **Allow**.
   - **Android 13+ first**: App info › ⋮ › *Allow restricted settings* (sideloaded apps need this for the next two).
   - Notification access: turn Emrys on (reading and replying to messages in any app).
   - App operator: Accessibility › Emrys app operator › on.
   - Call screening: accept the role prompt.
   - Default assistant: Default apps › Digital assistant app › Emrys (long-press Home/Power opens Emrys's overlay over
     the app you are in).
4. Settings › Permissions sets each `perm_*` action to "Always allowed" or "Asks you first" from the next conversation.

## On-device checklist

The cloud build environment has no emulator or phone, so these are checked on a real phone:

- [ ] The robot renders on Home and as a band on Activity and Settings; the mouth moves while the agent speaks.
- [ ] The stage layer: the Orb, the shimmering caption and the Matrix follow the agent's state and voice.
- [ ] A typed question reaches the text agent and the answer appears; the voice disc asks for the microphone and
      starts a voice session with the voice agent.
- [ ] Each phone tool from a live agent: `sms_list`, `sms_send`, `contacts_search`, `calls_recent`, `phone_call`,
      `calendar_list`, `calendar_create`, `notifications_list`, `notification_reply`, `reminder_create`,
      `reminders_list`, `reminder_cancel`, `email_draft`, `apps_list`, `app_open`, `device_status`, `screen_read`,
      `screen_act`.
- [ ] `request_approval` (once the owner attaches it to an agent): the approval sheet, Approve behind biometrics,
      Deny, "Always allow", and the notification while Emrys is in the background.
- [ ] A reminder notification arrives and survives killing the app.
- [ ] The default-assistant overlay listens over another app and sends to the text agent; it is locked out on a
      secured lock screen.
- [ ] The search-bar and panel widgets and the launcher shortcuts open the right screens.
