# jpdb fast review — project context

Goal: make jpdb.io reviews usable on bad cellular. Prefetch the whole due queue, review locally with no per-card page loads, and send grades back in the background (queued and retried).

## Current state
- `jpdb-fast-review.user.js`: a working userscript that runs on jpdb.io.
  - Open it at `https://jpdb.io/learn#fast`, or use the ⚡ button it adds to /learn and /review.
  - `makeCore({fetch, storage, now, sleep, isOnline})` contains all the logic and is DOM-free.
  - The UI is at the bottom of the file.
  - When `module` exists, the file exports `makeCore` for node tests.
- `test.js`: a mock jpdb server with an end-to-end test. Run it with `node test.js` (passes). It covers:
  - ordering
  - offline outbox
  - persistence across reloads
  - undo
  - lost-POST-response dedupe
  - deck-list caching
- Not yet tested against the live jpdb.io. The dev sandbox couldn't reach the site.

## jpdb protocol notes

### Official API
- Base URL: `https://jpdb.io/api/v1/`
- Requests are POST with a JSON body and the header `Authorization: Bearer <key>`. The key is at the bottom of jpdb.io/settings.
- Endpoints used:
  - `lookup-vocabulary`: body `{list: [[vid, sid], ...], fields: [...]}`, response `{vocabulary_info: [[...] | null]}`.
    - Fields seen in jpd-breader source: `vid`, `sid`, `rid`, `spelling`, `reading`, `frequency_rank`, `meanings`, `card_level`, `card_state`, `due_at`, `alt_sids`, `alt_spellings`, `part_of_speech`, `meanings_part_of_speech`, `meanings_chunks`, `pitch_accent`.
    - `card_state` examples: `["due"]`, `["failed"]`, `["new"]`, `["known"]`, `["redundant","due"]`, `["locked","due"]`. Only single-element `due` or `failed` is treated as reviewable.
  - `list-user-decks`: body `{fields: ["id","name"]}`, response `{decks: [[id, name], ...]}`. **UNVERIFIED shape.**
  - `deck/list-vocabulary`: body `{id, fetch_occurences: false}`, response `{vocabulary: [[vid, sid], ...]}`. **UNVERIFIED shape.**
- Error responses look like `{error: "bad_key" | "bad_request" | "too_many_requests" | ..., error_message}`.
- jpd-breader waits about 0.2 s between API calls.
- jpdb also has an official "add a new review" API endpoint (added 2024-04-15), but its schema is unknown. The script doesn't use it; it scrapes the review form instead.

### Private web review flow
Verified from jpd-breader's `src/background/backend.ts`. Needs the logged-in cookie session.
1. `GET /review?c=vf%2C<vid>%2C<sid>`, then read the hidden `<input name="r">` value (the review number).
2. `POST /review` with form body `c=vf%2C<vid>%2C<sid>&r=<n>&g=<grade>`.
   - Grade codes: `nothing=1`, `something=2`, `hard=3`, `okay=4`, `easy=5`, `pass=p`, `fail=f`, `known=k`, `unknown=n`, `never_forget=w`, `blacklist=-1`.
3. If the page contains `href="/login"`, the user is not logged in.
4. Wait about 1.1 s between scrape requests.

## Known limitations
- Only JP→EN vocab cards (`vf`). No kanji cards, and no example sentences (the API doesn't expose them).
- Due cards are ordered oldest-due first, then failed cards. jpdb itself orders by recall probability.

## Android app (`app/`, Kotlin + Compose)
- `MainActivity` hosts one long-lived WebView showing the real jpdb.io (`ui/WebScreen.kt`), with native screens overlaid on top: offline review (`ui/ReviewScreen.kt`) and settings.
  - A slim status bar above the site shows the offline queue size. If the page has not become visible within `slowMs` (3 s by default), or the load failed, it offers "Review offline".
  - With no validated network (`net/Connectivity.kt`), the app opens offline review automatically.
  - jpdb.io follows `prefers-color-scheme` itself. The WebView reports it from the app theme's `isLightTheme` (`values[-night]/themes.xml`).
  - `assets/bridge.js` is injected with `addDocumentStartJavaScript` and reports back through `addWebMessageListener`. It tells the app when a `vf` card is graded on the site, so the local queue drops it.
- `data/JpdbRepository.kt` is the Kotlin port of `makeCore`. It is pure JVM and unit tested with MockWebServer in `app/src/test`. Additions over the userscript:
  - It reads the API key automatically from `/settings` using the cookie session.
  - Before sending, a state lookup drops outbox grades whose card is no longer due as of when it was graded (reviewed elsewhere, or a lost-response POST).
  - It detects the grade mode from the review form.
  - A grade that is already being sent (`inFlight`) can't be undone.
  - `ioLock` keeps sync and flush from running at the same time.
- Storage is Room (`data/RoomStore.kt`) plus SharedPreferences config (`data/PrefsConfigStore.kt`). OkHttp shares the WebView's cookies through `net/WebViewCookieJar.kt`.
- `App.kickFlush()` runs an in-process upload loop with backoff. `work/Workers.kt` adds:
  - `OutboxWorker`: a one-time backstop for uploads after the app is killed.
  - `SyncWorker`: periodic, every `syncHours`. It flushes, then refreshes the due queue.
- The JS userscript's HTML UI is not used by the app. The userscript is still useful on desktop.

### Building (local, no CI)
- Arch packages: `jdk17-openjdk`, `gradle` (only to generate the wrapper) and `android-tools` (adb). From the AUR: `android-sdk-cmdline-tools-latest`.
- One-time setup:
  - `sdkmanager --sdk_root=$HOME/Android/Sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"`, then accept the licenses.
  - Create `local.properties` containing `sdk.dir=/home/travmonkey/Android/Sdk`.
  - Run `gradle wrapper --gradle-version 8.11.1`.
- Build and test:
  - `./gradlew testDebugUnitTest` runs the JVM tests.
  - `./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
- Install with `adb install -r`. The device is a Pixel 9 Pro on GrapheneOS: no Google Play Services needed, and set battery usage to Unrestricted for background sync.

## Unverified against live jpdb (check on device)
- Response shapes of `list-user-decks` and `deck/list-vocabulary`. The parser accepts arrays or objects.
- Markup of the API key on `/settings`. The parser takes the first hex token of 32–64 characters after "API key".
- Whether the `/review?c=` page contains `name="g"` buttons (grade-mode detection is best effort), and whether the site grades through a normal form submit (`bridge.js`).
