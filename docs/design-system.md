# Recall design system

Recall should feel like a calm, private place for the things you want to remember: deep ink,
soft aurora light, generous spacing, and one obvious action at a time. The interface is premium
but friendly — not a developer dashboard.

## Brand

- Name: **Recall**
- Tagline: **Your memory, encrypted.**
- Voice: short, warm, confident. Never cute, never corporate.
- Mark: a bare, Geist-grade **R** with a true liquid fill. The R itself is the clip: a
  violet/blue/cyan/pink tide rises from the bottom, a pink wash crosses from the right, and both
  wave surfaces shiver. Static icons use the same R with a fixed wave frame.

## Color

| Token | Value | Use |
| --- | --- | --- |
| `ink-950` | `#070A12` | App background base |
| `ink-900` | `#0A0F1C` | Page background |
| `ink-800` | `#111827` | Raised surfaces |
| `ink-700` | `#1A2336` | Inputs / hover |
| `text` | `#F7F9FC` | Primary text |
| `muted` | `#A8B3C7` | Secondary text |
| `faint` | `#6F7C93` | Metadata |
| `violet` | `#7C6CFF` | Primary action |
| `cyan` | `#22D3EE` | Secondary accent |
| `mint` | `#34D399` | Success / sync |
| `pink` | `#F472B6` | Logo wash / highlight |
| `danger` | `#FB7185` | Errors |
| `border` | `rgba(255,255,255,0.10)` | Default border |
| `border-strong` | `rgba(255,255,255,0.16)` | Hover/focus border |

Aurora gradient: `linear-gradient(135deg, #7C6CFF 0%, #22D3EE 55%, #34D399 100%)`.
Logo wave gradient: `#6E56FF → #3B82F6 → #06B6D4 → #EC4899`, cycling over 6s; the base R is
`#3B2F86` so it stays legible on dark surfaces.
Background aurora: radial violet top-left, cyan top-right, mint bottom-center, over `ink-950`,
plus a very subtle grid or noise layer. Keep contrast AA.

## Type

- Display / wordmark: **Space Grotesk**, 600.
- UI / body: **Manrope**, 400/500/600/700.
- Mono (optional): **JetBrains Mono**, 500, only for IDs/checksums.
- Sizes: 12, 13, 14, 16, 18, 22, 28, 36, 48.
- Line heights: tight 1.1 for display, 1.45 for body.
- Never use all-caps for long labels; small eyebrow labels may use 11px uppercase with tracking.

## Space, shape, depth

- Spacing scale: 4, 8, 12, 16, 20, 24, 32, 40, 56, 80.
- Radii: 12 (inputs), 16 (buttons), 20 (cards), 28 (hero), 999 (pills).
- Borders: 1px translucent white. Cards use a soft gradient surface, not a flat gray box.
- Shadows: `0 18px 50px rgba(0,0,0,0.32)`; accent glow only on primary actions.
- Glass surfaces: `background: linear-gradient(180deg, rgba(26,35,54,.86), rgba(15,23,42,.78))`.

## Motion

- Standard duration 180–240ms, ease-out.
- Logo tide: bottom-to-top fill, 5s loop with a short hold at the top.
- Logo wash: right-to-left pink pass, 5s loop; wave surfaces shiver on 1.8s and 2.1s loops.
- Logo gradient: violet/blue/cyan/pink cycle, 6s.
- Background orbs: 18–24s drift, very low opacity.
- Always honor `prefers-reduced-motion: reduce` on web and disable infinite animation on Android
  when the system animator scale is off.

## Icons

- 24px grid, 1.75px stroke, rounded caps/joins, outline style.
- Use icons for search, paste, sync, lock, settings, fingerprint, shield, download, close, back,
  check, chevron, image, link, text, note.
- Every icon-only button needs an accessible label.
- Do not use emoji as interface icons.

## Components

- **BrandMark**: animated liquid R; 28–84px depending on context.
- **PrimaryButton**: 52px tall, 16px radius, aurora gradient, dark text, icon optional.
- **SecondaryButton**: translucent surface, 1px border, text + optional icon.
- **IconButton**: 44px square, 14px radius, hover surface.
- **SearchField**: 52px tall, search icon, 16px radius, muted placeholder.
- **CaptureCard**: 20px radius, type accent, title 16–18px semibold, 2–3 line snippet, meta row.
- **SectionLabel**: 11px uppercase, letter-spacing .12em, faint.
- **EmptyState**: soft icon in a gradient halo, short headline, one sentence.
- **Message**: inline glass strip with icon; errors use danger tint.
- **DetailSheet**: image/text, source link, copy action, close icon.

## Screen rules

### Android

- Edge-to-edge, 20dp horizontal padding, 24dp section gaps.
- Setup: brand hero, passphrase + confirm, PIN + confirm, fingerprint toggle, one primary CTA.
- Unlock: fingerprint is the primary, auto-prompted option; PIN is the fallback; passphrase is a
  text link. Show the animated mark, a warm greeting, and no more than two actions above the fold.
- Main: compact header (brand, count, sync, settings), search field, filter chips, 2-column grid,
  floating Paste action. Lock lives in Settings and as a small header icon.
- Cards use type icons, not text-only badges. Keep text large enough to read at a glance.
- Settings: grouped glass sections with icon + title + one-line explanation; switches on the right.
- Remove redundant text buttons when an icon + label is clearer.

### Chrome popup

- 430px wide, 600–620px tall. Header 56px, compact but breathable.
- Setup/unlock: centered card, brand mark, one field group, one primary action.
- Main: segmented Search/Timeline, search field, filter row, 2-column cards, sticky footer with
  sync status and icon actions.
- Detail: full-popup glass sheet with close icon; never leave an empty overlay visible.

### Desktop

- 1100×760 default, 720×560 minimum. Left rail 248px: brand, Search/Timeline, filters, sync
  status, lock. Main: greeting, search, 2-column card grid, Paste CTA.
- Keep the tray/global shortcuts. Use the same tokens and motion as the web.
- Preserve `window.recall` and `document.documentElement.dataset.ready = '1'`.

### Landing page

- Hero: animated mark, “Your memory, encrypted.”, short subcopy, three download buttons.
- Sections: platform cards, how it works (capture → encrypt → recall), privacy/security, FAQ.
- Use real product vocabulary, no fake metrics. Make downloads obvious and include install notes.
- Responsive at 360, 768, 1200. No remote dependencies; self-host fonts and assets.

## Accessibility

- Contrast AA for text and controls. Focus rings use cyan at 2px with offset.
- Tap targets at least 44px. Don't rely on color alone.
- Respect reduced motion. Label every icon-only control.
