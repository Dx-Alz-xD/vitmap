#!/usr/bin/env python3
"""Emulator smoke test, run by CI inside android-emulator-runner.

Installs the APK, opens the home screen and every tool, runs code in Code Writer (JavaScript, Lua
and SQL offline; Python and TypeScript after downloading their runtimes on the spot), then edits and
exports a photo in the Photo Editor. Fails if the app
crashes or a step doesn't reach its expected screen. Screenshots of
every step are written to smoke-shots/ and uploaded as a CI artifact.

Usage: smoke.py <directory with APKs>
"""
import glob
import os
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.vitmap.app"
ACTIVITY = f"{PKG}/.MainActivity"
EXTRA_TOOL = f"{PKG}.extra.TOOL"
SHOTS = "smoke-shots"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
failures = []
mapping = None


def adb(*args, check=True, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=180)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def shell(cmd, check=True):
    return adb("shell", cmd, check=check)


def shot(name):
    with open(os.path.join(SHOTS, f"{name}.png"), "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))


def crash_log():
    return adb("logcat", "-b", "crash", "-d", check=False)


def retrace(log):
    """Deobfuscates a release stack trace with the build's R8 mapping, when both are available."""
    tool = os.path.join(os.environ.get("ANDROID_HOME", ""), "cmdline-tools", "latest", "bin", "retrace")
    if not mapping or not os.path.exists(tool):
        return log
    with open("/tmp/smoke-crash.txt", "w") as f:
        f.write(log)
    r = subprocess.run([tool, mapping, "/tmp/smoke-crash.txt"], capture_output=True, text=True, timeout=300)
    return r.stdout if r.returncode == 0 and r.stdout.strip() else log


def check_alive(step):
    crashes = crash_log()
    if PKG in crashes:
        failures.append(f"{step}: crashed")
        print(f"FAIL {step}: crashed\n{retrace(crashes)[:15000]}")
        hints = [l for l in adb("logcat", "-d", check=False).splitlines() if re.search(r"ComponentDiscovery|ComponentRuntime|Registrar|MlKitContext", l)]
        if hints:
            print("     related log lines:\n     " + "\n     ".join(hints[-30:]))
        adb("logcat", "-b", "crash", "-c", check=False)
        return False
    if not shell(f"pidof {PKG}", check=False).strip():
        failures.append(f"{step}: app is not running")
        print(f"FAIL {step}: app is not running")
        return False
    return True


def nodes():
    """The current UI as (label, class, (left, top, right, bottom)) tuples."""
    for _ in range(3):
        shell("uiautomator dump /sdcard/ui.xml", check=False)
        raw = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        if "<hierarchy" in raw:
            break
        time.sleep(1)
    else:
        return []
    out = []
    for n in ET.fromstring(raw[raw.index("<hierarchy"):]).iter("node"):
        b = [int(v) for v in re.findall(r"\d+", n.get("bounds", ""))]
        if len(b) == 4:
            out.append((n.get("text") or n.get("content-desc") or "", n.get("class", ""), tuple(b)))
    return out


def find(label, timeout=10.0):
    end = time.time() + timeout
    while True:
        for text, _, b in nodes():
            if text == label and b[2] > b[0] and b[3] > b[1]:
                return b
        if time.time() > end:
            return None
        time.sleep(1)


def describe():
    """The labels on screen, to explain a step that didn't find what it expected."""
    labels = [t for t, _, _ in nodes() if t.strip()]
    return " | ".join(labels[:60]) or "(nothing readable on screen)"


def miss(message, shot_name):
    shot(shot_name)
    failures.append(message)
    print(f"FAIL {message}\n     on screen: {describe()}")


def find_containing(part, timeout=10.0):
    end = time.time() + timeout
    while True:
        if any(part in text for text, _, _ in nodes()):
            return True
        if time.time() > end:
            return False
        time.sleep(1)


def tap_bounds(b):
    shell(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")


def tap(label, timeout=10.0):
    b = find(label, timeout)
    if b is None:
        return False
    tap_bounds(b)
    return True


def screen_size():
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return int(m.group(1)), int(m.group(2))


def write_test_photo(path, w=1200, h=900):
    """A 24-bit BMP with a sky gradient, a sun and a ground band: enough structure to edit."""
    row = (w * 3 + 3) & ~3
    data = bytearray(row * h)
    for y in range(h):
        for x in range(w):
            ty = y / h
            if ty > 0.7:
                r, g, b = 60 + int(40 * x / w), 120, 50
            else:
                r, g, b = int(90 + 120 * ty), int(150 + 60 * ty), 235
            if (x - w * 0.7) ** 2 + (y - h * 0.3) ** 2 < (h * 0.12) ** 2:
                r, g, b = 255, 210, 80
            i = (h - 1 - y) * row + x * 3
            data[i:i + 3] = bytes((b, g, r))
    header = b"BM" + struct.pack("<IHHI", 54 + len(data), 0, 0, 54)
    info = struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, len(data), 2835, 2835, 0, 0)
    with open(path, "wb") as f:
        f.write(header + info + data)


def launch_tool(tool):
    shell(f"am force-stop {PKG}")
    shell(f"am start -W -n {ACTIVITY} --es {EXTRA_TOOL} {tool}")
    time.sleep(4)


def media_id(name):
    out = shell(f"content query --uri content://media/external/images/media --projection _id --where \"_display_name='{name}'\"", check=False)
    m = re.search(r"_id=(\d+)", out)
    return m.group(1) if m else None


def open_language_picker():
    """Through the New file button, or the "All N languages" tile at the end of the start row."""
    if tap("New file", timeout=5):
        return True
    anchor = find("JavaScript", timeout=10)
    if anchor is None:
        return False
    width, _ = screen_size()
    y = (anchor[1] + anchor[3]) // 2
    for _ in range(5):
        tile = next((b for t, _, b in nodes() if re.fullmatch(r"All \d+", t)), None)
        if tile is not None:
            tap_bounds(tile)
            return True
        shell(f"input swipe {int(width * 0.85)} {y} {int(width * 0.15)} {y} 300")
        time.sleep(1)
    return False


def code_writer():
    """Creates a file from each language's template and runs it with the recommended runner."""
    languages = [
        ("JavaScript", "javascript", "Hello, World!"),
        ("Lua", "lua", "Hello, World!"),
        ("SQL (SQLite)", "sqlite", "Jupiter"),
        # These two need a runtime pack first, which the Run button offers to download.
        ("Python", "python", "Hello, World!"),
        ("TypeScript", "typescript", "Jupiter"),
    ]
    for lang, query, expect in languages:
        step = f"code {lang}"
        launch_tool("code")
        if not open_language_picker():
            miss(f"{step}: couldn't open the language list", f"code-{query}-no-picker")
            return
        field = find("Search languages or extensions", timeout=10) or next((b for _, cls, b in nodes() if cls.endswith("EditText")), None)
        if field is None:
            miss(f"{step}: language search not found", f"code-{query}-no-search")
            return
        tap_bounds(field)
        shell(f"input text {query}")
        time.sleep(2)
        if not tap(lang, timeout=10):
            miss(f"{step}: language not listed", f"code-{query}-not-listed")
            continue
        if not tap("Run", timeout=20):
            miss(f"{step}: Run button not found", f"code-{query}-no-run")
            continue
        download = None
        for _ in range(4):
            download = next((b for text, _, b in nodes() if text.startswith("Download ·")), None)
            if download is not None or find_containing(expect, timeout=0):
                break
            time.sleep(1.5)
        if download is not None:
            shot(f"code-{query}-download")
            tap_bounds(download)
        ok = find_containing(expect, timeout=240 if download else 40)
        if ok:
            shot(f"code-{query}")
        else:
            miss(f"{step}: output didn't contain {expect!r}", f"code-{query}")
        if check_alive(step) and ok:
            print(f"ok   {step}")


def photo_editor():
    """Opens a photo through "Edit with", visits every tool, moves a slider and exports."""
    write_test_photo("/tmp/smoke-photo.bmp")
    adb("push", "/tmp/smoke-photo.bmp", "/sdcard/Pictures/smoke-photo.bmp")
    mid = None
    for _ in range(10):
        mid = media_id("smoke-photo.bmp")
        if mid:
            break
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/smoke-photo.bmp", check=False)
        time.sleep(2)
    if not mid:
        failures.append("photo: test image never reached MediaStore")
        return
    shell(f"am force-stop {PKG}")
    shell(f"am start -W -a android.intent.action.EDIT -t image/bmp -d content://media/external/images/media/{mid} --grant-read-uri-permission -n {ACTIVITY}")
    if find("Looks", timeout=40) is None:
        miss("photo: editor didn't open the photo", "photo-0-open-failed")
        check_alive("photo open")
        return
    time.sleep(3)
    shot("photo-0-open")
    if not check_alive("photo open"):
        return

    width, _ = screen_size()
    tabs = ["Looks", "Adjust", "Curves", "Colour mix", "Grading", "Detail", "Effects", "Crop", "Heal", "Masks",
            "Background", "Text", "Draw", "Stickers", "Shapes", "Images", "Frame"]
    for i, tab in enumerate(tabs, 1):
        b = find(tab, timeout=3)
        swipes = 0
        while b is None and swipes < 6:
            # The tab row scrolls sideways; drag it left using a tab that is visible.
            row = next((nb for t, _, nb in nodes() if t in tabs), None)
            if row is None:
                break
            y = (row[1] + row[3]) // 2
            shell(f"input swipe {int(width * 0.8)} {y} {int(width * 0.2)} {y} 300")
            swipes += 1
            b = find(tab, timeout=2)
        if b is None:
            miss(f"photo: tool tab '{tab}' not found", f"photo-{i:02d}-missing")
            continue
        tap_bounds(b)
        time.sleep(2)
        if tab == "Adjust":
            sliders = [nb for _, cls, nb in nodes() if cls.endswith("SeekBar")]
            if sliders:
                s = sliders[0]
                y = (s[1] + s[3]) // 2
                shell(f"input swipe {s[0] + (s[2] - s[0]) // 2} {y} {s[0] + (s[2] - s[0]) * 3 // 4} {y} 500")
                time.sleep(3)
            else:
                miss("photo: no slider found in Adjust", "photo-adjust-no-slider")
        shot(f"photo-{i:02d}-{tab.lower().replace(' ', '-')}")
        if not check_alive(f"photo {tab}"):
            return

    if not tap("Save"):
        miss("photo: Save button not found", "photo-no-save")
        return
    if not tap("Save to Pictures", timeout=10):
        miss("photo: export sheet didn't open", "photo-export-sheet-missing")
        return
    if find("Saved", timeout=120) is None:
        miss("photo: export didn't finish", "photo-export-failed")
    else:
        time.sleep(2)
        shot("photo-99-saved")
    check_alive("photo export")


def main():
    apk_dir = sys.argv[1]
    apks = glob.glob(os.path.join(apk_dir, "*x86_64*.apk")) or glob.glob(os.path.join(apk_dir, "*universal*.apk"))
    if not apks:
        sys.exit(f"No x86_64 or universal APK in {apk_dir}")
    global mapping
    mapping = next(iter(glob.glob(os.path.join(apk_dir, "**", "mapping.txt"), recursive=True)), None)
    os.makedirs(SHOTS, exist_ok=True)
    # A crash dialog left on screen would cover every later step.
    shell("settings put global hide_error_dialogs 1", check=False)
    print("Installing", apks[0])
    adb("install", "-r", "-g", apks[0])
    adb("logcat", "-b", "all", "-c", check=False)

    shell(f"am start -W -n {ACTIVITY}")
    time.sleep(5)
    shot("00-home")
    check_alive("home")

    tools = re.findall(r'Tool\(\s*"([a-z0-9]+)"', open(os.path.join(ROOT, "app/src/main/java/com/vitmap/app/nav/Tools.kt")).read())
    for i, tool in enumerate(tools, 1):
        launch_tool(tool)
        shot(f"{i:02d}-{tool}")
        print(("ok   " if check_alive(tool) else "FAIL ") + tool)

    code_writer()
    photo_editor()

    if failures:
        print("\nSmoke test failures:\n  " + "\n  ".join(failures))
        print(adb("logcat", "-d", "-t", "400", "*:E", check=False)[-8000:])
        sys.exit(1)
    print(f"\nSmoke test passed: home, {len(tools)} tools, Code Writer runs and the photo editor flow.")


if __name__ == "__main__":
    main()
