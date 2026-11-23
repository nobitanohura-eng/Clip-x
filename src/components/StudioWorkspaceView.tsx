import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Download,
  FileText,
  Grid,
  Lock,
  Pause,
  Play,
  RefreshCw,
  RotateCcw,
  Scissors,
  Sliders,
  X,
} from 'lucide-react';
import {
  AppSettings,
  ClipCandidate,
  CropMode,
  ExportPreset,
  HookMode,
  ProjectRecord,
  WordToken,
} from '../types';
import {
  buildCaptionLines,
  formatBytes,
  formatTimecode,
  getCropCenterAtTime,
} from '../engine/domain';
import { exportClipSinglePass, renderVerticalFrame } from '../engine/videoProcessor';

interface StudioWorkspaceViewProps {
  project: ProjectRecord | null;
  activeClipId: string | null;
  settings: AppSettings;
  onSelectClip: (clipId: string) => void;
  onToggleKeepClip: (clipId: string) => void;
  onUpdateClip: (clipId: string, patch: Partial<ClipCandidate>) => void;
  onUpdateWord: (wordId: string, newText: string) => void;
  onImportCustomTranscript: (rawText: string) => void;
  onUpdatePreset: (preset: ExportPreset) => void;
  onReclipActive: () => void;
  onGoHome: () => void;
}

type InspectorTab = 'captions' | 'frame' | 'export';

