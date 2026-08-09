# Screenshots

The five images the README's `## Screenshots` table renders. Captured on a moto g06 at its
native 720×1640 against Ollama's hosted API.

| File | What is on screen |
|---|---|
| `01-model-picker.png` | The model sheet with the **Vision** filter selected, capability chips on every card |
| `02-parameters.png` | The parameters sheet — reasoning effort, system prompt, sampling controls, all on "default" |
| `03-markdown-code.png` | An answer containing a syntax-highlighted Python block, in an auto-named thread |
| `04-github-tools.png` | Settings › GitHub: **GitHub tools** on, **Allow write actions** off |
| `05-keystore.png` | Settings › Connection with a key saved, encrypted with a device-bound key |

Every file is well under the 500 KB budget and carries no developer overlays.

## Still missing

Two shots from the original plan were never captured, because the app was not in a state that
would have made them true:

- **A reasoning trace.** Needs a turn taken with *Reasoning effort* set to anything but Off.
- **A GitHub tool call.** Needs a token configured and a prompt that makes the model call a
  `github_*` tool, e.g. *"Read `ChatEngine.kt` in klaibercore/cirrus and explain how the tool
  loop terminates."*

Both would strengthen the set considerably — they are the two claims a sceptical reader is most
likely to want proof of. Capture them as `06-streaming-reasoning.png` and `07-github-tool.png`
and add two rows to the table.

There is deliberately no MCP screenshot: MCP has no UI yet.

## Capturing

`capture.py` pulls a frame over adb, scales the long edge to 1080 wide, and quantises the palette
until the file is under 500 KB — plain PNG optimisation is not enough for these, they land around
a megabyte.

```bash
python3 docs/screenshots/capture.py 01-model-picker
```

Needs Pillow (`pip install pillow`) and a device with USB debugging on.

### Getting 1080 out of a lower-resolution phone

A 720p phone can be told to render at 1080 for the duration. This changes the display for real,
so put it back afterwards:

```bash
adb shell wm size 1080x2460 && adb shell wm density 420
# ... capture ...
adb shell wm size reset && adb shell wm density reset
```

Raise the screen timeout first, or the phone locks mid-capture and you lose the session:

```bash
adb shell settings put system screen_off_timeout 1800000   # 30 minutes
adb shell settings put system screen_off_timeout 60000     # put it back
```

### Notes

- Dynamic colour follows the wallpaper. A colourful wallpaper gives the screenshots an accent;
  a dark grey one makes the whole set look washed out.
- Do not route screenshots through a messaging app to get them off the phone. WhatsApp
  re-encodes them as JPEG and downscales — 720×1640 PNG became 702×1600 JPEG, with compression
  artefacts on exactly the small UI text these are meant to show. Pull them over adb instead:

  ```bash
  adb pull /sdcard/Pictures/Screenshots/Screenshot_20260809-210516.png
  ```
