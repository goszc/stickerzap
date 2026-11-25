# WhatsApp sound stickers: research for StickerZap (25 Nov 2026)

Legend: **[CONFIRMED]** means an official source, multiple independent hands-on reports, or wire-level evidence. **[REPORTED]** means a single credible report or APK teardown. **[SPECULATION]** means my own inference, so test it before relying on it.

## TL;DR: why our stickers are silent and others' aren't

1. A WhatsApp sound sticker is **not a WebP with audio inside it**. On the wire it is a normal `StickerMessage` (an `image/webp` file, static or animated) that **also carries a separate, encrypted `AudioMessage` (an `audio/ogg; codecs=opus` file)**. That AudioMessage sits in the new field `StickerMessage.audioMessage = 26`, and the whole message is wrapped in a new top-level `Message` field 134 (`audioStickerMessage`, a FutureProofMessage). **[CONFIRMED]** by whatsmeow's proto, a decompile-based RE report, and two Baileys devs who tested sending and receiving end to end.
2. The **third-party sticker API** (ContentProvider plus the `ENABLE_STICKER_PACK` intent) has **no audio field**. WhatsApp reads only `sticker_file_name`, `sticker_emoji` and `sticker_accessibility_text`, then fetches the WebP. Any audio our app has never leaves the app. **[CONFIRMED]**
3. On 24 Nov 2026 a WhatsApp engineer answered the official repo: *"This API isn't available yet for third party stickers, but it's on our roadmap."* **[CONFIRMED, official]**
4. Sound stickers "from other people" were made **inside WhatsApp's own "Create sticker" editor**, which is rolling out gradually. **Being able to receive or play sound stickers does not mean your account can create them.** You can still favourite a received sound sticker and forward it with its sound. **[CONFIRMED by several Brazilian outlets]**
5. ⇒ **No legitimate way exists today for a third-party app to put sound into a WhatsApp sticker.** The best real path is to prepare the media in our app (trimmed, square-cropped MP4 with audio, plus an audio clip file) and hand it to WhatsApp's own sticker creator, where the user taps the sound-wave button. That works only if their account has the creator feature.

---

## 1. How sound stickers work technically

### 1.1 Wire format
- **whatsmeow's current proto** (`proto/waE2E/WAWebProtobufsE2E.proto`), as of today: **[CONFIRMED]**
  ```proto
  message StickerMessage {
    oneof audio { AudioMessage audioMessage = 26; }
    optional string URL = 1; ... optional string mimetype = 5; ...
    optional bool isAnimated = 13; ... optional bool isAvatar = 19; optional bool isAiSticker = 20;
    optional bool isLottie = 21; optional string accessibilityLabel = 22; optional int32 premium = 24; optional string emojis = 25;
  }
  message Message { ... optional FutureProofMessage audioStickerMessage = 134; ... }
  ```
  Source: https://github.com/tulir/whatsmeow/blob/main/proto/waE2E/WAWebProtobufsE2E.proto
- **Baileys issue #2932** (opened 24 Nov 2026, "Sound stickers ... arrive as an empty message: new top-level field 134"): https://github.com/WhiskeySockets/Baileys/issues/2932 **[CONFIRMED, tested end to end]**
  - Layout: `Message.134 { 1: Message { 26: StickerMessage { ...usual fields..., 24: 0, 25: emoji (optional), 26: AudioMessage } } }`.
  - The audio is a regular `AudioMessage`: `audio/ogg; codecs=opus`, `ptt` unset. It downloads through the normal media path.
  - The stanza still says `<enc mediatype="sticker">`. Nothing at the node level marks it as a sound sticker.
  - Sending works with a monkeypatch: upload the Opus audio, send a normal sticker, then rewrite the encoded proto into the 134 wrapper.
  - Follow-up comment (GoJoMc): the mimetype must be exactly `audio/ogg; codecs=opus`, because plain `audio/ogg` renders but stays silent. The limit appears to be file size (about 1,000,000 bytes) rather than duration, and that is unverified. ffmpeg `.ogg` output that carried a Theora cover-art stream did not play.
- **RE report of Android 2.26.41.6** (docs.baron0.com, static decompile, 1 Nov 2026): https://docs.baron0.com/WA_STICKERS/ **[REPORTED]**
  - Confirms `StickerMessage.audioMessage` (26, `oneof audio`) and `Message.audioStickerMessage` (134).
  - Confirms a client package `com.whatsapp.stickers.sound` containing `StickerOpusEncoder.nativeEncode(short[] pcm, String outPath)` and `SoundStickerAudioDownloader`.
  - The third-party ContentProvider contract has only `sticker_file_name`, `sticker_emoji` and `sticker_accessibility_text`. **No audio column.**
  - Lists an exported `com.whatsapp.intent.action.SEND_STICKER` activity (`SendStickerCommandActivity`, `mimeType="image/webp"`, 512×512, ≤500000 bytes). It is **not in the official README**, so treat it as undocumented. It also has no audio extra.