export const StudioWorkspaceView: React.FC<StudioWorkspaceViewProps> = ({
  project,
  activeClipId,
  settings,
  onSelectClip,
  onToggleKeepClip,
  onUpdateClip,
  onUpdateWord,
  onImportCustomTranscript,
  onUpdatePreset,
  onReclipActive,
  onGoHome,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const exportAbortRef = useRef<AbortController | null>(null);

  const [inspectorTab, setInspectorTab] = useState<InspectorTab>('captions');
  const [showAllCandidates, setShowAllCandidates] = useState(false);
  const [showScoreBreakdown, setShowScoreBreakdown] = useState(false);

  const clip = useMemo(() => {
    if (!project) return null;
    return (
      project.clips.find((c) => c.id === activeClipId) ??
      project.allCandidates.find((c) => c.id === activeClipId) ??
      project.clips[0] ??
      null
    );
  }, [project, activeClipId]);

  const [currentTime, setCurrentTime] = useState<number>(clip?.start ?? 0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [showSafeGuides, setShowSafeGuides] = useState(false);
  const [captionFilter, setCaptionFilter] = useState<'all' | 'review'>('all');
  const [editingWordId, setEditingWordId] = useState<string | null>(null);
  const [editingText, setEditingText] = useState('');
  const [showTranscriptImport, setShowTranscriptImport] = useState(false);
  const [customTranscriptDraft, setCustomTranscriptDraft] = useState('');

  const [isExporting, setIsExporting] = useState(false);
  const [exportPct, setExportPct] = useState(0);
  const [exportStageLabel, setExportStageLabel] = useState('');
  const [exportError, setExportError] = useState<string | null>(null);
  const [mediaReadyTick, setMediaReadyTick] = useState(0);

  useEffect(() => {
    if (!clip) return;
    setIsPlaying(false);
    setCurrentTime(clip.start);
    if (videoRef.current) {
      videoRef.current.currentTime = clip.start;
    }
  }, [clip?.id, clip?.start]);

  useEffect(() => {
    if (!project?.videoUrl) {
      videoRef.current = null;
      return;
    }
    const vid = document.createElement('video');
    vid.src = project.videoUrl;
    vid.crossOrigin = 'anonymous';
    vid.playsInline = true;
    vid.preload = 'auto';
    vid.onloadeddata = () => {
      videoRef.current = vid;
      if (clip) vid.currentTime = clip.start;
      setMediaReadyTick((t) => t + 1);
    };
    return () => {
      vid.pause();
      vid.src = '';
    };
  }, [project?.id, project?.videoUrl]);

  const captionLines = useMemo(() => {
    if (!project || !clip) return [];
    return buildCaptionLines(project.words, clip.start, clip.end);
  }, [project?.words, clip?.start, clip?.end]);

  const clipWords = useMemo(() => {
    if (!project || !clip) return [];
    return project.words.filter((w) => w.end > clip.start && w.start < clip.end);
  }, [project?.words, clip?.start, clip?.end]);

  const reviewCount = useMemo(
    () => clipWords.filter((w) => w.confidenceLevel !== 'high' && !w.locked).length,
    [clipWords]
  );

  const visibleLines = useMemo(() => {
    if (captionFilter === 'all') return captionLines;
    return captionLines.filter((line) =>
      line.words.some((w) => w.confidenceLevel !== 'high' && !w.locked)
    );
  }, [captionLines, captionFilter]);

  useEffect(() => {
    if (!isPlaying || !clip || !project) return;
    let rafId = 0;
    let lastTs = performance.now();

    if (videoRef.current) {
      videoRef.current.currentTime = currentTime;
      videoRef.current.play().catch(() => {});
    }

    const tick = (now: number) => {
      const dt = (now - lastTs) / 1000;
      lastTs = now;

      if (videoRef.current) {
        const vt = videoRef.current.currentTime;
        if (vt >= clip.end) {
          videoRef.current.pause();
          videoRef.current.currentTime = clip.start;
          setCurrentTime(clip.start);
          setIsPlaying(false);
          return;
        }
        setCurrentTime(vt);
      } else {
        setCurrentTime((prev) => {
          const next = prev + dt;
          if (next >= clip.end) {
            setIsPlaying(false);
            return clip.start;
          }
          return next;
        });
      }
      rafId = requestAnimationFrame(tick);
    };

    rafId = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(rafId);
      videoRef.current?.pause();
    };
  }, [isPlaying, clip?.id, clip?.start, clip?.end]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !clip || !project) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    renderVerticalFrame({
      ctx,
      width: canvas.width,
      height: canvas.height,
      sourceMedia: videoRef.current,
      clip,
      captionLines,
      currentTime,
      showSafeGuides,
      isDemo: false,
    });
  }, [clip, captionLines, currentTime, showSafeGuides, project, mediaReadyTick]);

  if (!project || !clip) {
    return (
      <div className="mx-auto flex min-h-[65vh] max-w-md flex-col items-center justify-center px-6 text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-[#222631] bg-[#111318] text-slate-400">
          <Scissors className="h-5 w-5" />
        </div>
        <h1 className="mt-5 font-display text-xl font-semibold text-slate-100">
          No video open in Studio
        </h1>
        <p className="mt-1.5 text-sm text-slate-400">
          Paste a YouTube or live stream link, or pick a local video on Home to generate clips.
        </p>
        <button
          type="button"
          onClick={onGoHome}
          className="mt-6 inline-flex h-10 items-center rounded-xl bg-amber-500 px-5 text-sm font-semibold text-neutral-950 transition-colors hover:bg-amber-400"
        >
          Go to Home
        </button>
      </div>
    );
  }

  const clipList = showAllCandidates ? project.allCandidates : project.clips;
  const relTime = Math.max(0, currentTime - clip.start);
  const currentCropX = getCropCenterAtTime(clip.cropKeyframes, currentTime, clip.manualCropOffsetX);
  const clipSceneCuts = project.sceneCuts.filter((c) => c.time > clip.start && c.time < clip.end);

  const handleSeek = (newAbsTime: number) => {
    const bounded = Math.max(clip.start, Math.min(clip.end, newAbsTime));
    setCurrentTime(bounded);
    if (videoRef.current) {
      videoRef.current.currentTime = bounded;
    }
  };

  const handleStartEditWord = (w: WordToken) => {
    setIsPlaying(false);
    handleSeek(w.start + 0.05);
    setEditingWordId(w.id);
    setEditingText(w.text);
  };

  const handleCommitWord = () => {
    if (editingWordId && editingText.trim()) {
      onUpdateWord(editingWordId, editingText.trim());
    }
    setEditingWordId(null);
    setEditingText('');
  };

  const handleExport = async () => {
    setExportError(null);
    setIsPlaying(false);
    setIsExporting(true);
    setExportPct(0);
    setExportStageLabel('Preparing 9:16 encoder');

    const controller = new AbortController();
    exportAbortRef.current = controller;

    try {
      const result = await exportClipSinglePass({
        clip,
        words: project.words,
        preset: settings.exportPreset,
        sourceMedia: videoRef.current,
        isDemo: false,
        targetLufs: settings.targetLufs,
        truePeakDbtp: settings.truePeakDbtp,
        onProgress: (pct, label) => {
          setExportPct(pct);
          setExportStageLabel(label);
        },
        signal: controller.signal,
      });

      onUpdateClip(clip.id, {
        exportedBlobUrl: result.blobUrl,
        exportedFileName: result.fileName,
        qaResult: result.qa,
      });
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        setExportError((err as Error).message || 'Export failed.');
      }
    } finally {
      setIsExporting(false);
      exportAbortRef.current = null;
    }
  };

  return (
    <div className="mx-auto max-w-[1440px] px-4 py-5 sm:px-6 lg:px-8">
      {/* Slim Contextual Project Bar */}
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3 border-b border-[#1D212B] pb-4">
        <div className="min-w-0">
          <h1 className="truncate font-display text-base font-semibold text-slate-100">
            {project.probe.fileName}
          </h1>
          <div className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-slate-500">
            <span className="font-mono tabular-nums">
              {project.probe.width}×{project.probe.height}
            </span>
            <span aria-hidden="true">·</span>
            <span className="font-mono tabular-nums">{formatTimecode(project.probe.duration)}</span>
            <span aria-hidden="true">·</span>
            <span>
              {project.clips.length} {project.clips.length === 1 ? 'clip' : 'clips'} ready
            </span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onReclipActive}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-[#242936] bg-[#111318] px-3 text-xs font-medium text-slate-300 transition-colors hover:border-slate-500 hover:text-white whitespace-nowrap"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Re-clip
          </button>
          <button
            type="button"
            onClick={() => setInspectorTab('export')}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-amber-500 px-4 text-xs font-semibold text-neutral-950 transition-colors hover:bg-amber-400 whitespace-nowrap"
          >
            Export 9:16
          </button>
        </div>
      </div>

      {/* 3-Pane Pro Clipping Layout */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 lg:items-start">
        {/* LEFT PANE: Ranked Clips Rail */}
        <aside className="rounded-2xl border border-[#222631] bg-[#111318] p-3.5 lg:col-span-3">
          <div className="mb-3 flex items-center justify-between px-1">
            <h2 className="text-xs font-semibold tracking-wide text-slate-300">
              Clips ({clipList.length})
            </h2>
            <button
              type="button"
              onClick={() => setShowAllCandidates((v) => !v)}
              className="text-[11px] text-slate-400 hover:text-amber-400 whitespace-nowrap"
            >
              {showAllCandidates ? 'Top only' : `All (${project.allCandidates.length})`}
            </button>
          </div>

          <div className="max-h-[580px] space-y-2 overflow-y-auto pr-0.5">
            {clipList.map((item) => {
              const active = item.id === clip.id;
              return (
                <div
                  key={item.id}
                  className={`rounded-xl border transition-colors ${
                    active
                      ? 'border-amber-500/60 bg-[#181C25]'
                      : 'border-transparent bg-[#0D0F14] hover:border-[#252A37]'
                  }`}
                >
                  <button
                    type="button"
                    onClick={() => onSelectClip(item.id)}
                    className="w-full p-3 text-left"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span
                        className={`font-mono text-xs font-semibold tabular-nums ${
                          item.selected ? 'text-amber-400' : 'text-slate-500'
                        }`}
                      >
                        {item.selected ? `Clip #${item.rank}` : 'Skipped'}
                      </span>
                      <span className="font-mono text-xs font-semibold tabular-nums text-slate-200">
                        {item.scores.total}
                      </span>
                    </div>

                    <p className="mt-1.5 line-clamp-2 text-xs leading-relaxed text-slate-200">
                      {/^(?:\[\d{2}:\d{2}\]\s*)+$/.test(item.verbatimHook.trim())
                        ? `Audio-framed highlight · ${formatTimecode(item.start)} – ${formatTimecode(item.end)}`
                        : item.verbatimHook}
                    </p>

                    <div className="mt-2 flex items-center justify-between font-mono text-[11px] tabular-nums text-slate-500">
                      <span>
                        {formatTimecode(item.start)} – {formatTimecode(item.end)}
                      </span>
                      <span>{item.duration.toFixed(0)}s</span>
                    </div>
                  </button>

                  {active && (
                    <div className="border-t border-[#222631] px-3 py-2">
                      <div className="flex items-center justify-between">
                        <button
                          type="button"
                          onClick={() => setShowScoreBreakdown((v) => !v)}
                          className="inline-flex items-center gap-1 text-[11px] text-slate-400 hover:text-slate-200"
                        >
                          Score math
                          {showScoreBreakdown ? (
                            <ChevronUp className="h-3 w-3" />
                          ) : (
                            <ChevronDown className="h-3 w-3" />
                          )}
                        </button>
                        {!item.selected && (
                          <button
                            type="button"
                            onClick={() => onToggleKeepClip(item.id)}
                            className="text-[11px] font-medium text-amber-400 hover:text-amber-300"
                          >
                            Keep clip
                          </button>
                        )}
                      </div>

                      {showScoreBreakdown && (
                        <div className="mt-2.5 grid grid-cols-2 gap-x-3 gap-y-1.5 font-mono text-[11px] tabular-nums text-slate-400">
                          <div>Hook: {item.scores.hook}</div>
                          <div>Flow: {item.scores.flow}</div>
                          <div>Value: {item.scores.value}</div>
                          <div>A/V: {item.scores.audioVisual}</div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </aside>

        {/* CENTER PANE: Clean 9:16 Vertical Stage */}
        <section className="flex flex-col items-center rounded-2xl border border-[#222631] bg-[#0D0F14] p-5 lg:col-span-5">
          <div className="mb-3 flex w-full max-w-[330px] items-center justify-between">
            <span className="font-mono text-xs tabular-nums text-slate-300">
              {formatTimecode(relTime)} / {formatTimecode(clip.duration)}
            </span>
            <button
              type="button"
              onClick={() => setShowSafeGuides((v) => !v)}
              className={`inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-medium transition-colors whitespace-nowrap ${
                showSafeGuides
                  ? 'bg-[#222631] text-amber-300'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Grid className="h-3.5 w-3.5" />
              Safe Zones
            </button>
          </div>

          <div className="aspect-[9/16] w-full max-w-[320px] overflow-hidden rounded-xl border border-[#232733] bg-black">
            <canvas
              ref={canvasRef}
              width={540}
              height={960}
              data-testid="vertical-preview-canvas"
              className="h-full w-full object-contain"
            />
          </div>

          {/* Scrubber + Minimal Transport */}
          <div className="mt-4 w-full max-w-[330px] space-y-3">
            <div className="relative flex items-center">
              <input
                type="range"
                aria-label="Clip playback timeline"
                min={clip.start}
                max={clip.end}
                step={0.05}
                value={currentTime}
                onChange={(e) => handleSeek(parseFloat(e.target.value))}
                className="h-1.5 w-full cursor-pointer appearance-none rounded-lg bg-[#222631] accent-amber-500"
              />
              {clipSceneCuts.map((cut) => {
                const pct = ((cut.time - clip.start) / Math.max(0.1, clip.duration)) * 100;
                return (
                  <span
                    key={cut.time}
                    className="pointer-events-none absolute top-0 h-3 w-0.5 -translate-y-0.5 bg-amber-400"
                    style={{ left: `${pct}%` }}
                  />
                );
              })}
            </div>

            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setIsPlaying((p) => !p)}
                  className="inline-flex h-9 items-center gap-1.5 rounded-xl bg-amber-500 px-4 text-xs font-semibold text-neutral-950 transition-colors hover:bg-amber-400 whitespace-nowrap"
                >
                  {isPlaying ? <Pause className="h-3.5 w-3.5" /> : <Play className="h-3.5 w-3.5" />}
                  {isPlaying ? 'Pause' : 'Play'}
                </button>
                <button
                  type="button"
                  aria-label="Restart clip preview"
                  onClick={() => handleSeek(clip.start)}
                  className="inline-flex h-9 w-9 items-center justify-center rounded-xl border border-[#252A37] text-slate-300 hover:border-slate-500 hover:text-white"
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                </button>
              </div>

              <span className="font-mono text-[11px] tabular-nums text-slate-500">
                Pan X {Math.round(currentCropX * 100)}%
              </span>
            </div>
          </div>
        </section>

        {/* RIGHT PANE: Clean 3-Tab Inspector */}
        <section className="rounded-2xl border border-[#222631] bg-[#111318] p-4 lg:col-span-4">
          <div
            role="tablist"
            aria-label="Editor tools"
            className="grid grid-cols-3 gap-1 rounded-xl border border-[#1E222D] bg-[#0A0B0F] p-1"
          >
            {(
              [
                { id: 'captions', label: 'Captions' },
                { id: 'frame', label: 'Frame & Hook' },
                { id: 'export', label: 'Export' },
              ] as const
            ).map((t) => (
              <button
                key={t.id}
                type="button"
                role="tab"
                aria-selected={inspectorTab === t.id}
                onClick={() => setInspectorTab(t.id)}
                className={`rounded-lg py-2 text-xs font-medium transition-colors whitespace-nowrap ${
                  inspectorTab === t.id
                    ? 'bg-[#222631] text-white'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>

          {/* TAB 1: CAPTIONS */}
          {inspectorTab === 'captions' && (
            <div className="mt-4 space-y-3">
              <div className="flex items-center justify-between gap-2">
                <div className="inline-flex rounded-lg border border-[#222631] bg-[#0A0B0F] p-0.5">
                  <button
                    type="button"
                    onClick={() => setCaptionFilter('all')}
                    className={`rounded-md px-2.5 py-1 text-[11px] font-medium whitespace-nowrap ${
                      captionFilter === 'all' ? 'bg-[#222631] text-white' : 'text-slate-400'
                    }`}
                  >
                    All ({captionLines.length})
                  </button>
                  <button
                    type="button"
                    onClick={() => setCaptionFilter('review')}
                    className={`rounded-md px-2.5 py-1 text-[11px] font-medium whitespace-nowrap ${
                      captionFilter === 'review' ? 'bg-[#222631] text-amber-300' : 'text-slate-400'
                    }`}
                  >
                    Review ({reviewCount})
                  </button>
                </div>

                <button
                  type="button"
                  onClick={() => {
                    setCustomTranscriptDraft(project.words.map((w) => w.text).join(' '));
                    setShowTranscriptImport((v) => !v);
                  }}
                  className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-[#252A37] px-2.5 text-xs font-medium text-slate-300 hover:border-slate-500 hover:text-white whitespace-nowrap"
                >
                  <FileText className="h-3.5 w-3.5" />
                  Paste Script
                </button>
              </div>

              {showTranscriptImport && (
                <div className="space-y-2 rounded-xl border border-[#252A37] bg-[#0A0B0F] p-3">
                  <label htmlFor="studio-paste-script" className="block text-xs text-slate-300">
                    Paste English, Hindi, or Hinglish script
                  </label>
                  <textarea
                    id="studio-paste-script"
                    rows={4}
                    value={customTranscriptDraft}
                    onChange={(e) => setCustomTranscriptDraft(e.target.value)}
                    className="w-full rounded-lg border border-[#222631] bg-[#111318] p-2.5 text-xs text-slate-100 focus:border-amber-500 focus:outline-none"
                  />
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => setShowTranscriptImport(false)}
                      className="px-3 py-1.5 text-xs text-slate-400 hover:text-slate-200"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        if (customTranscriptDraft.trim()) {
                          onImportCustomTranscript(customTranscriptDraft.trim());
                          setShowTranscriptImport(false);
                        }
                      }}
                      className="rounded-lg bg-amber-500 px-3 py-1.5 text-xs font-semibold text-neutral-950 hover:bg-amber-400"
                    >
                      Align Script
                    </button>
                  </div>
                </div>
              )}

              <div className="max-h-[460px] divide-y divide-[#1D212B] overflow-y-auto pr-1">
                {visibleLines.length === 0 ? (
                  <p className="py-10 text-center text-xs text-slate-500">
                    {captionFilter === 'review'
                      ? 'All words in this clip are verified.'
                      : 'No captions yet. Click Paste Script to align your text.'}
                  </p>
                ) : (
                  visibleLines.map((line) => {
                    const isCurrentLine =
                      currentTime >= line.start - 0.05 && currentTime <= line.end + 0.1;
                    return (
                      <div
                        key={line.id}
                        className={`py-2.5 ${isCurrentLine ? 'bg-amber-500/5' : ''}`}
                      >
                        <div className="mb-1.5 flex items-center justify-between font-mono text-[10px] tabular-nums text-slate-500">
                          <button
                            type="button"
                            onClick={() => handleSeek(line.start)}
                            className="hover:text-amber-400"
                          >
                            {formatTimecode(line.start - clip.start)}
                          </button>
                          <span>{line.charCount}/42</span>
                        </div>

                        <div className="flex flex-wrap gap-1.5">
                          {line.words.map((w) => {
                            const isWordActive =
                              currentTime >= w.start - 0.04 && currentTime <= w.end + 0.06;
                            const isEditing = editingWordId === w.id;

                            if (isEditing) {
                              return (
                                <form
                                  key={w.id}
                                  onSubmit={(e) => {
                                    e.preventDefault();
                                    handleCommitWord();
                                  }}
                                  className="inline-flex items-center gap-1"
                                >
                                  <input
                                    type="text"
                                    autoFocus
                                    value={editingText}
                                    onChange={(e) => setEditingText(e.target.value)}
                                    onBlur={handleCommitWord}
                                    className="w-24 rounded border border-amber-500 bg-[#0A0B0F] px-2 py-0.5 text-xs text-white focus:outline-none"
                                  />
                                  <button
                                    type="submit"
                                    aria-label="Save word"
                                    className="rounded bg-amber-500 p-1 text-neutral-950"
                                  >
                                    <Check className="h-3 w-3" />
                                  </button>
                                </form>
                              );
                            }

                            const label =
                              w.confidenceLevel === 'uncertain' && !w.locked ? '___' : w.text;

                            return (
                              <button
                                key={w.id}
                                type="button"
                                onClick={() => handleStartEditWord(w)}
                                className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs transition-colors ${
                                  isWordActive
                                    ? 'bg-amber-400 font-semibold text-neutral-950'
                                    : w.locked
                                    ? 'border border-slate-600 bg-[#1D212C] text-slate-100'
                                    : w.confidenceLevel === 'uncertain'
                                    ? 'border border-rose-500/40 bg-rose-950/50 text-rose-200'
                                    : w.confidenceLevel === 'review'
                                    ? 'border border-amber-500/40 bg-amber-950/40 text-amber-200'
                                    : 'bg-[#181B24] text-slate-200 hover:bg-[#232836]'
                                }`}
                              >
                                <span>{label}</span>
                                {w.locked && <Lock className="h-2.5 w-2.5 opacity-70" />}
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}

          {/* TAB 2: FRAME & HOOK */}
          {inspectorTab === 'frame' && (
            <div className="mt-5 space-y-6">
              <div className="space-y-3">
                <span className="block text-xs font-medium text-slate-300">9:16 Framing</span>
                <div className="grid grid-cols-3 gap-1 rounded-xl border border-[#222631] bg-[#0A0B0F] p-1">
                  {(['smart', 'blur-fill', 'center'] as CropMode[]).map((mode) => (
                    <button
                      key={mode}
                      type="button"
                      onClick={() => onUpdateClip(clip.id, { cropMode: mode })}
                      className={`rounded-lg py-2 text-xs font-medium transition-colors whitespace-nowrap ${
                        clip.cropMode === mode
                          ? 'bg-[#222631] text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      {mode === 'smart' ? 'Smart Track' : mode === 'blur-fill' ? 'Blur Fill' : 'Center'}
                    </button>
                  ))}
                </div>

                {clip.cropMode !== 'blur-fill' && (
                  <div className="pt-1">
                    <div className="flex items-center justify-between text-xs">
                      <label htmlFor="studio-crop-offset" className="text-slate-400">
                        Horizontal Position
                      </label>
                      <div className="flex items-center gap-2">
                        <span className="font-mono tabular-nums text-slate-200">
                          {clip.manualCropOffsetX >= 0 ? '+' : ''}
                          {Math.round(clip.manualCropOffsetX * 100)}%
                        </span>
                        {clip.manualCropOffsetX !== 0 && (
                          <button
                            type="button"
                            onClick={() => onUpdateClip(clip.id, { manualCropOffsetX: 0 })}
                            className="text-amber-400 hover:text-amber-300"
                          >
                            Reset
                          </button>
                        )}
                      </div>
                    </div>
                    <input
                      id="studio-crop-offset"
                      type="range"
                      min={-0.3}
                      max={0.3}
                      step={0.01}
                      value={clip.manualCropOffsetX}
                      onChange={(e) =>
                        onUpdateClip(clip.id, { manualCropOffsetX: parseFloat(e.target.value) })
                      }
                      className="mt-2 h-1.5 w-full cursor-pointer appearance-none rounded-lg bg-[#222631] accent-amber-500"
                    />
                  </div>
                )}
              </div>

              <div className="space-y-3 border-t border-[#1E222D] pt-5">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-medium text-slate-300">Opening Hook (0–3s)</span>
                  <div className="inline-flex rounded-lg border border-[#222631] bg-[#0A0B0F] p-0.5">
                    {(['verbatim', 'title'] as HookMode[]).map((hm) => (
                      <button
                        key={hm}
                        type="button"
                        onClick={() => onUpdateClip(clip.id, { hookMode: hm })}
                        className={`rounded-md px-2.5 py-1 text-[11px] font-medium whitespace-nowrap ${
                          clip.hookMode === hm ? 'bg-[#222631] text-amber-300' : 'text-slate-400'
                        }`}
                      >
                        {hm === 'verbatim' ? 'Quote' : 'Title'}
                      </button>
                    ))}
                  </div>
                </div>

                <input
                  type="text"
                  aria-label="Opening hook text"
                  value={clip.hookMode === 'verbatim' ? clip.verbatimHook : clip.titleHook}
                  onChange={(e) =>
                    onUpdateClip(
                      clip.id,
                      clip.hookMode === 'verbatim'
                        ? { verbatimHook: e.target.value }
                        : { titleHook: e.target.value }
                    )
                  }
                  className="w-full rounded-xl border border-[#252A37] bg-[#0A0B0F] px-3.5 py-2.5 text-xs text-slate-100 focus:border-amber-500 focus:outline-none"
                />
                <p className="text-[11px] text-slate-500">
                  Shown cleanly in the top third for the first 3 seconds of the clip.
                </p>
              </div>
            </div>
          )}

          {/* TAB 3: EXPORT */}
          {inspectorTab === 'export' && (
            <div className="mt-5 space-y-5">
              <div className="space-y-2.5">
                <span className="block text-xs font-medium text-slate-300">Output Resolution</span>
                <div className="grid grid-cols-2 gap-2">
                  {(['1080p', '720p'] as ExportPreset[]).map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => onUpdatePreset(preset)}
                      className={`rounded-xl border px-3 py-2.5 text-left transition-colors ${
                        settings.exportPreset === preset
                          ? 'border-amber-500/70 bg-amber-500/10 text-slate-100'
                          : 'border-[#232733] bg-[#0A0B0F] text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      <div className="font-mono text-xs font-semibold tabular-nums">
                        {preset === '1080p' ? '1080×1920' : '720×1280'}
                      </div>
                      <div className="mt-0.5 text-[11px] text-slate-500">
                        {preset === '1080p' ? 'Full HD · 9:16' : 'Fast HD · 9:16'}
                      </div>
                    </button>
                  ))}
                </div>
              </div>

              <div className="space-y-2.5">
                {isExporting ? (
                  <button
                    type="button"
                    onClick={() => exportAbortRef.current?.abort()}
                    className="flex h-11 w-full items-center justify-center gap-2 rounded-xl border border-rose-500/40 bg-rose-950/40 text-xs font-semibold text-rose-200 hover:bg-rose-900/50"
                  >
                    <X className="h-4 w-4" />
                    Cancel Export ({exportPct}%)
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={handleExport}
                    className="flex h-11 w-full items-center justify-center gap-2 rounded-xl bg-amber-500 text-xs font-semibold text-neutral-950 transition-colors hover:bg-amber-400"
                  >
                    <Sliders className="h-4 w-4" />
                    Export 9:16 Clip
                  </button>
                )}

                {clip.exportedBlobUrl && !isExporting && (
                  <a
                    href={clip.exportedBlobUrl}
                    download={clip.exportedFileName ?? 'clipai_9x16.webm'}
                    className="flex h-10 w-full items-center justify-center gap-2 rounded-xl border border-emerald-500/40 bg-emerald-950/30 text-xs font-medium text-emerald-300 hover:bg-emerald-900/40"
                  >
                    <Download className="h-4 w-4" />
                    Download Clip
                  </a>
                )}
              </div>

              {isExporting && (
                <div className="space-y-1.5">
                  <div className="flex justify-between text-xs">
                    <span className="text-amber-300">{exportStageLabel}</span>
                    <span className="font-mono tabular-nums text-slate-300">{exportPct}%</span>
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-full bg-[#222631]">
                    <div
                      className="h-full rounded-full bg-amber-500 transition-all"
                      style={{ width: `${exportPct}%` }}
                    />
                  </div>
                </div>
              )}

              {exportError && (
                <p className="rounded-xl border border-rose-500/30 bg-rose-950/30 px-3.5 py-2.5 text-xs text-rose-200">
                  {exportError}
                </p>
              )}

              {clip.qaResult && (
                <div className="space-y-2.5 rounded-xl border border-[#222631] bg-[#0A0B0F] p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span
                      className={`inline-flex items-center gap-1.5 font-medium ${
                        clip.qaResult.passed ? 'text-emerald-400' : 'text-rose-400'
                      }`}
                    >
                      {clip.qaResult.passed ? (
                        <CheckCircle2 className="h-4 w-4" />
                      ) : (
                        <AlertTriangle className="h-4 w-4" />
                      )}
                      {clip.qaResult.passed ? 'Quality Check Passed' : 'Quality Issue Found'}
                    </span>
                    <span className="font-mono text-[11px] tabular-nums text-slate-400">
                      {formatBytes(clip.qaResult.fileSizeBytes)}
                    </span>
                  </div>

                  <div className="grid grid-cols-2 gap-2 font-mono text-[11px] tabular-nums text-slate-400">
                    <div>
                      {clip.qaResult.width}×{clip.qaResult.height}
                    </div>
                    <div>{clip.qaResult.durationSec}s</div>
                    <div>{clip.qaResult.bitrateKbps} kbps</div>
                    <div>{clip.qaResult.loudnessLufs} LUFS</div>
                  </div>
                </div>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  );
};
