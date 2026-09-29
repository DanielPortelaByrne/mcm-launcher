# Spatial Motion & UX Experiments

Feature branch: `feature/spatial-motion-experiment`

This document describes the experimental motion and spatial UX enhancements implemented to push the MCM launcher's feel substantially further using sophisticated but restrained motion, depth and spatial continuity.

## Summary of Implemented Experiments

### 1. Richer Focus Microinteractions ✓ IMPLEMENTED

**Status**: Integrated into painting cards and Continue Watching cards

**What changed**:
- Image cards now have layered focus animations
- Outer card scales to 1.04f with elevation increase (16dp translationZ)
- Inner image/still scales to 1.015f with delayed settle (+40ms)
- Metadata text brightens on focus (from text_muted to text)
- All animations use Motion.SETTLE curve for smooth ease-out

**Files modified**:
- `design/Motion.kt` - Added `focusImageCard()`, `focusAppIcon()`, `parallelScrollVertical()`
- `design/LauncherTheme.kt` - Added `bindRichImageFocus()`, `bindAppIconFocus()`
- `ui/HomeCollection.kt` - Applied to painting shelf thumbnails
- `ui/ContinueRow.kt` - Applied to Continue Watching cards

**Visual effect**: Focused cards feel "picked up" and layered, with internal content responding separately from the frame. Makes objects feel like physical items rather than 2D UI elements.

### 2. Section-Aware Backdrop Transitions ✓ IMPLEMENTED

**Status**: Integrated into HomeBackdrop

**What changed**:
- Added semi-transparent theme overlay above the painting that shifts atmospheric tone
- Overlay colour depends on active section:
  - Apps: neutral (no tint)
  - Continue Watching: cool blue-grey tint
  - Tonight: warm amber tint
  - Now Spinning: teal influence
  - Projects: olive influence
  - Art Mode: no tint
- Transitions smooth over 500ms using Motion.SETTLE
- Overlay alpha is very subtle (8%) for subconscious effect rather than obvious

**Files modified**:
- `design/SectionTheme.kt` - New file with section-aware color mapping
- `ui/HomeBackdrop.kt` - Added theme overlay and `setSectionTheme()` method

**Visual effect**: As user navigates between sections, the room's atmospheric tone subtly shifts, creating a sense of moving through one cohesive designed space. Changes are meant to be noticed subconsciously rather than as obvious rainbow shifts.

**Integration notes**: Sections need to call `backdrop.setSectionTheme(SectionTheme.Section.APPS)` etc. when they gain focus. This can be wired into focus change listeners on each section header or main container.

### 3. Spatial Vertical Navigation (Micro-Parallax) ✓ UTILITIES CREATED

**Status**: Framework created, not yet integrated into CalmScrollView

**What included**:
- `ui/SpatialNavigation.kt` - Utility functions for parallax effects:
  - `updateParallax()`: Handles vertical scroll parallax with backdrop offset
  - `updateHorizontalParallax()`: Horizontal scroll parallax for rail items
- Backdrop moves at 0.4x foreground speed (slower for depth effect)
- Outgoing sections fade (max 25%) and scale down (max 1% scale reduction)
- Incoming sections settle from slight vertical offset
- All effects respect Motion.animationsOn() for accessibility

**Integration approach**: Apply these utilities to CalmScrollView's onScrollChanged listener:
```kotlin
SpatialNavigation.updateParallax(y, height, backdropView, sectionsList)
```

**Visual effect**: When navigating vertically through sections, the page feels like gliding through a designed room rather than independent elements flying around. Subtle offset and scale changes create micro-parallax depth.

### 4. Shared-Element-Style Transitions ✓ UTILITIES CREATED

**Status**: Framework created, designed but not yet integrated into FilmSheet

**What included**:
- `design/SharedElementTransition.kt` - Lightweight shared-element animation:
  - `transitionToDetailView()`: Animates source view to destination rectangle
  - Works via direct animation rather than formal Transition framework
  - Ideal for film poster lifting from shelf into detail sheet

**Typical usage pattern**:
1. Record source view bounds
2. Animate visual representation towards destination
3. Fade in supporting detail UI
4. Transfer to final view state
5. Reverse on back

