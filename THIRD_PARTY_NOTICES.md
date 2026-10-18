# Third-party notices

Android UI and board interaction are Kotlin/Jetpack Compose. No web UI or React/Expo runtime is shipped.

- **ChessTutor desktop algorithms, opening data and piece art** — Priyank Shaw, Apache 2.0; `licenses/ChessTutor-Apache-2.0.txt`. Source snapshot hashes in `upstream-manifest.json`; generated copies in `native-android/app/src/main/python/backend`. Sync does not copy desktop JSX/CSS.
- **Coach portrait** — original fictional ChessTutor illustration; provenance in `assets/coach/README.md`.
- **Chesslib 1.3.7** — Ben-Hur Carlos Vieira Langoni Junior, Apache 2.0; `licenses/Chesslib-Apache-2.0.txt`. Pinned binary/source hashes in `native-android/app/libs/README.md`. Source: https://github.com/bhlangonijr/chesslib/tree/1.3.7
- **Stockfish 19** — Stockfish developers, GPL 3 or later; bundled license. Source: https://github.com/official-stockfish/Stockfish/tree/sf_19. Phone executable is compiled from official source with baseline ARM64 NEON, static C++ runtime, verified default NNUE and 16 KB load/RELRO alignment. Emulator executable uses generic SSE2; it is excluded from phone APKs. Reproduce with `scripts/prepare-native-engine.ps1`; original official source archive and build script accompany APK. Search/evaluation code is unchanged.
- **python-chess / chess 1.11.2** — Niklas Fiekas and contributors, GPL 3 or later; bundled license and source wheel in `native-android/app/wheels`. Source: https://github.com/niklasf/python-chess/tree/v1.11.2
- **Chaquopy 17.0.0** — Chaquopy contributors, MIT. Source: https://github.com/chaquo/chaquopy
- **httpx 0.28.1** — Encode contributors, BSD 3-Clause; bundled license. Source: https://github.com/encode/httpx
- **AndroidX / Compose / Material icons** — Android Open Source Project, Apache 2.0; dependency notices retained in Android package metadata. https://android.googlesource.com/platform/frameworks/support/
- **Kotlin / coroutines, Apache Commons Lang** — respective contributors, Apache 2.0. https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines, https://github.com/apache/commons-lang

Generated move/capture WAVs and the adapted launcher vector are local app assets. Historical starter license remains in root `LICENSE`; it does not replace third-party terms. Keep corresponding source and notices when redistributing GPL binaries.
