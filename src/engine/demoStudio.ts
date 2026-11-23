import {
  AppSettings,
  DeviceProfile,
  FaceSample,
  ModelSpec,
  ProjectRecord,
  QaChecklistItem,
  RmsPoint,
  SceneCut,
  WordToken,
} from '../types';
import { buildClipsFromAnalysis, classifyConfidence } from './domain';

export const DEFAULT_SETTINGS: AppSettings = {
  exportPreset: '1080p',
  targetLufs: -14.0,
  truePeakDbtp: -1.0,
  minClipSec: 30,
  maxClipSec: 60,
  windowStepSec: 12,
  dedupGapSec: 30,
  maxClips: 5,
  wifiOnlyOver100Mb: true,
  requireChargingForHeavy: true,
};

export const DEFAULT_DEVICE_PROFILE: DeviceProfile = {
  ramGb: 8,
  freeStorageGb: 11.4,
  wifiConnected: true,
  charging: true,
  batteryPercent: 82,
  hfTokenConfigured: false,
  llmEnabled: false,
  asrModelId: 'whisper-tiny-int8',
  llmModelId: 'gemma-4-e2b-int4',
};

export const INITIAL_MODELS: ModelSpec[] = [
  {
    id: 'whisper-tiny-int8',
    name: 'Whisper Tiny (INT8)',
    runtime: 'Sherpa-ONNX AAR',
    category: 'asr',
    sizeMb: 44,
    ramRequiredMb: 150,
    minDeviceRamGb: 4,
    speedNote: 'RTF 0.20–0.80 · Multilingual · Word timestamps',
    role: 'Default ASR for all phones (4 GB+)',
    defaultSelected: true,
    license: 'MIT',
    sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    status: 'installed',
    downloadedMb: 44,
    verifiedSha256: true,
  },
  {
    id: 'whisper-base-int8',
    name: 'Whisper Base (INT8)',
    runtime: 'Sherpa-ONNX AAR',
    category: 'asr',
    sizeMb: 118,
    ramRequiredMb: 250,
    minDeviceRamGb: 8,
    speedNote: 'RTF ~1.20 · Higher multilingual accuracy',
    role: 'Opt-in ASR for 8 GB+ flagship devices',
    license: 'MIT',
    sha256: '8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4',
    status: 'not-downloaded',
    downloadedMb: 0,
    verifiedSha256: false,
  },
  {
    id: 'moonshine-tiny',
    name: 'Moonshine Tiny',
    runtime: 'ONNX Runtime',
    category: 'asr',
    sizeMb: 26,
    ramRequiredMb: 110,
    minDeviceRamGb: 4,
    speedNote: '~5x lower compute than Whisper Tiny · English-first',
    role: 'Fast low-power ASR fallback',
    license: 'MIT',
    sha256: '2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae',
    status: 'not-downloaded',
    downloadedMb: 0,
    verifiedSha256: false,
  },
  {
    id: 'blazeface-mediapipe',
    name: 'BlazeFace Short-Range',
    runtime: 'MediaPipe tasks-vision',
    category: 'vision',
    sizeMb: 15,
    ramRequiredMb: 45,
    minDeviceRamGb: 4,
    speedNote: '5 fps on 480p proxy · Smoothed cut-aware crop',
    role: 'Smart 9:16 face-tracking crop planner',
    defaultSelected: true,
    license: 'Apache 2.0',
    sha256: '4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a',
    status: 'installed',
    downloadedMb: 15,
    verifiedSha256: true,
  },
  {
    id: 'gemma-4-e2b-int4',
    name: 'Gemma 4 E2B (INT4 .litertlm)',
    runtime: 'LiteRT-LM 0.18.0',
    category: 'llm',
    sizeMb: 2580,
    ramRequiredMb: 1450,
    minDeviceRamGb: 8,
    speedNote: '~47 tok/s CPU decode · Top-10 re-rank & hooks',
    role: 'Optional LLM re-ranker (host-your-own CDN or user HF token)',
    gated: true,
    license: 'Apache 2.0 (HF gated terms)',
    sha256: '6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b',
    status: 'not-downloaded',
    downloadedMb: 0,
    verifiedSha256: false,
  },
  {
    id: 'gemma-3-1b-int4',
    name: 'Gemma 3 1B (INT4)',
    runtime: 'LiteRT-LM 0.18.0',
    category: 'llm',
    sizeMb: 780,
    ramRequiredMb: 1050,
    minDeviceRamGb: 6,
    speedNote: 'Short titles & hook rewrites only (not full ranking)',
    role: 'Low-RAM LLM fallback (6 GB devices)',
    gated: true,
    license: 'Gemma Terms',
    sha256: 'd4735e3a265e16eee03f59718b9b5d03019c07d8b6c51f90da3a666eec13ab35',
    status: 'not-downloaded',
    downloadedMb: 0,
    verifiedSha256: false,
  },
];

