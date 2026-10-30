/* Emrys app bridge. Loaded after the robot page's own script, so it shares its top-level bindings
   (play, D, TRACE, EMAIL_LINES, THREAD, H$, emote, body, time, cam, stage, and, where the page has them, BONES, BN,
   mPt, proj, lowFx).
   The app drives it with window.emrys.{state,trace,email,thread,say,mouth,quiet,viseme,theme,focus,voice,levels}; the
   page reports back to the app through the EmrysHost web message listener the app installs. The stage layer
   (android/stage-ui, loaded after this script) reads window.emrys.stageInfo() and window.emrys.speech() and hears
   a `emrys:stage` event on document whenever stageInfo changes. */
(() => {
  D.attract = false; // the robot moves only when the agent does something

  const STATE_MOVE = {
    idle: 'idle', listening: 'idle', greeting: 'wave', thinking: 'think', searching: 'think',
    working: 'desk', texting: 'text', emailing: 'email', calling: 'phone', video: 'video',
    done: 'cheer', failed: 'shrug', sleeping: 'sleep', waiting: 'idle',
  };
  /* one-shot scenes that should keep playing while the state holds */
  const HOLD = new Set(['think']);
  const TONES = new Set(['acc', 'cy', 'mut', 'ok', 'amb']);
  const THREAD_SLOTS = [0.8, 3.4, 4.0, 8.2];
  let current = 'idle';
  let emailMeta = null;
  let contact = null;
  const html = document.documentElement;

  function post(message) {
    try { window.EmrysHost && window.EmrysHost.postMessage(JSON.stringify(message)); } catch (e) { }
  }

  /* the stage glows behind Emrys in the Noir status colour of what it is doing: glacier working, honey waiting on
     the user, sage done, coral listening (a live mic) or stuck */
  const MOOD = {
    listening: 'rec', thinking: 'cy', searching: 'cy', working: 'cy', texting: 'cy', emailing: 'cy',
    calling: 'cy', video: 'cy', waiting: 'amb', done: 'ok', failed: 'rec',
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

  /* steps: [{tag, text, tone}] newest last; the trace panel shows the last seven */
  function trace(steps) {
    const rows = (steps || []).slice(-7).map(s => ({
      tag: String(s.tag || 'step').slice(0, 8),
      c: TONES.has(s.tone) ? s.tone : 'cy',
      text: String(s.text || '').slice(0, 44),
    }));
    TRACE.splice(0, TRACE.length, ...rows);
    if (H$.kind === 'think') H$.kind = null; // rebuild with the new rows
  }

  function email(o) {
    const lines = (o && o.lines ? o.lines : []).slice(0, 3).map(l => String(l).slice(0, 46));
    EMAIL_LINES.splice(0, EMAIL_LINES.length, ...(lines.length ? lines : ['']));
    emailMeta = { to: String((o && o.to) || ''), subject: String((o && o.subject) || '') };
  }

  /* messages: [{me: bool, text}] oldest first; the last four play out in the phone card */
  function thread(messages, name) {
    contact = name ? String(name).slice(0, 20) : null;
    const last = (messages || []).slice(-4);
    THREAD.splice(0, THREAD.length, ...last.map((m, i) => [THREAD_SLOTS[i], m.me ? 1 : 0, String(m.text || '').slice(0, 28)]));
    if (H$.kind === 'text') H$.kind = null;
  }

  function say(text) {
    // A bubble on a squeezed stage is too small to read.
    if (!text || stage.clientHeight < 200) return;
    emote(String(text).slice(0, 40), [body.x, 1.95, body.z], { vy: .25, life: 3.2, size: .9 });
  }

  /* What is being said, for the stage layer: the last viseme and its weight, and until when words are still being
     spoken (each word's length plus a breath, so the gaps between TTS words count as speech). */
  const SPEECH = { key: 'sil', weight: 0, at: 0, until: 0 };
  const now = () => performance.now();
  const pageViseme = window.emrys.viseme;
  function viseme(key, value) {
    pageViseme(key, value);
    const name = String(key).replace(/^viseme_/, '');
    const weight = +value || 0;
    if (weight > 0) Object.assign(SPEECH, { key: name, weight, at: now() });
    else if (name === SPEECH.key) SPEECH.weight = 0;
  }

  /* lip sync for one spoken word: the app calls this from TTS word boundaries */
  let mouthTimer = null;
  let mouthKey = null;
  function quiet() {
    clearInterval(mouthTimer);
    if (mouthKey) window.emrys.viseme(mouthKey, 0);
    mouthKey = null;
  }
  function mouth(word, seconds) {
    const letters = String(word || '').toLowerCase().replace(/[^a-z]/g, '');
    quiet();
    if (!letters) return;
    const length = seconds > 0 ? seconds : letters.length / 13;
    SPEECH.until = Math.max(SPEECH.until, now() + length * 1000 + 600);
    const step = Math.max(40, (length * 1000) / letters.length);
    let i = 0;
    mouthTimer = setInterval(() => {
      if (mouthKey) window.emrys.viseme(mouthKey, 0);
      if (i >= letters.length) { clearInterval(mouthTimer); mouthKey = null; return; }
      mouthKey = LETTER_VIS[letters[i++]] || 'nn';
      window.emrys.viseme(mouthKey, 0.85);
    }, step);
    publish();
  }
  /* the app's quiet ends the speech too (TTS done, the agent interrupted or listening again) */
  function hush() {
    quiet();
    SPEECH.until = 0;
    SPEECH.weight = 0;
    publish();
  }

  /* The live voice of an ElevenLabs agent session: whose turn it is ('listening' or 'speaking', null when no voice
     session runs), and the real levels the app measures, 0..1 (output: the agent's voice; input: how surely the user
     is speaking), null for a level the app doesn't have. Ending the session clears both. */
  const VOICE = { turn: null, output: null, input: null, at: 0 };
  const unit = v => (typeof v === 'number' && Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : null);
  function voice(turn) {
    VOICE.turn = turn === 'listening' || turn === 'speaking' ? turn : null;
    if (!VOICE.turn) Object.assign(VOICE, { output: null, input: null, at: 0 });
    publish();
  }
  function levels(output, input) {
    Object.assign(VOICE, { output: unit(output), input: unit(input), at: now() });
  }
  function speech() {
    return {
      key: SPEECH.key, weight: SPEECH.weight, at: SPEECH.at, talking: talking(),
      turn: VOICE.turn, output: VOICE.output, input: VOICE.input, levelAt: VOICE.at,
    };
  }
  const talking = () => VOICE.turn === 'speaking' || now() < SPEECH.until;

  /* the app's light/dark setting wins over the WebView's media query */
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
  let focusMode = 'stage';
  let focusZoom = 1;
  function framing() {
    return focusMode === 'greet' && stage.clientHeight < 470 ? GREET_SHORT : FOCUS[focusMode];
  }
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

  /* Where Emrys stands on the stage, in stage pixels: the head (just above the head bone) and the feet. Read through
     the page's own camera; null when the page draws no such skeleton. */
  const view = stage.querySelector(':scope > svg');
  const point = view && view.createSVGPoint ? view.createSVGPoint() : null;
  const out = [0, 0, 0];
  function anchor() {
    try {
      const m = view.getScreenCTM(), r = stage.getBoundingClientRect();
      const at = (x, y, z) => {
        proj(x, y, z, out);
        point.x = out[0]; point.y = out[1];
        const p = point.matrixTransform(m);
        return [Math.round(p.x - r.left), Math.round(p.y - r.top)];
      };
      const h = mPt(BONES[BN.head].W, 0, .22, 0);
      const [x, y] = at(h[0], h[1], h[2]);
      const [feetX, feetY] = at(body.x, 0, body.z);
      return [x, y, feetX, feetY].every(Number.isFinite) ? { x, y, feetX, feetY } : null;
    } catch (e) {
      return null;
    }
  }

  /* What the stage layer draws from, published as a `emrys:stage` event when any of it changes. */
  let info = null;
  let infoKey = '';
  function stageInfo() {
    let hud = false, low = false, move = null;
    try { hud = !!(H$.kind && H$.el && H$.a > .05); } catch (e) { }
    try { low = typeof lowFx !== 'undefined' && !!lowFx; } catch (e) { }
    try { move = D.cur.id || null; } catch (e) { }
    return {
      state: current, mood: MOOD[current] || null, talking: talking(), turn: VOICE.turn, hud, lowFx: low, move,
      width: stage.clientWidth, height: stage.clientHeight, short: stage.clientHeight < 200, anchor: anchor(),
    };
  }
  function publish() {
    const next = stageInfo();
    if (next.talking) html.setAttribute('data-voice', 'speaking');
    else html.removeAttribute('data-voice');
    const key = JSON.stringify(next);
    if (key === infoKey) return;
    infoKey = key;
    info = next;
    document.dispatchEvent(new CustomEvent('emrys:stage', { detail: info }));
  }

  function tick() {
    const id = STATE_MOVE[current];
    if (HOLD.has(id) && D.cur.id !== id && D.settle !== id) play(id);
    if (emailMeta && H$.kind === 'email' && H$.el) {
      const fields = H$.el.querySelectorAll('.card text.hud-t:not(.ebody)');
      if (fields[0] && fields[0].textContent !== emailMeta.to) fields[0].textContent = emailMeta.to;
      if (fields[1] && fields[1].textContent !== emailMeta.subject) fields[1].textContent = emailMeta.subject;
    }
    if (contact && H$.kind === 'text' && H$.el) {
      const title = H$.el.querySelector('.card .hud-h');
      if (title && title.textContent !== contact) title.textContent = contact;
    }
    publish();
  }
  setInterval(tick, 250);

  Object.assign(window.emrys, {
    state, trace, email, thread, say, mouth, quiet: hush, viseme, theme, focus, voice, levels, speech,
    stageInfo: () => info || stageInfo(), states: () => Object.keys(STATE_MOVE),
  });
  publish();
  post({ type: 'ready', moves: window.emrys.moves(), states: Object.keys(STATE_MOVE) });
})();
