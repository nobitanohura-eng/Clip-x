import React, { useRef, useState } from 'react';
import {
  ArrowRight,
  ChevronDown,
  ChevronUp,
  Film,
  Link2,
  Radio,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { PipelineStageState, ProjectRecord } from '../types';
import { formatBytes, formatTimecode } from '../engine/domain';

interface LibraryViewProps {
  projects: ProjectRecord[];
  activeProjectId: string | null;
  pipelineStages: PipelineStageState[];
  isPipelineRunning: boolean;
  pipelineError: string | null;
  onPickLocalFile: (file: File) => void;
  onIngestUrl: (url: string, captureSeconds: number) => void;
  onCancelPipeline: () => void;
  onOpenProjectInStudio: (projectId: string) => void;
  onDeleteProject: (projectId: string) => void;
}

const CAPTURE_OPTIONS = [
  { label: '1 min', seconds: 60 },
  { label: '2 min', seconds: 120 },
  { label: '3 min', seconds: 180 },
];

export const LibraryView: React.FC<LibraryViewProps> = ({
  projects,
  activeProjectId,
  pipelineStages,
  isPipelineRunning,
  pipelineError,
  onPickLocalFile,
  onIngestUrl,
  onCancelPipeline,
  onOpenProjectInStudio,
  onDeleteProject,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [linkInput, setLinkInput] = useState('');
  const [captureSeconds, setCaptureSeconds] = useState(120);
  const [isDragging, setIsDragging] = useState(false);
  const [showStageDetails, setShowStageDetails] = useState(false);

  const handleLinkSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!linkInput.trim() || isPipelineRunning) return;
    onIngestUrl(linkInput.trim(), captureSeconds);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file && file.type.startsWith('video/')) {
      onPickLocalFile(file);
    }
  };

  const activeStage =
    pipelineStages.find((s) => s.status === 'running') ??
    pipelineStages.find((s) => s.status === 'error') ??
    pipelineStages[pipelineStages.length - 1];

  const completedStages = pipelineStages.filter(
    (s) => s.status === 'done' || s.status === 'skipped'
  ).length;
  const overallPct = Math.round((completedStages / Math.max(1, pipelineStages.length)) * 100);

  return (
    <div className="mx-auto max-w-[960px] px-4 py-12 sm:px-6 lg:px-8 lg:py-16">
      {/* Focused Hero Workspace Entry */}
      <section className="mx-auto max-w-[760px] text-center">
        <h1 className="font-display text-3xl font-semibold tracking-tight text-slate-50 sm:text-5xl">
          Turn any video or live stream into vertical clips
        </h1>
        <p className="mx-auto mt-3 max-w-xl text-sm leading-relaxed text-slate-400 sm:text-base">
          Paste a YouTube video, live stream, or direct video link — or drop a local file. ClipAI
          finds the strongest moments, frames 9:16, adds karaoke captions, and exports cleanly.
        </p>

        {/* Primary Link Paste Bar */}
        <form
          onSubmit={handleLinkSubmit}
          className="mt-8 rounded-2xl border border-[#232733] bg-[#111318] p-2.5 text-left transition-colors focus-within:border-amber-500/60"
        >
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <div className="relative flex flex-1 items-center">
              <Link2 className="pointer-events-none absolute left-3.5 h-4 w-4 text-slate-500" />
              <input
                type="url"
                aria-label="Video or live stream URL"
                placeholder="Paste YouTube, live stream, or video link..."
                value={linkInput}
                onChange={(e) => setLinkInput(e.target.value)}
                disabled={isPipelineRunning}
                className="h-11 w-full rounded-xl bg-transparent pl-10 pr-3 text-sm text-slate-100 placeholder:text-slate-500 focus:outline-none disabled:opacity-50"
              />
            </div>

            <div className="flex items-center justify-between gap-2 sm:justify-end">
              <div
                role="group"
                aria-label="Stream capture length"
                className="inline-flex items-center rounded-lg border border-[#222631] bg-[#0A0B0F] p-0.5"
              >
                {CAPTURE_OPTIONS.map((opt) => (
                  <button
                    key={opt.seconds}
                    type="button"
                    disabled={isPipelineRunning}
                    onClick={() => setCaptureSeconds(opt.seconds)}
                    className={`rounded-md px-2.5 py-1.5 text-xs font-medium transition-colors whitespace-nowrap shrink-0 ${
                      captureSeconds === opt.seconds
                        ? 'bg-[#222631] text-slate-100'
                        : 'text-slate-500 hover:text-slate-300'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>

              <button
                type="submit"
                disabled={isPipelineRunning || !linkInput.trim()}
                className="inline-flex h-11 items-center gap-2 rounded-xl bg-amber-500 px-5 text-sm font-semibold text-neutral-950 transition-colors hover:bg-amber-400 disabled:opacity-40 whitespace-nowrap shrink-0"
              >
                Clip Link
                <ArrowRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        </form>

        {/* Quiet Inline Separator */}
        <div className="my-5 flex items-center gap-4 text-xs text-slate-500">
          <div className="h-px flex-1 bg-[#1E222D]" />
          <span>or upload from device</span>
          <div className="h-px flex-1 bg-[#1E222D]" />
        </div>

        {/* Clean Minimal Local File Dropzone */}
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragging(true);
          }}
          onDragLeave={() => setIsDragging(false)}
          onDrop={handleDrop}
          className={`flex flex-col items-center justify-between gap-4 rounded-2xl border border-dashed px-6 py-5 text-left transition-colors sm:flex-row ${
            isDragging
              ? 'border-amber-500 bg-amber-500/5'
              : 'border-[#252A37] bg-[#0E1015] hover:border-[#353C4E]'
          }`}
        >
          <div className="flex items-center gap-3.5">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#181B22] text-slate-300">
              <Upload className="h-4 w-4" />
            </div>
            <div>
              <p className="text-sm font-medium text-slate-200">Drop a local video file here</p>
              <p className="text-xs text-slate-500">
                MP4, MOV, or WebM · Processed locally without uploading
              </p>
            </div>
          </div>

          <input
            ref={fileInputRef}
            type="file"
            accept="video/mp4,video/webm,video/quicktime,video/*"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) onPickLocalFile(file);
              e.target.value = '';
            }}
          />
          <button
            type="button"
            disabled={isPipelineRunning}
            onClick={() => fileInputRef.current?.click()}
            className="inline-flex h-10 items-center gap-2 rounded-xl border border-[#2A303E] bg-[#161922] px-4 text-xs font-medium text-slate-100 transition-colors hover:border-slate-500 hover:bg-[#1E222E] disabled:opacity-40 whitespace-nowrap shrink-0"
          >
            Choose File
          </button>
        </div>

        {/* Clean Error Banner */}
        {pipelineError && (
          <div
            role="alert"
            className="mt-5 rounded-xl border border-rose-500/30 bg-rose-950/30 px-4 py-3 text-left text-xs leading-relaxed text-rose-200"
          >
            {pipelineError}
          </div>
        )}

        {/* Compact Live Progress Bar (Only visible while clipping is active) */}
        {isPipelineRunning && (
          <div className="mt-6 rounded-2xl border border-[#232733] bg-[#111318] p-5 text-left">
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0">
                <p className="text-sm font-medium text-slate-100">
                  {activeStage?.label ?? 'Analyzing video...'}
                </p>
                <p className="mt-0.5 truncate text-xs text-slate-400">
                  {activeStage?.detail ?? 'Preparing timeline and smart framing'}
                </p>
              </div>
              <div className="flex items-center gap-3 shrink-0">
                <span className="font-mono text-xs tabular-nums text-amber-400">
                  {activeStage?.progressPct !== null && activeStage?.progressPct !== undefined
                    ? `${Math.max(overallPct, activeStage.progressPct)}%`
                    : `${overallPct}%`}
                </span>
                <button
                  type="button"
                  onClick={onCancelPipeline}
                  className="inline-flex h-8 items-center gap-1 rounded-lg border border-rose-500/30 px-2.5 text-xs font-medium text-rose-300 hover:bg-rose-950/40 whitespace-nowrap"
                >
                  <X className="h-3.5 w-3.5" />
                  Cancel
                </button>
              </div>
            </div>

            <div className="mt-3.5 h-1.5 w-full overflow-hidden rounded-full bg-[#1E222D]">
              <div
                className="h-full rounded-full bg-amber-500 transition-all duration-300"
                style={{ width: `${Math.max(8, overallPct)}%` }}
              />
            </div>

            <div className="mt-3 flex justify-end">
              <button
                type="button"
                onClick={() => setShowStageDetails((v) => !v)}
                className="inline-flex items-center gap-1 text-[11px] text-slate-500 hover:text-slate-300"
              >
                {showStageDetails ? 'Hide steps' : 'Show steps'}
                {showStageDetails ? (
                  <ChevronUp className="h-3 w-3" />
                ) : (
                  <ChevronDown className="h-3 w-3" />
                )}
              </button>
            </div>

            {showStageDetails && (
              <ol className="mt-3 space-y-1.5 border-t border-[#1E222D] pt-3 text-xs">
                {pipelineStages.map((s) => (
                  <li key={s.id} className="flex items-center justify-between gap-3">
                    <span
                      className={
                        s.status === 'running'
                          ? 'text-amber-400'
                          : s.status === 'done'
                          ? 'text-slate-300'
                          : 'text-slate-500'
                      }
                    >
                      {s.label}
                    </span>
                    <span className="font-mono text-[11px] tabular-nums text-slate-500">
                      {s.status === 'done'
                        ? s.durationMs !== undefined
                          ? `${s.durationMs}ms`
                          : 'Done'
                        : s.status === 'running'
                        ? s.progressPct !== null
                          ? `${s.progressPct}%`
                          : 'Working...'
                        : s.status === 'skipped'
                        ? 'Skipped'
                        : 'Pending'}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        )}
      </section>

      {/* Recent Projects List (Only shown when real projects exist) */}
      {projects.length > 0 && (
        <section className="mt-14 border-t border-[#1C202A] pt-10">
          <div className="flex items-baseline justify-between">
            <h2 className="font-display text-lg font-semibold text-slate-100">Recent Projects</h2>
            <span className="font-mono text-xs tabular-nums text-slate-500">
              {projects.length} {projects.length === 1 ? 'project' : 'projects'}
            </span>
          </div>

          <div className="mt-4 divide-y divide-[#1C202A] rounded-2xl border border-[#222631] bg-[#111318]">
            {projects.map((proj) => {
              const isSelected = proj.id === activeProjectId;
              return (
                <div
                  key={proj.id}
                  className="flex flex-col gap-4 px-5 py-4 transition-colors hover:bg-[#151821] sm:flex-row sm:items-center sm:justify-between"
                >
                  <button
                    type="button"
                    onClick={() => onOpenProjectInStudio(proj.id)}
                    className="flex min-w-0 flex-1 items-center gap-4 text-left"
                  >
                    <div className="flex h-12 w-20 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-[#232733] bg-[#0A0B0F] text-slate-500">
                      {proj.probe.thumbnailUrl ? (
                        <img
                          src={proj.probe.thumbnailUrl}
                          alt={proj.probe.fileName}
                          referrerPolicy="no-referrer"
                          className="h-full w-full object-cover"
                        />
                      ) : proj.probe.isLiveCapture ? (
                        <Radio className="h-4 w-4 text-amber-400" />
                      ) : (
                        <Film className="h-4 w-4" />
                      )}
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="truncate text-sm font-medium text-slate-100">
                          {proj.probe.fileName}
                        </span>
                        {isSelected && (
                          <span className="text-[11px] font-medium text-amber-400">Active</span>
                        )}
                      </div>
                      <div className="mt-1 flex flex-wrap items-center gap-x-2 text-xs text-slate-500">
                        <span className="font-mono tabular-nums">
                          {proj.probe.width}×{proj.probe.height}
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="font-mono tabular-nums">
                          {formatTimecode(proj.probe.duration)}
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="font-mono tabular-nums">
                          {formatBytes(proj.probe.fileSizeBytes)}
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="text-slate-300">
                          {proj.clips.length} {proj.clips.length === 1 ? 'clip' : 'clips'}
                        </span>
                      </div>
                    </div>
                  </button>

                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      type="button"
                      onClick={() => onOpenProjectInStudio(proj.id)}
                      className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-[#1C202B] px-3.5 text-xs font-medium text-slate-100 transition-colors hover:bg-[#262B3A] whitespace-nowrap"
                    >
                      Open Studio
                      <ArrowRight className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      aria-label={`Delete ${proj.probe.fileName}`}
                      onClick={() => onDeleteProject(proj.id)}
                      className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-rose-950/40 hover:text-rose-300"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}
    </div>
  );
};