export const INITIAL_QA_CHECKLIST: QaChecklistItem[] = [
  {
    id: 'qa-hdr-4k',
    category: 'Ingest & Codecs',
    label: 'HDR / 4K / 60 fps source handling',
    detail: 'Verify SAF URI read without copying wholesale, proper tone-map/downscale to 1080x1920.',
    checked: false,
  },
  {
    id: 'qa-hinglish',
    category: 'Captions & Script',
    label: 'Hindi + English + Hinglish Devanagari wrapping',
    detail: 'Confirm zero split consonant clusters or orphaned matras; <=3 words and <=42 chars per line.',
    checked: true,
  },
  {
    id: 'qa-rotation-vfr',
    category: 'Ingest & Codecs',
    label: 'Portrait rotation metadata & variable frame rate (VFR)',
    detail: 'Confirm MediaMetadataRetriever rotation is respected and A/V stays synced on screen recordings.',
    checked: false,
  },
  {
    id: 'qa-low-ram',
    category: 'Memory & Thermal',
    label: '4 GB RAM minimum path (Tiny-only, no LLM, 720p)',
    detail: 'Confirm Gemma stays disabled, Whisper releases before export, and no OOM crash occurs.',
    checked: true,
  },
  {
    id: 'qa-cancel-resume',
    category: 'Reliability',
    label: 'Cancel mid-export keeps finished clips & resumes checkpoint',
    detail: 'Verify Foreground mediaProcessing service checkpoints after each stage and preserves finished MP4s.',
    checked: false,
  },
  {
    id: 'qa-offline',
    category: 'Privacy & Offline',
    label: 'Full airplane mode run after model download',
    detail: 'Zero network calls during probe, ASR, scoring, face tracking, render, and QA gate.',
    checked: true,
  },
  {
    id: 'qa-storage-guard',
    category: 'Guardrails',
    label: 'Storage-full guardrail (<3 GB free)',
    detail: 'Job refuses cleanly with human message before writing temp WAV or render files.',
    checked: true,
  },
  {
    id: 'qa-corrupt-sha',
    category: 'Model Manager',
    label: 'Corrupt model file SHA-256 rejection',
    detail: 'Modified .part or model binary fails SHA-256 verify and prompts clean redownload.',
    checked: false,
  },
  {
    id: 'qa-export-spec',
    category: 'Export Quality Gate',
    label: 'Shorts / Reels / TikTok export spec verification',
    detail: '1080x1920 H.264 High Profile fast-start MP4, AAC-LC 48 kHz, -14 LUFS / -1 dBTP, non-blank thumb.',
    checked: false,
  },
];

interface ScriptBlock {
  startSec: number;
  text: string;
  lowConfWords?: Record<string, number>;
  energy: number;
  faceX: number;
}

