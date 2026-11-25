# StickerZap v4: implementation brief (video → animated sticker + sound)

Read `research-sound-stickers.md` first. Key constraint: **WhatsApp's third-party sticker API cannot carry audio.** On the wire, a WhatsApp sound sticker is a WebP plus a separate Opus `AudioMessage` that only WhatsApp's own editor attaches. WhatsApp confirmed on 24 Nov 2026 that third-party support is "on our roadmap" (https://github.com/WhatsApp/stickers/issues/1163). So v4 must:
(a) build great animated stickers from video, with the audio kept for in-app preview;
(b) give a one-tap handoff that gets sound into WhatsApp **through WhatsApp's own "Criar figurinha" editor**;
(c) offer "Compartilhar áudio".
Do **not** fake it: no WebP audio chunks, no MP4 renamed to .webp, no Baileys or WhatsApp Web automation.

## 0. Project facts / constraints
- Path: `/workspace/stickerzap/src/StickerZapAndroid`. Kotlin + Jetpack Compose, single `MainActivity.kt` (~600 lines), plus `data/`, `media/` and `provider/`.
- Toolchain is fixed: AGP 8.5.2, Kotlin 2.0.20, Gradle 8.9, compileSdk/targetSdk 35, minSdk 26, JDK 17. The SDK is at `/opt/android-sdk` with platform **35 only**, and **there is no NDK** (don't add native code).
- **Must build with `./gradlew assembleDebug`**. Unit tests (`./gradlew testDebugUnitTest`) must pass too.
- UI language: Brazilian Portuguese (strings in `res/values/strings.xml`).
- Keep the existing, working WhatsApp import path untouched in behaviour: `WhatsAppIntegrator`, `StickerContentProvider`, `StickerPackContract`, `PackValidator`, the `<queries>` block, and `startActivityForResult` with `ENABLE_STICKER_PACK`. Extend it; don't rewrite it.
- Existing model: `StickerItem(soundFileName, soundLabel)` and `StickerPack(animatedStickerPack)`. `AudioHelper` uses MediaRecorder AAC/MPEG_4 and MediaPlayer.

## 1. Dependencies (only these new ones)
```kotlin
implementation("androidx.media3:media3-transformer:1.8.0")   // 1.8.0: minCompileSdk 35 (1.11.x needs compileSdk 36, don't use)
implementation("androidx.media3:media3-effect:1.8.0")
implementation("androidx.media3:media3-common:1.8.0")
implementation("androidx.media3:media3-exoplayer:1.8.0")
implementation("androidx.media3:media3-ui:1.8.0")            // PlayerView via AndroidView
implementation("io.coil-kt:coil-gif:2.7.0")                  // animated WebP preview on API 28+
```
No FFmpegKit. The original is archived. The community fork `dev.ffmpegkit-maintained` is unaudited and its full build is ~25 MB. Use it only if the pure-Kotlin encoder below truly fails, and report that if so.

## 2. Features

### 2.1 Video import
- "Importar vídeo" button: `ActivityResultContracts.PickVisualMedia` with `VisualMediaType.VideoOnly`.
- Copy the file to `filesDir/videos/<id>.mp4`. Read its duration, size and rotation with `MediaMetadataRetriever`.
- Reject a video with no video track, and show a friendly error.

### 2.2 Timeline trim (pick section)
- New Compose screen `VideoEditorScreen` with an ExoPlayer preview (`PlayerView` in `AndroidView`) and a filmstrip of about 8 thumbnails (`getScaledFrameAtTime` on API 27+, else `getFrameAtTime` + scale).
- Use a range slider (`RangeSlider`) for start and end.
- Default window: the first 3 s. **Max 5 s** for the WhatsApp-handoff path, because WhatsApp keeps 5 s of video audio. The sticker path allows up to 10 s, but warn that the size limit will lower the fps or quality.
- Play/loop the selected range. Show the timestamps, e.g. `00:02.4 – 00:05.0`.

### 2.3 Crop to square
- A draggable and pinch-zoomable square overlay on the preview frame. Store `cropRect` normalised (0..1) relative to the rotation-corrected frame.
- Buttons: "Centralizar", "Preencher" (cover) and "Encaixar" (fit with transparent padding).
- Optional caption text on top, reusing `StickerProcessor.drawMemeText`.

### 2.4 Convert to animated WebP sticker (pure Kotlin)
Create `media/AnimatedWebpEncoder.kt` and `media/VideoToStickerConverter.kt`.
- Frames: sample at `fps ∈ {15, 12, 10, 8}` over [start, end]. Crop and scale each frame to 512×512 ARGB_8888, with transparent letterbox in fit mode, and draw the caption. Run it on `Dispatchers.Default` with a progress callback, and make it cancellable.
- Encode each frame with `Bitmap.compress(WEBP_LOSSY, q)` (API 30+: `WEBP_LOSSY`; API 26–29: `WEBP`).
- Then **mux** the frames per the WebP RIFF container spec (https://developers.google.com/speed/webp/docs/riff_container):
  - Parse each single-frame file: `RIFF....WEBP`, then either `VP8 `, or `VP8L`, or `VP8X` [+`ICCP`/`EXIF` skip] + `ALPH`? + `VP8 `. Keep `ALPH` (if present) + `VP8 `/`VP8L` as the frame payload.
  - Output: `RIFF <size> WEBP`, then `VP8X` (flags: animation 0x02, plus alpha 0x10 if any frame has alpha or is VP8L; canvas width-1 and height-1 as 24-bit LE = 511), then `ANIM` (bg color 0x00000000, loop count 0 = infinite), then one `ANMF` per frame: X=0, Y=0 (24-bit, /2), width-1, height-1 (24-bit), duration ms (24-bit, **≥8**, use `round(1000/fps)`), flags byte `0b10` (do not blend, dispose none), then the frame chunks.
  - Pad every chunk to an even size. The RIFF size is the file length minus 8.
- **Size loop to fit ≤ 500 KB (500 × 1024 bytes; aim for ≤ 495 000 to be safe):**
  1. Quality 75 → 60 → 45 → 30 → 20.
  2. Then lower the fps: 15 → 12 → 10 → 8.
  3. Then shorten the duration from the end in 0.5 s steps (tell the user).
  - Total duration must stay ≤ 10 000 ms.
- **First frame rule:** WhatsApp ends the animation on frame 1. Option "Usar quadro atual como 1º quadro" puts the chosen frame first. Default: frame at start.
- Also save `thumb.png`, a static 96×96 tray candidate taken from the first frame through the existing `saveTrayIcon96`.

### 2.5 Extract audio
Create `media/AudioExtractor.kt`.
- `MediaExtractor` selects the first `audio/*` track. If it is AAC (`audio/mp4a-latm`), copy the samples in [start, end] with `MediaMuxer(MUXER_OUTPUT_MPEG_4)` to `filesDir/audio/<id>.m4a` (lossless, no re-encode). Seek with `SEEK_TO_PREVIOUS_SYNC`, skip samples before start, stop at end, and rebase the PTS to 0.
- If the codec isn't muxable into MP4, or the copy fails, fall back to a Media3 `Transformer` with `EditedMediaItem.Builder(...).setRemoveVideo(true)` + `ClippingConfiguration`, which outputs AAC m4a.
- No audio track: mark the sticker as silent and hide the audio buttons.
- Optional: "trocar áudio" lets the user record a voice note or pick a file, reusing `AudioHelper`, and trims it to the same length.
- Store the result as `StickerItem.soundFileName`.

### 2.6 In-app preview with audio
- On the sticker card and in the chat preview: animated WebP via Coil (`ImageDecoderDecoder` on API 28+). On API 26–27, show the trimmed MP4 muted in a small ExoPlayer instead.
- Show a 🔊 badge, top-left like WhatsApp. Tap it to play the audio with `MediaPlayer`, synced, **3 loops**, then stop. It is muted by default, mirroring WhatsApp.

### 2.7 "Compartilhar áudio no WhatsApp"
- Add `androidx.core.content.FileProvider` (authority `${applicationId}.fileprovider`, paths for `files/audio`, `files/export`).
- Build the intent: `Intent(ACTION_SEND).setType("audio/mp4").putExtra(EXTRA_STREAM, uri).addFlags(FLAG_GRANT_READ_URI_PERMISSION)`, with `setPackage("com.whatsapp")`, falling back to `com.whatsapp.w4b`, then to `Intent.createChooser`. Catch `ActivityNotFoundException`.
- Also add a generic "Compartilhar…" (chooser) option.

### 2.8 "Fazer com som no WhatsApp" (the real path to sound)
- Use Media3 `Transformer` to export the **trimmed + square-cropped MP4 with audio**, ≤5 s, H.264/AAC, 512×512 or 720×720:
  - `MediaItem.ClippingConfiguration` for start and end,
  - effects `Crop` (normalised NDC from cropRect) + `Presentation.createForWidthAndHeight(512, 512, LAYOUT_SCALE_TO_FIT)`.
- Save it to the gallery:
  - API 29+: `MediaStore.Video.Media` with `RELATIVE_PATH = "Movies/StickerZap"` and `IS_PENDING`.
  - API 26–28: `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`, requested at runtime.
- Save the audio clip too, to `Music/StickerZap/<name>.m4a`, so the user can pick it in WhatsApp's "importar áudio" option.
- Then show a bottom sheet with the guide (PT-BR):
  1. "Abra uma conversa → figurinhas → **Criar**"
  2. "Escolha o vídeo *StickerZap_<nome>* (já cortado)"
  3. "Toque no ícone de **ondas sonoras** e mantenha o som original → Enviar"
  4. Note: "Se o botão de som não aparecer, sua conta ainda não recebeu o recurso (liberação gradual desde 24/11/2026). Dica: figurinhas com som que você recebe podem ser favoritadas e reenviadas com som."
- Button "Abrir WhatsApp": `packageManager.getLaunchIntentForPackage("com.whatsapp")`, else `w4b`. There is **no documented intent that opens WhatsApp's sticker creator**, so don't invent one.
- Also offer "Compartilhar vídeo", an `ACTION_SEND video/mp4` chooser, for anyone who wants to send it somewhere else.

### 2.9 Albums / pack rules
- Each pack is **all static or all animated**. Show the pack type on creation. Block adding a mismatched sticker, with the message "Este álbum é de figurinhas estáticas/animadas".
- An animated pack sets `animated_sticker_pack = 1` in the ContentProvider metadata, keeps the tray icon as a static 96×96 PNG ≤50 KB, and bumps `image_data_version` on every change.
- `PackValidator`:
  - animated ≤500 KB, 512×512 (parse VP8X canvas), each ANMF duration ≥8 ms, total ≤10 s;
  - static ≤100 KB;
  - 3–30 stickers, 1–3 emojis;
  - accessibility text ≤125 chars for static, ≤255 for animated.
- Keep the existing "≥3 stickers" warning.
- The ContentProvider must serve `.webp` assets as `image/webp`. Never expose audio files through it, because WhatsApp ignores them.
- UI copy where the pack is added: "No WhatsApp a figurinha chega animada e sem som (limite do WhatsApp para apps de terceiros). Para som, use 'Fazer com som no WhatsApp'."

### 2.10 Model changes
- `StickerItem`: add `isAnimated: Boolean = false`, `sourceVideoFile: String? = null`, `trimStartMs/trimEndMs: Long? = null`, `cropRect: List<Float>? = null`, `exportVideoUri: String? = null`, and `durationMs`.
- Make it backward compatible with existing saved JSON (defaults).
- Bump `versionCode = 4`, `versionName = "4.0"`.

## 3. Tests (JVM, no Robolectric needed)
- `AnimatedWebpMuxerTest`:
  - feed synthetic single-frame WebP byte arrays (hand-built `RIFF/WEBP/VP8 ` with a dummy payload, plus one `VP8X+ALPH+VP8 ` variant);
  - assert the output layout `RIFF…WEBP VP8X ANIM ANMF…`, the flags (0x02 / 0x12), the 24-bit fields, the even padding, the RIFF size, and that durations are clamped to ≥8 ms.
  - Keep the muxer free of `android.*` so it is unit-testable; put the Bitmap encoding in a separate class.
- `PackValidatorTest`: mixed pack rejected, animated >500 KB rejected, total >10 s rejected.
- Existing `StickerPackContractTest` must still pass.

## 4. Acceptance criteria
1. `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` succeed from a clean checkout on the given toolchain: compileSdk 35, no NDK, no new native libs. The APK grows by < 6 MB.
2. Import a 30 s 1080p phone video, pick 2.0–5.0 s, crop a square, and convert: you get a 512×512 animated WebP ≤500 KB, the animation plays in-app, and `PackValidator` passes it.
3. The extracted audio (`.m4a`) has the same length as the selection ±100 ms. The 🔊 badge in-app plays it 3× in sync, muted by default.
4. "Compartilhar áudio" opens WhatsApp's contact picker (or a chooser) with the m4a. No crash if WhatsApp is missing.
5. "Fazer com som no WhatsApp" writes `Movies/StickerZap/StickerZap_<nome>.mp4` (square, ≤5 s, **with audio**) and `Music/StickerZap/<nome>.m4a`. Both show in the gallery and Files. The guide sheet appears, and "Abrir WhatsApp" launches WhatsApp.
6. An animated album with ≥3 video stickers is added via "Adicionar ao WhatsApp" and shows animated in WhatsApp's tray. A static album still works exactly as in v3. Mixing types is blocked in the UI.
7. Videos with no audio track work: the sticker is created, and the audio buttons are hidden.
8. Long conversions show progress, can be cancelled, and never block the UI thread. There is no OOM on a 10 s selection: recycle bitmaps and keep at most about 2 frames in memory plus the encoded byte arrays.
9. All new UI strings are in PT-BR and live in `strings.xml`.

## 5. Out of scope / do not do
- Anything that injects audio into WhatsApp sticker messages: protocol tricks, Baileys/whatsmeow, WhatsApp Web automation, or root.
- The undocumented `com.whatsapp.intent.action.SEND_STICKER` intent.
- Upgrading AGP, Kotlin or compileSdk.

## 6. Deliverables
- Updated source.
- `app/build/outputs/apk/debug/app-debug.apk`.
- A short CHANGELOG entry in `README.md` (PT-BR) explaining why sound only works via WhatsApp's own editor, with links to issue #1163 and the Baileys analysis.
