# ChessTutor Android

Kotlin + Jetpack Compose app for **ARM64 phones running Android 12 or newer**. Native board, screens, document picker, sharing, storage and background notification. No React Native, Expo, WebView, browser UI or development server.

## Install and use

Copy `artifacts/native-release/ChessTutor-native-arm64.apk` to your phone and open it. Allow APK installation from your file manager when Android asks. The preview has a separate app ID, `com.chesstutor.nativeapp`.

Stockfish 19, Python coaching and the full opening database are bundled. Playing, live coaching, PGN reviews and opening study work offline. Chess.com/Lichess downloads need internet. Allow notifications when requested for visible background analysis progress/cancellation.

- Tap a piece and destination, or drag the piece. Legal moves, castling, en passant and all four promotions are supported. Moves animate briefly; sounds can be disabled in Settings. No vibration.
- Live Analysis queues **every accepted move**. The board stays responsive during searches. Coach/badge always match the selected move. Earlier feedback stays in the move strip; pending and failed analysis are explicit. Swipe coach or tap Best for the better-move explanation/arrow.
- In Live Analysis, step back to any position and play a different move to replace the later continuation. Feedback for removed moves is discarded; retained and new moves keep their own feedback.
- AI is untimed practice: strength 1800/2000/2200/2500/maximum, White/Black and think time. Setup disappears after Start. Coaching is available **after the game**, when Review is tapped.
- Review includes real progress/cancel/retry, both players' accuracy, all 11 classifications, estimated performance ratings, move stepping, best arrows and practice. Coach text uses desktop's concise complete explanations.
- Review and Live Analysis show a vertical evaluation bar beside the board. It follows the selected move and reverses with board orientation. Settings offer complete/short explanations and piece names/SAN notation.
- In an imported game review, playing the original next move advances the review. A different move starts a variation: play both sides, step through its feedback, or rewind and change the continuation. **Resume** returns to the original game at the branch point (or an earlier selected position); **Next** then follows the imported moves. Variations survive leaving the screen and process restart. Save/share and the Review summary keep the original imported game; Resume closes the active variation.
- Analysis uses the synced desktop classifier, accuracy/rating calculations, opening checks and explanations. Live/variation requests include the original FEN, move history and the moving player's PGN rating. New settings default to desktop depth **16**, with the same **10–22** range. Existing depth preferences are kept. Phone searches use two threads and 64 MB hash; different hardware/search limits can produce different engine lines.
- After a desktop algorithm sync, Resume refreshes outdated cached analysis automatically. The imported PGN, active variation and selected move are retained while the original review and branch feedback are recalculated.
- Import uses the centered dialog from the references. Paste PGN, choose a file or fetch public Chess.com/Lichess games. No password or account required.
- Game options provide undo, flip, save, PGN sharing, saved games, new game and FEN positions. Home offers Resume game. Current game/feedback survive process restart; an interrupted review can be restarted from its saved PGN.
- Background analysis continues in a foreground service with notification. Android can still stop an app after force-stop, device shutdown or system resource pressure; saved PGN remains recoverable.
- Fullscreen hides the clock, battery and navigation bars. Swipe from a screen edge to show Android controls temporarily. Display cutouts and keyboard insets remain protected.

Saved games keep **3 games by default**, configurable to 3/5/10/30 in Settings, within a 2 MB PGN budget. Saving another game removes the oldest entry when the limit is reached. Updating or reviewing an existing game uses its existing entry. Existing libraries are kept until the next save; opening Settings does not trim them. Delete individual games from **Game options → Saved games**. Deleting the current game's saved entry leaves the board open and prevents automatic saves from adding it back; an explicit Save adds it again.

Download cache retains four accounts/about 2 MB total. Public requests are serial, cancellable and bounded, with 20-second timeouts and rate-limit cooldown. Standard chess PGNs are validated before review; maximum 1 MB/2000 plies.

## Run over USB

Open **`native-android`** in Android Studio. Enable Developer options and USB debugging on the phone, connect it by USB, authorize the computer, select the phone and press **Run**. Android Studio builds and installs directly; no release APK download is needed. Keep the installed app and signing key to preserve its saved games/settings between runs.

The build uses the workspace's `.venv/Scripts/python.exe` when available. Set `CHESSTUTOR_BUILD_PYTHON` to override it. For compilation, JVM tests and lint without packaging an APK:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.g"
.\native-android\gradlew.bat -p native-android :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