function buildDemoTimeline(blocks: ScriptBlock[], totalDuration: number) {
  const words: WordToken[] = [];
  let idx = 1;

  for (const block of blocks) {
    const rawTokens = block.text.split(/\s+/).filter(Boolean);
    let t = block.startSec;
    for (const token of rawTokens) {
      const dur = Math.max(0.24, Math.min(0.62, token.length * 0.052 + 0.16));
      const cleanKey = token.replace(/[.,!?;:।]/g, '').toLowerCase();
      const conf =
        block.lowConfWords && cleanKey in block.lowConfWords
          ? block.lowConfWords[cleanKey]
          : Number((0.88 + ((idx * 17) % 11) * 0.01).toFixed(2));
      words.push({
        id: `dw-${idx++}`,
        text: token,
        start: Number(t.toFixed(2)),
        end: Number((t + dur).toFixed(2)),
        confidence: conf,
        confidenceLevel: classifyConfidence(conf),
      });
      t += dur + (/[.!?।]$/.test(token) ? 0.52 : 0.06);
    }
  }

  // Generate RMS envelope at 0.5s intervals
  const rmsEnvelope: RmsPoint[] = [];
  for (let t = 0; t <= totalDuration; t += 0.5) {
    const activeWord = words.find((w) => t >= w.start - 0.15 && t <= w.end + 0.15);
    const activeBlock =
      [...blocks].reverse().find((b) => t >= b.startSec) ?? blocks[0];
    const jitter = Math.sin(t * 2.7) * 0.04;
    const rms = activeWord
      ? Math.max(0.12, Number((activeBlock.energy + jitter).toFixed(3)))
      : 0.03;
    rmsEnvelope.push({
      time: Number(t.toFixed(2)),
      rms,
      isSpeech: Boolean(activeWord),
    });
  }

  // Scene cuts at major block transitions
  const sceneCuts: SceneCut[] = blocks
    .slice(1)
    .map((b) => ({ time: Number(Math.max(0, b.startSec - 0.15).toFixed(2)), delta: 0.68 }));

  // BlazeFace 5fps-equivalent samples (every 0.5s for compact UI)
  const faceSamples: FaceSample[] = [];
  for (let t = 0; t <= totalDuration; t += 0.5) {
    const activeBlock =
      [...blocks].reverse().find((b) => t >= b.startSec) ?? blocks[0];
    const gentleSway = Math.sin(t * 0.85) * 0.025;
    faceSamples.push({
      time: Number(t.toFixed(2)),
      centerX: Number(Math.max(0.24, Math.min(0.76, activeBlock.faceX + gentleSway)).toFixed(3)),
      confidence: 0.92,
    });
  }

  return { words, rmsEnvelope, sceneCuts, faceSamples };
}

export function createEnglishPodcastDemo(): ProjectRecord {
  const duration = 178;
  const blocks: ScriptBlock[] = [
    {
      startSec: 1.0,
      energy: 0.39,
      faceX: 0.43,
      lowConfWords: { quantization: 0.72, litert: 0.51 },
      text: 'Why do 90% of mobile AI video apps fail before clip number one? Stop uploading raw gigabyte footage to slow cloud queues. Instead, run Whisper Tiny INT8 right on your arm64 phone CPU. When you cache word timestamps by file hash, re-clipping the exact same podcast takes seconds instead of ten minutes. Most creators never realize thermal throttling silently doubles render time when you run ASR and LiteRT together. Load one model at a time, release memory cleanly, and your 6 GB phone stays cool.',
    },
    {
      startSec: 46.0,
      energy: 0.24,
      faceX: 0.47,
      text: 'Well um basically when we tested the old Flutter bridge, it required platform channels for every single native library. That meant debugging Dart isolates alongside Kotlin threads just to extract audio frames. It worked on flagship devices, though memory spikes made mid-range testing frustrating for our small team.',
    },
    {
      startSec: 76.0,
      energy: 0.42,
      faceX: 0.41,
      lowConfWords: { blazeface: 0.76 },
      text: 'How to make vertical crops look like a real human editor without cloud vision? Here is the exact 3 step rule we shipped. First, run BlazeFace at 5 frames per second on a lightweight 480p proxy. Second, smooth the horizontal pan with a deadband so subtle head nods never jitter the frame. Third, snap immediately across hard scene cuts instead of drifting slowly across two different camera angles. Simple, deterministic, and completely offline.',
    },
    {
      startSec: 122.0,
      energy: 0.36,
      faceX: 0.45,
      lowConfWords: { lufs: 0.54 },
      text: 'Never trust a mystery viral score that refuses to show its math! Every clip needs an honest breakdown: 35% opening hook, 25% sentence flow, 25% content density, and 15% audio-visual energy. And before you share to Shorts or Reels, run a real quality gate checking resolution, non-blank thumbnails, and minus 14 LUFS loudness.',
    },
  ];

  const { words, rmsEnvelope, sceneCuts, faceSamples } = buildDemoTimeline(blocks, duration);
  const { selected, allAnnotated } = buildClipsFromAnalysis(
    words,
    rmsEnvelope,
    sceneCuts,
    faceSamples,
    duration,
    DEFAULT_SETTINGS
  );

  return {
    id: 'demo-en-podcast',
    createdAt: '2026-11-23T09:15:00Z',
    updatedAt: '2026-11-23T09:15:00Z',
    probe: {
      fileName: 'on_device_ai_podcast_ep14.mp4',
      fileSizeBytes: 318_400_000,
      fileHash: '9a4f81c2e7b03d14f6c8a2019d4e7f88c21b3a05e9d1c44b71a2f0e8c3d9b102',
      duration,
      width: 1920,
      height: 1080,
      fps: 30,
      rotation: 0,
      hasAudio: true,
      isDemo: true,
      demoImageSrc: '/src/assets/images/demo_podcast_creator.jpg',
      demoLanguage: 'en',
    },
    words,
    rmsEnvelope,
    sceneCuts,
    faceSamples,
    clips: selected,
    allCandidates: allAnnotated,
    transcriptCached: true,
    llmReranked: false,
  };
}