- ⇒ The audio is a **separate Opus file attached at send time**. It is not muxed into the WebP. Even a "WebP with an audio chunk" or an MP4 renamed to .webp will **never** play sound. **[CONFIRMED by the above]**

### 1.2 Behaviour **[CONFIRMED, WABetaInfo and press]**
- The sticker shows a speaker icon (top-left on phones, top-right on WhatsApp Web) and is **muted by default**. Tapping the icon plays the audio, which loops 3× in sync with the animation's 3 loops. It respects silent mode and is end-to-end encrypted.
- It works on **static and animated** stickers.
- Limits seen in an Android Authority teardown (v2.26.41.11): up to **10 s of audio for image stickers**, and **5 s of the original audio for video-based stickers**, which can be replaced. **[REPORTED]** Baileys testers saw received clips from 1 s up to 20 s.
- Sources:
  - https://wabetainfo.com/whatsapp-is-testing-a-new-sound-sticker-format-on-android/ (13 Oct 2026, v2.26.41.3)
  - https://www.androidauthority.com/whatsapp-sound-stickers-apk-teardown-3724247/ (16 Oct 2026)
  - https://wabetainfo.com/whatsapp-is-rolling-out-new-sound-sticker-messages-on-android/ (25 Nov 2026, Android beta 2.26.47.1)
  - https://wabetainfo.com/whatsapp-is-rolling-out-sticker-messages-with-audio-on-ios/ (25 Nov 2026, iOS beta 26.46.10.70)

## 2. How users create them inside WhatsApp (Nov 2026)
- The flow: open the sticker keyboard, tap **Create sticker**, and pick a **video** (or an image) from the gallery. WhatsApp auto-converts a video into an animated sticker. A **sound-wave/audio button** at the top of the editor opens "Add sound", which offers:
  - keep the original audio,
  - pick a preset effect (for example "Evil Laugh", "Crickets", "Cat Purr"),
  - import an audio file from Files, or
  - record a voice note.
  The user then trims the audio and sends. **[CONFIRMED]**
- Rollout: Android beta 2.26.47.1, iOS TestFlight 26.46.10.70 and some App Store users, and WhatsApp Web. In Brazil it is being **released gradually since 24 Nov 2026**, and some accounts can receive sound stickers but cannot create them. Meta has made no official announcement or global date. **[CONFIRMED by multiple outlets]**
  - https://www.techtudo.com.br/dicas-e-tutoriais/2026/11/como-criar-figurinhas-com-audio-no-whatsapp-veja-passo-a-passo-edapps.ghtml
  - https://tecnologia.ig.com.br/2026-11-25/whatsapp-libera-figurinha-com-audio--veja-como-criar-a-sua.html
  - https://www.otempo.com.br/tecnologia-e-games/2026/11/25/figurinhas-com-audio-no-whatsapp-veja-o-passo-a-passo-para-criar
  - https://celular.pro.br/whatsapp-figurinhas-com-som/
- **Workaround that already works for everyone who can receive them:** favourite a received sound sticker and re-send it, and the audio goes along. **[REPORTED, TecMundo via iG]**
- **The user's report fits this exactly.** "I couldn't make a sticker with sound, it stays silent, but other people's work fine" most likely means one of two things: (a) his account doesn't have the *creation* tool yet, or (b) he made it in our app, which can't attach audio. Both lead to the same conclusion. **[SPECULATION about which of the two]**

