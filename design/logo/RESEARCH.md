# Anon Player logo: research and prompt

## What the shelf already looks like (avoid these)

| App | Mark | Lesson |
|---|---|---|
| VLC | orange traffic cone | owns "object, not play button" |
| MX Player | blue/teal play triangle in a rounded square | the plain play triangle is taken |
| mpv | purple circle with play | circle + play is generic |
| PotPlayer / KMPlayer | yellow / multicolor play shapes | bright gradients read as "old Windows" |
| Plex | orange chevron | a single chevron is taken |
| Jellyfin | purple-to-blue gradient triangle | gradient triangle is taken |
| Infuse | orange gradient flame-like shape | |
| Just Player / Next Player | flat play icons | invisible on a home screen |

Most players are "a play triangle in a shape". Anon Player has to be recognised at 24 px next to them.

## What makes it ours

- **Anon family.** Anon Keyboard is a white key with a cyan mask on charcoal (`#26282D`, accent `#33B5E5`).
  Anon Player uses the same charcoal + cyan and the same mask, so the two read as one brand.
- **The idea:** a play triangle whose face is a mask. The two eye holes are cut out of the triangle (negative
  space). "Play" + "anonymous / private" in one shape. No text in the mark.
- **Constraints:** flat, 2 colours + background, no gradients or shadows; reads at 24 px; one-colour version
  works as the Android 13+ themed icon; fits the 66/108 safe circle of adaptive icons; simple geometry so
  `make.py` can rebuild it from numbers (rounded triangle + 2 ellipses).

## Prompt (Gemini / Nano Banana)

> Flat vector app icon for a private video player called "Anon Player". A single bold rounded play-triangle
> pointing right, white, centred on a dark charcoal (#26282D) rounded-square background. The triangle is also a
> masquerade mask: two almond-shaped eye holes cut through it (showing the charcoal background), and a thin
> cyan (#33B5E5) mask band across the eyes. Geometric, minimal, thick shapes, no text, no gradients, no
> shadows, no 3D, no outlines, large margins, readable at 24 pixels. Style of modern Android material icons.
> 1:1, plain background, 4 variations side by side.

Variations asked in follow-ups: (b) the mask as the triangle's negative space only, no band;
(c) triangle made of two overlapping shapes like a folded mask; (d) eye holes as a pair of rounded
"pause-like" slots.

The generated image is a reference only. The shipped logo is rebuilt by `make.py` from simple shapes
(traced outline, then cleaned to geometry), so there is no raster or AI watermark in the app.
