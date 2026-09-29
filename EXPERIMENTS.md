# Spatial motion experiment

Branch `feature/spatial-motion-experiment`. Not for merging as-is: an experiment to judge from the sofa.
Every timing, scale and tint lives in `design/Motion.kt`, `design/SectionTheme.kt` and
`ui/SpatialNavigation.kt`, so each effect can be tuned or switched off in one place.

## What is in it

| Effect | Where | Notes |
| --- | --- | --- |
| Layered image focus | `Motion.focusImageCard` (paintings, Continue watching) | Frame lifts 3dp and scales 1.045; the picture scales 1.025 inside its clip, 70ms slower; the caption rests at 82% and brightens/lifts 40ms later. |
| Press | `Motion.press` | Dips to 95.5% of the focused size (70ms), settles back (190ms). |
| App discs | `Motion.liftIcon` | 3dp lift alongside the existing halo and 1.08 scale. |
| Section moods | `SectionTheme` + `HomeBackdrop.setMood` | One low-alpha wash over the painting, driven by the focused section's tag; 560ms glide. Film: dimmer/cooler. Evening: warm. Spinning: teal. Sideboard: olive. Paintings/settings: faint darkening. |
| Page depth | `SpatialNavigation.update` via `CalmScrollView.onScrolled` | Rows leaving the top shrink ≤1.5% and dim ≤30%; rows arriving settle up the last 14dp; headings trail by 1.5×. Pure function of scrollY. |
| Painting parallax | `HomeBackdrop.onPageScrolled` | 6% of scroll speed inside a 6% overscan. |
| Poster flight | `InfoSheet.Flight`, `FilmSheet.show(from = poster)` | Eva's poster lifts from the film card into the sheet; the sheet's surface and text gather around it; Back flies it home. Falls back to the normal entrance when there is no poster. |
| Sheets | `InfoSheet.hide`, `Motion.exit`, `MainActivity.syncObscured` | Page steps back to 97.5% as a sheet arrives; sheets now leave with a reverse animation. Focus returns at once and keys go to the page while the sheet finishes leaving. |
| Content swaps | `Motion.swapText` / `Motion.swapImage` | Film title, availability and poster; sideboard caption; Tonight lines; Now spinning track and sleeve. Off-screen or unchanged content is set directly. |

## Fixed along the way

`LauncherTheme.recolor` could save a half-faded text colour as a label's resting colour when focus came
back mid-tween, leaving the label dim for good (seen on the film card after quickly opening/closing its sheet).

## Tried and rejected

- Static grain texture: not built. Every full-screen layer costs measurable frame time on this TV (below).
- Cheaper edge fades (layering only the fade strips): no measurable gain on the TV, so reverted.

## Performance on the TCL (1080p UI, GPU-bound)

Same scripted navigation run, median frame time: master 42–48ms, this branch ~48ms. The dominant cost is
pre-existing: the edge fades in `CalmScroll.kt` (offscreen layers every frame). With them disabled the same
run measured ~38ms. That is a visual trade-off for you to decide, not changed here.
