import express from 'express';
import { createServer as createViteServer } from 'vite';
import dns from 'node:dns/promises';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { isPrivateOrBlockedIp, parseMediaLink } from './src/engine/urlIngest.ts';

const execFileAsync = promisify(execFile);

const PORT = 3000;
const MEDIA_DIR = path.resolve(os.tmpdir(), 'clipai-ingested-media');
fs.mkdirSync(MEDIA_DIR, { recursive: true });

interface StoredMediaEntry {
  id: string;
  filePath: string;
  mimeType: string;
  createdAt: number;
}

const mediaRegistry = new Map<string, StoredMediaEntry>();

// Clean up ingested temporary media older than 30 minutes
setInterval(() => {
  const cutoff = Date.now() - 30 * 60 * 1000;
  for (const [id, entry] of mediaRegistry.entries()) {
    if (entry.createdAt < cutoff) {
      fs.rmSync(entry.filePath, { force: true });
      mediaRegistry.delete(id);
    }
  }
}, 5 * 60 * 1000).unref();

async function assertPublicHost(urlStr: string): Promise<URL> {
  const parsed = new URL(urlStr);
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Only http and https URLs are permitted.');
  }
  const host = parsed.hostname.toLowerCase();
  if (
    host === 'localhost' ||
    host.endsWith('.local') ||
    host.endsWith('.internal') ||
    host === 'metadata.google.internal'
  ) {
    throw new Error('Private or internal hostnames are blocked.');
  }

  const records = await dns.lookup(host, { all: true });
  if (!records.length) {
    throw new Error('Could not resolve host address.');
  }
  for (const rec of records) {
    if (isPrivateOrBlockedIp(rec.address)) {
      throw new Error('Target resolves to a blocked private or loopback network address.');
    }
  }
  return parsed;
}

interface ExtractedCaptionWord {
  text: string;
  start: number;
  end: number;
}

