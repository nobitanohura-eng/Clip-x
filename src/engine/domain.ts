import {
  CaptionLine,
  ClipCandidate,
  ConfidenceLevel,
  CropKeyframe,
  FaceSample,
  RmsPoint,
  SceneCut,
  ScoreBreakdown,
  SentenceSegment,
  UnitTestResult,
  WordToken,
} from '../types';

export const SCORING_WEIGHTS = {
  hook: 0.35,
  flow: 0.25,
  value: 0.25,
  audioVisual: 0.15,
} as const;

const DEVANAGARI_REGEX = /[\u0900-\u097F]/;
const SENTENCE_END_REGEX = /[.!?।]+$/;

const FILLER_WORDS = new Set([
  'um',
  'uh',
  'like',
  'basically',
  'literally',
  'actually',
  'so',
  'well',
  'hmm',
  'ah',
  'er',
  'matlab',
  'toh',
]);

const HOOK_QUESTION_WORDS = new Set([
  'why',
  'how',
  'what',
  'when',
  'where',
  'who',
  'did',
  'do',
  'does',
  'can',
  'is',
  'are',
  'क्या',
  'कैसे',
  'क्यों',
  'कब',
]);

const HOOK_BOLD_WORDS = new Set([
  'never',
  'stop',
  'mistake',
  'mistakes',
  'secret',
  'truth',
  'wrong',
  'broken',
  'fails',
  'fail',
  'myth',
  'rule',
  'best',
  'worst',
  'nobody',
  'everyone',
  'गलती',
  'गलतियाँ',
  'सच',
  'बंद',
  'सीखो',
  'बर्बाद',
]);

const HOOK_CONTRAST_WORDS = new Set([
  'but',
  'instead',
  'however',
  'except',
  'unlike',
  'versus',
  'vs',
  'लेकिन',
  'मगर',
  'बल्कि',
]);

const EMOTION_VALUE_WORDS = new Set([
  'real',
  'honest',
  'fast',
  'local',
  'free',
  'save',
  'money',
  'time',
  'battery',
  'memory',
  'phone',
  'privacy',
  'offline',
  'proven',
  'step',
  'exact',
  'simple',
  'critical',
  'risk',
  'compound',
  'पैसा',
  'बचत',
  'निवेश',
  'आसान',
  'सीधा',
  'फायदा',
  'नुकसान',
]);

export function classifyConfidence(confidence: number): ConfidenceLevel {
  if (confidence >= 0.85) return 'high';
  if (confidence >= 0.6) return 'review';
  return 'uncertain';
}

