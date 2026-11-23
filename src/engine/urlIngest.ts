export type DetectedSourceKind = 'youtube' | 'youtube-live' | 'hls-stream' | 'direct-video' | 'unsupported';

export interface ParsedMediaLink {
  normalizedUrl: string;
  kind: DetectedSourceKind;
  youtubeVideoId?: string;
  hostname: string;
  label: string;
}

const YOUTUBE_HOSTS = new Set([
  'youtube.com',
  'www.youtube.com',
  'm.youtube.com',
  'youtu.be',
  'www.youtu.be',
  'music.youtube.com',
]);

const YOUTUBE_ID_RE = /^[A-Za-z0-9_-]{11}$/;

/**
 * Extracts an 11-character YouTube video/live/shorts ID from standard YouTube URL formats.
 */
export function extractYouTubeVideoId(url: URL): { videoId: string; isLiveUrl: boolean } | null {
  const host = url.hostname.toLowerCase();
  if (!YOUTUBE_HOSTS.has(host)) return null;

  if (host === 'youtu.be' || host === 'www.youtu.be') {
    const seg = url.pathname.split('/').filter(Boolean)[0];
    if (seg && YOUTUBE_ID_RE.test(seg)) {
      return { videoId: seg, isLiveUrl: false };
    }
    return null;
  }

  const vParam = url.searchParams.get('v');
  if (vParam && YOUTUBE_ID_RE.test(vParam)) {
    return { videoId: vParam, isLiveUrl: false };
  }

  const parts = url.pathname.split('/').filter(Boolean);
  if (parts.length >= 2) {
    const [prefix, candidate] = parts;
    if ((prefix === 'live' || prefix === 'shorts' || prefix === 'embed' || prefix === 'v') && YOUTUBE_ID_RE.test(candidate)) {
      return { videoId: candidate, isLiveUrl: prefix === 'live' };
    }
  }

  return null;
}

/**
 * Validates and classifies a user-pasted video or live stream URL.
 * Rejects non-HTTP(S) schemes and malformed links.
 */
export function parseMediaLink(rawInput: string): { ok: true; data: ParsedMediaLink } | { ok: false; error: string } {
  const trimmed = rawInput.trim();
  if (!trimmed) {
    return { ok: false, error: 'Paste a YouTube, video, or live stream URL first.' };
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { ok: false, error: 'Enter a valid link starting with https:// or http://' };
  }

  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    return { ok: false, error: 'Only http:// and https:// video links are supported.' };
  }

  const host = parsed.hostname.toLowerCase();
  if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) {
    return { ok: false, error: 'Local or private network URLs are not allowed.' };
  }

  const yt = extractYouTubeVideoId(parsed);
  if (yt) {
    return {
      ok: true,
      data: {
        normalizedUrl: parsed.toString(),
        kind: yt.isLiveUrl ? 'youtube-live' : 'youtube',
        youtubeVideoId: yt.videoId,
        hostname: host,
        label: yt.isLiveUrl ? 'YouTube Live' : 'YouTube Video',
      },
    };
  }

  const lowerPath = parsed.pathname.toLowerCase();
  if (lowerPath.endsWith('.m3u8') || lowerPath.includes('.m3u8') || parsed.search.toLowerCase().includes('m3u8')) {
    return {
      ok: true,
      data: {
        normalizedUrl: parsed.toString(),
        kind: 'hls-stream',
        hostname: host,
        label: 'HLS Live / Stream',
      },
    };
  }

  if (/\.(mp4|webm|mov|m4v|mkv|ts)$/i.test(lowerPath)) {
    return {
      ok: true,
      data: {
        normalizedUrl: parsed.toString(),
        kind: 'direct-video',
        hostname: host,
        label: 'Direct Video Link',
      },
    };
  }

  // Allow general HTTPS video/stream endpoints so the server can probe content-type or HLS playlist
  return {
    ok: true,
    data: {
      normalizedUrl: parsed.toString(),
      kind: 'direct-video',
      hostname: host,
      label: 'Video / Stream Link',
    },
  };
}

/**
 * Checks whether an IPv4 or IPv6 address string is private, loopback, link-local, or unspecified.
 */
export function isPrivateOrBlockedIp(ip: string): boolean {
  const normalized = ip.trim().toLowerCase();
  if (!normalized) return true;

  // IPv6 loopback / unspecified / unique-local / link-local / IPv4-mapped
  if (normalized === '::1' || normalized === '::' || normalized === '0:0:0:0:0:0:0:1') return true;
  if (normalized.startsWith('fc') || normalized.startsWith('fd') || normalized.startsWith('fe80:')) return true;
  if (normalized.startsWith('::ffff:')) {
    return isPrivateOrBlockedIp(normalized.slice(7));
  }

  const parts = normalized.split('.').map((n) => Number(n));
  if (parts.length === 4 && parts.every((n) => Number.isInteger(n) && n >= 0 && n <= 255)) {
    const [a, b] = parts;
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 169 && b === 254) return true; // link-local & cloud metadata
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
    if (a >= 224) return true; // multicast/reserved
    return false;
  }

  return false;
}
