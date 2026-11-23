import test from 'node:test';
import assert from 'node:assert/strict';
import {
  SCORING_WEIGHTS,
  buildCaptionLines,
  buildClipsFromAnalysis,
  buildSentences,
  extractVerbatimHook,
  getCropCenterAtTime,
  planSmoothedCrop,
  scoreCandidate,
  selectWindowsGreedyDedup,
} from './domain';
import { isPrivateOrBlockedIp, parseMediaLink } from './urlIngest';
import type { ClipCandidate, FaceSample, RmsPoint, SceneCut, ScoreBreakdown, WordToken } from '../types';

test('scoring weights sum strictly to 1.0', () => {
  const sum =
    SCORING_WEIGHTS.hook +
    SCORING_WEIGHTS.flow +
    SCORING_WEIGHTS.value +
    SCORING_WEIGHTS.audioVisual;
  assert.ok(Math.abs(sum - 1.0) < 1e-9);
});

test('strong question and number hook outscores flat filler opening', () => {
  const strong: WordToken[] = [
    { id: 'w1', text: 'Why', start: 0, end: 0.35, confidence: 0.96, confidenceLevel: 'high' },
    { id: 'w2', text: 'do', start: 0.35, end: 0.6, confidence: 0.95, confidenceLevel: 'high' },
    { id: 'w3', text: '3', start: 0.6, end: 0.9, confidence: 0.98, confidenceLevel: 'high' },
    { id: 'w4', text: 'mistakes', start: 0.9, end: 1.5, confidence: 0.95, confidenceLevel: 'high' },
    { id: 'w5', text: 'never', start: 1.5, end: 2.0, confidence: 0.94, confidenceLevel: 'high' },
    { id: 'w6', text: 'stop?', start: 2.0, end: 2.5, confidence: 0.96, confidenceLevel: 'high' },
  ];
  const flat: WordToken[] = [
    { id: 'f1', text: 'Well', start: 0, end: 0.5, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f2', text: 'um', start: 0.5, end: 1.0, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f3', text: 'today', start: 1.0, end: 1.5, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f4', text: 'we', start: 1.5, end: 2.0, confidence: 0.9, confidenceLevel: 'high' },
    { id: 'f5', text: 'talk.', start: 2.0, end: 2.5, confidence: 0.9, confidenceLevel: 'high' },
  ];
  const rms: RmsPoint[] = [
    { time: 0.5, rms: 0.36, isSpeech: true },
    { time: 1.5, rms: 0.35, isSpeech: true },
    { time: 2.5, rms: 0.28, isSpeech: true },
  ];
  const faces: FaceSample[] = [{ time: 1.0, centerX: 0.5, confidence: 0.94 }];

  const strongScore = scoreCandidate(
    { start: 0, end: 35, sentences: buildSentences(strong), cleanStartPause: true, cleanEndPause: true },
    strong,
    rms,
    faces
  );
  const flatScore = scoreCandidate(
    { start: 0, end: 35, sentences: buildSentences(flat), cleanStartPause: true, cleanEndPause: true },
    flat,
    rms,
    faces
  );

  assert.ok(strongScore.hook > flatScore.hook + 20);
  assert.ok(strongScore.total > flatScore.total);
});

test('greedy deduplication enforces 30s gap rule and zero overlap', () => {
  const makeScore = (total: number): ScoreBreakdown => ({
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

  const candidates: ClipCandidate[] = [
    { id: 'c1', rank: 0, start: 10, end: 50, duration: 40, scores: makeScore(92), verbatimHook: 'A', titleHook: 'A', hookMode: 'verbatim', cropMode: 'smart', manualCropOffsetX: 0, cropKeyframes: [], selected: false },
    { id: 'c2', rank: 0, start: 65, end: 105, duration: 40, scores: makeScore(89), verbatimHook: 'B', titleHook: 'B', hookMode: 'verbatim', cropMode: 'smart', manualCropOffsetX: 0, cropKeyframes: [], selected: false },
    { id: 'c3', rank: 0, start: 85, end: 125, duration: 40, scores: makeScore(85), verbatimHook: 'C', titleHook: 'C', hookMode: 'verbatim', cropMode: 'smart', manualCropOffsetX: 0, cropKeyframes: [], selected: false },
  ];

  const { selected } = selectWindowsGreedyDedup(candidates, 5, 30);
  assert.deepEqual(selected.map((c) => c.id), ['c1', 'c3']);
});

test('caption engine enforces <=3 words, <=42 chars, Devanagari safety, and uncertain masking', () => {
  const devWords: WordToken[] = [
    'तीसरी', 'सबसे', 'बड़ी', 'गलती', 'निवेशकों', 'को', 'नुकसान', 'देती', 'है।',
  ].map((text, i) => ({
    id: `dv-${i}`,
    text,
    start: i * 0.45,
    end: i * 0.45 + 0.4,
    confidence: i === 2 ? 0.45 : 0.94,
    confidenceLevel: i === 2 ? 'uncertain' : 'high',
  }));

  const lines = buildCaptionLines(devWords, 0, 6);
  assert.ok(lines.length >= 3);
  assert.ok(lines.every((l) => l.wordCount <= 3 && l.charCount <= 42 && l.hasDevanagari));
  assert.ok(lines[0].displayText.includes('___'));

  const longOpening: WordToken[] = Array.from({ length: 14 }, (_, i) => ({
    id: `w-${i}`,
    text: `token${i}`,
    start: i * 0.25,
    end: i * 0.25 + 0.2,
    confidence: 0.95,
    confidenceLevel: 'high',
  }));
  const hook = extractVerbatimHook(longOpening, 0, 10);
  assert.ok(hook.split(/\s+/).length <= 10);
});

test('crop planner snaps across hard scene cuts and clamps manual offset', () => {
  const faces: FaceSample[] = [
    { time: 0, centerX: 0.3, confidence: 0.95 },
    { time: 1, centerX: 0.31, confidence: 0.95 },
    { time: 2, centerX: 0.72, confidence: 0.95 },
  ];
  const cuts: SceneCut[] = [{ time: 1.5, delta: 0.7 }];
  const keyframes = planSmoothedCrop(faces, cuts, 0, 2);
  assert.equal(keyframes[2].isCutBoundary, true);
  assert.equal(keyframes[2].centerX, 0.72);
  assert.equal(getCropCenterAtTime(keyframes, 2, 0.4), 0.85);
});

test('buildClipsFromAnalysis produces valid ranked clips from real timeline inputs', () => {
  const words: WordToken[] = Array.from({ length: 70 }, (_, i) => ({
    id: `t-${i}`,
    text: i === 0 ? 'Why' : i === 1 ? '3' : i === 2 ? 'mistakes?' : `word${i}${i % 10 === 9 ? '.' : ''}`,
    start: i * 0.55,
    end: i * 0.55 + 0.45,
    confidence: 0.92,
    confidenceLevel: 'high',
  }));
  const rms: RmsPoint[] = Array.from({ length: 80 }, (_, i) => ({
    time: i * 0.5,
    rms: 0.3,
    isSpeech: true,
  }));
  const faces: FaceSample[] = Array.from({ length: 40 }, (_, i) => ({
    time: i,
    centerX: 0.5,
    confidence: 0.9,
  }));

  const { selected, allAnnotated } = buildClipsFromAnalysis(words, rms, [], faces, 40, {
    minClipSec: 15,
    maxClipSec: 30,
    windowStepSec: 10,
    dedupGapSec: 20,
    maxClips: 3,
  });
  assert.ok(allAnnotated.length >= 1);
  assert.ok(selected.length >= 1);
  assert.equal(selected[0].rank, 1);
});

test('URL parser classifies YouTube, YouTube Live, HLS streams, direct videos, and blocks unsafe URLs', () => {
  const yt = parseMediaLink('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  assert.equal(yt.ok, true);
  if (yt.ok) {
    assert.equal(yt.data.kind, 'youtube');
    assert.equal(yt.data.youtubeVideoId, 'dQw4w9WgXcQ');
  }

  const ytLive = parseMediaLink('https://youtube.com/live/jfKfPfyJRdk?feature=share');
  assert.equal(ytLive.ok, true);
  if (ytLive.ok) {
    assert.equal(ytLive.data.kind, 'youtube-live');
    assert.equal(ytLive.data.youtubeVideoId, 'jfKfPfyJRdk');
  }

  const hls = parseMediaLink('https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8');
  assert.equal(hls.ok, true);
  if (hls.ok) {
    assert.equal(hls.data.kind, 'hls-stream');
  }

  const mp4 = parseMediaLink('https://cdn.example.com/podcast/ep1.mp4');
  assert.equal(mp4.ok, true);
  if (mp4.ok) {
    assert.equal(mp4.data.kind, 'direct-video');
  }

  assert.equal(parseMediaLink('javascript:alert(1)').ok, false);
  assert.equal(parseMediaLink('http://localhost:8080/video.mp4').ok, false);
  assert.equal(isPrivateOrBlockedIp('127.0.0.1'), true);
  assert.equal(isPrivateOrBlockedIp('169.254.169.254'), true);
  assert.equal(isPrivateOrBlockedIp('10.0.0.5'), true);
  assert.equal(isPrivateOrBlockedIp('192.168.1.10'), true);
  assert.equal(isPrivateOrBlockedIp('8.8.8.8'), false);
});