export function cleanWord(text: string): string {
  return text
    .toLowerCase()
    .replace(/^[.,!?;:"'()[\]।]+|[.,!?;:"'()[\]।]+$/g, '')
    .trim();
}

export function containsDevanagari(text: string): boolean {
  return DEVANAGARI_REGEX.test(text);
}

/**
 * Counts grapheme clusters safely (important for Devanagari matras & conjuncts).
 */
export function countGraphemes(text: string): number {
  if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
    const seg = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
    let count = 0;
    for (const _ of seg.segment(text)) count++;
    return count;
  }
  return Array.from(text).length;
}

/**
 * Groups word tokens into sentence segments by punctuation or pause >= 0.45s
 * so candidate clip boundaries never slice mid-sentence.
 */
export function buildSentences(words: WordToken[], pauseThresholdSec = 0.45): SentenceSegment[] {
  if (words.length === 0) return [];
  const sorted = [...words].sort((a, b) => a.start - b.start);
  const segments: SentenceSegment[] = [];
  let current: WordToken[] = [];

  for (let i = 0; i < sorted.length; i++) {
    const w = sorted[i];
    const next = sorted[i + 1];
    current.push(w);

    const pauseAfter = next ? Math.max(0, next.start - w.end) : 1.0;
    const hasPunct = SENTENCE_END_REGEX.test(w.text);
    const isLongBreak = pauseAfter >= pauseThresholdSec;
    const isOverLong = current.length >= 22 && pauseAfter >= 0.2;

    if (hasPunct || isLongBreak || isOverLong || !next) {
      segments.push({
        id: `seg-${segments.length + 1}`,
        start: current[0].start,
        end: current[current.length - 1].end,
        words: current,
        text: current.map((t) => t.text).join(' '),
        pauseAfter,
      });
      current = [];
    }
  }
  return segments;
}

export interface RawWindow {
  start: number;
  end: number;
  sentences: SentenceSegment[];
  cleanStartPause: boolean;
  cleanEndPause: boolean;
}

/**
 * Generates candidate clip windows snapped to sentence boundaries,
 * respecting duration bounds (30-60s default, adaptive if source video is shorter)
 * and avoiding boundaries that slice right across a scene cut.
 */
export function generateCandidateWindows(
  sentences: SentenceSegment[],
  sceneCuts: SceneCut[],
  sourceDuration: number,
  minDurSec = 30,
  maxDurSec = 60,
  stepSec = 12
): RawWindow[] {
  if (sentences.length === 0) return [];

  // Adapt gracefully when user uploads a short clip (< 40s)
  const effMin = sourceDuration < minDurSec + 4 ? Math.max(8, Math.floor(sourceDuration * 0.45)) : minDurSec;
  const effMax = Math.min(maxDurSec, Math.max(effMin + 5, sourceDuration));
  const effStep = sourceDuration < 45 ? Math.max(4, Math.floor(stepSec / 2)) : stepSec;

  const windows: RawWindow[] = [];
  const seenKeys = new Set<string>();

  let nextAllowedStart = 0;
  for (let i = 0; i < sentences.length; i++) {
    const startSeg = sentences[i];
    if (startSeg.start < nextAllowedStart) continue;

    // Try target lengths within [effMin, effMax]
    const targetDurs = [
      Math.round((effMin + effMax) * 0.5),
      effMin + 4,
      Math.min(effMax, effMin + 22),
    ];

    for (const targetDur of targetDurs) {
      let bestEndIdx = -1;
      let bestDist = Infinity;

      for (let j = i; j < sentences.length; j++) {
        const dur = sentences[j].end - startSeg.start;
        if (dur >= effMin && dur <= effMax) {
          // Prefer boundaries not colliding within 0.35s of a hard scene cut
          const endTime = sentences[j].end;
          const nearCut = sceneCuts.some((c) => Math.abs(c.time - endTime) < 0.35);
          const dist = Math.abs(dur - targetDur) + (nearCut ? 6 : 0);
          if (dist < bestDist) {
            bestDist = dist;
            bestEndIdx = j;
          }
        }
        if (dur > effMax) break;
      }

      if (bestEndIdx >= i) {
        const slice = sentences.slice(i, bestEndIdx + 1);
        const start = slice[0].start;
        const end = slice[slice.length - 1].end;
        const key = `${Math.round(start * 10)}-${Math.round(end * 10)}`;
        if (!seenKeys.has(key)) {
          seenKeys.add(key);
          const prevSeg = i > 0 ? sentences[i - 1] : null;
          const startGap = prevSeg ? start - prevSeg.end : 1.0;
          const endGap = slice[slice.length - 1].pauseAfter;
          windows.push({
            start,
            end,
            sentences: slice,
            cleanStartPause: startGap >= 0.3 || i === 0,
            cleanEndPause: endGap >= 0.35 || bestEndIdx === sentences.length - 1,
          });
        }
      }
    }

    nextAllowedStart = startSeg.start + effStep;
  }

  // Fallback: if video is very short and produced zero windows, include full transcript range
  if (windows.length === 0 && sentences.length > 0) {
    windows.push({
      start: sentences[0].start,
      end: sentences[sentences.length - 1].end,
      sentences,
      cleanStartPause: true,
      cleanEndPause: true,
    });
  }

  return windows;
}

/**
 * Deterministic, explainable rule-based scoring engine:
 * Hook 35% / Flow 25% / Value 25% / Audio-Visual 15% (weights sum strictly to 1.0).
 */
export function scoreCandidate(
  win: RawWindow,
  allWords: WordToken[],
  rmsEnvelope: RmsPoint[],
  faceSamples: FaceSample[]
): ScoreBreakdown {
  const duration = Math.max(1, win.end - win.start);
  const clipWords = allWords.filter((w) => w.start >= win.start - 0.05 && w.end <= win.end + 0.05);
  const hookWords = clipWords.filter((w) => w.start <= win.start + 3.2);
  const hookText = hookWords.map((w) => w.text).join(' ');

  // 1. HOOK SCORE (35%)
  let hookScore = 44;
  const hookSignals: string[] = [];

  const hasQuestion =
    hookText.includes('?') || hookWords.some((w) => HOOK_QUESTION_WORDS.has(cleanWord(w.text)));
  if (hasQuestion) {
    hookScore += 18;
    hookSignals.push('Question in first 3s');
  }

  const hasNumber = /\d+/.test(hookText) || /(three|five|ten|zero|तीन|पाँच|दो)/i.test(hookText);
  if (hasNumber) {
    hookScore += 14;
    hookSignals.push('Specific number in opening');
  }

  const hasBold = hookWords.some((w) => HOOK_BOLD_WORDS.has(cleanWord(w.text)));
  if (hasBold) {
    hookScore += 15;
    hookSignals.push('Bold claim / pattern interrupt');
  }

  const hasHowTo = /\bhow to\b|कैसे करें|सीखो/i.test(hookText);
  if (hasHowTo) {
    hookScore += 12;
    hookSignals.push('Actionable "how-to" hook');
  }

  const hasContrast = hookWords.some((w) => HOOK_CONTRAST_WORDS.has(cleanWord(w.text)));
  if (hasContrast) {
    hookScore += 10;
    hookSignals.push('Contrast pivot');
  }

  // Opening energy boost
  const globalAvgRms =
    rmsEnvelope.length > 0
      ? rmsEnvelope.reduce((acc, p) => acc + p.rms, 0) / rmsEnvelope.length
      : 0.25;
  const hookRmsPoints = rmsEnvelope.filter((p) => p.time >= win.start && p.time <= win.start + 3.2);
  const hookAvgRms =
    hookRmsPoints.length > 0
      ? hookRmsPoints.reduce((acc, p) => acc + p.rms, 0) / hookRmsPoints.length
      : globalAvgRms;

  if (globalAvgRms > 0 && hookAvgRms > globalAvgRms * 1.08) {
    hookScore += 8;
    hookSignals.push('High vocal energy on entry');
  }

  if (hookSignals.length === 0) {
    hookSignals.push('Standard spoken opening');
  }
  hookScore = clamp(Math.round(hookScore), 20, 99);

  // 2. FLOW SCORE (25%)
  const clipRms = rmsEnvelope.filter((p) => p.time >= win.start && p.time <= win.end);
  const silenceCount = clipRms.filter((p) => !p.isSpeech).length;
  const silenceFraction = clipRms.length > 0 ? silenceCount / clipRms.length : 0.08;

  let flowScore = 62;
  if (win.cleanStartPause) flowScore += 14;
  if (win.cleanEndPause) flowScore += 14;
  // Sweet spot duration around 35-52s
  if (duration >= 32 && duration <= 54) flowScore += 8;
  // Penalize high silence fraction
  if (silenceFraction > 0.22) {
    flowScore -= Math.round((silenceFraction - 0.22) * 110);
  } else if (silenceFraction < 0.14) {
    flowScore += 5;
  }
  flowScore = clamp(Math.round(flowScore), 25, 98);

  // 3. VALUE SCORE (25%)
  const contentWords = clipWords.filter((w) => {
    const cleaned = cleanWord(w.text);
    return cleaned.length > 2 && !FILLER_WORDS.has(cleaned);
  });
  const fillerCount = clipWords.filter((w) => FILLER_WORDS.has(cleanWord(w.text))).length;
  const infoDensityWps = Number((contentWords.length / duration).toFixed(2));
  const emotionHits = clipWords.filter((w) => EMOTION_VALUE_WORDS.has(cleanWord(w.text))).length;
  const uniqueContentRatio =
    contentWords.length > 0
      ? new Set(contentWords.map((w) => cleanWord(w.text))).size / contentWords.length
      : 0.5;

  let valueScore = 46;
  // Ideal content density ~1.4 to 2.4 content words/sec
  if (infoDensityWps >= 1.2 && infoDensityWps <= 2.6) {
    valueScore += 22;
  } else if (infoDensityWps >= 0.85) {
    valueScore += 12;
  }
  valueScore += Math.min(18, emotionHits * 4);
  valueScore += Math.round(uniqueContentRatio * 14);
  valueScore -= Math.min(16, fillerCount * 2);
  valueScore = clamp(Math.round(valueScore), 25, 98);

  // 4. AUDIO-VISUAL SCORE (15%)
  const clipAvgRms =
    clipRms.length > 0 ? clipRms.reduce((acc, p) => acc + p.rms, 0) / clipRms.length : globalAvgRms;
  const energyRatio = globalAvgRms > 0 ? Number((clipAvgRms / globalAvgRms).toFixed(2)) : 1.0;

  // RMS variance (expressive cadence vs flat monotone)
  const rmsVar =
    clipRms.length > 1
      ? clipRms.reduce((acc, p) => acc + Math.pow(p.rms - clipAvgRms, 2), 0) / clipRms.length
      : 0.01;

  const clipFaces = faceSamples.filter((f) => f.time >= win.start && f.time <= win.end);
  const faceVisibleFraction =
    clipFaces.length > 0
      ? clipFaces.filter((f) => f.confidence >= 0.55).length / clipFaces.length
      : 0.85;

  let avScore = 48;
  avScore += Math.round(faceVisibleFraction * 26);
  if (energyRatio >= 1.0) avScore += Math.min(14, Math.round((energyRatio - 0.9) * 30));
  if (rmsVar > 0.004) avScore += 8;
  if (silenceFraction > 0.25) avScore -= 14;
  avScore = clamp(Math.round(avScore), 25, 99);

  const total = Math.round(
    hookScore * SCORING_WEIGHTS.hook +
      flowScore * SCORING_WEIGHTS.flow +
      valueScore * SCORING_WEIGHTS.value +
      avScore * SCORING_WEIGHTS.audioVisual
  );

  return {
    hook: hookScore,
    flow: flowScore,
    value: valueScore,
    audioVisual: avScore,
    total,
    hookSignals,
    silenceFraction: Number(silenceFraction.toFixed(2)),
    faceVisibleFraction: Number(faceVisibleFraction.toFixed(2)),
    infoDensityWps,
    energyRatio,
  };
}

/**
 * Extracts a verbatim opening speaker quote capped at <= 10 words. Never fabricated.
 */
export function extractVerbatimHook(words: WordToken[], clipStart: number, maxWords = 10): string {
  const opening = words
    .filter((w) => w.start >= clipStart - 0.05 && w.start <= clipStart + 4.5)
    .slice(0, maxWords);

  if (opening.length === 0) return 'Opening moment';

  // Stop early if a clean sentence boundary occurs after >= 4 words
  const picked: WordToken[] = [];
  for (const w of opening) {
    picked.push(w);
    if (picked.length >= 4 && SENTENCE_END_REGEX.test(w.text)) break;
  }

  return picked
    .map((w) => (w.confidenceLevel === 'uncertain' && !w.locked ? '___' : w.text))
    .join(' ')
    .trim();
}

/**
 * Offline deterministic title template (used when LLM is disabled or phone RAM < 6 GB).
 * Always clearly distinguished from verbatim speaker quotes in UI and overlay.
 */
export function generateOfflineTitle(
  words: WordToken[],
  clipStart: number,
  clipEnd: number,
  signals: string[]
): string {
  const clipWords = words.filter((w) => w.start >= clipStart && w.end <= clipEnd);
  const keywords = clipWords
    .map((w) => cleanWord(w.text))
    .filter((w) => w.length > 3 && !FILLER_WORDS.has(w))
    .slice(0, 4);

  if (keywords.length === 0) return 'Key Moment Breakdown';
  const topPhrase = keywords
    .slice(0, 3)
    .map((k) => (containsDevanagari(k) ? k : k.charAt(0).toUpperCase() + k.slice(1)))
    .join(' · ');

  if (signals.some((s) => s.includes('Question'))) {
    return `Why ${topPhrase}`;
  }
  if (signals.some((s) => s.includes('number'))) {
    return `Key Rule: ${topPhrase}`;
  }
  return `Insight: ${topPhrase}`;
}

/**
 * Cut-aware BlazeFace crop planner: smooths X-center within shots and snaps immediately
 * on hard scene cuts so the 9:16 crop never drifts sluggishly across a cut.
 */
export function planSmoothedCrop(
  faceSamples: FaceSample[],
  sceneCuts: SceneCut[],
  clipStart: number,
  clipEnd: number,
  alpha = 0.22
): CropKeyframe[] {
  const relevant = faceSamples.filter((s) => s.time >= clipStart - 0.25 && s.time <= clipEnd + 0.25);
  if (relevant.length === 0) {
    return [
      { time: clipStart, centerX: 0.5, faceConfidence: 0.5, isCutBoundary: true },
      { time: clipEnd, centerX: 0.5, faceConfidence: 0.5, isCutBoundary: false },
    ];
  }

  const keyframes: CropKeyframe[] = [];
  let smoothedX = relevant[0].centerX;

  for (let i = 0; i < relevant.length; i++) {
    const sample = relevant[i];
    const prev = i > 0 ? relevant[i - 1] : null;
    const crossedCut =
      prev !== null && sceneCuts.some((c) => c.time > prev.time && c.time <= sample.time);

    if (i === 0 || crossedCut) {
      smoothedX = sample.centerX;
    } else if (sample.confidence >= 0.45) {
      // Deadband of 0.025 prevents micro-jitter when speaker nods
      const delta = sample.centerX - smoothedX;
      if (Math.abs(delta) > 0.025) {
        smoothedX = smoothedX + delta * alpha;
      }
    }

    keyframes.push({
      time: sample.time,
      centerX: clamp(Number(smoothedX.toFixed(4)), 0.18, 0.82),
      faceConfidence: sample.confidence,
      isCutBoundary: crossedCut,
    });
  }

  return keyframes;
}

/**
 * Greedy deduplication WindowSelector:
 * Sort scored candidates best-first. Keep the best clip, discard any candidate
 * overlapping it OR within `minGapSec` (default 30s) of it, repeat until `maxClips`.
 */
export function selectWindowsGreedyDedup(
  candidates: ClipCandidate[],
  maxClips = 5,
  minGapSec = 30
): { selected: ClipCandidate[]; allAnnotated: ClipCandidate[] } {
  const sorted = [...candidates].sort((a, b) => b.scores.total - a.scores.total);
  const kept: ClipCandidate[] = [];
  const allAnnotated: ClipCandidate[] = [];

  for (const cand of sorted) {
    let blockingClip: ClipCandidate | null = null;
    let blockingGap = 0;

    for (const k of kept) {
      const overlaps = cand.start <= k.end && cand.end >= k.start;
      const gap = overlaps ? 0 : Math.min(Math.abs(cand.start - k.end), Math.abs(k.start - cand.end));
      if (overlaps || gap < minGapSec) {
        blockingClip = k;
        blockingGap = Math.round(gap);
        break;
      }
    }

    if (!blockingClip && kept.length < maxClips) {
      const accepted: ClipCandidate = {
        ...cand,
        rank: kept.length + 1,
        selected: true,
        dedupedByClipId: undefined,
        dedupGapSec: undefined,
      };
      kept.push(accepted);
      allAnnotated.push(accepted);
    } else {
      allAnnotated.push({
        ...cand,
        rank: 0,
        selected: false,
        dedupedByClipId: blockingClip ? blockingClip.id : 'limit-reached',
        dedupGapSec: blockingClip ? blockingGap : undefined,
      });
    }
  }

  return { selected: kept, allAnnotated };
}

/**
 * Builds karaoke caption lines strictly honoring ClipAI v2 rules:
 * - 2-3 words per line (<= 3 words)
 * - <= 42 characters per line (grapheme-aware)
 * - Devanagari-safe wrapping: never splits inside a word, consonant cluster, or matra
 * - Unedited uncertain words render as `___` instead of hallucinated text
 */
export function buildCaptionLines(
  words: WordToken[],
  clipStart: number,
  clipEnd: number,
  maxWordsPerLine = 3,
  maxCharsPerLine = 42
): CaptionLine[] {
  const clipWords = words
    .filter((w) => w.end > clipStart && w.start < clipEnd)
    .sort((a, b) => a.start - b.start);

  const lines: CaptionLine[] = [];
  let bucket: WordToken[] = [];

  const wordDisplay = (w: WordToken) =>
    w.confidenceLevel === 'uncertain' && !w.locked ? '___' : w.text;

  const flush = () => {
    if (bucket.length === 0) return;
    const displayText = bucket.map(wordDisplay).join(' ');
    lines.push({
      id: `cap-${lines.length + 1}`,
      start: Math.max(clipStart, bucket[0].start),
      end: Math.min(clipEnd, bucket[bucket.length - 1].end),
      words: bucket,
      displayText,
      charCount: countGraphemes(displayText),
      wordCount: bucket.length,
      hasDevanagari: containsDevanagari(displayText),
    });
    bucket = [];
  };

  for (let i = 0; i < clipWords.length; i++) {
    const w = clipWords[i];
    const candidateBucket = [...bucket, w];
    const candidateText = candidateBucket.map(wordDisplay).join(' ');
    const candidateLen = countGraphemes(candidateText);

    const prev = bucket[bucket.length - 1];
    const bigPause = prev ? w.start - prev.end > 0.65 : false;

    if (
      bucket.length > 0 &&
      (candidateBucket.length > maxWordsPerLine || candidateLen > maxCharsPerLine || bigPause)
    ) {
      flush();
    }

    bucket.push(w);

    // Flush naturally on sentence-ending punctuation if we already have >= 2 words
    if (bucket.length >= 2 && SENTENCE_END_REGEX.test(w.text)) {
      flush();
    }
  }
  flush();

  return lines;
}

/**
 * Full deterministic pipeline runner over extracted words, audio envelope, cuts, and face tracks.
 */
export function buildClipsFromAnalysis(
  words: WordToken[],
  rmsEnvelope: RmsPoint[],
  sceneCuts: SceneCut[],
  faceSamples: FaceSample[],
  sourceDuration: number,
  options: {
    minClipSec: number;
    maxClipSec: number;
    windowStepSec: number;
    dedupGapSec: number;
    maxClips: number;
  }
): { selected: ClipCandidate[]; allAnnotated: ClipCandidate[] } {
  const sentences = buildSentences(words);
  const rawWindows = generateCandidateWindows(
    sentences,
    sceneCuts,
    sourceDuration,
    options.minClipSec,
    options.maxClipSec,
    options.windowStepSec
  );

  const candidates: ClipCandidate[] = rawWindows.map((win, idx) => {
    const scores = scoreCandidate(win, words, rmsEnvelope, faceSamples);
    const verbatimHook = extractVerbatimHook(words, win.start, 10);
    const titleHook = generateOfflineTitle(words, win.start, win.end, scores.hookSignals);
    const cropKeyframes = planSmoothedCrop(faceSamples, sceneCuts, win.start, win.end);

    return {
      id: `clip-${idx + 1}`,
      rank: 0,
      start: Number(win.start.toFixed(2)),
      end: Number(win.end.toFixed(2)),
      duration: Number((win.end - win.start).toFixed(2)),
      scores,
      verbatimHook,
      titleHook,
      hookMode: 'verbatim',
      cropMode: 'smart',
      manualCropOffsetX: 0,
      cropKeyframes,
      selected: false,
    };
  });

  return selectWindowsGreedyDedup(candidates, options.maxClips, options.dedupGapSec);
}

/**
 * Interpolates smoothed BlazeFace X center at time `t`, honoring scene-cut snaps.
 */
export function getCropCenterAtTime(
  keyframes: CropKeyframe[],
  time: number,
  manualOffsetX = 0
): number {
  if (keyframes.length === 0) return clamp(0.5 + manualOffsetX, 0.15, 0.85);
  if (time <= keyframes[0].time) return clamp(keyframes[0].centerX + manualOffsetX, 0.15, 0.85);
  if (time >= keyframes[keyframes.length - 1].time) {
    return clamp(keyframes[keyframes.length - 1].centerX + manualOffsetX, 0.15, 0.85);
  }

  for (let i = 0; i < keyframes.length - 1; i++) {
    const a = keyframes[i];
    const b = keyframes[i + 1];
    if (time >= a.time && time <= b.time) {
      if (b.isCutBoundary) {
        return clamp(a.centerX + manualOffsetX, 0.15, 0.85);
      }
      const ratio = (time - a.time) / Math.max(0.001, b.time - a.time);
      const interp = a.centerX + (b.centerX - a.centerX) * ratio;
      return clamp(interp + manualOffsetX, 0.15, 0.85);
    }
  }
  return clamp(0.5 + manualOffsetX, 0.15, 0.85);
}

/**
 * Live unit test runner mirroring ClipAI v2's Kotlin JUnit suites:
 * ScoringEngineTest, WindowSelectorTest, and CaptionEngineTest.
 */
export function runDomainUnitTests(): UnitTestResult[] {
  const results: UnitTestResult[] = [];

  // 1. ScoringEngineTest: weights sum strictly to 1.0
  const weightSum = Number(
    (
      SCORING_WEIGHTS.hook +
      SCORING_WEIGHTS.flow +
      SCORING_WEIGHTS.value +
      SCORING_WEIGHTS.audioVisual
    ).toFixed(4)
  );
  results.push({
    suite: 'ScoringEngineTest',
    name: 'scoringWeightsSumToOne',
    passed: Math.abs(weightSum - 1.0) < 1e-6,
    detail: `0.35 + 0.25 + 0.25 + 0.15 = ${weightSum.toFixed(2)}`,
  });

  // 2. ScoringEngineTest: strong hook outscores flat opening
  const strongWords: WordToken[] = [
    { id: 'w1', text: 'Why', start: 0, end: 0.4, confidence: 0.96, confidenceLevel: 'high' },
    { id: 'w2', text: 'do', start: 0.4, end: 0.7, confidence: 0.95, confidenceLevel: 'high' },
    { id: 'w3', text: '3', start: 0.7, end: 1.0, confidence: 0.98, confidenceLevel: 'high' },
    { id: 'w4', text: 'mistakes', start: 1.0, end: 1.6, confidence: 0.94, confidenceLevel: 'high' },
    { id: 'w5', text: 'never', start: 1.6, end: 2.1, confidence: 0.94, confidenceLevel: 'high' },
    { id: 'w6', text: 'stop?', start: 2.1, end: 2.7, confidence: 0.96, confidenceLevel: 'high' },
  ];
  const flatWords: WordToken[] = [
    { id: 'f1', text: 'Well', start: 0, end: 0.5, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f2', text: 'um', start: 0.5, end: 1.0, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f3', text: 'today', start: 1.0, end: 1.6, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f4', text: 'we', start: 1.6, end: 2.1, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f5', text: 'talk.', start: 2.1, end: 2.7, confidence: 0.9, confidenceLevel: 'high' },
  ];
  const rmsMock: RmsPoint[] = [
    { time: 0.5, rms: 0.36, isSpeech: true },
    { time: 1.5, rms: 0.34, isSpeech: true },
    { time: 2.5, rms: 0.25, isSpeech: true },
  ];
  const strongScore = scoreCandidate(
    { start: 0, end: 35, sentences: buildSentences(strongWords), cleanStartPause: true, cleanEndPause: true },
    strongWords,
    rmsMock,
    [{ time: 1, centerX: 0.5, confidence: 0.95 }]
  );
  const flatScore = scoreCandidate(
    { start: 0, end: 35, sentences: buildSentences(flatWords), cleanStartPause: true, cleanEndPause: true },
    flatWords,
    rmsMock,
    [{ time: 1, centerX: 0.5, confidence: 0.95 }]
  );
  results.push({
    suite: 'ScoringEngineTest',
    name: 'hookQuestionAndNumberOutscoreFillerOpening',
    passed: strongScore.hook > flatScore.hook + 20,
    detail: `Strong hook ${strongScore.hook} vs flat opening ${flatScore.hook}`,
  });

  // 3. WindowSelectorTest: greedy dedup enforces 30s gap rule and zero overlap
  const dummyScores = (total: number): ScoreBreakdown => ({
    hook: total,
    flow: total,
    value: total,
    audioVisual: total,
    total,
    hookSignals: [],
    silenceFraction: 0.05,
    faceVisibleFraction: 0.9,
    infoDensityWps: 1.8,
    energyRatio: 1.1,
  });
  const mockCands: ClipCandidate[] = [
    { id: 'c1', rank: 0, start: 10, end: 50, duration: 40, scores: dummyScores(92), verbatimHook: 'A', titleHook: 'A', hookMode: 'verbatim', cropMode: 'smart', manualCropOffsetX: 0, cropKeyframes: [], selected: false },
    { id: 'c2', rank: 0, start: 65, end: 105, duration: 40, scores: dummyScores(89), verbatimHook: 'B', titleHook: 'B', hookMode: 'verbatim', cropMode: 'smart', manualCropOffsetX: 0, cropKeyframes: [], selected: false }, // gap = 15s < 30s -> must reject
    { id: 'c3', rank: 0, start: 85, end: 125, duration: 40, scores: dummyScores(85), verbatimHook: 'C', titleHook: 'C', hookMode: 'verbatim', cropMode: 'smart', manualCropOffsetX: 0, cropKeyframes: [], selected: false }, // gap = 35s >= 30s -> must keep
  ];
  const deduped = selectWindowsGreedyDedup(mockCands, 5, 30);
  const keptIds = deduped.selected.map((c) => c.id).join(',');
  results.push({
    suite: 'WindowSelectorTest',
    name: 'greedyDedupEnforces30SecondRuleAndNoOverlap',
    passed: keptIds === 'c1,c3',
    detail: `Kept [${keptIds}]; discarded c2 (15s gap < 30s threshold)`,
  });

  // 4. CaptionEngineTest: <= 3 words and <= 42 chars
  const captionInput: WordToken[] = [
    'Never', 'upload', 'your', 'raw', 'footage', 'to', 'slow', 'cloud', 'servers',
  ].map((text, i) => ({
    id: `cw-${i}`,
    text,
    start: i * 0.4,
    end: i * 0.4 + 0.35,
    confidence: 0.94,
    confidenceLevel: 'high',
  }));
  const lines = buildCaptionLines(captionInput, 0, 5);
  const maxWordsOk = lines.every((l) => l.wordCount <= 3);
  const maxCharsOk = lines.every((l) => l.charCount <= 42);
  results.push({
    suite: 'CaptionEngineTest',
    name: 'linesStayWithin3WordsAnd42Chars',
    passed: maxWordsOk && maxCharsOk && lines.length === 3,
    detail: `${lines.length} lines generated; max ${Math.max(...lines.map((l) => l.wordCount))} words, max ${Math.max(...lines.map((l) => l.charCount))} chars`,
  });

  // 5. CaptionEngineTest: Devanagari-safe wrapping never splits a word or matra
  const devWords: WordToken[] = [
    'तीसरी', 'सबसे', 'बड़ी', 'गलती', 'निवेशकों', 'को', 'नुकसान', 'देती', 'है।',
  ].map((text, i) => ({
    id: `dv-${i}`,
    text,
    start: i * 0.45,
    end: i * 0.45 + 0.4,
    confidence: 0.93,
    confidenceLevel: 'high',
  }));
  const devLines = buildCaptionLines(devWords, 0, 6);
  const rejoined = devLines.map((l) => l.displayText).join(' ');
  const expected = devWords.map((w) => w.text).join(' ');
  results.push({
    suite: 'CaptionEngineTest',
    name: 'devanagariWrappingPreservesWholeWordsAndMatras',
    passed: rejoined === expected && devLines.every((l) => l.hasDevanagari && l.wordCount <= 3),
    detail: `Preserved ${devWords.length} intact Devanagari words across ${devLines.length} lines`,
  });

  // 6. CaptionEngineTest: Hook quote strictly <= 10 words
  const longOpening: WordToken[] = Array.from({ length: 14 }, (_, i) => ({
    id: `ho-${i}`,
    text: `word${i + 1}`,
    start: i * 0.25,
    end: i * 0.25 + 0.2,
    confidence: 0.95,
    confidenceLevel: 'high',
  }));
  const hook = extractVerbatimHook(longOpening, 0, 10);
  const hookWordCount = hook.split(/\s+/).length;
  results.push({
    suite: 'CaptionEngineTest',
    name: 'verbatimHookOverlayCappedAt10Words',
    passed: hookWordCount <= 10,
    detail: `Extracted ${hookWordCount} words (limit <= 10)`,
  });

  return results;
}

export function formatTimecode(seconds: number): string {
  const s = Math.max(0, seconds);
  const mins = Math.floor(s / 60);
  const secs = Math.floor(s % 60);
  const tenths = Math.floor((s - Math.floor(s)) * 10);
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}.${tenths}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function clamp(val: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, val));
}
