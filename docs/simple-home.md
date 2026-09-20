# Home carousel and themes

Hearth 0.19.0 uses a horizontal carousel of floating, image-led feature cards.
Each card has an original cover image, a title, a short description and one direct
Open button. Adjacent cards peek into the viewport; swipe horizontally or use the
labelled previous/next buttons. Dots identify the current position. There is no
automatic advancement or ambient animation.

The six cards are Live captions, Conversation, Face to face, Type to translate,
Camera & photos, and Screen & manga. They open the existing feature screens or
reader setup directly. The earlier three-button chooser was removed following the
owner's request. The gear still opens shared settings; Classic tabs remains an
optional navigation layout.

## Remembered service

The last settled/used service is saved under `hearth-home/selected-service`.
The preference is a stable ID, not an ordinal: an app update can reorder cards
without restoring a different service. Unknown/missing values fall back to Live
captions. Main-app feature navigation (including share links and Classic tabs)
updates the same preference; opening settings does not.

Returning Home and cold-starting the app restores the selected service's index.
Temporary drag offsets are not persisted. No provider, model, credential, transcript
or history data enters this preference. Scrolling Home starts no inference or
network work. Screen sharing and other Android consent remain explicit.

## Layout and access

Cards use a bounded-height, vertically scrollable interior on small displays or
large font settings. Images are decorative; native text, headings and labelled
buttons provide the actionable semantics. Previous/next controls have 48 dp touch
targets. Settings keeps direct Local/Cloud destinations, with Local only in FOSS.
These design choices are not a claim of a complete TalkBack/device-matrix audit.

When a card is visible, its feature takes one tap to open. Settings → category is
two taps; a Cloud category adds the Cloud tab. Theme changes take Settings →
Appearance & navigation → choice. Swiping cards, text entry, setup/download work
and Android permissions are separate from those navigation tap counts.

## Appearance

Clean, Ink and Sky are minimal palettes with sans-serif headings. Organic · classic,
Ember, Forest and Amethyst preserve the previous palettes and typography. Every
palette supports System/Light/Dark. Themes, brightness and navigation choice persist
across app restart. Caption and reading overlays retain independent text/background
controls. Classic tabs preserves the previous five-tab layout and return paths.

## Components

- `home/home_screen.kt`: pager, page position, swipe/navigation and launch dispatch.
- `home/home_service_card.kt`: image, native text and feature action.
- `home/HomeService.kt`: stable service identities and app-page mapping.
- `home/HomeServiceStore.kt`: bounded preference storage.
- `settings/settings_quick_sheet.kt`: shared setup shortcuts.
- `ui/theme/AppearanceSettings.kt`: live persisted theme/navigation choices.

[Artwork provenance and exact prompts](home-artwork.md) document the built-in
image-generation output and its six bundled WebP paths. No image service is used
at runtime. [Validation](validation-v34.md) records actual checks and limitations.
