# FoldAmp

A classic-skinned (Winamp 2.x style) music player for the Galaxy Fold 8, written in Kotlin with
Media3/ExoPlayer and plain Android views.

## Dev environment

Everything lives in the standard locations on this Mac:

| Tool | Version | Location |
|---|---|---|
| JDK | Temurin 21 | `/Library/Java/JavaVirtualMachines/temurin-21.jdk` |
| Android SDK | platforms 36 / 37, build-tools 36 / 37, emulator, platform-tools | `~/Library/Android/sdk` |
| Gradle | 9.8.2 (wrapper checked in) | `./gradlew` |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin) | `build.gradle.kts` |
| Emulator | `tl_fold` (Pixel Pro Fold profile, inner + cover displays) | `~/.android/avd` |

```bash
scripts/run.sh             # build debug APK, install, launch
scripts/run.sh --emulator  # same, booting the tl_fold AVD first if nothing is connected
./gradlew assembleRelease  # minified APK (debug-signed for now)
```

**adb on this Mac:** the default macOS USB backend makes the adb daemon hang at startup. Set
`ADB_LIBUSB=1` (the run script does this) or add it to your shell profile.

Emulator tricks used during development:

```bash
adb emu fold / adb emu unfold                 # switch between cover and inner screen
adb shell cmd window user-rotation lock 1     # landscape (0 = portrait, "free" to unlock)
adb exec-out screencap -p -d <display-id>     # ids from: adb shell dumpsys SurfaceFlinger --display-id
```

## Features

- **Playback:** MP3, AAC/M4A, FLAC, Ogg Vorbis, Opus, WAV and AMR via platform decoders, and
  ALAC, AC-3/E-AC-3, DTS and more via the prebuilt Jellyfin FFmpeg extension. (FFmpeg only adds
  decoders; WMA, APE and WavPack containers have no Media3 extractor, so they don't play.) Media3 is
  pinned to 1.9.x so it stays binary-compatible with that extension. Background playback runs
  through a `MediaSessionService`, with the system notification and lock-screen controls.
- **Metadata:** title, artist, album artist, album, genre, composer, length, track and disc number,
  year, bitrate, sample rate, channels. Tags come from MediaStore, and gaps (MediaStore often drops
  the year for ID3v2.4, FLAC and Vorbis) are filled by reading the files directly. Results are
  cached in `files/tagcache.json`.
- **Equalizer:** 10 bands (60 Hz to 16 kHz) plus preamp, implemented as biquad peaking filters in
  a Media3 `AudioProcessor`. It has 18 classic presets, user presets (save and delete), ON, and
  AUTO (picks a preset matching the track's genre). Double-tap a slider to reset it to 0 dB.
- **Skins:** classic `.wsz` skins (`main`, `titlebar`, `cbuttons`, `shufrep`, `text`, `numbers` /
  `nums_ex`, `volume`, `balance`, `posbar`, `playpaus`, `monoster`, `eqmain`, `pledit` + `pledit.txt`,
  `gen`, `viscolor.txt`). Missing bitmaps fall back to the base skin. `region.txt` is ignored
  (square windows only). The three skins in `skins/` are bundled; import more from the ⚡ menu ▸
  Skins ▸ Import, or open a `.wsz` with FoldAmp.
- **Windows:** Player, Equalizer, Playlist, Media Library (search, plus Songs / Artists / Albums /
  Genres / Playlists / Radio tabs), and a Spectrum visualizer (tap it to cycle rainbow blocks / skin colors /
  oscilloscope).
- **Internet radio:** the Library's Radio tab lists the most-voted working stations from
  [radio-browser.info](https://www.radio-browser.info) (free, no API key) and searches by name or
  genre tag. Tap a station to play it; long-press to add or remove it as a favorite, which puts it
  in the library under "Internet Radio". Icecast/SHOUTcast now-playing titles scroll in the marquee,
  and HLS streams work too. If the directory is unreachable, a built-in list of SomaFM channels is shown.
- **Rebranded skins:** the bundled skins' title bars say FOLDAMP instead of WINAMP.
  `python3 scripts/rebrand_skins.py` regenerates them from `skins/` (needs Pillow).

## Fold layouts (`ui/FoldLayout.kt`)

All windows share one scale factor, so their chrome lines up. The cover and inner screens are told
apart by `smallestScreenWidthDp >= 600`.

| Screen | Layout |
|---|---|
| Cover, portrait | Player (full width, fixed ratio); Playlist fills the rest |
| Cover, landscape | Player over Equalizer, scaled uniformly to fit, centered. EQ/PL buttons swap the lower window |
| Inner, landscape | Player top-left at 50% width, Visualizer below it; Playlist on the right, full height |
| Inner, portrait | Player, a Visualizer strip, then the Playlist (as in the rotated mockup) |

EQ and PL toggle those windows anywhere. ⏏ / ADD opens the Library in the playlist or visualizer slot.

## Touch controls

- Playlist: tap selects, double-tap plays, long-press adds to the selection and opens row actions,
  drag or fling to scroll. ADD / REM / SEL / MISC / LIST OPTS menus work as in Winamp (sort,
  randomize, crop, save and load playlists, and so on).
- Player: tap the clock to toggle elapsed / remaining, tap the song title for file info, tap the
  mini analyzer to change its mode. The options button (top-left) opens the main menu.

## Code map

```
app/src/main/java/com/foldamp/
  playback/  PlayerHub (ExoPlayer + playlist + persistence), PlaybackService (MediaSession),
             EqAudioProcessor (EQ/balance DSP + visualizer tap), Equalizer (state/presets),
             VisTap (ring buffer + FFT analyzer)
  library/   Track, MediaLibrary (MediaStore scan, file tags, saved playlists, radio favorites),
             RadioBrowser (radio-browser.info client + SomaFM fallback)
  skin/      Skin + SkinManager (.wsz loading), BmpDecoder (fallback for odd BMPs)
  ui/        MainActivity, FoldLayout, SkinView base, Main/Eq/Playlist/Gen(Visualizer) windows,
             LibraryWindow
```

## License

MIT; see [LICENSE](LICENSE). The bundled skins belong to their respective authors.
