# Voz Traductor – Spanish ⇄ English conversation translator

A separate Android app (`com.abhi.vozconversa`) that does one job: it listens to a conversation,
waits for each **whole sentence**, translates Spanish → English or English → Spanish, **saves** it,
and keeps going for as long as it is switched on. It does not touch the Traductor app or its data.

## How to use
1. Install `VozTraductor.apk` (in the repository root) and open it.
2. Tap **Start listening** and allow the microphone and notifications.
3. Tap **Allow 24/7** once, so Android does not pause the app at night.
4. The first start downloads the Spanish–English language pack (about 30 MB, one time). After that, translation works offline.
5. Talk. Each sentence appears with its translation.
6. **Speaker button** (🔊 / 🔇): tap it at any time to have translations read aloud or to stay silent. It also works from the notification while the screen is off, and turning it off in the middle of a sentence stops at once. Tap 🔊 on any translation to hear it again.
7. **Saved translations**: search, delete, share as `.txt` or `.csv`, and choose how long to keep them.

Modes: **Auto ES⇄EN** detects who is speaking. **Español → EN** and **English → ES** lock the direction, which is the most reliable option when only one person talks.

## How it works
| Requirement | How |
|---|---|
| Whole sentence | The recognizer waits for a 1.5 s pause. If a sentence still ends on a word like "and", "y", "que" or "the", the app waits up to 1.8 s more and joins the continuation before translating. |
| Both directions | Android 14+ reports the spoken language; on any phone the text is also checked for Spanish/English words and accents. The recognizer is primed for the other language after each sentence, since conversations alternate. |
| Keeps translating | A foreground service with a silent notification restarts listening after every sentence, silence or error (with back-off), and a watchdog restarts a stuck recognizer every 10 s. |
| 24/7 | Partial wake lock, battery-optimisation exemption and a sticky service. After a reboot, Android does not allow an app to switch the microphone on by itself, so a silent notification lets you resume with one tap. |
| Saved | Every sentence goes into an on-phone SQLite database straight away, even if translation fails (it is then saved untranslated). |
| Doesn't disturb the phone | Text only, no audio is stored (about 200 bytes per sentence, under 2 MB for a very busy day). Old entries are removed automatically (1 year by default; 30 days–forever). The notification is low priority, with no sound or vibration. When speaking a translation, the app lowers other audio instead of stopping it and pauses the microphone so it does not translate itself. |

## Build
```
cd voice-translator
./gradlew testDebugUnitTest assembleRelease
```
Needs the Android SDK (`local.properties` → `sdk.dir`). The release is signed with a key from `keystore.properties` (not in git). Without that file the APK gets debug signing.

## Tests
119 automated tests (`app/src/test`):

- **100 conversation tests, each with different sentences.** Every test has its own Spanish sentence and its own
  English sentence, so 200 sentences in total, and none is repeated (`app/src/test/resources/*.tsv`; a test checks this).
  Ten situations rotate across the 100 tests: phone reports the language; older phone that doesn't; Spanish speaker
  pauses mid-sentence; English speaker pauses mid-sentence; filler sounds and noise; direction locked with the buttons;
  English first with the microphone primed for Spanish; translator busy on the first try; messy recognizer text; and quick
  back-and-forth. Each checks the direction, that the whole sentence was kept, the translation, saving, and export.
- A 2-hour non-stop session with 1,440 sentences that are all different: order kept, all saved, under 300 KB.
- Speaker button: tapping turns it off and on, it is remembered, and every saved translation has a 🔊 play button.
- Logic tests (language guess, sentence joining, restart back-off, retention, export) and database tests.

Version 1.1 result: all 119 tests passed on 3 separate full runs.
The microphone, Google speech recognition and ML Kit translation only run on a real phone, so they still need a test on a device.
