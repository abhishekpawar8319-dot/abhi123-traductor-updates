# Traductor – app updates

New versions of the Traductor app (Spanish ⇄ English/Hindi translator and tower erection checklist).

The newest APK is `SpanishTranslator.apk` and its version is in `latest.json`. The app checks this repository when it opens,
shows "Update available" and installs it with one tap. Your checklist and history stay on the phone.

This channel serves the **public edition**: company data (the DOA approval sheet and the site drawings) is not in it.
The full edition is shared privately and never replaces itself with the public one.

To publish a version: replace the APK and raise `versionCode` in latest.json.

To install by hand: download `SpanishTranslator.apk` and open it on the phone.

## Abhishek – Spanish ⇄ English voice translator (separate app)

`Abhishek.apk` is a separate app for live Spanish ⇄ English conversation translation that stays on 24/7 and saves every sentence.
It installs next to Traductor and does not change it. Source code and details: [`voice-translator/`](voice-translator/README.md).
