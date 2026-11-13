/** Types for the generated Timmy engine (timmy-core.js). */
export interface CatoCoreMesh {
  bone: number;
  v0: number;
  v1: number;
  f0: number;
  f1: number;
  vis: boolean;
  prop: string | null;
}
export interface CatoCoreMaterial {
  name: string;
  c: [number, number, number]; // linear RGB
  spec: number;
  pow: number;
  rim: number;
  led?: boolean;
  fill?: string;
  same?: boolean;
}
export interface CatoCoreDecal {
  pts: [number, number, number][];
  fill: string; // "rgb(r,g,b)"
  alpha: number;
  glow: number;
  order: number;
}
export interface CatoCore {
  frame(dt: number, view?: { p: number[]; f: number[]; r: number[]; u: number[]; w: number; h: number; fov: number; minX?: number }): void;
  meshes: CatoCoreMesh[];
  fIdx: number[];
  fStart: number[];
  fLen: number[];
  fMat: number[];
  LP: Float32Array;
  MATS: CatoCoreMaterial[];
  BONES: { name: string; W: Float64Array }[];
  decals: CatoCoreDecal[];
  lights: { key: number[]; fill: number[]; rim: number[]; sky: number[]; gnd: number[]; scene: { light: string } };
  led(): number[];
  svg: SVGSVGElement;
  body: { x: number; z: number; heading: number };
  play(id: string): void;
  scrub(id: string, t: number): void;
  release(): void;
  viseme(key: string, value: number): void;
  beat(bpm: number, phase?: number): void;
  pointerAt(x: number, y: number, active: boolean): void;
  moves(): string[];
  duration(id: string): number;
  isLoop(id: string): boolean;
  mode(): string;
  onMode(fn: (id: string) => void): void;
}
export function createCatoCore(opts?: { attract?: boolean; fidget?: boolean }): CatoCore;
