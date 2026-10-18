# ChessTutor native Android

Use concise Caveman communication; keep code and exact errors in normal form.

This project is Kotlin/Jetpack Compose, Android 12+ ARM64. Native project: `native-android`. React Native/Expo was retired; recovery source archive is in `artifacts/legacy-react-native`.

- Understand current desktop algorithms in `../ChessTutor` before changing coaching. Desktop may be edited concurrently: read only, never modify it during mobile work.
- Sync explicitly with `scripts/sync-upstream.py`. Preserve manifest hashes and run matching snapshot regressions. Do not hand-edit generated Python `backend`; change adapter when endpoint schema changes.
- Keep board/legal move updates on the UI thread short. Engine, imports and persistence run off it. Queue every accepted live move; bind feedback to immutable move IDs. AI coaching starts only after game ends and Review is tapped.
- Match supplied screenshot layouts with native Compose, safe insets, touch/drag, accessibility and pinned game controls. No WebView/web UI.
- Read official Android/Compose/Chaquopy docs for APIs and pin compatible dependencies.
- Run Kotlin compilation, JVM tests and Android lint before declaring completion. `scripts/build-apk.ps1` also runs desktop/runtime/book parity and verifies the release APK. Run device tests when connected; never claim unmeasured physical-phone smoothness.
- Default builds target ARM64. `scripts/prepare-native-engine.ps1` compiles the pinned Stockfish source for 16 KB memory pages. Optional emulator tests use `-PchessTutorAbi=x86_64` and `-Abi x86_64`; never ship an emulator build as phone APK. Verify load segments and RELRO alignment.
- Preserve games through validated PGN, atomic SQLite storage and Android backup. Do not delete user data or recovery archives.
