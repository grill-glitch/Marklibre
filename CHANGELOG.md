# Changelog

All notable changes to Marklibre are documented in this file.

## [1.2.1] - 2026-08-05

### Changed

- **Faster metadata strip** — removing metadata from a JPEG is now a fast
  binary pass that drops the APP1 (Exif) segment without decoding or
  re-encoding the pixels, and it runs on a worker thread (the button is
  disabled while working) so the UI stays responsive; non-JPEG sources
  still re-encode but also off the main thread.
- **Save keeps metadata by default** — the "Strip metadata when saving"
  switch now defaults to off: saving keeps the source image's metadata
  (JPEG EXIF is preserved); sharing still strips metadata by default
  (switch defaults to on). Users who already toggled the switches keep
  their choice.

### Added

- **Highlighter width** — the width slider now also applies to the
  highlighter (8-32 dp, default 24 dp, thicker than the pen's 2-16 dp);
  the slider range and value switch when you swap between pen and
  highlighter, and each tool remembers its own width.

## [1.2.0] - 2026-08-05

### Added

- **Metadata** — a button next to Save opens a bottom sheet with the
  image's metadata and location (source, dimensions, last modified, GPS
  coordinates, taken time, camera); the fire-department button in its top
  right strips all metadata & location by re-encoding the file, and a
  help button beside it explains what metadata is and why removing it
  matters. Two switches (persisted, default on) control whether sharing
  and saving strip metadata automatically; the output always keeps the
  source format (JPEG/PNG/WebP), and a JPEG keeps the source EXIF when
  the switch is off.
- **Localization** — the UI is now translated into Spanish, German,
  Japanese, Chinese (Simplified and Traditional), French and Russian,
  following the system language (English remains the default).

## [1.1.0] - 2026-07-30

### Added

- **Rotate** — rotate the whole image 90° clockwise with one tap (bottom
  toolbar, next to the eraser). Ink is flattened into the image as a single
  undoable op, exactly like crop, so Undo restores the previous state.
- The top-left button is always **Save** — it no longer switches to Share
  when editing a screenshot; Share stays in the top-right.
- **Two-finger zoom / pan** — while a brush tool (pen / highlighter /
  eraser) is active, pinch to zoom (0.2x-8x) and drag with two fingers to
  pan the image; strokes and text scale with it. A single finger still
  draws.
- Fix: the highlighter's live stroke no longer renders opaque while
  drawing — the in-progress stroke is composited directly on the canvas
  instead of being re-drawn into the persistent ink layer every frame
  (which stacked the translucent alpha to opaque); the eraser preview
  still composites on the ink layer so it only clears ink.
- **Pen width slider** — the pen's stroke width is now adjustable (2-16 dp)
  via a slider with a live preview bar in the color panel (pen tool only).
- **Custom palette color** — a palette button in the color row opens a
  color picker with hue / saturation / brightness / opacity sliders and a
  hex field; picked colors (with their opacity) apply to the pen and text,
  and the palette button shows the custom color while selected.
- The palette picker is an inline panel floating above the toolbar (not a
  separate window, so preset color dots, tools and the canvas stay tappable
  in one tap); BACK collapses it first.
- The palette button mirrors the preset dots: unchecked it shows a ring in
  the palette's own color, checked it fills with the custom color; the
  custom color is remembered across launches (SharedPreferences).
- The palette panel shows an Original vs Current swatch comparison, has a
  Cancel button that restores the color the picker opened with, and an
  eyedropper that picks a color straight from the image; tapping a preset
  color dot closes the panel.
- Tapping the pen button toggles the color panel on/off; if the palette
  panel is open it closes first.

### Fixed

- Preset color dots stopped working when the pen width row was added
  to the color panel - the dot collector only looked at the panel's direct
  children (the row container), so no dot had a click listener; it now
  walks the panel recursively.
- BACK with unsaved edits asks "Discard changes?" instead of
  dropping the annotations silently; handled via OnBackPressedCallback so
  it also works on Android 16+ (predictive back no longer calls
  onBackPressed()).

## [1.0.0] - 2026-07-31

### 1.0.0 — The first stable release

Marklibre 1.0.0 is the first stable release: a complete, from-scratch
reimplementation of Google Markup — no Google code, no closed SDKs, no native
Ink engine. The whole editor is built on a hand-rolled Canvas ink engine in
Kotlin.

#### Full Markup feature parity

- **Pen** with 7 ink colors and smooth quadratic-curve strokes
- **Highlighter** — translucent, thick strokes that never hide the image
- **Eraser** — removes ink only; the underlying image is never touched
- **Text tool** — 6 font styles (Bold / Classic / Modern / Script / Soft /
  Bubbly) × 7 colors
- **Crop** — draggable rectangle with 8 resize handles and a dimmed overlay
- **Sticker editor** — draw on a transparent canvas and export as a sticker
  PNG, mirroring the original `StickerActivity` flow
- **Undo / Redo** for everything: strokes, text edits, moves, rotations,
  scaling and crops
- **Save / Share / Copy / Delete** — full-resolution PNG export through a
  FileProvider
- **Drop-in for custom ROMs** — identical package name, versionCode and
  `ACTION_EDIT image/*` entry point, so SystemUI screenshot "edit"
  integration works out of the box

#### Elegant UI

- Material 3 dynamic color (Monet) — the editor follows the wallpaper palette
- Tool highlight styled like the Save button: colorPrimary fill with
  onPrimary icons; brush tools only show their ink color while active
- Bottom-sheet toolbar and trash bin that sit flush to the edge with rounded
  top corners; the trash bin is primary by default and shifts to
  primaryContainer while hovering
- Crop handles and borders drawn in the Save accent color; text boxes are
  outlined in the text color with white corner dots
- Polished tool-switch animation with a color-gradient sweep and dimmed
  highlight (120 ms), no press ripple

#### Intuitive UX

- Text interaction model that just works: tap to select, tap again to edit,
  drag to move, corner handles to scale (anchored on the opposite corner),
  rotation knob with a drop-line indicator and refresh icon
- Drag-to-trash deletion with undo — restoring the element to its exact
  pre-drag position
- Eraser preview composited on the ink layer, so no dark streaks remain
- Everything is undoable; nothing is destructive

#### Tech stack

- Kotlin 2.4.0, Gradle 9.6 wrapper, Android Gradle Plugin 9.3.1
- compileSdk / targetSdk 36, minSdk 35 (Android 15)
- Zero runtime dependencies beyond androidx
