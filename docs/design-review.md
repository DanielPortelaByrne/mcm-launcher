# Design review: MCM Google TV launcher

Reviewer: senior UI/UX + MCM interior design consultant. Method: read the brief, the Brain project note, the theme/motion/room code and the `assets/home` data; walked the live TV over ADB (home top to bottom, header, account menu, search/all-apps grid, film sheet and its tabs). ~45 screenshots. Read-only throughout, except the incident below.

**Procedure disclosure.** Once I chained D-pad presses ending in OK without a screenshot in between, and it opened Netflix (focus had been restored to the Netflix icon after closing search). I pressed HOME straight away. Nothing was changed, but Netflix is probably still in the recent-apps stack, and I broke the "confirm focus before OK" rule. Every later OK was preceded by a screenshot of the focus.

---

## 1. Verdict and scores

The launcher is a genuinely good idea executed unevenly. The bones are right: a real painting behind everything, one dark-brown surface, ivory as the single "lit" state, and a vertical page that feels like a stock Google TV. The "personal room" sections (sideboard prints, Tonight card, recipe deck, turntables) are the most distinctive thing on any TV and give it a point of view. But it reads as **two different rooms**: a competent, very stock dark-glass app launcher on top, and a warm, tactile, skeuomorphic "objects on a table" world below. The top half is Google TV in a brown skin; the bottom half is a cosy illustrated diorama. They share a palette but not a design language (flat/glass vs. paper/brass/wood; one typeface vs. serif+sans; ring focus vs. lift focus vs. inverted-dark focus). The launcher also leans on copy and captions that are too small, and the painting rotation puts busy art directly behind body text. It is closer to "themed skin with a lovely lower half" than to "interior designer's living room", but it is one focused editing pass away from the latter.

| # | Area | Score |
|---|------|------|
| 1 | Concept and cohesion | 6.5 |
| 2 | Interior design language | 6 |
| 3 | Colour palette | 7 |
| 4 | Typography and language | 5.5 |
| 5 | Layout and hierarchy | 6 |
| 6 | Focus and navigation flow | 6 |
| 7 | Motion | 7 (as specified; not felt on device) |
| 8 | Component quality (avg) | 6.5 |
| 9 | Achievement of stated goals | 6.5 |

Component scores: app icons 6 (row) / 8 (drawer), More-apps pills 6, Continue watching cards 7, Film card + sheet 7.5, sideboard prints 8, Tonight card 8, recipe deck 7.5, turntables 5.5, painting cards 7, header/avatar 6.5, account menu 5.5, search/all-apps panel 5. **Weakest: turntables and the account menu; then the search/all-apps overlay, which drops the painting entirely.**

---

## 2. Five things to keep

1. **The painting background with the dark-brown "no outline" surface.** Header bar, More-apps pills, Continue cards and Make-it-yours pills all sit on one translucent espresso (`#E6302319`) and it looks calm and expensive. Keep exactly this.
2. **Solid ivory as the one focus colour on text surfaces** (Browse apps, Channel 4 pill, Painting settings, Art mode, painting cards). It is unmistakable at 10 ft and tied to the cream that also appears in the prints and paper cards.
3. **The sideboard prints.** Freddo, Tayto, mirror and sofa as flat cream prints with olive/ochre/teal/terracotta on a walnut shelf are the best single piece of MCM on the page: restraint, flat colour, one idea per object, real negative space. This is the standard the rest should meet.
4. **The paper cards ("Tonight?" and recipe deck).** Bold-italic serif title, tracked terracotta caps (`WATCH / MAKE / EAT`), hairline rule. Warm, legible, and feels printed. The recipe deck lift on focus with the ochre "OK to shuffle" is charming.
5. **The film sheet structure.** Serif title, italic tagline, quiet metadata, ochre genre line, PLAY showing only "4K · Dolby Vision". Hierarchy is clear, copy is short, tabs switching on focus is fast and D-pad-friendly.

---

## 3. Problems, ranked

