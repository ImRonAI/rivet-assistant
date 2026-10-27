# Rivet for Android

## Build

Needs JDK 17+ and the Android SDK (platform 36, build-tools 36).

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleRelease
# app/build/outputs/apk/release/app-release.apk (minified, signed with your local debug key)
```

`app/src/main/assets/rivet/index.html` is generated from `robot/Rivet_the_Robot.html` and `robot/bridge.js`:
`python3 tools/build_robot_asset.py` rebuilds it, and `--check` fails if it is stale.

## Install (sideload)

1. Copy the APK to the phone and open it, allowing installs from that source; or `adb install app-release.apk`.
2. Open Rivet › Settings. Enter the brain URL (`https://<your-brain-host>`), the device token (the brain's
   `RIVET_DEVICE_TOKEN`) and keep or change the session id. Tap **Save & connect**.
3. Grant permissions under Settings › Permissions:
   - Texts, Contacts, Calendar, Phone, Notifications: tap **Allow**.
   - **Android 13+ first**: App info › ⋮ › *Allow restricted settings* (sideloaded apps need this for the next two).
   - Notification access: turn Rivet on (reading and replying to messages in any app).
   - App operator: Accessibility › Rivet app operator › on.
   - Call screening: accept the role prompt.
   - Default assistant: Default apps › Digital assistant app › Rivet (long-press Home/Power opens Rivet's overlay,
     listening, over the app you are in). The first time, the overlay's **Allow mic** key opens Rivet to grant the
     microphone.

## On-device checklist

There is no emulator in the build environment, so these are checked on a real phone:

- [ ] Robot renders, follows light/dark, waves through states while a turn runs; mouth moves while replies are read aloud.
- [ ] "What's on my calendar tomorrow?" lists events (calendar_list).
- [ ] "Text <contact> I'm running late": contacts_search, then an approval card; Approve sends; the text appears in Messages.
- [ ] Deny the same request: nothing is sent and Rivet says so.
- [ ] With notification access, "Reply to <name>'s WhatsApp: on my way" uses notification_reply after approval.
- [ ] "Remind me in 2 minutes to stretch": a reminder notification arrives; it survives killing the app.
- [ ] "Open Spotify and play my liked songs": app_open, screen_read, screen_act; the red **Stop Rivet** button
      floats while it works and stops further actions when pressed.
- [ ] Turn on airplane mode mid-turn, then off: the app reconnects, pending approvals come back, the turn finishes.
- [ ] Lock the phone while an approval is pending: the notification's Approve asks for unlock; a high-risk
      approval only offers Review, and approving it in the app asks for biometrics.
- [ ] Long-press Home with Rivet as the digital assistant, from another app: the overlay slides up over that app
      and listens; your words appear as you speak; the reply streams in and is read aloud; tapping the scrim or Back
      closes it and stops speech; **Open Rivet** shows the same conversation in the app; **Type** opens a field
      above the keyboard. With the phone locked, it only offers to unlock.
- [ ] Call answering (brain needs `RIVET_PUBLIC_URL`): "Set up call answering" ends with the number saved in
      Settings › Call answering; **Turn on** shows the carrier's confirmation; a call you let ring goes to
      Rivet, which takes a message; the phone shows "Rivet is answering…" and then the message; **Turn off** restores normal ringing.
- [ ] Live call (after setting up call answering again, and with your own number in Settings): the ongoing
      notification shows what the caller and Rivet say; **Take over** (after unlocking) rings your phone and
      connects you to the caller; when the call ends, the summary replaces the notification and appears in Activity.
