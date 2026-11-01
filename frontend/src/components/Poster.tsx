import { useState } from 'react';
import type { Certificate } from '../api/types';
import { posterGradient } from '../lib/poster';

interface Props {
  title: string;
  posterUrl?: string | null;
  certificate?: Certificate;
  className?: string;
  /** Smaller title text, for thumbnails. */
  compact?: boolean;
}

/** The movie's poster image, or a generated gradient poster with the title on it. */
export function Poster({ title, posterUrl, certificate, className = '', compact }: Props) {
  const [broken, setBroken] = useState(false);
  const showImage = posterUrl && !broken;
  return (
    <div className={`relative isolate overflow-hidden rounded-2xl bg-ink-800 ${className}`}
         style={showImage ? undefined : { backgroundImage: posterGradient(title) }}>
      {showImage ? (
        <img src={posterUrl} alt="" loading="lazy" onError={() => setBroken(true)} className="h-full w-full object-cover" />
      ) : (
        <>
          {/* Film-grain sparkle and a soft vignette so generated posters don't look flat */}
          <div className="absolute inset-0 -z-10 opacity-40 mix-blend-overlay"
               style={{ backgroundImage: 'radial-gradient(circle at 20% 15%, rgba(255,255,255,0.7) 0, transparent 35%), radial-gradient(circle at 85% 90%, rgba(0,0,0,0.6) 0, transparent 45%)' }} />
          <div className="flex h-full flex-col justify-end bg-gradient-to-t from-black/70 via-black/10 to-transparent p-3">
            <p className={`font-extrabold uppercase leading-tight tracking-tight text-white drop-shadow ${compact ? 'text-xs' : 'text-lg sm:text-xl'}`}>
              {title}
            </p>
          </div>
        </>
      )}
      {certificate && (
        <span className="absolute left-2 top-2 rounded-md bg-black/55 px-1.5 py-0.5 text-[10px] font-bold text-white backdrop-blur">
          {certificate === 'UA' ? 'U/A' : certificate}
        </span>
      )}
    </div>
  );
}
