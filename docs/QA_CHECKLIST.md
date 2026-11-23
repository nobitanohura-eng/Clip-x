# ClipAI v2 — Manual Device QA Checklist

Run every item below on physical arm64 Android hardware (API 29+) before tagging a production release.

## 1. Device Matrix & Memory Tiers
- [ ] **4 GB RAM (minimum path):** Whisper Tiny only, LLM disabled automatically, 720p fast export recommended, zero OOM crashes across a 10-minute source video.
- [ ] **6 GB RAM (mid-range):** Whisper Tiny default, optional Gemma 3 1B only, sequential load/release verified via Android Studio Memory Profiler (Whisper and Gemma never resident together).
- [ ] **8–12 GB RAM (flagship):** Whisper Base opt-in and Gemma 4 E2B `.litertlm` GPU->CPU fallback verified.

## 2. Video Ingest, Codecs & Edge Cases
- [ ] **SAF URI access:** Video picked via `ACTION_OPEN_DOCUMENT` without copying the source file wholesale into app-private storage.
- [ ] **HDR / 4K / 60 fps:** Probed cleanly, proxy frames sampled without OOM, exported cleanly as SDR 1080×1920 H.264.
- [ ] **Portrait rotation metadata & VFR screen recordings:** Output orientation matches source visually; A/V sync holds to the final second.
- [ ] **Re-clipping same video:** SHA-256 hash matches cached Room transcript and skips ASR stage cleanly.

## 3. Captions, Devanagari & Hooks
- [ ] **Hindi + English + Hinglish:** Verify zero split words, zero orphaned Devanagari matras or consonant clusters; every caption line stays <= 3 words and <= 42 characters.
- [ ] **Confidence review:** Low-confidence words show `⚠ Review` / `⚠ Uncertain` (`___`), and manual edits lock in and survive re-scoring.
- [ ] **Hook overlay:** Shown strictly in top third for 0–3.0 s; verbatim quotes <= 10 words; titles explicitly marked with `TITLE`.

## 4. Reliability, Offline & Guardrails
- [ ] **Airplane mode:** After models are downloaded, run full Auto Clip + Export in airplane mode with zero crashes or network errors.
- [ ] **Storage-full guardrail (<3 GB free):** App refuses cleanly before writing temp WAV or render intermediates with a human-readable message.
- [ ] **Cancel & resume:** Cancelling foreground `mediaProcessing` job stops promptly, cleans up temp files, and keeps already-finished clips.
- [ ] **Corrupt model file:** Modifying a downloaded model triggers SHA-256 mismatch rejection and prompts clean redownload.
- [ ] **Post-export QA gate:** Verify duration, 9:16 resolution, bitrate, non-blank midpoint thumbnail, and loudness target (-14 LUFS / -1 dBTP) before sharing.