**Visual effect**: Poster appears to lift off shelf, expand, and transform into the detail sheet content area. Creates strong spatial continuity between browsing and detail views.

### 5. Animated Content Replacement ✓ IMPLEMENTED

**Status**: Integrated into Letterboxd film card

**What changed**:
- Film title changes with fade-through animation (title fades out, new title fades in)
- Availability text crossfades when updated
- Poster image fades out, then fades back in
- All use ContentTransition utilities with appropriate durations

**Files modified**:
- `design/ContentTransition.kt` - New file with utilities:
  - `fadeThroughText()`: Text content crossfade
  - `slideAndFadeImage()`: Image slide + fade replacement
  - `crossfadeImage()`: Simple image crossfade
  - `fadeVisibility()`: Animated visibility toggle
- `ui/HomeCollection.kt` - Applied to Letterboxd card rendering

**Visual effect**: When Eva's film selection changes, the content replaces smoothly rather than snapping. Feels deliberate and polished, drawing attention to the change without being jarring.

### 6. Enhanced Sheet & Overlay Transitions ✓ UTILITIES CREATED

**Status**: Framework created, can enhance existing overlays

**What included**:
- `design/SheetTransition.kt` - Enhanced entrance/exit animations:
  - `enterSheet()`: Coordinates scrim fade, sheet rise, background recede
  - `exitSheet()`: Smooth reversal with coordinated animations
- Background page subtly recedes (0.99 scale, 95% alpha)
- Sheet enters with small rise and fade-in over 280ms
- Scrim fades over 220ms for atmospheric effect

**Integration approach**: Replace existing Motion.enter() calls with SheetTransition.enterSheet():
```kotlin
SheetTransition.enterSheet(sheet, scrimView, backgroundContent, risePx = 24f)
```

**Visual effect**: Overlays feel like physical layers placed on top of the interface, with the background receding to give space. More sophisticated than simple fade-in.

### 7. Subtle Tactile Details ✓ PARTIALLY IMPLEMENTED

**Status**: Some utilities created, more can be added opportunistically

**Implemented**:
- Text colour easing via Motion.tweenTextColor() (existing)
- Image scale layering in focus (new)
- Metadata text brightening on focus (new)

**Framework ready for additional details**:
- Selection indicators sliding
- Focus transfer following spatial direction
- Opacity changes for inactive neighbours

## Build & Deployment

### Building the APK

Debug APK:
```bash
./gradlew assembleDebug
```
Location: `app/build/outputs/apk/debug/app-debug.apk`

Release APK:
```bash
./gradlew assembleRelease
```
Location: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Deploying to TV

