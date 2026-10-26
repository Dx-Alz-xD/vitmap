# Vitmap — all-in-one Android toolbox

Vitmap bundles seventeen tools into one Android app. Each tool has its own animated, Material 3 interface, and everything runs on the device except the tasks that genuinely need the internet (browsing, downloading videos, live exchange rates and cloud compilers).

The APK stays small: large components (text-recognition models, the dictionary, FFmpeg, Python, yt-dlp, language runtimes and the subject-detection model) are **downloadable packs**, fetched the first time a feature needs them and listed under **Settings → Downloads**, where they can be removed again.

| Category | Tool | Works offline |
| --- | --- | --- |
| Games | **Chess** | Yes |
| Games | **Tic-Tac-Toe** | Yes |
| Games | **Sudoku** | Yes |
| Camera & vision | **Color Picker** | Yes |
| Camera & vision | **Measure** | Yes |
| Documents & PDF | **PDF Scanner** | Yes |
| Documents & PDF | **PDF Tools** | Yes (OCR text extraction after a one-time download) |
| Media & files | **Compress** | Yes, after a one-time FFmpeg download for video and audio |
| Media & files | **File Converter** | Yes, after a one-time FFmpeg download for video and audio |
| Media & files | **YT Downloader** | No (downloads from the web) |
| Creative | **Photo Editor** | Yes (background removal after a one-time download) |
| Developer | **Code Writer** | Many languages offline (some after a runtime download); the rest on free online compilers |
| Language | **Translator** | Yes, after a one-time ~30 MB download per language |
| Internet | **AllBrowser** | No (it's a web browser) |
| Utilities | **World Clock** | Yes |
| Utilities | **ForEx** | Yes, with rates from the last refresh |
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

### Sudoku
- Generated puzzles with a unique solution, from Easy to Master, on 4×4, 6×6, 8×8, 9×9, 12×12 and 16×16 grids, in classic or diagonal variants.
- Notes, highlighting of matching digits, a mistake counter (with an optional three-mistake limit), undo, a timer and hints that explain the technique they use.

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

### PDF Scanner
- **Live page detection** with an outline that follows the document, **auto capture** once the page is steady or manual capture, flash and torch, and exposure (contrast) adjustment while shooting.
- Multi-page documents: reorder by dragging, remove or duplicate pages, insert photos from the gallery between pages, and add blank pages.
- Per-page editing: perspective crop with a magnifier, rotate, filters (Magic, Enhance, Grayscale, B&W, Ink saver) and brightness/contrast.
- **Annotate and sign:** pen, marker, eraser, text, shapes, images, whiteout and a saved signature.
- Save as a PDF with a name of your choice, page size and quality.

### PDF Tools
- **Edit & annotate** any PDF, **sign**, **merge** (keeping links and bookmarks), **split & extract**, images to PDF and PDF to images.
- **Protect** with a password (optionally blocking printing, copying or editing), **unlock**, page numbers, watermarks, text extraction (with OCR for scans), document info, and **compress to a target size**.

### Compress
- Shrinks photos, videos, audio, PDFs and documents **to the size you ask for** (for example "under 2 MB" for an upload limit), searching quality and resolution to land just under it.
- Photo output as JPEG or WebP (or the original format) with EXIF kept or removed; video in H.264 or H.265 with a resolution cap and audio options; PDFs with image downsampling and optional greyscale; anything else into ZIP or XZ.

### File Converter
- Converts between 86 formats: images (including HEIC, WebP, AVIF, GIF, TIFF, PSD and SVG), video, audio, documents (PDF, DOCX, ODT, RTF, HTML, Markdown, EPUB, TXT), data (JSON, YAML, TOML, CSV, TSV, XML), subtitles (SRT, VTT, ASS, LRC) and archives (ZIP, 7z, TAR, GZ, XZ, BZ2).
- Several files at once, a searchable target list grouped by family, per-family options, and combining photos into one PDF.
- Files shared to Vitmap from other apps open here.

### YT Downloader
- Downloads videos, Shorts, music and whole playlists from YouTube and the 1,800+ other sites **yt-dlp** supports. yt-dlp is downloaded from its official releases and can be updated in the app.
- Pick the exact video and audio streams, or a quality (up to 8K, 60 fps and HDR), a container (MP4, MKV, WebM, MOV) and codec priority; or audio only as MP3, M4A, Opus, Ogg Vorbis, FLAC, WAV or the original stream.
- Name files with a template, embed or save subtitles (including auto-captions), cover art, tags and chapters, split by chapter, mark or remove SponsorBlock segments, and limit speed.
- A background download queue with notifications, retry and history. "Download video" in AllBrowser and links shared from other apps open here.

### Photo Editor
- **Non-destructive:** every photo is a project that keeps the original and the edit, so anything can be changed, undone or redone later. Hold to compare with the original; pinch to zoom.
- **Looks:** 34 presets (natural, film, black & white, cinematic, vintage, creative) with a strength slider, plus auto enhance.
- **Light and colour:** exposure, contrast, highlights, shadows, whites, blacks, temperature, tint, vibrance, saturation and hue; **tone curves** per channel over a live histogram; an eight-band **colour mixer**; three-way **colour grading**.
- **Detail and effects:** clarity, dehaze, sharpening, noise reduction, vignette, grain, fade, glow, colour fringe, posterize, black & white and invert.
- **Crop and geometry:** aspect presets, rotate, flip, straighten and perspective correction.
- **Masks:** radial, gradient and brush masks, each with its own adjustments.
- **Heal:** paint over blemishes or objects and they are filled in from their surroundings.
- **Background:** on-device subject detection to remove, blur, recolour, desaturate or replace the background.
- **Text, drawing, stickers, shapes and image layers** with fonts, outlines, curved text, brushes (pen, marker, highlighter, neon, spray), blend modes and opacity; **frames**.
- Export to JPEG, PNG, WebP, lossless WebP or HEIC at full or reduced size, keeping EXIF, into Pictures/Vitmap. Also available from the gallery's "Edit with" menu.

### Code Writer
- Projects with multiple files, a code editor (Sora) with TextMate highlighting for 240+ grammars, light and dark themes, and an output console with input.
- **Runs 90+ languages:** 57 with ready-made templates and tuned settings, plus the other languages Wandbox and Compiler Explorer offer, which are listed live from those services. On the phone: JavaScript (QuickJS and the WebView engine), Python 3.12, Lua, SQL (SQLite), shell, TypeScript, CoffeeScript, Ruby 3.4 (WebAssembly), Scheme, Prolog, Clojure, and esoteric languages (Brainfuck, Ook!, Befunge-93, Whitespace, Deadfish, ///). Runtimes download the first time they're needed.
- **On free online compilers** (Wandbox and Compiler Explorer): C, C++, C#, Java, Kotlin, Scala, Go, Rust, Swift, Dart, Haskell, OCaml, F#, Zig, D, Nim, Fortran, COBOL, Ada, Pascal, Objective-C, assembly, LLVM IR and more.
- HTML, Markdown and SVG preview; JSON, YAML, XML and CSV are checked and formatted. Elixir, Erlang, Pony, Vim script and Clean can be edited with highlighting but have no working runner yet.

### Translator
- On-device **ML Kit translation across 59 languages** with automatic language detection. Download each language once, then translate offline.
- **Photo translation:** OCR for Latin, Chinese, Japanese, Korean and Devanagari scripts (each model installed on first use), with the translation overlaid on the image where the original text was.
- **Voice:** on-device speech recognition with a live waveform, and a **conversation mode** for two people that speaks each translation aloud.
- **Read aloud** in any installed text-to-speech voice, with adjustable speed.
- **Documents:** TXT, Markdown, SRT/VTT, HTML, DOCX, ODT and PDF (pages are read with OCR). Paragraph-by-paragraph progress, side-by-side view, and export to `.txt`.
- **Offline dictionary** (WordNet 3.1, about 150,000 entries, an 8 MB download): definitions, examples, synonyms, antonyms and "type of" relations, with morphology (so "running" finds "run" and "mice" finds "mouse"). Words in other languages are looked up through English, and definitions can be shown translated.
- History, favourites, and share or "Process text" integration: select text in any app and choose Vitmap.

### AllBrowser
- A full WebView (Chromium) browser: tabs with a swipe-to-close tab switcher and thumbnails, start page, smart address bar (URL or search with DuckDuckGo, Google, Bing, Brave, Startpage or Ecosia), back/forward, reload/stop and a progress bar.
- **Incognito tabs** in an isolated WebView profile (cookies, storage and cache kept separate and deleted when the last incognito tab closes) on WebView versions that support it.
- Bookmarks, history, downloads through Android's download manager, find in page, desktop-site mode, text size, darkened pages in dark theme, file uploads, fullscreen video, site permission prompts, link and image long-press menu, and **tracker/ad blocking**.
- **Inspect element:** tap-to-pick with an on-page highlight, a DOM tree, live editing of text, HTML, attributes and inline styles, computed styles, a **JavaScript console** with a REPL, a **network log** (with blocked requests flagged), **page source** with syntax highlighting, and cookies/localStorage. Remote debugging through `chrome://inspect` can be switched on in settings.
- Links opened from other apps, and shared URLs, open in AllBrowser.

### World Clock
- A world map with the live day/night terminator. Opening a city or country zooms the map to it.
- Clocks for any city or time zone, with offsets, sunrise and sunset, and a meeting planner that finds hours that work everywhere.

### ForEx
- **348 currencies:** every circulating fiat currency, including niche and local ones, plus historic currencies, precious metals and cryptocurrencies.
- Rates refresh from free public sources and are cached for offline use, with history charts over several ranges and favourites.

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
  feature/scan         camera capture, page editing, annotation, PDF export and PDF tools
  feature/compress     size-targeted compression
  feature/fileconvert  image, media, document, data, subtitle and archive converters
  feature/ytdl         yt-dlp front end, download queue and foreground service
  feature/photo        photo editor: canvas, panels, renderer, projects
  feature/code         Code Writer: catalog, editor, runners, cloud compilers
  feature/clock, sudoku, forex
  data/packs           downloadable packs (files, Play feature modules) and the Downloads screen
  media/MediaEngine.kt FFmpeg, Python and QuickJS on demand
  nav/Tools.kt         tool registry (add a tool here to put it on the home screen)
core/  Pure Kotlin logic with unit tests: chess engine, unit catalog, colour science,
       tic-tac-toe AI, measurement geometry, expression parser, document detection,
       sudoku generator and solver, compression search, format catalogs, yt-dlp
       arguments, code runners (Lua, esolangs), and the photo pipeline (tone, colour,
       masks, inpainting)
tools/ scripts that regenerate the dictionary pack, world data and language catalog
```

To add a tool: add an entry to `nav/Tools.kt` and a branch in `nav/ToolHost.kt`.

## Building

Requirements: JDK 17 or newer, and the Android SDK with platform 37.

```bash
./gradlew :core:test            # engine, units, colour, geometry tests
./gradlew :app:assembleDebug    # APKs in app/build/outputs/apk/debug/
```

Every push runs GitHub Actions (`.github/workflows/android.yml`), which runs the tests and uploads two artifacts: **vitmap-release-apks** (minified with R8; install this one, since animations and the chess engine run much faster) and **vitmap-debug-apks**. Splits are produced per ABI (`arm64-v8a` for most phones) along with a universal APK. Debug and release builds are signed with the checked-in `app/debug.keystore`, so newer builds install over older ones. Replace that key before publishing to a store.

Toolchain: AGP 9.3.1, Gradle 9.5, Kotlin 2.4.20, Compose BOM 2026.09.00, CameraX 1.4.2, ML Kit (translate 17.0.3, language ID 17.0.6, text recognition and subject segmentation through Google Play services), PDFBox-Android 2.0.27, Apache Commons Compress 1.28, LuaJ 3.0.1, Sora Editor 0.24.7. minSdk 26, targetSdk 36.

## Credits
- Chess pieces "cburnett" by Colin M.L. Burnett (BSD / GPL / GFDL / CC BY-SA 3.0), via Wikimedia Commons and lichess.org.
- PeSTO piece-square tables by Ronald Friederich.
- WordNet 3.1 © Princeton University (WordNet licence), packaged from the `wordnet-db` npm module.
- Colour names from [color-name-list](https://github.com/meodai/color-name-list) (MIT).
- Outfit and JetBrains Mono fonts (SIL Open Font Licence 1.1).
- World map from [world-atlas](https://github.com/topojson/world-atlas) (Natural Earth, public domain; ISC); country names from [world-countries](https://github.com/mledoze/countries) (ODbL); zone coordinates from moment-timezone (MIT); cities from city-timezones (MIT, data by simplemaps, CC BY 4.0).
- Exchange rates from [fawazahmed0/exchange-api](https://github.com/fawazahmed0/exchange-api) (CC0), [ExchangeRate-API](https://www.exchangerate-api.com) and [Frankfurter](https://frankfurter.dev).
- Downloaded on demand: FFmpeg (LGPL/GPL), Python (PSF), QuickJS (MIT) via [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0); [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense); TypeScript (Apache 2.0); CoffeeScript (MIT); BiwaScheme (MIT); Tau Prolog (BSD-3-Clause); ruby.wasm (MIT) and CRuby (Ruby licence); Scittle (EPL 1.0).
- Syntax highlighting grammars and themes from [tm-grammars](https://github.com/shikijs/textmate-grammars-themes) and tm-themes (MIT; individual grammars keep their own licences); [Sora Editor](https://github.com/Rosemoe/sora-editor) (LGPL 2.1); [LuaJ](https://github.com/luaj/luaj) (MIT).
- Online compilers: [Wandbox](https://wandbox.org) and [Compiler Explorer](https://godbolt.org).
- PDFBox-Android (Apache 2.0), Apache Commons Compress (Apache 2.0), XZ for Java (0BSD), SnakeYAML (Apache 2.0).
- Google ML Kit, AndroidX and Jetpack Compose.

The full licence texts are in `app/src/main/assets/licenses/` and in the app under **Settings → About**.
