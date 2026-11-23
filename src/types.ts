export type ConfidenceLevel = 'high' | 'review' | 'uncertain';

export interface WordToken {
  id: string;
  text: string;
  start: number;
  end: number;
  confidence: number;
  confidenceLevel: ConfidenceLevel;
  locked?: boolean;
}

export interface SentenceSegment {
  id: string;
  start: number;
  end: number;
  words: WordToken[];
  text: string;
  pauseAfter: number;
}

export interface SceneCut {
  time: number;
  delta: number;
}

export interface RmsPoint {
  time: number;
  rms: number;
  isSpeech: boolean;
}

export interface FaceSample {
  time: number;
  centerX: number; // 0..1 normalized horizontal position
  confidence: number; // 0..1
}

export interface CropKeyframe {
  time: number;
  centerX: number;
  faceConfidence: number;
  isCutBoundary: boolean;
}

export interface CaptionLine {
  id: string;
  start: number;
  end: number;
  words: WordToken[];
  displayText: string;
  charCount: number;
  wordCount: number;
  hasDevanagari: boolean;
}

export interface ScoreBreakdown {
  hook: number;        // 0..100 (weight 0.35)
  flow: number;        // 0..100 (weight 0.25)
  value: number;       // 0..100 (weight 0.25)
  audioVisual: number; // 0..100 (weight 0.15)
  total: number;       // weighted 0..100
  hookSignals: string[];
  silenceFraction: number;
  faceVisibleFraction: number;
  infoDensityWps: number;
  energyRatio: number;
  llmDelta?: number;
  llmNote?: string;
}

export type CropMode = 'smart' | 'blur-fill' | 'center';
export type HookMode = 'verbatim' | 'title';

export interface QaReport {
  durationSec: number;
  durationOk: boolean;
  width: number;
  height: number;
  resolutionOk: boolean;
  bitrateKbps: number;
  bitrateOk: boolean;
  thumbnailOk: boolean;
  loudnessLufs: number;
  truePeakDbtp: number;
  loudnessOk: boolean;
  fileSizeBytes: number;
  passed: boolean;
  checkedAt: string;
}

export interface ClipCandidate {
  id: string;
  rank: number;
  start: number;
  end: number;
  duration: number;
  scores: ScoreBreakdown;
  verbatimHook: string;
  titleHook: string;
  hookMode: HookMode;
  cropMode: CropMode;
  manualCropOffsetX: number; // -0.35..0.35 manual override
  cropKeyframes: CropKeyframe[];
  selected: boolean;
  dedupedByClipId?: string;
  dedupGapSec?: number;
  exportedBlobUrl?: string;
  exportedFileName?: string;
  qaResult?: QaReport;
}

export interface VideoProbe {
  fileName: string;
  fileSizeBytes: number;
  fileHash: string;
  duration: number;
  width: number;
  height: number;
  fps: number;
  rotation: number;
  hasAudio: boolean;
  isDemo: boolean;
  demoImageSrc?: string;
  demoLanguage?: 'en' | 'hi-en';
  sourceUrl?: string;
  sourceKind?: 'local' | 'youtube' | 'youtube-live' | 'hls-stream' | 'direct-video';
  thumbnailUrl?: string;
  isLiveCapture?: boolean;
}

export type PipelineStageId =
  | 'probe'
  | 'audio-vad'
  | 'asr'
  | 'sentences'
  | 'scene-cuts'
  | 'windows'
  | 'scoring'
  | 'llm-rerank'
  | 'crop-plan';

export interface PipelineStageState {
  id: PipelineStageId;
  label: string;
  detail: string;
  status: 'pending' | 'running' | 'done' | 'skipped' | 'cancelled' | 'error';
  progressPct: number | null; // null = indeterminate (honest, e.g., loading model)
  durationMs?: number;
  checkpointed?: boolean;
}

export interface ProjectRecord {
  id: string;
  createdAt: string;
  updatedAt: string;
  probe: VideoProbe;
  videoUrl?: string; // transient object URL for uploaded files
  words: WordToken[];
  rmsEnvelope: RmsPoint[];
  sceneCuts: SceneCut[];
  faceSamples: FaceSample[];
  clips: ClipCandidate[];
  allCandidates: ClipCandidate[];
  transcriptCached: boolean;
  llmReranked: boolean;
}

export type ModelCategory = 'asr' | 'llm' | 'vision';

export interface ModelSpec {
  id: string;
  name: string;
  runtime: string;
  category: ModelCategory;
  sizeMb: number;
  ramRequiredMb: number;
  minDeviceRamGb: number;
  speedNote: string;
  role: string;
  defaultSelected?: boolean;
  gated?: boolean;
  license: string;
  sha256: string;
  status: 'not-downloaded' | 'downloading' | 'paused' | 'verifying' | 'installed';
  downloadedMb: number;
  verifiedSha256: boolean;
}

export interface DeviceProfile {
  ramGb: 4 | 6 | 8 | 12;
  freeStorageGb: number;
  wifiConnected: boolean;
  charging: boolean;
  batteryPercent: number;
  hfTokenConfigured: boolean;
  llmEnabled: boolean;
  asrModelId: string;
  llmModelId: string;
}

export type ExportPreset = '1080p' | '720p';

export interface AppSettings {
  exportPreset: ExportPreset;
  targetLufs: number;
  truePeakDbtp: number;
  minClipSec: number;
  maxClipSec: number;
  windowStepSec: number;
  dedupGapSec: number;
  maxClips: number;
  wifiOnlyOver100Mb: boolean;
  requireChargingForHeavy: boolean;
}

export interface QaChecklistItem {
  id: string;
  category: string;
  label: string;
  detail: string;
  checked: boolean;
}

export interface UnitTestResult {
  suite: string;
  name: string;
  passed: boolean;
  detail: string;
}

export type ActiveNav = 'home' | 'studio' | 'settings';