Source edits do not update the APK/source ZIP in `artifacts/native-release`. Changes appear on your phone after Android Studio Run; archived release artifacts stay unchanged until a release build is requested.

## Build

Install JDK 17 or newer, Android SDK platform 36, NDK 27 or newer, CMake and Python 3.12. Open **`native-android`** in Android Studio, or use PowerShell:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\scripts\prepare-stockfish.ps1
.\scripts\build-apk.ps1
```

`build-apk.ps1` accepts `-AndroidSdk` and `-Python`. It runs desktop regressions, runtime/book tests, JVM chess/import tests, Kotlin compilation, Android lint, R8 release build, package checks and signature verification. First build downloads pinned Gradle/Maven/Python dependencies. The APK uses preview/debug signing; Play Store distribution requires your own release signing key. Keep the signing key for compatible updates.

The release folder also contains `ChessTutor-native-source.zip`, the matching Stockfish source archive, `build-manifest.json` and `SHA256SUMS.txt`. The source ZIP includes the verified desktop snapshot, its tests, opening data, pinned Chesslib source and the exact NNUE network. Build caches and signing keys are excluded.

## Sync desktop improvements

Desktop remains source of truth at `D:\Applications\ChessTutor`. Inspect its changes before requesting mobile sync. Stop mobile Python tests and wait for desktop edits to finish, then:

```powershell
.\scripts\build-apk.ps1 -SyncDesktop
```

Sync copies algorithm function bodies, Python modules, exact book data, piece/coach art and matching regression fixtures. `upstream-manifest.json` records timestamp and SHA-256 hashes. It refuses a mixed snapshot if desktop changes during copying, and fails on unsupported endpoint fields. Only book storage and engine resources are adapted; UI is independently written in Kotlin. Build without `-SyncDesktop` retains the verified snapshot. Updating desktop does not silently change an installed APK: sync, test, rebuild and install the update.

To sync and verify source without packaging an APK:

```powershell
.\.venv\Scripts\python.exe scripts\sync-upstream.py
.\.venv\Scripts\python.exe scripts\test-desktop-parity.py
.\.venv\Scripts\python.exe -m unittest tests.test_runtime tests.test_book_index tests.test_upstream_snapshot
```

Then run the compilation/lint command under **Run over USB**. Desktop coaching regressions run against the bundled mobile modules and copied audit helpers. Desktop launcher, HTTP and Pydantic request-model tests are excluded; native runtime tests cover its request validation instead. Manifest hashes also verify generated modules, opening data and copied regression fixtures.

## Native device tests

The default APK is ARM64. For an x86 Android emulator, compile the same Stockfish 19 source with Android NDK/CMake:

```powershell
.\scripts\prepare-native-engine.ps1 -Abi x86_64
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.g"
$env:CHESSTUTOR_BUILD_PYTHON = "$PWD\.venv\Scripts\python.exe"
.\native-android\gradlew.bat -p native-android -PchessTutorAbi=x86_64 :app:connectedDebugAndroidTest
```

UI tests cover home/setup/import, tap/drag input, promotion, queued coaching, complete offline review, practice, saved-session restoration, background analysis, openings and AI without live feedback. JVM tests compare legal moves/SAN with 299 independent python-chess positions, plus castling, en passant, promotions, checkmate, repetition, undo and black-first PGN. Screenshots/logs are in `artifacts/native-tests`.

For physical-phone testing, connect USB debugging and install the default ARM64 build. Measure **release** performance on real hardware; emulator timings are not phone frame-rate guarantees.

## Migration and licenses

Previous Expo/React Native code was removed after a verified recovery archive: `artifacts/legacy-react-native/source-backup.zip`. Expo Go saves cannot be read from another app's private storage. Transfer games by exporting PGNs from the old app and importing them here; PGN sharing also transfers games to another phone. Android backup/device transfer covers native saved games/settings.

Stockfish is built from official version 19 source with baseline ARM64 NEON and verified NNUE, with 16 KB-compatible load segments and RELRO. Package checks verify all native libraries. See `THIRD_PARTY_NOTICES.md` and bundled licenses. GPL Stockfish/python-chess source and generated desktop Python accompany the delivered APK/source artifacts.