## 3. Third-party apps
- Official repo WhatsApp/stickers: the last commit is **7 Nov 2025** ("Add copyright headers"). The README doesn't mention audio or sound. https://github.com/WhatsApp/stickers
- **Issue #1163** (24 Nov 2026), "Audio stickers: will third-party sticker packs support them, and what's the API?" A WhatsApp member (omerfarukozturk) replied: **"This API isn't available yet for third party stickers, but it's on our roadmap. We'll post an update here..."** https://github.com/WhatsApp/stickers/issues/1163 **[CONFIRMED]**
- Sticker.ly, Wemoji and similar apps: their store listings and reviews show only static and animated export. No sound-sticker export was found. **[CONFIRMED absence in listings; can't prove a negative]**
  - https://play.google.com/store/apps/details?id=com.snowcorp.stickerly.android
  - https://www.archyde.com/whatsapp-begins-rolling-out-sound-stickers-on-android-beta/ (notes that the creation flow is built into WhatsApp, unlike third-party makers)
- **No public intent opens WhatsApp's sticker creator with a preloaded video.** The only documented intent is `com.whatsapp.intent.action.ENABLE_STICKER_PACK`. `ACTION_SEND video/mp4` to `com.whatsapp` sends a **video message**, not a sticker. **[CONFIRMED that no such intent is documented; SPECULATION that no hidden one exists]**
- Unofficial route: automate a linked device with Baileys or whatsmeow to send the 134-wrapped message (proven working in #2932). This **violates the WhatsApp ToS, risks a ban, and needs the user's session**. **NOT recommended or used.**

## 4. Third-party sticker limits (still apply to our pack export) **[CONFIRMED, official README]**
https://github.com/WhatsApp/stickers/blob/main/Android/README.md
- 512×512 WebP. Static stickers ≤100 KB, animated ≤500 KB.
- Animated: each frame ≥8 ms and total ≤10 s. The first frame should show the full image, because the animation ends on it.
- Tray icon: 96×96 PNG, ≤50 KB, always static.
- 3–30 stickers per pack, **never a mix of static and animated**. The `animated_sticker_pack` metadata column is required (1) for animated packs. Emojis: 1–3 per sticker. Accessibility text: ≤125 chars for static, ≤255 for animated.
- `ENABLE_STICKER_PACK` must be started with `startActivityForResult`. The result extras are `add_successful`, `already_added` or `validation_error` (docs.baron0).

## 5. Building blocks on Android (verified 25 Nov 2026)
| Need | Option | Status |
|---|---|---|
| Trim, square-crop and re-encode MP4 with audio | **Media3 Transformer**: `MediaItem.ClippingConfiguration`, `androidx.media3.effect.Presentation`/`Crop`, `EditedMediaItem`. **1.8.0 needs minCompileSdk 35**, which our project uses. 1.11.1 is the latest stable and needs **compileSdk 36**. | [CONFIRMED from AAR metadata] https://developer.android.com/media/media3/transformer |
| Extract or trim audio losslessly | `MediaExtractor` + `MediaMuxer(MUXER_OUTPUT_MPEG_4)` copies the AAC track into `.m4a`. Alternatively use Media3 `EditedMediaItem.setRemoveVideo(true)`. | platform API |
| Opus/OGG audio | `MediaCodec` Opus encoder plus `MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG`, both **API 29+**. Optional: WhatsApp's file importer probably also accepts m4a/mp3. | [CONFIRMED API levels; SPECULATION on importer formats] |
| Video frames | `MediaMetadataRetriever.getScaledFrameAtTime` (API 27+) or `getFrameAtTime(OPTION_CLOSEST)`. Media3 `ExperimentalFrameExtractor` is newer. | platform |
| Animated WebP encode | (a) **Pure Kotlin RIFF muxer**: encode each frame with `Bitmap.compress(WEBP_LOSSY)`, take its `ALPH`/`VP8 `/`VP8L` chunks, and wrap them in `VP8X`+`ANIM`+`ANMF`, following the spec at https://developers.google.com/speed/webp/docs/riff_container. No NDK is needed. (b) libwebp `WebPAnimEncoder` via NDK and JNI: libwebp v1.6.0 is the latest, it compresses better because it can diff frames, but our box has **no NDK installed**. (c) FFmpeg `libwebp_anim`. | |
| FFmpegKit | The original `arthenica/ffmpeg-kit` is **archived** and its binaries are gone from Maven Central. The community fork **`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`** (LGPL; `-video` includes libwebp; the AAR is ~25 MB) is a drop-in group-ID swap, but it is an unofficial maintainer that also upsells a "Pro" build, so **audit before use**. The official successor `arthenica/ffmpeg-kit-next` publishes nothing to Maven, so you would have to build it yourself. | [CONFIRMED] https://github.com/arthenica/ffmpeg-kit · https://github.com/ffmpegkit-maintained/ffmpeg · https://github.com/arthenica/ffmpeg-kit-next |
| Android's built-in animated WebP | `AnimatedImageDrawable` / `ImageDecoder` can **decode only** (API 28+). There is no platform encoder. | [CONFIRMED] |

## 6. Recommended architecture (summary; details in cursor-brief.md)
1. **Video → animated WebP sticker** (silent in WhatsApp, as required by the API): pick a video, choose a ≤5 s section on a timeline (≤10 s is allowed, but 5 s keeps the file under 500 KB), crop to a square, sample at about 10–15 fps, encode an animated WebP ≤500 KB, and store the extracted audio next to it. The in-app preview plays it with sound. Export packs through the ContentProvider; such packs must be all animated.
2. **"Fazer com som no WhatsApp" (the real path to sound):** export the trimmed, square MP4 **with its audio** (≤5 s) to the gallery (`Movies/StickerZap`) and the audio clip to `Music/StickerZap`. Then open WhatsApp with a 3-step guide: Figurinhas → Criar → pick the StickerZap video → tap 🔊 and keep the original sound. This requires the account to have the creator feature, so the app explains that.
3. **"Compartilhar áudio"**: `ACTION_SEND`, `audio/mp4` (m4a), with a FileProvider URI and `setPackage("com.whatsapp")`. A chooser fallback is also offered. This sends the clip as an audio message right after the sticker.
4. Watch issue #1163. If an audio column or field is added to the ContentProvider contract, wire the stored Opus clip into it.
