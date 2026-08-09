# Screenshots

The README's `## Screenshots` table expects these six files. Until they exist those images are
broken, so **this branch should not be merged before the PNGs are added.**

| File | What has to be on screen | Caption in the README |
|---|---|---|
| `01-model-picker.png` | The model sheet, list unfiltered, capability chips visible on several cards | Model picker knows which models support tools and vision |
| `02-capability-chips.png` | The same sheet with the **Vision** filter chip selected, so the list is visibly narrowed | Capability chips — see at a glance what each model can do |
| `03-streaming-reasoning.png` | A reply mid-stream, with the reasoning section expanded | Streaming chat with visible reasoning trace |
| `04-github-tools.png` | Settings › GitHub, showing the tool switches and **Allow write actions** off | GitHub tools — each one switchable, writes off by default |
| `05-github-tool.png` | A turn where the model called a `github_*` tool and answered from the result | GitHub tool — let the model read your repo |
| `06-keystore.png` | Settings › Connection, key saved, with the Keystore help text visible | Credentials stay in the Android Keystore |

Requirements from the brief: phone resolution around 1080×2400, **under 500 KB each**, no
developer overlays, and captions that match what is actually on screen.

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
- Shot 3 needs a model that reports the `thinking` capability — filter by **Reasoning** in the
  picker to find one.
- Shots 4 and 5 need a GitHub token configured. Shot 5 in particular needs a real tool call, so
  ask something like *"Read `ChatEngine.kt` in klaibercore/cirrus and explain how the tool loop
  terminates."*
- There is deliberately no MCP screenshot: MCP has no UI yet. Shot 4 covers the GitHub tool
  switches instead.