Once TV is connected via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or for release:
```bash
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

### Launching the app

```bash
adb shell am start -n com.example.tvlauncher/.MainActivity
```

## Testing Checklist

### Focus Microinteractions
- [ ] Navigate to painting shelf, verify cards have subtle layered focus
- [ ] Check painting inner image scales slightly slower than outer card
- [ ] Verify title text brightens smoothly on focus
- [ ] Continue Watching cards have similar layered feel
- [ ] Rapid left/right focus movement is smooth and responsive

### Section Theme Transitions
- [ ] **Note**: Currently needs manual wiring into section focus listeners
- [ ] When implemented: Observe backdrop colour subtly shifting as focus moves between sections
- [ ] Verify transitions are smooth (~500ms) and not jarring
- [ ] Check that text contrast remains stable across all colour variations

### Spatial Navigation
- [ ] **Note**: Currently framework only, needs integration into CalmScrollView
- [ ] When implemented: Scroll down page, verify backdrop moves slower than foreground
- [ ] Check outgoing sections fade and scale down subtly
- [ ] Verify incoming sections settle from offset
- [ ] Test rapid up/down navigation, ensure no jank

### Content Replacement
- [ ] Open home page, wait for Eva's film card to load
- [ ] When it auto-refreshes or you navigate to next film, watch for smooth fade-through
- [ ] Poster should fade out and fade in cleanly
- [ ] Availability text should crossfade without snapping

### Sheet & Overlay Transitions
- [ ] **Note**: Existing Motion.enter() is used; enhanced SheetTransition available for future use
- [ ] Open All Apps, check overlay fades in smoothly
- [ ] Open SearchPanel, verify entrance animation
- [ ] Check background remains responsive during transitions

### Performance & Accessibility
- [ ] Watch for any frame drops while animating (target: 60fps)
- [ ] Turn off animations in system accessibility settings, verify UI remains functional
- [ ] Test rapid D-pad input during animations, check focus doesn't get stuck
- [ ] Long D-pad presses should not accumulate animation artifacts
- [ ] Memory usage should not grow noticeably over time

### Regressions
- [ ] Launcher HOME button behavior unchanged
- [ ] D-pad navigation works smoothly everywhere
- [ ] Hold-OK app rearrangement still functions
- [ ] All Apps panel opens and closes correctly
- [ ] Edit Apps panel works
- [ ] Picture overlay accessible and functional
- [ ] Stremio flows unaffected
- [ ] Now Spinning turntable animation continues
- [ ] Profile picker accessible
- [ ] Settings accessible
- [ ] Media watcher continues working

## Git History

```
e754e3c feat: add animated content replacement for dynamic Letterboxd film card
9dd15d4 feat: integrate rich layered focus animations into image cards
bd78a30 feat: integrate section-aware backdrop theme transitions
cdcdce0 feat: add spatial motion experiment framework
```

## Not Yet Integrated (Available for Further Development)

These utilities are built and ready but not yet wired into the UI:

1. **Spatial Vertical Navigation parallax** - Framework in `SpatialNavigation.kt`
2. **Shared-element transitions** - Framework in `SharedElementTransition.kt`
3. **Enhanced sheet transitions** - Framework in `SheetTransition.kt`
4. **Section theme on active section** - Theme system in `SectionTheme.kt` ready, just needs focus listeners

These can be integrated incrementally when testing on device shows they work well.

## Design Decisions & Trade-offs

### Why these timing constants?
- **FOCUS_IN_MS (160ms)**: Swift enough to feel responsive, slow enough to not feel twitchy
- **FOCUS_OUT_MS (120ms)**: Faster out than in, feels snappier when losing focus
- **Section theme transition (500ms)**: Slow enough to be atmospheric, not so slow it feels sluggish
- **Content replacement (180-220ms)**: Fast enough to not feel like lag, slow enough to see the change

### Why Motion.SETTLE instead of Material curves?
- Motion.SETTLE (PathInterpolator 0,0 0.2,1) provides a gentle ease-out matching the launcher's existing aesthetic
- Avoids the "pop" of OvershootInterpolator
- Maintains architectural discipline: all motion goes through the one Motion object

### Why these scale values?
- Card focus scale 1.04f: Noticeable but not so large it dominates the screen
- Inner image scale 1.015f: Subtle, not competing with card scale
- Background recede scale 0.99f: Almost imperceptible, just enough to create depth

### Why parallax at 0.4x speed?
- Slow enough to create clear depth without being disorienting
- Backed by performance constraints on TV hardware
- Faster parallax looks like floating/separation rather than depth

## Known Limitations & Future Directions

### Current limitations
- No live blur on background (TV performance constraint)
- Parallax and section theme not yet wired to active focus
- Shared-element transitions created but not integrated
- Some motion utilities created but not used everywhere they could be

### Potential future enhancements
- Connect section theme updates to section headers' focus listeners
- Integrate parallax into CalmScrollView
- Apply shared-element transitions to FilmSheet opening
- Add more tactile details (selection slides, direction-aware focus transitions)
- Explore subtle grain texture overlay (very low alpha, very subtle)
- Extended focus animations for app icons in AllApps panel

## Performance Metrics to Monitor

On TV, watch for:
- Frame timing in dumpsys gfxinfo (target: consistent 60fps)
- Memory growth over time (reopen overlays repeatedly)
- Focus responsiveness (should feel instant)
- Input lag during animations (should be imperceptible)

## Disable for Comparison

To see the launcher without these experiments:
1. Checkout master branch
2. Build and install
3. Compare feel directly with feature branch

The baseline Motion.SETTLE and Focus curves remain the same; these experiments layer additional microinteractions on top.

---

**Next steps**: Connect TV and test on device. Adjust animation durations/scales based on actual TV performance.
