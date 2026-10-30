# jpdb offline

An Android app that makes [jpdb.io](https://jpdb.io) reviews usable on bad mobile signal.

It shows the real jpdb.io website. In the background it keeps a copy of your due vocabulary reviews on the phone. When the site is slow or you have no signal, you can keep reviewing in a native screen that never waits on the network. Your grades are sent to jpdb when the connection comes back, even if the app is closed by then.

## Features
- **The real website:** jpdb.io runs in a full-screen WebView with your normal login. Everything on the site works as usual.
- **Offline review:**
  - A native review screen with pitch-accent readings, meanings, Pass/Fail or 5-grade buttons, and undo.
  - Japanese text uses Japanese kanji shapes rather than Chinese ones.
- **Switches when the connection is bad:**
  - With no connection at all, the app opens offline review straight away.
  - If a jpdb page hasn't appeared after 3 s (you can change this), a bar offers **Review offline**.
- **Background uploads:** grades are queued on the phone and retried until they go through. Android's WorkManager finishes any uploads left over after the app is closed.
- **Background refresh:** every 2 h (you can change this), the app uploads anything pending, then downloads your current due cards, so the offline queue is always ready.
- **No double reviews:** before uploading, the app checks each card is still due. If you've already reviewed it on the website or another device, it skips that offline grade.
- **Light/dark mode:** both the website and the native screens follow the system setting. The native screens use Material You colours.
- **No Google Play Services needed:** it works on GrapheneOS.

## Setup
1. Install the APK. The build steps are below.
2. Open the app and log in to jpdb as usual.
3. Tap **⚡ Review offline**. The first sync reads your API key from jpdb.io/settings and downloads your due cards.
   - If it can't find the key, paste it in Settings. It's at the bottom of jpdb.io/settings.
4. Set the app's battery usage to **Unrestricted**, so background refresh runs reliably.
5. Allow notifications. The app uses them only to tell you when you're logged out and grades can't upload.

## Limitations
- Only JP→EN vocabulary cards. Kanji cards, reverse cards and new cards stay website-only.
- Offline cards have no example sentences, because the jpdb API doesn't provide them.
- Offline order is oldest due first, then failed cards. jpdb's own order uses recall probability.
- Grades are sent through jpdb's web review form using your login, the same way jpd-breader does it, because the review API isn't documented. If jpdb changes that page, uploads may break until the app is updated. Any unsent grades stay queued until then.

## Building
You need JDK 17 and the Android SDK command-line tools. On Arch:

```sh
sudo pacman -S jdk17-openjdk android-tools

mkdir -p ~/Android/Sdk/cmdline-tools && cd /tmp
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-11076708_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0"
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

Then, in the repo:

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew testDebugUnitTest   # unit tests (mock jpdb server)
./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with your local key in `~/.android/debug.keystore`, so new builds install over old ones.

## Project layout
| Path | What |
| --- | --- |
| `app/src/main/java/.../data/JpdbRepository.kt` | Logic for syncing the queue, grading, undo and the upload queue. Plain Kotlin with no Android code, so it can be unit tested. |
| `app/src/main/java/.../data/JpdbApi.kt` | HTTP: the official API (Bearer key) and the logged-in web review form |
| `app/src/main/java/.../data/RoomStore.kt` | On-device database for the queue, upload queue and card content |
| `app/src/main/java/.../ui/` | Compose screens: website, offline review, settings |
| `app/src/main/java/.../work/Workers.kt` | Background upload and periodic refresh |
| `app/src/main/assets/bridge.js` | Script injected into jpdb.io. It tells the app about cards you grade on the website. |
| `jpdb-fast-review.user.js` | The original browser userscript (the same idea, for desktop browsers), tested by `node test.js` |

## License
MIT. See [LICENSE](LICENSE).
