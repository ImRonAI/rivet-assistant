/* Emrys app bridge. Loaded after the robot page's own script, so it shares its top-level bindings
   (play, D, time, cam, stage, LETTER_VIS, and for the stage layer BONES, BN, mPt, proj, body, lowFx).
   The app drives it with window.emrys.{state,mouth,quiet,viseme,theme,focus,voice,level}; the page reports back to
   the app through the EmrysHost web message listener the app installs. The stage layer (android/stage-ui, loaded
   after this script) reads window.emrys.stageInfo() and window.emrys.speech(), and hears an `emrys:stage` event on
   document whenever stageInfo changes. */
(() => {
  /** @type {Record<string, string>} */
  const STATE_MOVE = {
    idle: 'idle', listening: 'idle', thinking: 'think', searching: 'think', working: 'desk',
    texting: 'text', emailing: 'email', calling: 'phone', waiting: 'idle', done: 'cheer', failed: 'shrug',
  };
  const html = document.documentElement;

  /** @param {{ type: string }} message */
  function post(message) {
    try { window.EmrysHost && window.EmrysHost.postMessage(JSON.stringify(message)); } catch (e) { }
  }

  /* the stage glows behind Emrys in the Noir status colour of what it is doing: glacier working, honey waiting on
     the user, sage done, coral listening (a live mic) or stuck */
  /** @type {Record<string, Mood>} */
  const MOOD = {
    listening: 'rec', thinking: 'cy', searching: 'cy', working: 'cy', texting: 'cy', emailing: 'cy',
    calling: 'cy', waiting: 'amb', done: 'ok', failed: 'rec',
  };
  const glow = document.createElement('style');
  glow.textContent = `
    .stage::before{content:"";position:absolute;inset:0;pointer-events:none;opacity:0;transition:opacity .8s ease;
      background:radial-gradient(38% 34% at 50% 46%,var(--mood-c,transparent),transparent 72%)}
    html[data-mood] .stage::before{opacity:.26;animation:moodbreathe 2.8s ease-in-out infinite}
    html[data-mood="cy"]{--mood-c:#8FDBE8} html[data-mood="amb"]{--mood-c:#E8C07C}
    html[data-mood="ok"]{--mood-c:#97D4A6} html[data-mood="rec"]{--mood-c:#F07B6C}
    html[data-mood] .plate-ring{stroke:var(--mood-c);stroke-opacity:.55;transition:stroke .8s ease}
    html[data-mood] .plate-tick{stroke:var(--mood-c);stroke-opacity:.45}
    html[data-halo] .stage::before{opacity:0;animation:none}
    @keyframes moodbreathe{50%{opacity:.14}}
    @media (prefers-reduced-motion: reduce){html[data-mood] .stage::before{animation:none}}`;
  document.head.appendChild(glow);

  let current = 'idle';
  /** @param {string} name */
  function state(name) {
    const id = STATE_MOVE[name] || 'idle';
    current = name in STATE_MOVE ? name : 'idle';
    const mood = MOOD[current];
    if (mood) html.setAttribute('data-mood', mood);
    else html.removeAttribute('data-mood');
    D.lastInput = time;
    if (D.cur.id !== id && D.settle !== id) play(id);
    publish();
  }

  /* What is being said, for the stage layer: the viseme on the mouth now and its weight (0 between letters), and
     whether words are being spoken, from the first word until the app says quiet. */
  const SPEECH = { viseme: 'sil', weight: 0, mouthing: false };
  const pageViseme = window.emrys.viseme;
  /** @param {string} key @param {number} value */
  function viseme(key, value) {
    pageViseme(key, value);
    const name = String(key).replace(/^viseme_/, '');
    const weight = Number(value) || 0;
    if (weight > 0) Object.assign(SPEECH, { viseme: name, weight });
    else if (name === SPEECH.viseme) SPEECH.weight = 0;
  }

  /* lip sync for one spoken word: the app calls this from TTS word boundaries */
  let mouthTimer = 0;
  /** @type {string | null} */
  let mouthKey = null;
  function quiet() {
    clearInterval(mouthTimer);
    if (mouthKey) window.emrys.viseme(mouthKey, 0);
    mouthKey = null;
  }
  /** @param {string} word @param {number} seconds */
  function mouth(word, seconds) {
    const letters = String(word || '').toLowerCase().replace(/[^a-z]/g, '');
    quiet();
    if (!letters) return;
    SPEECH.mouthing = true;
    const step = Math.max(40, ((seconds > 0 ? seconds : letters.length / 13) * 1000) / letters.length);
    let i = 0;
    mouthTimer = setInterval(() => {
      if (mouthKey) window.emrys.viseme(mouthKey, 0);
      if (i >= letters.length) { clearInterval(mouthTimer); mouthKey = null; return; }
      mouthKey = LETTER_VIS[letters.charAt(i++)] || 'nn';
      window.emrys.viseme(mouthKey, 0.85);
    }, step);
    publish();
  }
  /* the app's quiet also ends the speech (TTS done, the agent interrupted or listening again) */
  function hush() {
    quiet();
    Object.assign(SPEECH, { weight: 0, mouthing: false });
    publish();
  }

  /* The live voice of an ElevenLabs voice session: whose turn it is ('none' when no voice session runs) and the
     agent's audio level as the SDK reports it, 0..1 (null until it reports one; cleared when the session ends). */
  /** @type {{ turn: Turn, level: number | null }} */
  const VOICE = { turn: 'none', level: null };
  /** @param {string} turn */
  function voice(turn) {
    VOICE.turn = turn === 'listening' || turn === 'speaking' ? turn : 'none';
    if (VOICE.turn === 'none') VOICE.level = null;
    publish();
  }
  /** @param {number} value */
  function level(value) {
    const first = VOICE.level === null;
    VOICE.level = Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0;
    if (first) publish();
  }
  const talking = () => VOICE.turn === 'speaking' || SPEECH.mouthing;
  /** @returns {Speech} */
  function speech() {
    return { viseme: SPEECH.viseme, weight: SPEECH.weight, talking: talking(), level: VOICE.level };
  }

  /* the app's light/dark setting wins over the WebView's media query */
  /** @param {string} name */
  function theme(name) {
    document.documentElement.setAttribute('data-theme', name === 'dark' ? 'dark' : 'light');
  }

  /* camera framing: 'stage' fills a full-height stage, 'card' sits the robot in a short card, 'greet' stands it
     in the upper part of the stage so a greeting fits under its feet. ty is the look-at height the page eases
     back to at rest (a lower one lifts the robot in the frame); a double tap resets the view to zoom 1 and the
     framing is put back after it. */
  const FOCUS = { stage: { zoom: 1, ty: 1.1 }, card: { zoom: 1.3, ty: .86 }, greet: { zoom: 1.25, ty: .5 } };
  /* a short greeting stage (large text, small phones) stands Emrys further back so its feet stay above the words */
  const GREET_SHORT = { zoom: 1.5, ty: .34 };
  /** @type {keyof typeof FOCUS} */
  let focusMode = 'stage';
  let focusZoom = 1;
  function framing() {
    return focusMode === 'greet' && stage.clientHeight < 470 ? GREET_SHORT : FOCUS[focusMode];
  }
  /** @param {keyof typeof FOCUS} mode */
  function focus(mode) {
    if (!Object.prototype.hasOwnProperty.call(FOCUS, mode)) return;
    focusMode = mode;
    const f = framing();
    focusZoom = f.zoom;
    cam.rest = f.ty;
    cam.zoom = focusZoom;
    cam.dirty = true;
  }
  window.addEventListener('resize', () => { if (focusMode === 'greet') focus(focusMode); });
  stage.addEventListener('dblclick', () => { cam.zoom = focusZoom; cam.dirty = true; });

  /* Where Emrys stands on the stage, in stage pixels: the head (just above the head bone) and the feet, through the
     page's own camera. Null when the page has no such skeleton. */
  const view = document.getElementById('view');
  const svg = view instanceof SVGSVGElement ? view : null;
  /** @type {[number, number, number]} */
  const out = [0, 0, 0];
  /** @returns {Anchor | null} */
  function anchor() {
    const head = BONES[BN['head'] ?? -1];
    const screen = svg && svg.getScreenCTM();
    if (!head || !screen) return null;
    const box = stage.getBoundingClientRect();
    /** @param {[number, number, number]} p @returns {[number, number]} */
    const at = p => {
      proj(p[0], p[1], p[2], out);
      const q = new DOMPoint(out[0], out[1]).matrixTransform(screen);
      return [Math.round(q.x - box.left), Math.round(q.y - box.top)];
    };
    const [x, y] = at(mPt(head.W, 0, .22, 0));
    const [feetX, feetY] = at([body.x, 0, body.z]);
    return [x, y, feetX, feetY].every(Number.isFinite) ? { x, y, feetX, feetY } : null;
  }

  /** @returns {StageInfo} */
  function stageInfo() {
    return {
      state: current, mood: MOOD[current] ?? null, talking: talking(), turn: VOICE.turn, voiced: VOICE.level !== null,
      lowFx, width: stage.clientWidth, height: stage.clientHeight, short: stage.clientHeight < 200, anchor: anchor(),
    };
  }
  let info = stageInfo();
  let infoKey = '';
  /* What the stage layer draws from, published as an `emrys:stage` event when any of it changes; the anchor moves
     with Emrys and the page can drop to lowFx on its own, so it is also looked at four times a second. */
  function publish() {
    const next = stageInfo();
    if (next.talking) html.setAttribute('data-voice', 'speaking');
    else html.removeAttribute('data-voice');
    const key = JSON.stringify(next);
    if (key === infoKey) return;
    infoKey = key;
    info = next;
    document.dispatchEvent(new Event('emrys:stage'));
  }
  setInterval(publish, 250);

  Object.assign(window.emrys, { state, mouth, quiet: hush, viseme, theme, focus, voice, level, speech, stageInfo: () => info });
  publish();
  post({ type: 'ready' });
})();
