'use client';

import { useEffect, useState } from 'react';

export interface Quality {
  /** devicePixelRatio clamp for the canvas */
  dpr: [number, number];
  /** particle-count multiplier (scenes must scale counts by this) */
  particles: number;
  /** whether bloom/postprocessing should render */
  bloom: boolean;
  /** false when the user prefers reduced motion — render static compositions */
  animate: boolean;
  /** true on small/touch-first viewports */
  compact: boolean;
}

const DEFAULT_Q: Quality = {
  dpr: [1, 1.6],
  particles: 1,
  bloom: true,
  animate: true,
  compact: false,
};

/**
 * Adaptive quality. Scenes MUST respect `particles` and `animate` so the
 * site stays at 60fps on laptops and respectful of reduced-motion settings.
 */
export function useQuality(): Quality {
  const [q, setQ] = useState<Quality>(DEFAULT_Q);

  useEffect(() => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const compact = window.matchMedia('(max-width: 820px)').matches;
    const cores = navigator.hardwareConcurrency ?? 8;
    const low = compact || cores <= 4;

    setQ({
      dpr: low ? [1, 1.3] : [1, 1.75],
      particles: low ? 0.45 : 1,
      bloom: !low && !reduced,
      animate: !reduced,
      compact,
    });
  }, []);

  return q;
}
