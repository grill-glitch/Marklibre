# Changelog

All notable changes to Marklibre are documented in this file.

## [Unreleased]

### Added

- **Rotate** — rotate the whole image 90° clockwise with one tap (bottom
  toolbar, next to the eraser). Ink is flattened into the image as a single
  undoable op, exactly like crop, so Undo restores the previous state.
- The top-left button is always **Save** — it no longer switches to Share
  when editing a screenshot; Share stays in the top-right.

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