### 1. Two design languages on one page (cohesion)
**Evidence.** Home top half (header, hero, app row, More-apps pills) is flat translucent-brown Google-TV grammar in Roboto. From "On the sideboard" down it is skeuomorphic: cream paper stock, brass plaques, wooden turntables with gradients, serif type. The Continue watching row is a third thing (full-colour video thumbnails with a cream ring on focus).
**Why it hurts.** The eye re-calibrates at every section; the illusion of "one room" breaks. The most stock part (apps) is also the most prominent and the first thing seen.
**Fix.** Pick the sideboard print as the reference and pull the rest toward it.
- Give the hero column the same object treatment: drop "Make yourself at home." in `serif` Bold at 40sp with the same ivory `#F3E9CF` and set it as a single-line print/plaque, not a web hero.
- Put the app rows on the walnut shelf language (see #6): a low `#3B2A1D` strip under the row so apps read as objects on a shelf, like the prints.
- Bring paper-stock ivory (`#F3E9CF`) into the Continue and More-apps cards as a thin inner mat, or leave them dark but stop mixing in a third focus style (see #3).

### 2. Body copy and captions too small; legibility on busy paintings
**Evidence.** Hero eyebrow "MCM / THE LIVING ROOM" 13sp, tracked ochre, on the striped lamp/orange painting; "Hold OK on an app to rearrange" (right, y≈566) sits directly over the painting with no scrim and disappears; "13 paintings for a quieter screen", "A few gentle ideas", "What you're making" subtitles are 14–15sp at ~55% ivory over trees and sea; "1742 films · OK for options" and the film metadata are ~14sp; footer hints "OK Open / Hold OK Add to Your apps" ~13sp. Hero title "Make yourself at home." crosses the lamp's vertical slats and the plant (Hugo painting) and clips visually.
**Why it hurts.** At 1080p layout on a 4K panel viewed from 3 m the effective minimum comfortable size is ~18–20sp for anything that carries meaning. Anything at 55% alpha over the painting fails contrast.
**Fix.**
- Floor: 18sp for all readable text; 16sp only for decorative tracked labels. Section subtitles 18sp at `#F3E9CF` @ 78% (`#C7F3E9CF`).
- Add a per-section soft scrim behind headings and captions: vertical gradient `#00232616 → #99232616`, 120dp high, behind text only. The header bar already proves the language works.
- Hero: put a pill-less but scrimmed panel (`#8C1E1810`, radius 28dp) behind title + subtitle + button, or move the hero into a paper card like Tonight.
- Delete the footer hint strip on rows where it does not apply (see #7).

### 3. Three focus styles plus a fourth inverted one
**Evidence.** (a) Text surfaces: solid ivory + ink text (Browse apps, Channel 4, pills). (b) Round icons and avatar: a cream ring with a dark gap (SmartTube focus, drawer "5" focus, header avatar), i.e. exactly the coloured outline Daniel has said he dislikes. (c) Continue watching: a cream outline ring on the thumbnail (South Park card) that also overlaps the neighbouring card and is slightly cut by the left margin. (d) "Tonight?" WATCH row when focused turns **dark brown** (`#3B2A1D`) on the ivory paper, the opposite of the rule. (e) Film sheet PLAY is terracotta at rest and ivory when focused; "Overview" selected-tab is light brown, focused tab ivory. (f) Sideboard and recipe deck use a lift with no colour change.
**Why it hurts.** Focus is the one thing users learn once. Six treatments means the eye hunts. Rings also read as outlines and look "clipped" at row edges.
**Fix.** One rule: **lit ivory for anything with a flat surface; lift + tint for objects; ring never.**
- Round icons: instead of a ring, scale to 1.08 and fill the label in a lit ivory pill `#F3E9CF` (ink text) under the icon, like the pill focus elsewhere. Or place a 12dp-blurred ivory glow disc behind the icon (`#F3E9CF` @ 35%), no stroke.
- Continue cards: lit ivory 6dp frame is a stroke; use an ivory title strip (ink text) along the bottom edge of the card instead, and scale 1.04.
- Tonight rows: focused = ink-brown is defensible on paper, but make it the exception by naming it: use `#26271D` for text and an **ivory-darker** paper `#E6D6AE` fill instead of dark brown, so the paper stays paper.
- PLAY: keep terracotta (`#AF5938`) only if it is the same colour every time; focused = ivory with ink text like all others.

### 4. Turntables: the weakest component
**Evidence.** Eva's deck sits at y≈372 and Daniel's at y≈354 (uneven baselines); Daniel's turntable-shelf name label floats higher. Daniel's empty state prints a lone "-" above "Nothing playing" on the brass plaque and an empty dashed sleeve; the two decks and plaques have four different heights. Playback status ("Paused", "Nothing on the platter") sits in small type above the deck and reads like debug output. Gradient wood + gold gradient plaques + shadowed feet is the only place in the UI with heavy gradients and drop shadows: it looks like a different (kitsch, "vintage clip-art") art style than the flat prints.
**Why it hurts.** This is the most literal skeuomorph on the page and it is placed in the middle of a very flat design; misaligned decks look broken, and demo data ("Sunday Morning", "Golden Slumbers") is currently the content.
**Fix.**
- Redraw as flat MCM objects like the prints: one solid walnut plinth `#795638`, a flat ink `#26271D` record, terracotta/teal label (already good), a single ochre `#DDBB57` tonearm line. Remove gradients and the highlight arcs on the vinyl.
- Fix the baseline: same top for both decks; plaques identical width (300dp) and height; content vertically centred.
- Empty state: remove the "-", show "Nothing on the platter" once, in serif italic.
- Status text goes onto the plaque ("Now playing" / "Paused") in tracked caps 14sp; person name in caps above the deck at 18sp.

### 5. Typography: a good serif, used unevenly; sans headings feel generic
**Evidence.** All section titles ("Your apps", "More apps", "Continue watching", "On the sideboard", "This evening", "Now spinning", "The MCM collection", "Make it yours") are Roboto Medium ~26sp. Serif appears only on paper objects (Tonight, recipes, plaques, film-sheet title, project title). The hero headline is a heavy Roboto. Section-title case is inconsistent ("The MCM collection", "On the sideboard", "Make it yours" vs "Your apps", "More apps"). The serif is the Android system `serif` (Noto Serif), a passable but not distinctive face.
**Why it hurts.** Serif/sans pairing is the strongest lever for "designed" and is being used only on the objects, not on the room. Titles are the first thing read.
**Fix.**
- Section titles: serif (Noto Serif or, if you can bundle it, **DM Serif Display**/**Fraunces** 600) 28sp, ivory; keep Roboto for functional labels (pills, hints, metadata). That single change unifies the two halves.
- Hero headline: serif 44sp, weight 600, ivory; subtitle Roboto 18sp.
- Keep tracked caps for eyebrow labels but at ≥14sp, letter spacing 0.14em, ochre `#DDBB57` at 100%.
- Sentence case everywhere for titles; caps only for tracked labels.

### 6. Apps: the row is the most generic element and appears twice
**Evidence.** Your apps (round brand-colour icons, labels below) then More apps (wide pills with a small round icon) then the drawer (a third grid). Original brand colours are on (Make it yours shows "Icons: Original"): Netflix white, Paramount electric blue, Peacock black, S red, PrivadoVPN violet, Channel 4 lime green against a teal/ochre/terracotta painting is a noticeable clash, and is exactly the reason the MCM disc mode was built. In the row after returning from Netflix, Paramount, Peacock, PrivadoVPN and BBC iPlayer were rendered at ~120px while SmartTube, Stremio and Netflix were ~152px (persisting across several screenshots), so icon sizes were inconsistent (looks like a stuck scale state after a focus/resume). The Your-apps row is 8 apps with the last cut by the right edge, and its 152px icons are 24 px bigger than Continue cards' badges etc.
**Why it hurts.** The most-used task (open a favourite app) is served by the least designed element, and the brand-colour explosion is the single loudest colour on the page.
**Fix.**
- **Make the abstract MCM disc the default** (the code exists). Brand colours only on the focused icon if wanted. This alone will remove most clashes.
- Icon size: 128px discs (not 152) with a 24dp gutter; label 18sp Roboto Medium; one label style (no ochre label on focus, use the lit ivory pill).
- Merge "More apps" into the same row visual as Your apps or make it a quieter text-only shelf; two different shapes for the same object type is confusing. Alternative: More apps as a single row of 96px discs with no label until focus.
- Investigate the stuck-scale bug: after the Netflix launch/return several icons stayed smaller than their peers. Reset scale on `onResume`/`onWindowFocusChanged`.

### 7. Focus, scrolling and navigation problems
**Evidence.**
- From the Home tab, DOWN goes Browse apps → Your apps (first icon) → More apps. The whole page jumps ~690 px between Your apps and More apps so **Your apps disappears almost entirely** when More apps is focused (screenshot a3: only the bottom edge of the icons). Similarly focusing Continue watching leaves half of More apps visible.
- After BACK from the film sheet, focus landed on the Wi-Fi icon; after closing search, it returned to the Netflix icon instead of the search icon. Focus restoration is unpredictable, and this is how OK on the wrong thing happens.
- In the film sheet, PLAY is focused by default. A single accidental OK starts torrent playback; the first focus should be the tab strip or Open in Stremio, or PLAY should require a confirm.
- In the sheet, RIGHT from the last tab (Watch) jumped down to Versions rather than staying in the tab row.
- The footer hint strip ("OK Open  Hold OK Add to Your apps") is static and wrong for most rows (apps, prints, recipes); "Art mode" floats bottom-right and is only reachable by scrolling to the very bottom.
- Reaching the bottom (Make it yours) takes ~11 presses; there is no "back to top" and no section shortcuts.
- Header: the ivory Apps pill overlaps the light-brown Home pill (they touch and overlap by ~6px).
**Why it hurts.** The two goals "familiar, useful navigation" and "reach common tasks quickly" both depend on predictable D-pad movement.
**Fix.**
- Scroll policy: keep the focused row's title visible and bring **one row above** into view; snap so the focused row sits at ~60% of screen height (y≈650 at 1080), so the previous shelf peeks above (~120 px) as context.
- Restore focus to the exact element that opened an overlay, always. On Back from the film sheet return to the film card.
- Sheet default focus: Overview tab (not PLAY); PLAY requires focus by DOWN.
- Trap RIGHT inside the tab row; wrap or stop at Watch.
- Replace the static footer with a contextual hint that changes with the focused item, or remove it. Move "Art mode" into the header (an Art tab already exists, so delete the duplicate).
- Add a long-press-UP or a BACK-once-to-top behaviour: BACK on any lower section returns to the top of Home before leaving.

### 8. Section order, density and redundancy
**Evidence.** Order: hero, film card, Your apps, More apps, Continue watching, sideboard, This evening, Now spinning, painting collection, Make it yours: 10 sections, ~2,300 px of scroll. "Continue watching" (the most common task after opening an app) is the 3rd row after 2 app rows. The Letterboxd card dominates the top right (larger than any app row) and its film changes on every visit. The hero ("Make yourself at home." + Browse apps) duplicates the Apps tab and the search icon. "Art" tab, "The MCM collection" and "Art mode" are three routes to the same idea. "Make it yours" has four pills of different importance ("Icons: Original" is a toggle, not navigation). The film card lists "Stream: HBO Max Amazon Channel, HBO Max" (duplicate) and, for Paradise Lost, "Not on UK services right now" in place of any useful action.
**Why it hurts.** The page is long but not paced: the hero and film card compete, and utility content is buried under décor.
**Fix.**
- New order: film card + hero (one composition) → **Continue watching** → Your apps → sideboard → This evening → Now spinning → paintings → Make it yours. Move More apps into the Apps tab/drawer; Home keeps a single app row.
- Delete the "Browse apps" button (Apps tab does it); reuse that space for the greeting only.
- Remove "Art mode" duplicate; keep the Art tab. Make "Icons: Original" a setting inside "Painting settings" or rename to a proper toggle.
- Film card: de-duplicate providers; if not available, show "Rent or buy: …" or hide the card and offer "Another film" as the primary action.
- Cap each row's height consistently (see #9 for rhythm).

### 9. Spacing rhythm and alignment
**Evidence.** The left margin is 96px on titles but the sideboard shelf and paintings start at ~96/112; the first sideboard print is at x=160 (inset 64 px from the shelf edge) while apps start at x=144 (icon centre). Row heights and gaps between title and content vary: title→content gaps are ~40 px (Your apps), ~55 px (More apps), ~85 px (paintings); section gaps vary from 35 to 180 px. The sideboard shelf spans to x=1820 while the paintings row runs off the right edge. The "Freddo Figurine" caption (serif 34px) under the shelf then the next section header (Roboto) is only ~60 px away: tight. Recipe deck's "OK to shuffle" floats in the painting with no scrim.
**Fix.** Adopt an 8-based rhythm at 1080p: 96 px side margin; section-title baseline to content top = 24 px; section-to-section = 64 px; title 28sp / subtitle 18sp. Right edge: every row either ends at x=1824 or bleeds *intentionally* (a card cut exactly at the edge with a fade mask, `#00 → #E6232616` over the last 96 px) rather than clipping mid-icon (BBC iPlayer at x=1800).

### 10. Overlays drop the world: search/all-apps, account menu, sheets
**Evidence.**
- Search/all-apps: flat `#1E1810` slab, no painting, a large lighter-brown search bar (`#5C4531`) with 18% contrast to placeholder text, 7-column brand-colour grid with the last row hard-cropped at y=1030 (Spotify/Stremio/STV cut mid-icon), first item only shows the ring after DOWN.
- Account menu: the focused row (Daniel (UK)) is an ivory bar whose right end is a straight cut against the rounded panel (clipped highlight, exactly what Daniel dislikes); it also shows real email addresses on screen, and "Switch profile / Manage accounts" are unstyled text rows floating below a hairline.
- Film sheet: good, but the Crew tab prints "ORIGINAL WRITER" touching its value ("ORIGINAL WRITERBrian Selznick", no gutter) and the last row (EDITOR) is faded/cut at the bottom; the sheet's outer radius (~48 px) differs from every other card (28–32).
**Fix.**
- Search/drawer: keep the painting visible behind a 92% espresso scrim (`#EB1E1810`), grid of MCM discs at 128px, fade-out mask at the bottom, search field `#3B2A1D` with placeholder `#B3F3E9CF` (≥4.5:1).
- Account menu: inset the focused row 12 px from the panel edge and use the same radius (24) so the ivory never touches the edge; hide emails behind the name (show only when focused, 14sp) — they are also private on a shared TV; treat "Switch profile" as pill buttons like Make-it-yours.
- Crew tab: label column fixed at 220dp, value column starts at 236dp; give the list bottom padding 24dp and a fade mask; consider truncating to 5 rows.

---

## 4. Colour, motion and copy notes

**Palette.** The espresso/ivory/ochre/terracotta/teal/olive set is right and sits well on the paintings. The problems are the *imported* colours: app brand colours (Paramount blue `#0064FF`, Channel 4 lime, Netflix white, Peacock black) and Continue thumbnails (South Park, clowns, a fire) that are the loudest objects on the page, plus the Letterboxd tricolour dots. Terracotta PLAY (`#AF5938`) is the only saturated CTA and is used only in the sheet: good, but make it the only "primary" colour on the page. Ochre `#DDBB57` is used for eyebrow, genres, "Next:" text, plaque, tonight labels and status: consistent, but too often at reduced opacity where it goes muddy on the paintings.

**Motion.** The values (focus 200/180 ms, pans 280–400 ms, select dip 80 + 200 ms) are sensible and match Daniel's recorded range. Given the motion-sickness history:
- Keep pans at **320–380 ms** with `PathInterpolator(0.2, 0, 0, 1)` (a plain ease-out-expo). The current `SWOOSH` (0.05, 0.7, 0.1, 1) has a very fast start (almost 70% covered in the first 25% of time) that produces exactly the "whip then long tail" feel that upsets vestibular sensitivity. Use shorter total travel instead of a longer tail.
- Never animate: the page pan direction reversals mid-flight (cancel and jump to target), text colour tween on focus (use a crossfade of the pill only; text colour change should be instant to avoid a grey mid-state on ivory), the Continue progress bars, the background painting *scale/pan* (crossfade only, ≥1200 ms, no Ken Burns), the turntable at any speed above ~10 rpm-equivalent or when off-screen, and anything looping while the user is navigating.
- Focus lift: 1.05 scale is right for pills; 1.08 for icons/prints; 1.02 for wide cards. Duration 160 ms in, 120 ms out, ease-out. Select: 60 ms dip to 0.97, 160 ms settle.
- Sheets: 220 ms fade + 12 dp rise, no scale. Overlays are already close.
- Reduce Motion should also drop the painting crossfade to 400 ms.

**Copy and tone.** The voice is warm and consistent when it is written for the room ("What you're making", "A few gentle ideas", "Nothing on the platter", "OK to shuffle") and generic when it is system copy ("Search your apps", "Choose an account", "Hold OK on an app to rearrange", "OK for options", "Browse apps"). Visible **placeholder copy is on screen**: "Placeholder pick" under Paddington 2, "Next: …" actions marked as placeholders in the data, demo turntable tracks. It breaks the premium illusion: replace with real data or empty states written in the room's voice ("Nothing planned yet"). Also: "MCM / THE LIVING ROOM" is jargon-y (say "The living room"); "Eva's pick for tonight" appears twice in two type styles; "1742 films" is a system stat, not a designer's caption.

---

## 5. If you only did three things

1. **Make the MCM disc icons the default and unify focus to one rule** (lit ivory for surfaces, lift for objects, no rings). This removes the loudest clash and the most visible inconsistency in one pass.
2. **Type and legibility pass:** serif for all section titles, 18sp minimum for anything meaningful, and a soft scrim under headings/captions. This turns "themed skin" into "designed room" and fixes 10-foot readability.
3. **Fix navigation basics:** exact focus restoration after overlays, PLAY not focused by default, Continue watching moved directly under the hero, scroll policy that keeps the previous row peeking. This is the "familiar, useful navigation" half of the brief.

(Next after those: redraw the turntables in the flat print style, and clear all placeholder text.)

---

## 6. Not assessed, and why

- **Motion feel.** Screenshots only; I can judge the specified values and curves but not how they feel on the panel. Daniel should judge on the TV.
- **The "Who's watching?" picker.** It appears only once per boot / after 10+ minutes of standby; I read `WhoPicker.kt` but did not see it rendered.
- **Edit-apps panel, hold-OK rearrange, Art mode, Live TV, the Apps-tab drawer via OK, PLAY / Versions / Open in Stremio / Another film.** These launch apps, change state or start playback, which the brief forbade or which I could not confirm safely. The all-apps grid was seen only through the search overlay; the Apps tab probably shares it.
- **Icon-shrink glitch.** Observed after the Netflix launch and return; I could not tell whether it is a persistent bug or a transient state, and I did not restart anything to check.
- **Turntable rotation and the painting crossfade timing.** Static frames only.
- **Placeholder data.** Judged as placeholder per the brief, but it is what is visible today.
- **Real 4K rendering.** Reviewed 1080p screencaps; sharpness of the redrawn discs, serif hairlines and paper texture on the 4K panel is unverified.
