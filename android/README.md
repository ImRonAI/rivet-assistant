# ren mobile for Android

## Build

Needs JDK 21, the Android SDK (platform 36, build-tools 36) and Node 22 with npm (Gradle builds the app's web pages).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleRelease
# app/build/outputs/apk/release/app-release.apk (minified, arm64-v8a only, signed with your local debug key)
```

`ren.local.properties` (gitignored, next to this file) bakes defaults into the build: `answeringNumber`,
`braveSearchKey`, `braveAnswersKey` and `geminiToken` (the workspace VM's bearer token, for Ren's hub, Ren's search and
the Strands agents). Without it they start empty and stay editable in Settings.

### The bundled pages (`web/`)

The robot and the search page are a Vite + React project in `web/`. Gradle's `webInstall` (`npm ci`) and `webBuild`
(`npm run build`) build them into `app/src/main/assets/ren/web/` before every build; that folder is not in git.

- `src/cato/` is Cato, the robot of catolabs.com, byte for byte. `npm run gen:robot` generates ren's robot from it
  into `src/robot/` (volt instead of Cato's blue), and `npm run check:robot` fails if either is stale or edited.
- `src/ren/` is ren desktop's search results page and everything it imports, byte for byte, with its Tailwind config.
  `npm run check:ren` verifies it against `SHA256SUMS`; `SOURCE.txt` says how the file list is made. `src/search/` is
  the phone's page around it.
- `npm test` runs Playwright on the real robot page.

The agent panel's chat page is still built from `stage-ui/` (ElevenLabs UI) into `assets/ren/chat/`, which is in git.

The launcher icon, notification icon and wordmark are generated from the "ren." logotype
(`../docs/design/ren-logotype.json`) by `python3 tools/build_icon.py`; `--check` fails if they are stale.

## Install (sideload)

1. Copy the APK to the phone and open it, allowing installs from that source; or `adb install app-release.apk`.
2. Open Ren and go through the power-on screen: the talk key, the default assistant and phone access. The app
   connects to the ElevenLabs agents by their ids, so there is nothing to sign in to.
3. Grant what Ren can use under Settings › What Ren can use:
   - Texts, Contacts, Calendar, Phone, Notifications: tap **Allow**.
   - **Android 13+ first**: App info › ⋮ › *Allow restricted settings* (sideloaded apps need this for the next two).
   - Notification access: turn Ren on (reading and replying to messages in any app).
   - App operator: Accessibility › Ren app operator › on.
   - Call screening: accept the role prompt.
   - Default assistant: Default apps › Digital assistant app › Ren (long-press Home/Power opens Ren's overlay over
     the app you are in).
4. Settings › Permissions sets each `perm_*` action to "Always allowed" or "Asks you first" from the next conversation.

## On-device checklist

These are checked on a real phone:

- [ ] The browser: pages load, tabs open and close, a download saves, a search opens ren's results page.
- [ ] Settings › Remote tools attaches to Ren's hub and shows whether ren desktop is attached.
- [ ] The robot renders in the agent panel (Ask Ren); the mouth moves while the agent speaks.
- [ ] A typed question reaches the text agent and the answer appears; the voice disc asks for the microphone and
      starts a voice session with the voice agent.
- [ ] Each phone tool from a live agent: `sms_list`, `sms_send`, `contacts_search`, `calls_recent`, `phone_call`,
      `calendar_list`, `calendar_create`, `notifications_list`, `notification_reply`, `reminder_create`,
      `reminders_list`, `reminder_cancel`, `email_draft`, `apps_list`, `app_open`, `device_status`, `screen_read`,
      `screen_act`.
- [ ] `request_approval` (once the owner attaches it to an agent): the approval sheet, Approve behind biometrics,
      Deny, "Always allow", and the notification while Ren is in the background.
- [ ] A reminder notification arrives and survives killing the app.
- [ ] The default-assistant overlay listens over another app and sends to the text agent; it is locked out on a
      secured lock screen.
- [ ] The search-bar and panel widgets and the launcher shortcuts open the right screens.