function decodeXmlEntities(text: string): string {
  return text
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

function parseYouTubeTimedTextXml(xml: string): ExtractedCaptionWord[] {
  const words: ExtractedCaptionWord[] = [];
  const regex = /<text\s+start="([\d.]+)"(?:\s+dur="([\d.]+)")?[^>]*>([\s\S]*?)<\/text>/g;
  let match: RegExpExecArray | null;
  while ((match = regex.exec(xml)) !== null) {
    const segStart = Number(match[1]);
    const segDur = Math.max(0.4, Number(match[2] || '2.0'));
    const clean = decodeXmlEntities(match[3].replace(/<[^>]+>/g, ' '));
    const tokens = clean.split(/\s+/).filter(Boolean);
    if (!tokens.length) continue;
    const perWord = segDur / tokens.length;
    tokens.forEach((tok, idx) => {
      const start = Number((segStart + idx * perWord).toFixed(2));
      const end = Number((start + Math.max(0.18, perWord * 0.92)).toFixed(2));
      words.push({ text: tok, start, end });
    });
  }
  return words;
}

function parseYouTubeJson3(json: any): ExtractedCaptionWord[] {
  const words: ExtractedCaptionWord[] = [];
  const events = Array.isArray(json?.events) ? json.events : [];
  for (const ev of events) {
    if (!Array.isArray(ev?.segs) || typeof ev.tStartMs !== 'number') continue;
    const baseMs = ev.tStartMs;
    const durMs = typeof ev.dDurationMs === 'number' ? ev.dDurationMs : 2200;
    const rawSegs = ev.segs
      .map((s: any) => ({
        text: typeof s?.utf8 === 'string' ? s.utf8.replace(/\n/g, ' ').trim() : '',
        offsetMs: typeof s?.tOffsetMs === 'number' ? s.tOffsetMs : null,
      }))
      .filter((s: any) => s.text.length > 0);

    if (!rawSegs.length) continue;

    for (let i = 0; i < rawSegs.length; i++) {
      const seg = rawSegs[i];
      const next = rawSegs[i + 1];
      const startSec =
        seg.offsetMs !== null
          ? (baseMs + seg.offsetMs) / 1000
          : (baseMs + (i / rawSegs.length) * durMs) / 1000;
      const endSec =
        next && next.offsetMs !== null
          ? (baseMs + next.offsetMs) / 1000
          : (baseMs + ((i + 1) / rawSegs.length) * durMs) / 1000;

      const splitTokens = seg.text.split(/\s+/).filter(Boolean);
      const step = Math.max(0.18, (endSec - startSec) / Math.max(1, splitTokens.length));
      splitTokens.forEach((tok: string, idx: number) => {
        const st = Number((startSec + idx * step).toFixed(2));
        const en = Number((st + Math.max(0.16, step * 0.92)).toFixed(2));
        words.push({ text: tok, start: st, end: en });
      });
    }
  }
  return words;
}

async function fetchYouTubeMetadataAndStreams(videoId: string) {
  const watchUrl = `https://www.youtube.com/watch?v=${encodeURIComponent(videoId)}`;
  let title = `YouTube Video (${videoId})`;
  let author = 'YouTube';
  let durationSec = 0;
  let isLive = false;
  let thumbnailUrl = `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
  let playableUrl: string | null = null;
  let words: ExtractedCaptionWord[] = [];

  // 1. Lightweight oEmbed check for verified title & thumbnail
  try {
    const oembedRes = await fetch(
      `https://www.youtube.com/oembed?url=${encodeURIComponent(watchUrl)}&format=json`,
      { signal: AbortSignal.timeout(8000) }
    );
    if (oembedRes.ok) {
      const oembed = (await oembedRes.json()) as any;
      if (typeof oembed?.title === 'string' && oembed.title.trim()) title = oembed.title.trim();
      if (typeof oembed?.author_name === 'string') author = oembed.author_name;
      if (typeof oembed?.thumbnail_url === 'string') thumbnailUrl = oembed.thumbnail_url;
    }
  } catch {
    // Fallback to player response below
  }

  // 2. Innertube player metadata + captionTracks + stream extraction
  const clients = [
    {
      clientName: 'ANDROID',
      clientVersion: '19.44.38',
      androidSdkVersion: 34,
      hl: 'en',
      gl: 'US',
    },
    {
      clientName: 'IOS',
      clientVersion: '19.45.4',
      hl: 'en',
      gl: 'US',
    },
  ];

  let playerData: any = null;
  for (const client of clients) {
    try {
      const res = await fetch('https://www.youtube.com/youtubei/v1/player?prettyPrint=false', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'com.google.android.youtube/19.44.38 (Linux; U; Android 14)',
        },
        body: JSON.stringify({
          videoId,
          context: { client },
          contentCheckOk: true,
          racyCheckOk: true,
        }),
        signal: AbortSignal.timeout(10000),
      });
      if (res.ok) {
        const json = (await res.json()) as any;
        if (json?.videoDetails?.videoId === videoId) {
          playerData = json;
          if (json?.streamingData?.formats?.length || json?.streamingData?.hlsManifestUrl) {
            break;
          }
        }
      }
    } catch {
      // Try next client
    }
  }

  if (playerData?.videoDetails) {
    if (playerData.videoDetails.title) title = playerData.videoDetails.title;
    if (playerData.videoDetails.author) author = playerData.videoDetails.author;
    durationSec = Number(playerData.videoDetails.lengthSeconds || 0);
    isLive = Boolean(playerData.videoDetails.isLiveContent || playerData.videoDetails.isLive);
  }

  const playabilityStatus = playerData?.playabilityStatus?.status;
  const playabilityReason = playerData?.playabilityStatus?.reason || null;

  // Pick progressive MP4 or HLS manifest if exposed
  const streaming = playerData?.streamingData;
  if (streaming?.hlsManifestUrl && typeof streaming.hlsManifestUrl === 'string') {
    playableUrl = streaming.hlsManifestUrl;
  } else if (Array.isArray(streaming?.formats)) {
    const mp4Formats = streaming.formats
      .filter((f: any) => typeof f?.url === 'string' && String(f?.mimeType || '').includes('video/mp4'))
      .sort((a: any, b: any) => (Number(b.height) || 0) - (Number(a.height) || 0));
    const preferred = mp4Formats.find((f: any) => (Number(f.height) || 0) <= 720) ?? mp4Formats[0];
    if (preferred?.url) {
      playableUrl = preferred.url;
    }
  }

  // Extract real timed captions (prefer English / Hindi / auto-generated)
  const tracks: any[] =
    playerData?.captions?.playerCaptionsTracklistRenderer?.captionTracks ?? [];
  if (tracks.length > 0) {
    const chosen =
      tracks.find((t) => String(t.languageCode).startsWith('en') && t.kind !== 'asr') ??
      tracks.find((t) => String(t.languageCode).startsWith('hi')) ??
      tracks.find((t) => String(t.languageCode).startsWith('en')) ??
      tracks[0];

    if (chosen?.baseUrl) {
      try {
        const capUrl = new URL(chosen.baseUrl);
        capUrl.searchParams.set('fmt', 'json3');
        await assertPublicHost(capUrl.toString());
        const capRes = await fetch(capUrl.toString(), { signal: AbortSignal.timeout(9000) });
        if (capRes.ok) {
          const text = await capRes.text();
          if (text.trim().startsWith('{')) {
            words = parseYouTubeJson3(JSON.parse(text));
          } else {
            words = parseYouTubeTimedTextXml(text);
          }
        }
      } catch {
        // Fallback to XML timedtext
        try {
          await assertPublicHost(chosen.baseUrl);
          const xmlRes = await fetch(chosen.baseUrl, { signal: AbortSignal.timeout(8000) });
          if (xmlRes.ok) {
            words = parseYouTubeTimedTextXml(await xmlRes.text());
          }
        } catch {
          // Proceed without captions
        }
      }
    }
  }

  return {
    title,
    author,
    durationSec,
    isLive,
    thumbnailUrl,
    playableUrl,
    words,
    playabilityStatus,
    playabilityReason,
  };
}

