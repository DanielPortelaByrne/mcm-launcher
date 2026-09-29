# Prompt: make the MCM launcher look professionally designed

Act as the lead product designer and art director for my MCM TV launcher (`C:\Users\danie\AndroidTVLauncher`), then build what you design. The bar is a shipped premium product: think Apple TV's tvOS, a Criterion Collection menu, a Braun product sheet, a Kinfolk or Cereal spread. It should feel like one art-directed mid-century room, not a themed Android app. It currently looks a bit unprofessional. Find out exactly why and fix it properly.

## Ground yourself first (no code changes in this phase)

1. Read `README.md`, `docs/design-review.md` (the earlier critique; check which of its points are still true), and the Brain note `G:\My Drive\Daniel Brain\90 - Projects\MCM Google TV Launcher.md` for my goals and past decisions.
2. Walk the live TV over ADB (`192.168.1.232:5555`; screenshots via Bash `exec-out screencap -p`, never PowerShell). Capture every screen and state: each home section at rest and focused, the header, the film sheet and its tabs, account menu, Who's watching, all apps, edit apps, search, art mode, and the Settings-button picture overlay (`adb shell am broadcast -a com.example.tvlauncher.DEBUG_PICTURE`). Never press OK on app icons, Continue watching cards or PLAY.
3. Read the design code: `res/values/*`, `design/*`, `res/layout/*`, `ui/**`.

## Then give me a design direction and wait for my approval

Publish it as an artifact page with the real screenshots. It should cover:

- **Audit:** the 10 things that most make it look amateur, each with a screenshot, the element, and why it reads as unprofessional. Look for inconsistent spacing, misalignment, too many type sizes or weights, mixed focus styles, clashing surface treatments, off-palette colours, weak hierarchy, clutter, generic copy, glyphs used as UI, clipping, and text lost over the paintings.
- **The design system**, defined as tokens before any screen is touched:
  - an 8dp spacing grid and TV-safe margins
  - a type scale of 5–6 named styles at most, serif plus sans, with an 18sp floor for meaningful text
  - colour roles (surface, raised, text, muted, accent, focus) drawn from the existing palette only
  - one corner-radius scale
  - exactly one focus treatment for every focusable element
  - an elevation and scrim rule for text over paintings (at least 4.5:1 contrast)
  - an icon style
  - motion tokens (keep today's calm durations; I get motion sick)
- **Component inventory:** every component the launcher uses (pill, card, round app icon, row header, sheet, menu, slider, hint bar and so on), with its one canonical spec. List what gets merged, restyled or cut.
- **Before/after mockups** of home and one overlay so I can judge the direction.
- **Phased plan:** order the phases by visual impact, and name the files each phase touches.

## Build it, phase by phase

- Put every value in the tokens (`colors.xml`, `dimens.xml`, a text-style/type file, `LauncherTheme`, `Motion`). No raw hex, dp or sp literals in UI code afterwards. Grep for them and clear them.
- After each phase, build, install on the TV, and screenshot the same states as the audit. Put before/after pairs in the artifact page. Check focus with the D-pad on every screen you touched.
- Cut or quiet anything that doesn't earn its place. Restraint and negative space over decoration.
- Hold it to this checklist:
  - left edges align on one grid
  - every row, card and sheet shares the same radii, padding and header style
  - sentence case throughout, with a consistent, warm, short voice
  - real vector icons, not Unicode arrows or emoji
  - nothing clipped at row edges when focused or scaled
  - smooth scrolling and a snappy Home return (check `Choreographer` skipped frames in logcat, and fix the main-thread stall when home resumes)

## Keep

- all current functionality: the remote button mappings, the picture overlay, organise-by-hold, Continue watching and the film sheet with PLAY
- the painting backdrop and the MCM palette family
- D-pad-first navigation, and today's motion speeds
- unit tests passing (`./gradlew testDebugUnitTest assembleDebug`)

Update the README's design sections as you go. At the end, tell me honestly what still falls short of the bar.
