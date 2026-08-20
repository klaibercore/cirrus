# Design source

`cirrus-icons.svg` is where the mark and the icon set are drawn. Everything in the two apps that
shows the mark — the Android launcher and notification vectors, `ui/components/CirrusMark.kt`, the
desktop's `CirrusAppIcon.kt` — quotes coordinates from this file rather than re-tracing them. The
launcher and the status bar had already drifted into two different clouds once, back when each was
drawn separately; sharing the numbers is the only thing that stops it happening again.

## The system in four rules

- **One primitive.** A curve that rises to the right and ends in a round cap. Slope stays between
  −0.25 and −0.40 — flatter reads as a list, steeper as a chart — and the sweep is never mirrored,
  because a reversed sweep means the wind changed and it never does.
- **Round caps and joins, always.** Nothing in the sky has a corner.
- **Colour is a role.** Blue is structure, near-white (or near-black, on light) is content, amber is
  electricity and nothing else, green is connected-and-done. These are `CirrusAccents` in both apps.
- **The ink of every glyph is centred on (12,12)** of the 24-unit grid, so a row of them sits on one
  optical line.

## Two marks, and where the line is

`i-mark` is three sweeps inside a pair of braces. `i-stream` is the same three sweeps without them,
re-proportioned and drawn heavier so they fill the box on their own.

The first draft put the boundary at 20px. Rendered against a real pixel grid the braced mark is a
smudge at 20 and marginal at 24; it only separates into two braces and three sweeps at about **28**.
The stream glyph is still clean at 14. So 28 is the boundary the apps use, which costs the braces at
the two smallest sizes and buys a mark that is legible everywhere it appears.

## What was corrected in the first draft

All of it alignment rather than redrawing; the full list is in the file's own header comment. The
two that mattered:

- **The mark's sweeps sat 0.95 units low** inside braces that were already perfectly centred, and
  their vertical rhythm was 2.85 then 3.90, so the bottom wisp read as detached from the other two.
  That wisp was also a 2.9-unit stub against a 5.0 top wisp. The rhythm is now an even 3.0 and the
  taper 5.4 / 8.0 / 4.4.
- **`i-link`'s connector stopped 2.1 units short of its upper node**, leaving a gap in a drawing
  whose whole idea is a single line under tension. Both ends now land exactly on their circle.

## Reading it

The file is a sprite of `<symbol>`s with no page around it, so opening it directly shows nothing.
Reference a symbol from a page of your own:

```html
<svg width="48" height="48"><use href="cirrus-icons.svg#i-mark"/></svg>
```

Colour comes from four custom properties — `--brand`, `--contrast`, `--ember`, `--knockout` — each
carrying a dark-theme fallback inside its own `var()`, so a bare `<use>` on a dark page is already
right. Set them on the host page for the light theme.

The fallbacks live inside the `var()` rather than in a `<style>` block on purpose. A style block
can only reach a consuming `<use>` through a selector like `svg{...}`, which matches *every* svg on
the host page, and a rule on the element beats a value inherited from an ancestor — so the defaults
would override the page's own theme and the light palette would never take. That is exactly what
the first version of this file did, and it showed up as a near-white mark on a white page.
