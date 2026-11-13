# FoldAmp

A classic-skinned (Winamp 2.x style) music player for the Galaxy Fold 8, written in Kotlin with
Media3/ExoPlayer and plain Android views.

## Just give me the APK

Sure, man. Knock yourself out. You can find it in [Releases](https://github.com/fraize/FoldAmp/releases). 

There are instructions for how to install .apk files outside of the Play Store [over here](https://www.androidauthority.com/how-to-install-apks-31494/).

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

## Building from source

FoldAmp builds the same way on macOS, Linux and Windows. CI builds it on all three on every push
(see `.github/workflows/build.yml`).

### What you need

| | Version | Notes |
|---|---|---|
| JDK | 17 or newer (21 recommended) | Android Studio ships one; otherwise e.g. [Temurin](https://adoptium.net) |
| Android SDK | Platform 37, build-tools, platform-tools | Missing SDK packages download on the first build once licenses are accepted |
| Gradle | 9.8.2 | Included: always use the wrapper (`gradlew`), not a system Gradle |
| Python 3 + Pillow | any recent | Only for regenerating the rebranded skins |

**The easy route:** install [Android Studio](https://developer.android.com/studio), open this
folder, and let it sync. It provides the JDK and SDK and writes `local.properties` for you.

**Command line only:** install a JDK and the Android
[command-line tools](https://developer.android.com/studio#command-line-tools-only), then point
Gradle at the SDK, either with an `ANDROID_HOME` environment variable or a `local.properties` file in
the project root:

```properties
sdk.dir=/path/to/Android/sdk
```

Default SDK locations: `~/Library/Android/sdk` (macOS), `~/Android/Sdk` (Linux),
`%LOCALAPPDATA%\Android\Sdk` (Windows; in `local.properties` write it with forward slashes or
doubled backslashes). Accept the SDK licenses once with `sdkmanager --licenses`.

### Build and run

The commands below use `./gradlew` (macOS, Linux, Git Bash, PowerShell). In Windows `cmd`, type
`gradlew` instead.

```bash
./gradlew assembleDebug     # debug APK in app/build/outputs/apk/debug/
./gradlew runDebug          # build, install on the connected phone or emulator, and launch
./gradlew assembleRelease   # minified APK (signed with the debug key for now)
```

`runDebug` uses the SDK's own `adb`, so it doesn't need `adb` on your `PATH`. To install on a
phone, turn on Developer options ▸ USB debugging and accept the prompt the first time you connect.

### Emulator

In Android Studio's Device Manager, create a virtual device from a foldable profile (for example
Pixel 9 Pro Fold), which has both an inner and a cover display. Start it, then `./gradlew runDebug`.
Handy commands while testing (needs `platform-tools` on your `PATH`):

```bash
adb emu fold / adb emu unfold                 # switch between cover and inner screen
adb shell cmd window user-rotation lock 1     # landscape (0 = portrait, "free" to unlock)
adb exec-out screencap -p -d <display-id> > shot.png   # ids from: adb shell dumpsys SurfaceFlinger --display-id
```

### Rebranded skins

The patched skins in `app/src/main/assets/skins/` are generated from the originals in `skins/`:

```bash
python3 -m pip install Pillow     # Windows: py -m pip install Pillow
python3 scripts/rebrand_skins.py  # Windows: py scripts\rebrand_skins.py
```

### Troubleshooting

- **macOS: `adb` hangs when its server starts.** Some Macs need the libusb backend. Set
  `ADB_LIBUSB=1` in your shell profile, then run `adb kill-server`.
- **Windows: the phone doesn't show up in `adb devices`.** Install your phone maker's USB driver
  (for Samsung, the Samsung Android USB Driver).
- **Linux: `adb devices` says "no permissions".** Add a udev rule for your phone (or install your
  distro's `android-udev-rules` package) and make sure your user is in the `plugdev` group.
- **"SDK location not found".** Set `ANDROID_HOME` or create `local.properties` as shown above.

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