async function captureMediaWithFfmpeg(
  sourceUrl: string,
  maxSeconds: number,
  isLiveStream: boolean
): Promise<{
  mediaId: string;
  filePath: string;
  fileSizeBytes: number;
  width: number;
  height: number;
  duration: number;
  fps: number;
}> {
  await assertPublicHost(sourceUrl);

  const mediaId = crypto.randomUUID();
  const outputPath = path.resolve(MEDIA_DIR, `${mediaId}.mp4`);
  if (!outputPath.startsWith(MEDIA_DIR + path.sep)) {
    throw new Error('Invalid media path.');
  }

  const boundedSeconds = Math.max(15, Math.min(300, Math.round(maxSeconds)));

  // Fast remux/transcode capture capped to bounded duration and max 720p so clipping stays fast and responsive
  const args = [
    '-y',
    '-hide_banner',
    '-loglevel',
    'error',
    '-protocol_whitelist',
    'file,http,https,tcp,tls,crypto',
    '-reconnect',
    '1',
    '-reconnect_streamed',
    '1',
    '-reconnect_delay_max',
    '3',
    '-i',
    sourceUrl,
    '-t',
    String(boundedSeconds),
    '-vf',
    'scale=-2:min(720\\,ih)',
    '-c:v',
    'libx264',
    '-preset',
    'veryfast',
    '-crf',
    '24',
    '-c:a',
    'aac',
    '-b:a',
    '128k',
    '-movflags',
    '+faststart',
    outputPath,
  ];

  // If it's a direct static MP4 (not live), try fast stream copy first (instant, zero re-encode)
  if (!isLiveStream && /\.(mp4|m4v)(\?|$)/i.test(sourceUrl)) {
    try {
      await execFileAsync(
        'ffmpeg',
        [
          '-y',
          '-hide_banner',
          '-loglevel',
          'error',
          '-protocol_whitelist',
          'file,http,https,tcp,tls,crypto',
          '-i',
          sourceUrl,
          '-t',
          String(boundedSeconds),
          '-c',
          'copy',
          '-movflags',
          '+faststart',
          outputPath,
        ],
        { timeout: 45_000, maxBuffer: 4 * 1024 * 1024 }
      );
    } catch {
      await execFileAsync('ffmpeg', args, { timeout: 90_000, maxBuffer: 4 * 1024 * 1024 });
    }
  } else {
    await execFileAsync('ffmpeg', args, { timeout: 90_000, maxBuffer: 4 * 1024 * 1024 });
  }

  const stat = fs.statSync(outputPath);
  if (!stat.isFile() || stat.size < 1024) {
    fs.rmSync(outputPath, { force: true });
    throw new Error('Captured media file was empty.');
  }

  // Probe real resolution, duration, and fps with ffprobe
  const { stdout } = await execFileAsync(
    'ffprobe',
    [
      '-v',
      'error',
      '-print_format',
      'json',
      '-show_format',
      '-show_streams',
      outputPath,
    ],
    { timeout: 15_000 }
  );

  const probeJson = JSON.parse(stdout || '{}');
  const videoStream = (probeJson.streams || []).find((s: any) => s.codec_type === 'video');
  const width = Number(videoStream?.width || 1280);
  const height = Number(videoStream?.height || 720);
  const duration = Number(probeJson.format?.duration || videoStream?.duration || boundedSeconds);
  let fps = 30;
  if (typeof videoStream?.r_frame_rate === 'string' && videoStream.r_frame_rate.includes('/')) {
    const [num, den] = videoStream.r_frame_rate.split('/').map(Number);
    if (num > 0 && den > 0) fps = Math.round(num / den);
  }

  mediaRegistry.set(mediaId, {
    id: mediaId,
    filePath: outputPath,
    mimeType: 'video/mp4',
    createdAt: Date.now(),
  });

  return {
    mediaId,
    filePath: outputPath,
    fileSizeBytes: stat.size,
    width,
    height,
    duration,
    fps,
  };
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/api/health', (_req, res) => {
    res.json({ ok: true });
  });

  // Stream captured local media with Range support for smooth HTML5 video scrubbing
  app.get('/api/media/:id', (req, res) => {
    const id = req.params.id;
    if (!/^[a-f0-9-]{8,64}$/i.test(id)) {
      res.status(400).json({ error: 'Invalid media ID.' });
      return;
    }

    const entry = mediaRegistry.get(id);
    if (!entry) {
      res.status(404).json({ error: 'Media session expired or not found.' });
      return;
    }

    const resolved = path.resolve(entry.filePath);
    if (!resolved.startsWith(MEDIA_DIR + path.sep) || !fs.existsSync(resolved)) {
      res.status(404).json({ error: 'Media file missing.' });
      return;
    }

    const stat = fs.statSync(resolved);
    const range = req.headers.range;
    if (range) {
      const [startStr, endStr] = range.replace(/bytes=/, '').split('-');
      const start = parseInt(startStr, 10);
      const end = endStr ? parseInt(endStr, 10) : stat.size - 1;
      if (isNaN(start) || isNaN(end) || start > end || end >= stat.size) {
        res.status(416).end();
        return;
      }
      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${stat.size}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': end - start + 1,
        'Content-Type': entry.mimeType,
      });
      fs.createReadStream(resolved, { start, end }).pipe(res);
    } else {
      res.writeHead(200, {
        'Content-Length': stat.size,
        'Content-Type': entry.mimeType,
        'Accept-Ranges': 'bytes',
      });
      fs.createReadStream(resolved).pipe(res);
    }
  });

  // Ingest YouTube video, YouTube Live, HLS .m3u8 live/VOD stream, or direct MP4/WebM/MOV link
  app.post('/api/ingest-url', async (req, res) => {
    try {
      const rawUrl = typeof req.body?.url === 'string' ? req.body.url : '';
      const captureSeconds =
        typeof req.body?.captureSeconds === 'number'
          ? Math.max(20, Math.min(240, req.body.captureSeconds))
          : 120;

      const parsedResult = parseMediaLink(rawUrl);
      if ('error' in parsedResult) {
        res.status(400).json({ error: parsedResult.error });
        return;
      }

      const link = parsedResult.data;
      await assertPublicHost(link.normalizedUrl);

      if (link.kind === 'youtube' || link.kind === 'youtube-live') {
        const yt = await fetchYouTubeMetadataAndStreams(link.youtubeVideoId!);

        if (!yt.playableUrl) {
          const reasonDetail = yt.playabilityReason
            ? ` (${yt.playabilityReason})`
            : '';
          res.status(422).json({
            error: `YouTube restricted direct stream access for "${yt.title}"${reasonDetail}. Try another public YouTube link, a direct MP4/HLS (.m3u8) stream link, or pick a local video file.`,
            title: yt.title,
            thumbnailUrl: yt.thumbnailUrl,
            words: yt.words,
            durationSec: yt.durationSec,
          });
          return;
        }

        const captured = await captureMediaWithFfmpeg(
          yt.playableUrl,
          yt.isLive ? Math.min(captureSeconds, 90) : Math.min(captureSeconds, Math.max(30, yt.durationSec || 120)),
          yt.isLive
        );

        const wordsWithinCapture = yt.words.filter((w) => w.start <= captured.duration + 0.5);

        res.json({
          title: yt.title,
          author: yt.author,
          sourceKind: yt.isLive ? 'youtube-live' : 'youtube',
          sourceUrl: link.normalizedUrl,
          mediaUrl: `/api/media/${captured.mediaId}`,
          thumbnailUrl: yt.thumbnailUrl,
          fileSizeBytes: captured.fileSizeBytes,
          width: captured.width,
          height: captured.height,
          duration: captured.duration,
          fps: captured.fps,
          words: wordsWithinCapture,
          isLiveCapture: yt.isLive,
        });
        return;
      }

      // Direct video (.mp4/.webm/.mov) or HLS (.m3u8) live/VOD stream
      const isLive = link.kind === 'hls-stream';
      const captured = await captureMediaWithFfmpeg(
        link.normalizedUrl,
        isLive ? Math.min(captureSeconds, 90) : captureSeconds,
        isLive
      );

      const urlObj = new URL(link.normalizedUrl);
      const baseName = decodeURIComponent(path.basename(urlObj.pathname) || urlObj.hostname);

      res.json({
        title: baseName || 'Stream Capture',
        author: urlObj.hostname,
        sourceKind: link.kind,
        sourceUrl: link.normalizedUrl,
        mediaUrl: `/api/media/${captured.mediaId}`,
        fileSizeBytes: captured.fileSizeBytes,
        width: captured.width,
        height: captured.height,
        duration: captured.duration,
        fps: captured.fps,
        words: [],
        isLiveCapture: isLive,
      });
    } catch (err: any) {
      const message =
        typeof err?.message === 'string' && err.message.trim()
          ? err.message
          : 'Unable to load video from that link. Verify the URL is public and reachable.';
      res.status(400).json({ error: message });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        hmr: false,
        watch: process.env.DISABLE_HMR === 'true' ? null : {},
      },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(process.cwd(), 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`ClipAI server listening on http://0.0.0.0:${PORT}`);
  });
}

startServer();
