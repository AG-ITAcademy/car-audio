# Nightline — the Car Audio design system

**A dashboard at night.** Deep asphalt surfaces, one warm *signal amber* light for the thing
you act on, *lane cyan* for what is live. Large type and large targets: everything reads at
a glance and works with one thumb.

## Principles

1. **One amber focus per screen.** Amber means "act here": the play button, the current
   song, the selected filter. If two things are amber, one of them is wrong.
2. **Cyan means live.** Progress, the dancing equalizer, the road marks. Never a button.
3. **Glanceable first.** Titles in Space Grotesk, big. Secondary text is dimmed, never small
   and bright.
4. **Thumb-sized.** Touch targets ≥ 56 dp. The main play button is 88 dp. Next/previous 64 dp.
5. **The song lights the room.** The full player and the playlist screen use the artwork,
   heavily blurred, as an ambient backdrop.

## Tokens (`ui/Theme.kt`)

| Role | Night | Day |
|---|---|---|
| Background (Asphalt) | `#0B0D12` | `#F6F4EF` (Paper) |
| Surface low / high / highest | `#12151C` / `#1A1E28` / `#232836` | white / `#F0EDE6` / `#E7E3DA` |
| Text / dimmed text | `#F3F5F9` / `#9AA3B5` | `#14161B` / `#5B6273` |
| Primary — Signal Amber | `#FFB224` on `#1C1300` | `#B86A00` on white |
| Secondary — Lane Cyan | `#2EE6D6` | `#00857A` |
| Error — Brake | `#FF4D5E` | `#C62838` |

**Type:** Space Grotesk (display, titles, numbers) + Inter (reading text). The *eyebrow* —
small spaced capitals in amber above a title — marks every section.

**Shapes:** 8 / 12 / 18 / 26 / 34 dp corners. Buttons are fully round or pills.

**Spacing:** 4 · 8 · 12 · 16 · 24 · 32 dp.

## Components (`ui/Components.kt`)

`RoundButton` (with amber glow for the hero), `PillButton`, `FilterPill`, `SearchPill`,
`MoodCard`, `PlaylistCard`, `TrackRow` (current song: amber title + equalizer),
`EqualizerBars`, `ProgressLine`, `MiniPlayer`, `PlayButton` (cyan ring while loading).

## Screens (`ui/Screens.kt`)

- **Home** — greeting for the time of day, search, filters, *Drive moods* carousel,
  *Recently played*; results as playlist cards (2 columns) and song rows.
- **Playlist** — ambient backdrop, title, Play / Shuffle, the *now playing* panel with big
  controls, numbered songs with the current one marked.
- **Now playing** — full screen: large glowing artwork, title, artist in amber, scrubber,
  shuffle · previous · **play** · next · repeat, *Up next*.

## The car screen (Android Auto)

Android Auto draws media apps with its own templates, so Nightline shows there through
content and structure:

- **Tabs:** *Moods* (grid of mood tiles), *Recent*, *Search* — with monochrome tab icons.
- **Mood tiles:** the same generated artwork as on the phone (`assets/art`), served by
  `ArtProvider`. A mood opens a grid of playlists; a playlist opens a list of songs.
- **Search results** are grouped: *Playlists*, then *Songs*.
- **Playback screen:** Shuffle and Repeat buttons; a bar under the icon means "on".
- Clean metadata: "Artist – Song (Official Video)" becomes title *Song*, artist *Artist*.

## Assets

- Mood tiles and tab icons are generated (gradient, grain, glowing glyph, the Nightline
  road marks). To change them, change `tools/generate_art.py` and run it again; keep the file names.
- Icons are Material Symbols paths (Apache 2.0) as vector drawables in `res/drawable`.
- Fonts: Space Grotesk and Inter, SIL Open Font License 1.1.
