/* What bridge.js and the stage layer use from the robot page (Emrys_the_Robot.html): its script's top-level bindings and
   window members. */
declare const D: { cur: { id: string }; settle: string | null; lastInput: number };
declare let time: number;
declare const cam: { rest: number; zoom: number; dirty: boolean };
declare const stage: HTMLElement;
declare const LETTER_VIS: { readonly [letter: string]: string };
declare function play(id: string): void;

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
