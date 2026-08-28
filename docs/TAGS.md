# Tags

Registry for greppable `[[type:slug]]` anchors in the TVC RootEncoder fork (see the TVC
[Code Tagging System](https://github.com/masoumehizadi/TVC-Wiki/wiki/Code-Tagging-System)).
Earlier fork additions are marked with the single-bracket `[tvc-sei-capture-ts]` form at the
call sites; they are registered by the app's `[[contract:encoder-stats]]` row in
`mobile_streaming/docs/TAGS.md`.

| Tag | Location | Purpose |
|-----|----------|---------|
| `[[contract:change-video-size-on-fly]]` | `library/src/main/java/com/pedro/library/base/StreamBase.kt` (`changeVideoSizeOnFly`, `resetStreamVideoEncoder`), `encoder/src/main/java/com/pedro/encoder/video/VideoEncoder.java` (`reset(width, height)`); consumer: Android app adaptive-resolution rung (tvc `docs/ADAPTIVE-RESOLUTION-PLAN.md`) | Change the ENCODED size under a live SRT socket. `prepareVideo` refuses while streaming and MediaCodec cannot resize in place, so the rung is a stop/configure/start of the stream encoder — the same path `resetVideoEncoder` takes for codec errors — with the size written into the encoder's stored fields first; the socket, sender queue and TS muxer never see it, the PTS base survives, and the new SPS/PPS ride the existing `onVideoInfo` path onto the next keyframe. Refused while a record SHARES the encoder: the MP4 track keeps its original SPS and would stop decoding at the switch. Published as JitPack tag `2.8.0-tvcsrt4`. |
