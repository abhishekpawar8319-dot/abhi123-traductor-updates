# Abhishek – Spanish ⇄ English conversation translator

A separate Android app (`com.abhi.vozconversa`) that does one job: it listens to a conversation,
waits for each **whole sentence**, translates Spanish → English or English → Spanish, **saves** it,
and keeps going for as long as it is switched on. It does not touch the Traductor app or its data.

## How to use
1. Install `Abhishek.apk` (in the repository root) and open it.
2. Tap **Start listening** and allow the microphone and notifications.
3. Tap **Allow 24/7** once, so Android does not pause the app at night.
4. The first start downloads the Spanish–English language pack (about 30 MB, one time). After that, translation works offline.
5. Talk. Each sentence appears with its translation.
6. **Speaker button** (🔊 / 🔇): tap it at any time to have translations read aloud or to stay silent. It also works from the notification while the screen is off, and turning it off in the middle of a sentence stops at once. Tap 🔊 on any translation to hear it again.
7. **Fix a misheard word** (✏️ on any sentence, or tap the sentence): tap only the wrong word, then pick a
   suggestion, type the right spelling, or press **🎤 Say just this word**. The sentence is translated again and the
   saved entry updated, with no need to repeat the whole sentence. With **Always change** ticked, the app learns the fix and
   corrects that word by itself next time (see **📖 Word fixes** in Saved translations to remove one).
8. **Saved translations**: search, delete, share as `.txt` or `.csv`, and choose how long to keep them.

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

## Working offline (no internet)
Set up once while connected: tap **📴 Offline** on the main screen, then **1. Download all** (translation pack and, on
Android 13+, the Spanish and English speech models), then **2. Phone settings** to check that Spanish and English are
downloaded for offline speech recognition and for text-to-speech. The button shows **Offline: ready ✅** when done.

After that:
- Listening switches to the speech pack on the phone as soon as the internet goes (no signal, airplane mode, Wi-Fi without
  internet) and back to Google's online recognizer when it returns. It never waits on network retries.
- Translation uses the pack on the phone and never checks the internet.
- The speaker uses a voice stored on the phone.
- "Say just this word" also works offline.
- Offline speech packs understand one language at a time, so in Auto mode the app alternates Spanish/English between turns.
  For one person talking, the ES/EN buttons work best offline.

## Sentence auditor
Every sentence is checked before it is translated:

| Check | What happens |
|---|---|
| A word you taught it ("tore" → "torre") | fixed automatically |
| A small word said twice ("the the", "la la") | fixed automatically (real repeats like "no no" are kept) |
| Spanish word without its accent ("camion", "dia", "telefono") | fixed automatically |
| Spanish question without accent ("Donde está…?") | becomes "¿Dónde está…?" |
| A word from the other language ("Necesito **the** herramientas") | shown in orange; tap to fix |
| A word the recognizer was unsure about (its other guesses differ) | shown in orange with the other guesses as one-tap suggestions |
| Other repeated words ("we work work") | shown in orange |

Names such as Los Angeles, El Paso or Rio de Janeiro are not flagged.

## Build
```
cd voice-translator
./gradlew testDebugUnitTest assembleRelease
```
Needs the Android SDK (`local.properties` → `sdk.dir`). The release is signed with a key from `keystore.properties` (not in git). Without that file the APK gets debug signing.

## Tests
228 automated tests (`app/src/test`):

- **Auditor, round 1: 100 tests with 100 different sentences** (10 groups of 10): learned fixes, doubled words,
  missing accents, Spanish questions, English inside Spanish, Spanish inside English, recognizer unsure, tap-to-fix-and-learn,
  correct sentences left alone, and the full flow (heard → audited → translated → saved → one word fixed → re-translated →
  same entry updated). Deliberately breaking the auditor makes these tests fail, so they really check it.
- **Auditor, round 2:** 100 new correct sentences with tricky words ("no", "me", "Que tengas…", "café", "piñata", place names)
  must come through untouched. *This round found 4 mistakes* (Los Angeles, El Paso, Las Vegas and Rio de Janeiro were wrongly
  flagged); fixed and re-tested.
- **Auditor, round 3:** 100 new mistakes injected into correct sentences (29 missing accents, 43 doubled words, 28 other-language
  words): all repaired or pointed out exactly.
- The 200 earlier sentences pass the auditor with no false alarms, and 100 conversation tests now run with the auditor on.
- Upgrading from 1.2 keeps saved translations; the fix window opens and marks only the doubtful word.
- 100 conversation tests, a 2-hour session with 1,440 different sentences, speaker button, storage and logic tests.

- Offline (11 tests): switching when the internet drops or comes back, falling back from the on-device model to Google's
  offline pack, missing-pack handling in Auto mode, the offline recognizer request, offline voice choice, and a conversation
  where the internet drops mid-way and comes back (all 8 sentences translated and saved, no wait longer than 0.2 s).

Version 1.4 result: all 239 tests passed on 3 separate full runs.
The microphone, Google speech recognition and ML Kit translation only run on a real phone, so they still need a test on a device.
