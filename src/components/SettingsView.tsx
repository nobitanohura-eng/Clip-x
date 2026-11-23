import React, { useState } from 'react';
import { Download, Pause, Trash2 } from 'lucide-react';
import { AppSettings, DeviceProfile, ExportPreset, ModelSpec } from '../types';

interface SettingsViewProps {
  settings: AppSettings;
  deviceProfile: DeviceProfile;
  models: ModelSpec[];
  onUpdateSettings: (patch: Partial<AppSettings>) => void;
  onUpdateDeviceProfile: (patch: Partial<DeviceProfile>) => void;
  onDownloadModel: (modelId: string) => void;
  onPauseModel: (modelId: string) => void;
  onDeleteModel: (modelId: string) => void;
  onSelectAsrModel: (modelId: string) => void;
  onSelectLlmModel: (modelId: string) => void;
}

export const SettingsView: React.FC<SettingsViewProps> = ({
  settings,
  deviceProfile,
  models,
  onUpdateSettings,
  onUpdateDeviceProfile,
  onDownloadModel,
  onPauseModel,
  onDeleteModel,
  onSelectAsrModel,
  onSelectLlmModel,
}) => {
  const [tab, setTab] = useState<'clipping' | 'models'>('clipping');

  return (
    <div className="mx-auto max-w-[820px] px-4 py-10 sm:px-6 lg:px-8">
      <div className="flex flex-col gap-4 border-b border-[#1E222D] pb-6 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="font-display text-2xl font-semibold text-slate-50">Settings</h1>
          <p className="mt-1 text-sm text-slate-400">
            Export quality, automatic clip rules, and on-device models.
          </p>
        </div>

        <div
          role="tablist"
          aria-label="Settings sections"
          className="inline-flex rounded-xl border border-[#222631] bg-[#111318] p-1 self-start"
        >
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'clipping'}
            onClick={() => setTab('clipping')}
            className={`rounded-lg px-4 py-1.5 text-xs font-medium transition-colors whitespace-nowrap ${
              tab === 'clipping'
                ? 'bg-[#222631] text-white'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            Clipping & Export
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'models'}
            onClick={() => setTab('models')}
            className={`rounded-lg px-4 py-1.5 text-xs font-medium transition-colors whitespace-nowrap ${
              tab === 'models'
                ? 'bg-[#222631] text-white'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            On-Device Models
          </button>
        </div>
      </div>

      {tab === 'clipping' && (
        <div className="mt-8 space-y-6">
          {/* Export Resolution */}
          <section className="rounded-2xl border border-[#222631] bg-[#111318] p-6">
            <h2 className="font-display text-base font-semibold text-slate-100">Export Format</h2>
            <p className="mt-1 text-xs text-slate-400">
              Vertical 9:16 video normalized to -14 LUFS for Shorts, Reels, and TikTok.
            </p>

            <div className="mt-4 grid grid-cols-2 gap-3">
              {(['1080p', '720p'] as ExportPreset[]).map((preset) => {
                const active = settings.exportPreset === preset;
                return (
                  <button
                    key={preset}
                    type="button"
                    onClick={() => onUpdateSettings({ exportPreset: preset })}
                    className={`rounded-xl border px-4 py-3.5 text-left transition-colors ${
                      active
                        ? 'border-amber-500/70 bg-amber-500/10'
                        : 'border-[#232733] bg-[#0B0D11] hover:border-[#343B4D]'
                    }`}
                  >
                    <div className="text-sm font-semibold text-slate-100">
                      {preset === '1080p' ? '1080 × 1920 Full HD' : '720 × 1280 Fast HD'}
                    </div>
                    <div className="mt-0.5 text-xs text-slate-400">
                      {preset === '1080p' ? 'Best quality · 30 fps' : 'Faster render · lighter file'}
                    </div>
                  </button>
                );
              })}
            </div>
          </section>

          {/* Clip Windowing Rules */}
          <section className="space-y-5 rounded-2xl border border-[#222631] bg-[#111318] p-6">
            <div>
              <h2 className="font-display text-base font-semibold text-slate-100">
                Auto-Clip Timing
              </h2>
              <p className="mt-1 text-xs text-slate-400">
                Clips snap to complete sentences and avoid overlapping moments.
              </p>
            </div>

            <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
              <SliderField
                id="set-max-clips"
                label="Maximum clips per video"
                value={settings.maxClips}
                min={1}
                max={10}
                unit="clips"
                onChange={(v) => onUpdateSettings({ maxClips: v })}
              />
              <SliderField
                id="set-dedup-gap"
                label="Minimum spacing between clips"
                value={settings.dedupGapSec}
                min={10}
                max={60}
                unit="s"
                onChange={(v) => onUpdateSettings({ dedupGapSec: v })}
              />
              <SliderField
                id="set-min-dur"
                label="Minimum clip length"
                value={settings.minClipSec}
                min={15}
                max={45}
                unit="s"
                onChange={(v) => onUpdateSettings({ minClipSec: v })}
              />
              <SliderField
                id="set-max-dur"
                label="Maximum clip length"
                value={settings.maxClipSec}
                min={40}
                max={120}
                unit="s"
                onChange={(v) => onUpdateSettings({ maxClipSec: v })}
              />
            </div>
          </section>

          {/* Download & Device Safety */}
          <section className="divide-y divide-[#1E222D] rounded-2xl border border-[#222631] bg-[#111318]">
            <label className="flex cursor-pointer items-center justify-between gap-4 px-6 py-4">
              <div>
                <span className="block text-sm font-medium text-slate-100">
                  Wi-Fi only for large model downloads
                </span>
                <span className="block text-xs text-slate-500">
                  Requires Wi-Fi before downloading models larger than 100 MB
                </span>
              </div>
              <input
                type="checkbox"
                checked={settings.wifiOnlyOver100Mb}
                onChange={(e) => onUpdateSettings({ wifiOnlyOver100Mb: e.target.checked })}
                className="h-4 w-4 accent-amber-500"
              />
            </label>

            <label className="flex cursor-pointer items-center justify-between gap-4 px-6 py-4">
              <div>
                <span className="block text-sm font-medium text-slate-100">
                  Battery protection on long exports
                </span>
                <span className="block text-xs text-slate-500">
                  Warns when battery is at or below 30% while unplugged
                </span>
              </div>
              <input
                type="checkbox"
                checked={settings.requireChargingForHeavy}
                onChange={(e) => onUpdateSettings({ requireChargingForHeavy: e.target.checked })}
                className="h-4 w-4 accent-amber-500"
              />
            </label>
          </section>
        </div>
      )}

      {tab === 'models' && (
        <div className="mt-8 space-y-5">
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-[#222631] bg-[#111318] px-5 py-4 text-xs text-slate-400">
            <span>
              Rule-based scoring runs by default with zero LLM required. Optional Gemma models
              re-rank top clips on 6 GB+ devices.
            </span>
            <label className="inline-flex cursor-pointer items-center gap-2 text-slate-200">
              <input
                type="checkbox"
                checked={deviceProfile.llmEnabled}
                onChange={(e) => onUpdateDeviceProfile({ llmEnabled: e.target.checked })}
                className="h-4 w-4 accent-amber-500"
              />
              Enable LLM Re-rank
            </label>
          </div>

          <div className="divide-y divide-[#1E222D] rounded-2xl border border-[#222631] bg-[#111318]">
            {models.map((m) => {
              const isSelected =
                (m.category === 'asr' && deviceProfile.asrModelId === m.id) ||
                (m.category === 'llm' && deviceProfile.llmModelId === m.id) ||
                m.category === 'vision';
              const pct = Math.round((m.downloadedMb / Math.max(1, m.sizeMb)) * 100);

              return (
                <div
                  key={m.id}
                  className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-slate-100">{m.name}</span>
                      {isSelected && m.status === 'installed' && (
                        <span className="text-[11px] font-medium text-amber-400">Active</span>
                      )}
                    </div>
                    <p className="mt-0.5 text-xs text-slate-400">{m.role}</p>
                    <p className="mt-1 font-mono text-[11px] tabular-nums text-slate-500">
                      {m.sizeMb} MB · Min {m.minDeviceRamGb} GB RAM · {m.runtime}
                    </p>
                    {(m.status === 'downloading' || m.status === 'paused' || m.status === 'verifying') && (
                      <div className="mt-2 max-w-xs space-y-1">
                        <div className="flex justify-between font-mono text-[11px] tabular-nums text-amber-400">
                          <span>
                            {m.status === 'verifying'
                              ? 'Verifying SHA-256...'
                              : m.status === 'paused'
                              ? 'Paused'
                              : 'Downloading'}
                          </span>
                          <span>{pct}%</span>
                        </div>
                        <div className="h-1 w-full overflow-hidden rounded-full bg-[#222631]">
                          <div className="h-full bg-amber-500" style={{ width: `${pct}%` }} />
                        </div>
                      </div>
                    )}
                  </div>

                  <div className="flex items-center gap-2 shrink-0">
                    {m.status === 'installed' ? (
                      <>
                        {!isSelected && m.category !== 'vision' && (
                          <button
                            type="button"
                            onClick={() =>
                              m.category === 'asr' ? onSelectAsrModel(m.id) : onSelectLlmModel(m.id)
                            }
                            className="h-8 rounded-lg border border-[#2C3240] px-3 text-xs font-medium text-slate-200 hover:border-amber-500/60 hover:text-amber-300 whitespace-nowrap"
                          >
                            Use
                          </button>
                        )}
                        <button
                          type="button"
                          aria-label={`Remove ${m.name}`}
                          onClick={() => onDeleteModel(m.id)}
                          className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-500 hover:bg-rose-950/40 hover:text-rose-300"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </>
                    ) : m.status === 'downloading' ? (
                      <button
                        type="button"
                        onClick={() => onPauseModel(m.id)}
                        className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-amber-500/40 px-3 text-xs font-medium text-amber-300"
                      >
                        <Pause className="h-3.5 w-3.5" />
                        Pause
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={() => onDownloadModel(m.id)}
                        className="inline-flex h-8 items-center gap-1.5 rounded-lg bg-[#1D212C] px-3 text-xs font-medium text-slate-100 hover:bg-[#282D3C] whitespace-nowrap"
                      >
                        <Download className="h-3.5 w-3.5" />
                        {m.status === 'paused' ? 'Resume' : 'Download'}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};

const SliderField: React.FC<{
  id: string;
  label: string;
  value: number;
  min: number;
  max: number;
  unit: string;
  onChange: (val: number) => void;
}> = ({ id, label, value, min, max, unit, onChange }) => (
  <div>
    <div className="flex items-center justify-between text-xs">
      <label htmlFor={id} className="text-slate-300">
        {label}
      </label>
      <span className="font-mono tabular-nums text-slate-100">
        {value} {unit}
      </span>
    </div>
    <input
      id={id}
      type="range"
      min={min}
      max={max}
      value={value}
      onChange={(e) => onChange(Number(e.target.value))}
      className="mt-2 h-1.5 w-full cursor-pointer appearance-none rounded-lg bg-[#222631] accent-amber-500"
    />
  </div>
);
