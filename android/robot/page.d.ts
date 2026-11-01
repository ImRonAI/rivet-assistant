/* What bridge.js and the stage layer use from the robot page (Emrys_the_Robot.html): its script's top-level bindings and
   window members. */
declare const D: { cur: { id: string }; settle: string | null; lastInput: number };
declare let time: number;
declare const cam: { rest: number; zoom: number; dirty: boolean };
declare const stage: HTMLElement;
declare const LETTER_VIS: { readonly [letter: string]: string };
declare function play(id: string): void;
/* The skeleton, camera and frame-rate fallback, for where Emrys stands and when the page goes low on effects. */
declare const BONES: readonly { readonly W: readonly number[] }[];
declare const BN: { readonly [bone: string]: number };
declare function mPt(m: readonly number[], x: number, y: number, z: number): [number, number, number];
declare function proj(x: number, y: number, z: number, out: [number, number, number]): [number, number, number];
declare const body: { x: number; z: number };
declare let lowFx: boolean;

/** A Matrix slot (docs/design/EMRYS_MATRIX.md §3): whether it is on screen, and how squarely it faces the camera
    (the cosine between its surface normal and the view, 1 face on). */
interface EmrysSlot { readonly visible: boolean; readonly facing: number }

interface Window {
  emrys: {
    viseme(key: string, value: number): void;
    slots(): Readonly<Record<'chest' | 'mouth' | 'gauntlet-l' | 'gauntlet-r' | 'floor', EmrysSlot>>;
  };
  EmrysHost?: { postMessage(message: string): void };
}

/* What the bridge publishes for the stage layer (android/stage-ui/src/emrys.ts reads the same shapes). */
type Mood = 'cy' | 'amb' | 'ok' | 'rec';
type Turn = 'listening' | 'speaking' | 'none';
/** Where Emrys stands, in stage pixels. */
type Anchor = { x: number; y: number; feetX: number; feetY: number };
/** What Emrys is doing and where, for the stage layer. */
type StageInfo = {
  state: string;
  mood: Mood | null;
  talking: boolean;
  turn: Turn;
  voiced: boolean;
  lowFx: boolean;
  width: number;
  height: number;
  short: boolean;
  anchor: Anchor | null;
};
/** What is being said now: the viseme and its weight, whether Emrys is speaking, and the agent's audio level. */
type Speech = { viseme: string; weight: number; talking: boolean; level: number | null };
