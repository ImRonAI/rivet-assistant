/* Rivet app bridge. Loaded after the robot page's own script, so it shares its top-level bindings
   (play, D, time, cam, stage, LETTER_VIS).
   The app drives it with window.rivet.{state,mouth,quiet,viseme,theme,focus}; the page reports back to
   the app through the RivetHost web message listener the app installs. */
(() => {
  const STATE_MOVE = {
    idle: 'idle', listening: 'idle', thinking: 'think', searching: 'think', working: 'desk',
    texting: 'text', emailing: 'email', calling: 'phone', waiting: 'idle', done: 'cheer', failed: 'shrug',
  };

  function post(message) {
    try { window.RivetHost && window.RivetHost.postMessage(JSON.stringify(message)); } catch (e) { }
  }

  /* the stage glows behind Rivet in the colour of what it is doing: cyan working, amber waiting on the user,
     green done, crimson listening (a live sensor) or stuck */
  const MOOD = {
    listening: 'rec', thinking: 'cy', searching: 'cy', working: 'cy', texting: 'cy', emailing: 'cy',
    calling: 'cy', waiting: 'amb', done: 'ok', failed: 'rec',
  };
  const glow = document.createElement('style');
  glow.textContent = `
    .stage::before{content:"";position:absolute;inset:0;pointer-events:none;opacity:0;transition:opacity .8s ease;
      background:radial-gradient(38% 34% at 50% 46%,var(--mood-c,transparent),transparent 72%)}
    html[data-mood] .stage::before{opacity:.26;animation:moodbreathe 2.8s ease-in-out infinite}
    html[data-mood="cy"]{--mood-c:var(--cy)} html[data-mood="amb"]{--mood-c:var(--amb)}
    html[data-mood="ok"]{--mood-c:var(--ok)} html[data-mood="rec"]{--mood-c:var(--rec)}
    html[data-mood] .plate-ring{stroke:var(--mood-c);stroke-opacity:.55;transition:stroke .8s ease}
    html[data-mood] .plate-tick{stroke:var(--mood-c);stroke-opacity:.45}
    @keyframes moodbreathe{50%{opacity:.14}}
    @media (prefers-reduced-motion: reduce){html[data-mood] .stage::before{animation:none}}`;
  document.head.appendChild(glow);

  function state(name) {
    const id = STATE_MOVE[name] || 'idle';
    const current = name in STATE_MOVE ? name : 'idle';
    const mood = MOOD[current];
    if (mood) document.documentElement.setAttribute('data-mood', mood);
    else document.documentElement.removeAttribute('data-mood');
    D.lastInput = time;
    if (D.cur.id !== id && D.settle !== id) play(id);
  }

  /* lip sync for one spoken word: the app calls this from TTS word boundaries */
  let mouthTimer = null;
  let mouthKey = null;
  function quiet() {
    clearInterval(mouthTimer);
    if (mouthKey) window.rivet.viseme(mouthKey, 0);
    mouthKey = null;
  }
  function mouth(word, seconds) {
    const letters = String(word || '').toLowerCase().replace(/[^a-z]/g, '');
    quiet();
    if (!letters) return;
    const step = Math.max(40, ((seconds > 0 ? seconds : letters.length / 13) * 1000) / letters.length);
    let i = 0;
    mouthTimer = setInterval(() => {
      if (mouthKey) window.rivet.viseme(mouthKey, 0);
      if (i >= letters.length) { clearInterval(mouthTimer); mouthKey = null; return; }
      mouthKey = LETTER_VIS[letters[i++]] || 'nn';
      window.rivet.viseme(mouthKey, 0.85);
    }, step);
  }

  /* the app's light/dark setting wins over the WebView's media query */
  function theme(name) {
    document.documentElement.setAttribute('data-theme', name === 'dark' ? 'dark' : 'light');
  }

  /* camera framing: 'stage' fills a full-height stage, 'card' sits the robot in a short card, 'greet' stands it
     in the upper part of the stage so a greeting fits under its feet. ty is the look-at height the page eases
     back to at rest (a lower one lifts the robot in the frame); a double tap resets the view to zoom 1 and the
     framing is put back after it. */
  const FOCUS = { stage: { zoom: 1, ty: 1.1 }, card: { zoom: 1.3, ty: .86 }, greet: { zoom: 1.25, ty: .5 } };
  /* a short greeting stage (large text, small phones) stands Rivet further back so its feet stay above the words */
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

  Object.assign(window.rivet, { state, mouth, quiet, theme, focus });
  post({ type: 'ready' });
})();
