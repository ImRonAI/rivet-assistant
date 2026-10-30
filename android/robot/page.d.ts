/* What bridge.js uses from the robot page (Rivet_the_Robot.html): its script's top-level bindings and window members. */
declare const D: { cur: { id: string }; settle: string | null; lastInput: number };
declare let time: number;
declare const cam: { rest: number; zoom: number; dirty: boolean };
declare const stage: HTMLElement;
declare const LETTER_VIS: { readonly [letter: string]: string };
declare function play(id: string): void;

interface Window {
  rivet: { viseme(key: string, value: number): void };
  RivetHost?: { postMessage(message: string): void };
}