export function createHinglishDevanagariDemo(): ProjectRecord {
  const duration = 154;
  const blocks: ScriptBlock[] = [
    {
      startSec: 1.2,
      energy: 0.41,
      faceX: 0.57,
      lowConfWords: { inflation: 0.74, compounding: 0.53 },
      text: 'क्या आप भी ये 3 बड़ी गलतियाँ हर महीने सैलरी आते ही करते हैं? पहली गलती: पैसा बचाना महीने के अंत के लिए छोड़ देना। लेकिन सच यह है कि बचत पहले दिन ऑटोमैटिक होनी चाहिए। दूसरी गलती: बिना इमरजेंसी फंड के रिस्की निवेश शुरू करना। अगर छह महीने का खर्च सुरक्षित है, तो बाजार गिरने पर भी आप घबराते नहीं हैं। तीसरी गलती: inflation को नज़रअंदाज़ करना, क्योंकि बैंक खाते में पड़ा पैसा धीरे-धीरे अपनी ताकत खो देता है।',
    },
    {
      startSec: 52.0,
      energy: 0.25,
      faceX: 0.54,
      text: 'तो बेसिकली जब लोग पहला म्यूचुअल फंड चुनते हैं, तो वे सिर्फ पिछले एक साल का रिटर्न देखते हैं। यह तरीका थोड़ा अधूरा है क्योंकि हर मार्केट साइकिल अलग होती है। हमें शांत रहकर लंबा समय देना पड़ता है।',
    },
    {
      startSec: 84.0,
      energy: 0.43,
      faceX: 0.59,
      lowConfWords: { sip: 0.78 },
      text: 'कैसे करें 50-30-20 रूल का सही इस्तेमाल बिना किसी कठिन स्प्रेडशीट के? सीधा नियम सीखो: 50 प्रतिशत ज़रूरी खर्च, 30 प्रतिशत लाइफस्टाइल, और कम से कम 20 प्रतिशत सीधा SIP निवेश में। जब सैलरी बढ़े तो हर साल निवेश 10 प्रतिशत बढ़ाओ। यही आसान आदत दस साल में बड़ा फायदा और असली आर्थिक आज़ादी देती है।',
    },
  ];

  const { words, rmsEnvelope, sceneCuts, faceSamples } = buildDemoTimeline(blocks, duration);
  const { selected, allAnnotated } = buildClipsFromAnalysis(
    words,
    rmsEnvelope,
    sceneCuts,
    faceSamples,
    duration,
    DEFAULT_SETTINGS
  );

  return {
    id: 'demo-hi-hinglish',
    createdAt: '2026-11-23T09:40:00Z',
    updatedAt: '2026-11-23T09:40:00Z',
    probe: {
      fileName: 'hinglish_money_rules_1080p.mp4',
      fileSizeBytes: 246_900_000,
      fileHash: '4d12c9f083ab11e79c5b20d1f7a38e66b109c4d2e5f8a712c3b4d5e6f7a8b901',
      duration,
      width: 1920,
      height: 1080,
      fps: 30,
      rotation: 0,
      hasAudio: true,
      isDemo: true,
      demoImageSrc: '/src/assets/images/demo_hinglish_studio.jpg',
      demoLanguage: 'hi-en',
    },
    words,
    rmsEnvelope,
    sceneCuts,
    faceSamples,
    clips: selected,
    allCandidates: allAnnotated,
    transcriptCached: true,
    llmReranked: false,
  };
}
