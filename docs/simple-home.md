# Simple home and themes

Hearth 0.18.0 defaults to a quiet, neutral **Clean** theme and **Simple home**.
Six labelled rows with circular icons open Live captions, Conversation, Face to
face, Type to translate, Camera & photos, and Screen & manga. Home does not load
models, request permissions or start capture. Feature setup and Android consent
remain explicit.

## Short paths

These counts are navigation taps from Home, excluding scrolling, keyboard entry,
model downloads and Android permission dialogs. They describe the shipped entry
paths, not a claim that every provider can be configured from scratch in five taps.

| Action | Path | Taps |
| --- | --- | ---: |
| Open captions, either conversation layout, typed translation or camera | Feature row | 1 |
| Open screen/manga overlay setup | Screen & manga | 1 |
| Move between main features | Home → feature | 2 |
| Change theme, brightness or navigation layout | Settings → Appearance & navigation → choice | 3 |
| Open local speech/translation/voice/OCR setup | Settings → category | 2 |
| Open cloud speech/translation/voice setup | Settings → Cloud → category | 3 |
| Open caption or screen overlay controls | Settings → overlay controls | 2 |
| Open downloads/imports | Settings → Downloads & imports | 2 |
| Open phone tile setup or help | Settings → All settings & diagnostics → destination | 3 |

The toolbar settings sheet is available on every main-app page in both navigation
layouts. Local and Cloud keep their separate editors. FOSS exposes Local only;
appearance/navigation changes never alter the chosen engine or provider.

Simple mode provides Home and Back controls. Returning from a setup page follows
the originating feature; opening setup directly from Home returns there. Existing
notification/share/overlay deep links still open their requested page. Classic tabs
preserves the previous five-tab navigation and each tab's return path.

## Appearance

- **Clean:** neutral white/slate surfaces with a restrained blue accent.
- **Ink:** neutral surfaces, monochrome primary controls, black dark-mode background.
- **Sky:** softly blue surfaces and slate accents.
- **Organic · classic:** the previous parchment/terracotta/sage appearance.
- Ember, Forest and Amethyst remain available.

The three minimal palettes use sans-serif headings; classic palettes retain their
original typography. Every palette supports System, Light and Dark. Choices persist through app restart
and are observed by separately composed app activities. Caption text/background
and reading overlay opacity retain their own settings. Saved `OCEAN` identifiers
still select Organic. Missing/unknown palette values select Clean.

Labels use native Compose text for shaping, scaling and semantics. Feature rows
have a single accessible action and decorative icons, flexible height and a
scrollable layout. Navigation controls retain labelled 48 dp targets. No continuous
animation or new graphics/runtime dependency is introduced.

## Implementation and validation

- `home/home_screen.kt`: independent feature entry UI.
- `settings/settings_quick_sheet.kt`: shared direct settings destinations.
- `settings/settings_app_preferences.kt`: common directory links.
- `ui/theme/AppearanceSettings.kt`: live persisted appearance/navigation choices.
- `ui/theme/MinimalPalette.kt`: the three minimal palettes.
- `MainActivity.kt`: integration with existing feature screens and saved navigation.

Host checks cover text contrast on the new palettes, compatibility of saved theme
names, defaults, and appearance/shortcut return paths and restoration. This is not
a full TalkBack or complete provider-setup usability audit. See the release's
validation note for actual phone observations.
