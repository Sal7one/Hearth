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

New installations default to **Ink + Dark**, with Color & blur enabled. Existing
explicit theme/brightness selections are preserved; missing or unknown preferences
fall back to Ink and Dark. System and Light remain selectable.

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

## Hearth 0.20.0 feature screens

The illustrated Home carousel and remembered service remain unchanged. Color &
blur is the default surface style: feature-specific colored panels, lit edges,
static artwork blurred behind the content, and illustrated conversation/reading
entry areas. Appearance → Minimal · backup restores plain surfaces. The setting
is independent of theme and persists; it does not overwrite the saved palette.
Only decorative artwork is blurred (Android 12+); older devices retain the color
wash without blur. Text, camera images and touch targets stay sharp. No recurring
animation or network artwork request is introduced.

Inside features, everyday actions take priority over setup:

- Captions: audio, caption/translation mode and supported language choices stay
  visible. Speech engine/provider setup moves into Speech & translation. Start
  stays pinned at the bottom; running/failed/setup-needed states remain explicit.
- Typed translation: language pair, input, Translate/Listen, then result. Translation
  settings opens the existing provider/model chooser and typing/voice options.
- Conversation: language pair, dialogue and two Speak buttons. History and Settings
  have labelled icon buttons; New conversation is in Settings. Face to face stays
  directly available. Source-language capability failures remain visible.
- Camera: preview and Capture/Choose photo come first. Live mode remains available;
  the Screen & manga shortcut follows the camera content. Setup uses one sheet.
- Reading: trigger choice and Start stay on the main page. Timing, translator,
  OCR/language setup and interaction help live in Reading settings. Start is pinned.
- Downloads: Get models, Folder and From link precede transfer cards. Folder and
  direct URL details open separate sheets. FOSS has an Import models action.
- Shared setup: large Speech, Translation, Voices and Camera/OCR tiles, separated
  by Local/Cloud. Appearance and downloads remain direct settings shortcuts.

`FeatureAction` is a native, labelled card with a decorative icon and current-value
summary. `FeatureOptionsSheet` provides a visible Close action, scrolling and
feature-owned scroll state. Both are presentation components, not new engine or
configuration stores. Settings screens continue to use their existing per-tab
saved state. Images remain local; no new model/runtime dependency was added.

Manual read-aloud now has one Listen action using the saved default voice, plus a
labelled menu for explicit Android/custom playback and voice setup. This reuses
existing voice route validation and FOSS restrictions. Opening a menu never plays
speech or changes the default.

### Navigation paths

From a feature: Settings → existing translator chooser → provider/model (three
controls, excluding any necessary downloads, key entry or Android consent).
Conversation → Settings → New conversation is two taps. The toolbar gear → Local
or Cloud → setup tile is at most three taps. Typed text model links open Translation,
and camera model links open Camera, instead of opening unrelated Speech setup.

These counts describe entry paths, not a claim that every advanced setting or
installation can be completed in a fixed number of taps. Detailed provider forms,
model catalog cards and overlay editor controls remain available. Full TalkBack,
large-font and all-device validation remains a separate check.
