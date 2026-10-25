# Vitmap — all-in-one Android toolbox

Vitmap bundles seven tools into one Android app. Each tool has its own animated, Material 3 interface, and everything runs on the device except the tasks that genuinely need the internet (browsing the web, and the one-time download of translation language packs).

| Category | Tool | Works offline |
| --- | --- | --- |
| Games | **Chess** | Yes |
| Games | **Tic-Tac-Toe** | Yes |
| Camera & vision | **Color Picker** | Yes |
| Camera & vision | **Measure** | Yes |
| Language | **Translator** | Yes, after a one-time ~30 MB download per language |
| Internet | **AllBrowser** | No (it's a web browser) |
| Utilities | **Conversion** | Yes |

## Features

### Chess
- Built-in engine written in Kotlin: bitboard pawn evaluation, PeSTO piece-square tables, iterative-deepening PVS with a transposition table, null-move pruning, late-move reductions and quiescence search. Move generation is verified with perft tests.
- **Bots from 100 to 3000 Elo.** Weaker levels search shallower, add noise to their move choice and occasionally blunder; stronger levels use full search and an opening book.
- **Modes:** vs Bot (play either colour), pass-and-play, **Bot vs Bot** with independent Elos and an adjustable speed, and an analysis board.
- **Live move rating.** Every move is classified as Brilliant, Great, Best, Excellent, Good, Book, Forced, Inaccuracy, Mistake or Blunder, with a badge that pops onto the board.
- Live engine analysis: eval bar, three engine lines with depth and speed, and best-move hint arrows.
- **Game report** with per-player accuracy, move-quality counts and an evaluation graph (tap the graph to jump to that move).
- **Board editor:** place, move and erase pieces, set side to move and castling rights, edit the FEN live, then analyse or play from the position.
- Set/copy FEN, load/copy/share PGN (comments, variations and NAGs are handled), opening names, undo, history navigation, flip board, chess clocks (bullet to classical), resign and draw offers, seven board themes.

### Tic-Tac-Toe
- Any board from **3×3 to 15×15**, with a configurable win length (3 to 7 in a row).
- Play against a friend or a bot (Easy / Medium / Hard). On small boards the Hard bot plays perfect minimax; on large boards it uses a threat-aware heuristic search.
- Animated pen strokes, a sweeping win line, scoreboard, undo, alternating starts, pinch-zoom on big boards and confetti on a win.

### Color Picker
- **Live camera identification:** aim the reticle and see the colour's name (5,000 named colours) and every format updating in real time: **HEX, RGB, CMYK, HSLA, HSV, HWB, OKLCH, CIELAB** and Android ARGB. Tap any value to copy it.
- Freeze frame, torch, adjustable sample area, tap to focus, pinch to zoom.
- **Photo picking** with a magnifying loupe for pixel-exact sampling, plus an automatically extracted palette.
- **Compare** two colours: CIEDE2000 ΔE with a plain-language verdict, WCAG contrast ratio with AA/AAA ratings and text previews, per-channel differences, and a perceptual (OKLab) blend.
- Saved palette with harmonies (complementary, analogous, triadic, split, tetradic), tints and shades, and sharing.

### Measure
- Take a photo, or pick one, with a **reference object** in frame. About 60 references are built in: bank and ID cards, A-series and US paper, banknotes, coins from several countries, batteries, CDs, USB plugs and more.
- **Perspective-corrected calibration:** for rectangular references, drag four corners and a homography corrects for camera tilt. Coins and lengths calibrate with two points.
- Tools for **lines, paths, areas (with perimeter), angles and circles**. Draggable handles show a magnifying loupe while you drag. Units are mm, cm, m, in or ft.
- **Measure something once and save it as a new reference** for future photos, or add your own references by size.
- On-screen **ruler** (cm/mm and inches in sixteenths) with credit-card calibration, plus a history of saved measurement sessions.

### Translator
- On-device **ML Kit translation across 59 languages** with automatic language detection. Download each language once, then translate offline.
- **Photo translation:** OCR for Latin, Chinese, Japanese, Korean and Devanagari scripts, with the translation overlaid on the image where the original text was.
- **Voice:** on-device speech recognition with a live waveform, and a **conversation mode** for two people that speaks each translation aloud.
- **Read aloud** in any installed text-to-speech voice, with adjustable speed.
- **Documents:** TXT, Markdown, SRT/VTT, HTML, DOCX, ODT and PDF (pages are read with OCR). Paragraph-by-paragraph progress, side-by-side view, and export to `.txt`.
- **Offline dictionary** (WordNet 3.1, about 150,000 entries): definitions, examples, synonyms, antonyms and "type of" relations, with morphology (so "running" finds "run" and "mice" finds "mouse"). Words in other languages are looked up through English, and definitions can be shown translated.
- History, favourites, and share or "Process text" integration: select text in any app and choose Vitmap.

### AllBrowser
- A full WebView (Chromium) browser: tabs with a swipe-to-close tab switcher and thumbnails, start page, smart address bar (URL or search with DuckDuckGo, Google, Bing, Brave, Startpage or Ecosia), back/forward, reload/stop and a progress bar.
- **Incognito tabs** in an isolated WebView profile (cookies, storage and cache kept separate and deleted when the last incognito tab closes) on WebView versions that support it.
- Bookmarks, history, downloads through Android's download manager, find in page, desktop-site mode, text size, darkened pages in dark theme, file uploads, fullscreen video, site permission prompts, link and image long-press menu, and **tracker/ad blocking**.
- **Inspect element:** tap-to-pick with an on-page highlight, a DOM tree, live editing of text, HTML, attributes and inline styles, computed styles, a **JavaScript console** with a REPL, a **network log** (with blocked requests flagged), **page source** with syntax highlighting, and cookies/localStorage. Remote debugging through `chrome://inspect` can be switched on in settings.
- Links opened from other apps, and shared URLs, open in AllBrowser.

### Conversion
- **49 categories and nearly 800 units**, ranging from everyday units to obscure ones: length, mass, temperature (including Rømer, Delisle and Newton), area, volume and cooking, time, speed, acceleration, pressure, energy, power, force, torque, density, data and data rate, angle, frequency, fuel economy (reciprocal units such as L/100 km handled correctly), the full set of electrical and magnetic units, photometry, radiation, viscosity, flow, concentration, typography, counts (lakh, crore, googol) and more.
- Units are grouped as Common, Metric, Imperial & US, Scientific, Astronomical, Regional (Japanese, Chinese, Indian, Russian…), Historical, and Fun (smoots, beard-seconds, bananas…).
- The input accepts arithmetic expressions such as `12*3.5` or `2^10`. Every unit converts live, and you can search across all units, mark favourite categories, and set precision or force scientific notation.
- **Custom units and custom categories:** define units in terms of any existing unit, including your own units, or create entirely new categories.

## Look and feel
- Material 3 with an Outfit and JetBrains Mono type system, light and dark themes, and optional dynamic (wallpaper) colour.
- Shared-element container transforms between home cards and tools, spring-based press feedback, staggered entrance animations, a drifting aurora header, predictive back, haptics and confetti.

## Project layout

```
app/   Android app (Jetpack Compose)
  feature/chess        board, sheets, editor, view model
  feature/tictactoe
  feature/color        camera analysis, loupe, compare, palette
  feature/measure      calibration, tools, ruler, history
  feature/translate    ML Kit, OCR, speech, documents, WordNet dictionary
  feature/browser      WebView session, inspector, panels
  feature/convert
  nav/Tools.kt         tool registry (add a tool here to put it on the home screen)
core/  Pure Kotlin logic with unit tests: chess engine, unit catalog, colour science,
       tic-tac-toe AI, measurement geometry, expression parser
tools/ build_wordnet_db.py — regenerates the bundled dictionary
```

To add a tool: add an entry to `nav/Tools.kt` and a branch in `nav/ToolHost.kt`.

## Building

Requirements: JDK 17 or newer, and the Android SDK with platform 37.

```bash
./gradlew :core:test            # engine, units, colour, geometry tests
./gradlew :app:assembleDebug    # APKs in app/build/outputs/apk/debug/
```

Every push runs GitHub Actions (`.github/workflows/android.yml`), which runs the tests and uploads the debug APKs as the **vitmap-debug-apks** artifact. Splits are produced per ABI (`arm64-v8a` for most phones) along with a universal APK. Debug and release builds are signed with the checked-in `app/debug.keystore`, so newer builds install over older ones. Replace that key before publishing to a store.

Toolchain: AGP 9.3.1, Gradle 9.5, Kotlin 2.4.20, Compose BOM 2026.09.00, CameraX 1.4.2, ML Kit (translate 17.0.3, text recognition 16.0.1, language ID 17.0.6). minSdk 26, targetSdk 36.

## Credits
- Chess pieces "cburnett" by Colin M.L. Burnett (BSD / GPL / GFDL / CC BY-SA 3.0), via Wikimedia Commons and lichess.org.
- PeSTO piece-square tables by Ronald Friederich.
- WordNet 3.1 © Princeton University (WordNet licence), packaged from the `wordnet-db` npm module.
- Colour names from [color-name-list](https://github.com/meodai/color-name-list) (MIT).
- Outfit and JetBrains Mono fonts (SIL Open Font Licence 1.1).
- Google ML Kit, AndroidX and Jetpack Compose.

The full licence texts are in `app/src/main/assets/licenses/` and in the app under **Settings → About**.
