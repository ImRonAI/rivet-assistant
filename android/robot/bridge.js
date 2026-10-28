/* Rivet app bridge. Loaded after the robot page's own script, so it shares its top-level bindings
   (play, D, TRACE, EMAIL_LINES, THREAD, H$, emote, body, time, cam, stage).
   The app drives it with window.rivet.{state,trace,email,thread,say,mouth,quiet,viseme,theme,focus}; the page reports back to
   the app through the RivetHost web message listener the app installs. */
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

  function post(message) {
    try { window.RivetHost && window.RivetHost.postMessage(JSON.stringify(message)); } catch (e) { }
  }

  /* the stage glows behind Rivet in the colour of what it is doing: cyan working, amber waiting on the user,
     green done, crimson listening (a live sensor) or stuck */
  const MOOD = {
    listening: 'rec', thinking: 'cy', searching: 'cy', working: 'cy', texting: 'cy', emailing: 'cy',
    calling: 'cy', video: 'cy', waiting: 'amb', done: 'ok', failed: 'rec',
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
    current = name in STATE_MOVE ? name : 'idle';
    const mood = MOOD[current];
    if (mood) document.documentElement.setAttribute('data-mood', mood);
    else document.documentElement.removeAttribute('data-mood');
    D.lastInput = time;
    if (D.cur.id !== id && D.settle !== id) play(id);
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
    if (!text) return;
    emote(String(text).slice(0, 40), [body.x, 1.95, body.z], { vy: .25, life: 3.2, size: .9 });
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

  /* camera framing: 'stage' fills a full-height stage, 'card' sits the robot in a short card. The page eases its
     look-at height back to its own rest every frame, so only the zoom holds; a double tap resets the view to
     zoom 1 and the framing is put back after it. */
  const FOCUS = { stage: 1, card: .92 };
  let focusZoom = 1;
  function focus(mode) {
    if (!Object.prototype.hasOwnProperty.call(FOCUS, mode)) return;
    focusZoom = FOCUS[mode];
    cam.zoom = focusZoom;
    cam.dirty = true;
  }
  stage.addEventListener('dblclick', () => { cam.zoom = focusZoom; cam.dirty = true; });

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
  }
  setInterval(tick, 250);

  Object.assign(window.rivet, { state, trace, email, thread, say, mouth, quiet, theme, focus, states: () => Object.keys(STATE_MOVE) });
  post({ type: 'ready', moves: window.rivet.moves(), states: Object.keys(STATE_MOVE) });
})();
