# Hindu Storybooks — Android app

Package `com.mahesh.hindustorybooks`, display name देवों की कहानियाँ.

Kotlin + Views/XML (not Compose). Single Activity + Fragments + bottom navigation:
मुख्य (Home), सूची (Catalog), सहेजी हुई (Saved), सेटिंग (Settings).

## Data — same catalog as the website

The app reads the same `catalog.json` that powers
https://mahashg.github.io/hindu-storybooks/ (bundled copy in
`app/src/main/assets/catalog.json`). Refresh (the Settings
"नई किताबें आईं?" button, plus a daily background refresh) fetches the live
file, validates its structure and HEAD-checks every published PDF and pool
painting, and only then replaces the cached catalog. New arrivals — a planned
book getting its PDF, or a brand-new book appearing with a PDF — carry the
नया tag until the next refresh.

Stories open in an in-app reader (built-in `PdfRenderer`, no WebView, no
external browser): pages render fit to the phone's screen width, with pinch
zoom and +/− buttons, and a default zoom setting in Settings.

## Building (this environment)

Gradle cannot download artifacts over HTTPS here, so Maven artifacts are
mirrored with `~/.tooling/resolve.py` (curl) into `~/.tooling/localrepo`, and
`settings.gradle` points at it. For every build, export (as separate
statements):

- `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"` (without this the
  Gradle daemon handshake fails)
- `JAVA_HOME=~/workspace/.tooling/jdk17`
- `GRADLE_USER_HOME` under the workspace (e.g. `~/workspace/.gradle-hindu`;
  `/tmp` is a 512M tmpfs)

Use system Gradle 8.9 with `--offline`. Debug signing is pinned to
`~/workspace/.tooling/signing/debug.keystore` — never the default keystore,
whose SHA-1 silently rotates. Verify a built APK with
`apksigner verify --print-certs`.

No on-device testing is possible in this environment; a real phone check is
the true proof.
