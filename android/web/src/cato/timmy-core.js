/* eslint-disable */
// @ts-nocheck
/*
 * Cato core: the owner's Timmy.html robot ("Rivet"), verbatim — procedural
 * geometry, skeleton, rig evaluation, move library, props and face decals —
 * recoloured to Cato blue/white and renamed. Generated from Timmy.html; the
 * SVG painter, its frame loop, the dock UI and pointer input are removed.
 * three.js draws it (see CatoBody.tsx): each mesh is rigid on one bone, so a
 * mesh's world matrix is its bone's 3x4 matrix W. Decals (eyes, mouth, chest
 * core, prop screens) are recorded per frame as world-space polygons.
 * Its SVG scaffold is kept: CatoBody mounts it over the canvas and shows only
 * the cards (#gHud) and emotes (#gFx), projected with the three.js camera.
 */
export function createCatoCore(opts = {}) {
const host = document.createElement('div');
host.innerHTML = '<div id="stage">' + "<svg id=\"view\" class=\"cato-host\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"Rivet the robot\">\n      <defs>\n        <filter id=\"fShadow\" x=\"-60%\" y=\"-60%\" width=\"220%\" height=\"220%\"><feGaussianBlur stdDeviation=\"7\"/></filter>\n        <filter id=\"fContact\" x=\"-60%\" y=\"-60%\" width=\"220%\" height=\"220%\"><feGaussianBlur stdDeviation=\"3\"/></filter>\n        <filter id=\"fGlow\" x=\"-100%\" y=\"-100%\" width=\"300%\" height=\"300%\"><feGaussianBlur stdDeviation=\"5\"/></filter>\n        <linearGradient id=\"hfT\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop class=\"hf0\" offset=\"0\"/><stop class=\"hf1\" offset=\"1\"/></linearGradient>\n        <linearGradient id=\"hfB\" x1=\"0\" y1=\"1\" x2=\"0\" y2=\"0\"><stop class=\"hf0\" offset=\"0\"/><stop class=\"hf1\" offset=\"1\"/></linearGradient>\n        <radialGradient id=\"gPool\"><stop offset=\"0\" stop-color=\"#fff\" stop-opacity=\"0\"/><stop offset=\"1\" stop-color=\"#fff\" stop-opacity=\"0\"/></radialGradient>\n        <linearGradient id=\"gVisor\" x1=\"0.1\" y1=\"0\" x2=\"0.45\" y2=\"1\">\n          <stop offset=\"0\" stop-color=\"#2B3A4C\"/><stop offset=\".42\" stop-color=\"#0C121A\"/><stop offset=\"1\" stop-color=\"#04060A\"/>\n        </linearGradient>\n      </defs>\n      <g id=\"gFloor\"></g>\n      <path id=\"pPool\" fill=\"url(#gPool)\"/>\n      <g id=\"gShadow\" filter=\"url(#fShadow)\"><path id=\"pShadow\"/></g>\n      <g id=\"gContact\" filter=\"url(#fContact)\"><path id=\"pContact\"/></g>\n      <g id=\"gMesh\"></g>\n      <g id=\"gGlow\" filter=\"url(#fGlow)\"></g>\n      <g id=\"gFx\"></g>\n      <g id=\"gHud\"></g>\n    </svg>" + '<nav id="dock"></nav></div>';
const hooks = { onMode: null };
const DECALS = [];
let scrub = null, HUD_MIN_X = 14;

/* =========================================================================
   Cato — a real-time 3D robot rendered entirely as SVG polygons.
   Core: math, easing, affine matrices, procedural geometry.
   ========================================================================= */
const PI = Math.PI, TAU = PI * 2;
const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
const sat = v => v < 0 ? 0 : v > 1 ? 1 : v;
const lerp = (a, b, t) => a + (b - a) * t;
const ramp = (t, a, b) => sat((t - a) / (b - a));
const smooth = t => { t = sat(t); return t * t * (3 - 2 * t); };
const smoother = t => { t = sat(t); return t * t * t * (t * (t * 6 - 15) + 10); };
const easeOut = t => { t = sat(t); return 1 - (1 - t) ** 3; };
const easeIn = t => { t = sat(t); return t * t * t; };
const easeIO = t => { t = sat(t); return t < .5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2; };
const backOut = (t, s = 1.7) => { t = sat(t); const u = t - 1; return 1 + (s + 1) * u * u * u + s * u * u; };
const wrapA = a => { a = (a + PI) % TAU; if (a < 0) a += TAU; return a - PI; };
const lerpA = (a, b, t) => a + wrapA(b - a) * t;
/* window: rises a→b, holds, falls c→d (all smooth) */
const win = (t, a, b, c, d) => smooth(ramp(t, a, b)) * (1 - smooth(ramp(t, c, d)));
/* keyframe track: [[time,value],...] with smoother easing between keys */
function track(t, k, ease = smoother) {
  if (t <= k[0][0]) return k[0][1];
  for (let i = 1; i < k.length; i++) {
    if (t < k[i][0]) { const a = k[i - 1], b = k[i]; return lerp(a[1], b[1], ease((t - a[0]) / (b[0] - a[0]))); }
  }
  return k[k.length - 1][1];
}
/* critically damped approach (frame-rate independent) */
const damp = (cur, target, rate, dt) => lerp(cur, target, 1 - Math.exp(-rate * dt));
const dampA = (cur, target, rate, dt) => lerpA(cur, target, 1 - Math.exp(-rate * dt));
/* deterministic hash noise for idle variation */
const hash = n => { const s = Math.sin(n * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };
const noise1 = x => { const i = Math.floor(x), f = x - i, u = f * f * (3 - 2 * f); return lerp(hash(i), hash(i + 1), u) * 2 - 1; };

/* ---------- affine 3x4 matrices: [r00 r01 r02 tx | r10 r11 r12 ty | r20 r21 r22 tz] ---------- */
const M = () => { const m = new Float64Array(12); m[0] = m[5] = m[10] = 1; return m; };
function mCopy(a, o) { for (let i = 0; i < 12; i++) o[i] = a[i]; return o; }
function mMul(a, b, o) {
  const a0 = a[0], a1 = a[1], a2 = a[2], a3 = a[3], a4 = a[4], a5 = a[5], a6 = a[6], a7 = a[7], a8 = a[8], a9 = a[9], a10 = a[10], a11 = a[11];
  const b0 = b[0], b1 = b[1], b2 = b[2], b3 = b[3], b4 = b[4], b5 = b[5], b6 = b[6], b7 = b[7], b8 = b[8], b9 = b[9], b10 = b[10], b11 = b[11];
  o[0] = a0 * b0 + a1 * b4 + a2 * b8; o[1] = a0 * b1 + a1 * b5 + a2 * b9; o[2] = a0 * b2 + a1 * b6 + a2 * b10; o[3] = a0 * b3 + a1 * b7 + a2 * b11 + a3;
  o[4] = a4 * b0 + a5 * b4 + a6 * b8; o[5] = a4 * b1 + a5 * b5 + a6 * b9; o[6] = a4 * b2 + a5 * b6 + a6 * b10; o[7] = a4 * b3 + a5 * b7 + a6 * b11 + a7;
  o[8] = a8 * b0 + a9 * b4 + a10 * b8; o[9] = a8 * b1 + a9 * b5 + a10 * b9; o[10] = a8 * b2 + a9 * b6 + a10 * b10; o[11] = a8 * b3 + a9 * b7 + a10 * b11 + a11;
  return o;
}
/* Euler, order R = Rz(z)·Rx(x)·Ry(y), plus translation */
function mEuler(x, y, z, tx, ty, tz, o) {
  const sa = Math.sin(x), ca = Math.cos(x), sb = Math.sin(y), cb = Math.cos(y), sc = Math.sin(z), cc = Math.cos(z);
  o[0] = cc * cb - sc * sa * sb; o[1] = -sc * ca; o[2] = cc * sb + sc * sa * cb; o[3] = tx;
  o[4] = sc * cb + cc * sa * sb; o[5] = cc * ca; o[6] = sc * sb - cc * sa * cb; o[7] = ty;
  o[8] = -ca * sb; o[9] = sa; o[10] = ca * cb; o[11] = tz;
  return o;
}
/* inverse of mEuler's rotation part → [x,y,z] */
function eulerOf(m) { return [Math.asin(clamp(m[9], -1, 1)), Math.atan2(-m[8], m[10]), Math.atan2(-m[1], m[5])]; }
function mRotY(a, o) { const s = Math.sin(a), c = Math.cos(a); o[0] = c; o[1] = 0; o[2] = s; o[3] = 0; o[4] = 0; o[5] = 1; o[6] = 0; o[7] = 0; o[8] = -s; o[9] = 0; o[10] = c; o[11] = 0; return o; }
function mRotX(a, o) { const s = Math.sin(a), c = Math.cos(a); o[0] = 1; o[1] = 0; o[2] = 0; o[3] = 0; o[4] = 0; o[5] = c; o[6] = -s; o[7] = 0; o[8] = 0; o[9] = s; o[10] = c; o[11] = 0; return o; }
function mRotZ(a, o) { const s = Math.sin(a), c = Math.cos(a); o[0] = c; o[1] = -s; o[2] = 0; o[3] = 0; o[4] = s; o[5] = c; o[6] = 0; o[7] = 0; o[8] = 0; o[9] = 0; o[10] = 1; o[11] = 0; return o; }
function mTrans(x, y, z, o) { o.fill(0); o[0] = o[5] = o[10] = 1; o[3] = x; o[7] = y; o[11] = z; return o; }
/* rotation-only transpose (inverse for orthonormal) */
function mRotT(m, o) { o[0] = m[0]; o[1] = m[4]; o[2] = m[8]; o[4] = m[1]; o[5] = m[5]; o[6] = m[9]; o[8] = m[2]; o[9] = m[6]; o[10] = m[10]; o[3] = o[7] = o[11] = 0; return o; }
const mPt = (m, x, y, z) => [m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]];
const mDir = (m, x, y, z) => [m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z];
const mDirT = (m, x, y, z) => [m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z];
const norm3 = v => { const l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
const cross3 = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const dot3 = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

/* ---------- procedural geometry: {p:[x,y,z,...], f:[[i,j,k,...],...]} with CCW-outward faces ---------- */
function roundBox(hx, hy, hz, r, k = 2, flat = 1) {
  r = Math.min(r, hx, hy, hz);
  const H = [hx, hy, hz], I = [hx - r, hy - r, hz - r];
  const axis = a => {
    const pos = []; for (let j = 0; j <= k; j++) pos.push(I[a] + r * Math.tan(j * PI / 4 / k));
    const out = []; for (let j = k; j >= 0; j--) out.push(-pos[j]);
    for (let s = 1; s < flat; s++) out.push(-I[a] + 2 * I[a] * s / flat);
    for (let j = 0; j <= k; j++) out.push(pos[j]);
    return out.filter((v, i, arr) => i === 0 || Math.abs(v - arr[i - 1]) > 1e-7);
  };
  const g = { p: [], f: [] };
  for (let a = 0; a < 3; a++) for (const s of [-1, 1]) {
    const u = (a + 1) % 3, v = (a + 2) % 3, U = axis(u), V = axis(v), base = g.p.length / 3, nu = U.length;
    for (let j = 0; j < V.length; j++) for (let i = 0; i < nu; i++) {
      const q = [0, 0, 0]; q[a] = s * H[a]; q[u] = U[i]; q[v] = V[j];
      const c = [clamp(q[0], -I[0], I[0]), clamp(q[1], -I[1], I[1]), clamp(q[2], -I[2], I[2])];
      const dx = q[0] - c[0], dy = q[1] - c[1], dz = q[2] - c[2], L = Math.hypot(dx, dy, dz) || 1;
      g.p.push(c[0] + dx / L * r, c[1] + dy / L * r, c[2] + dz / L * r);
    }
    for (let j = 0; j < V.length - 1; j++) for (let i = 0; i < nu - 1; i++) {
      const i0 = base + j * nu + i, q = [i0, i0 + 1, i0 + 1 + nu, i0 + nu];
      g.f.push(s > 0 ? q : q.reverse());
    }
  }
  return g;
}
/* surface of revolution around Y. prof: [[radius, y], ...] ordered top → bottom */
function lathe(prof, segs, capTop = true, capBot = true, phase = 0) {
  const g = { p: [], f: [] }, rings = [];
  for (const [r, y] of prof) {
    if (r < 1e-6) { rings.push({ pole: g.p.length / 3 }); g.p.push(0, y, 0); continue; }
    const start = g.p.length / 3;
    for (let j = 0; j < segs; j++) { const t = phase + j / segs * TAU; g.p.push(r * Math.cos(t), y, r * Math.sin(t)); }
    rings.push({ start });
  }
  for (let i = 0; i < rings.length - 1; i++) {
    const A = rings[i], B = rings[i + 1];
    if (A.pole !== undefined && B.pole !== undefined) continue;
    for (let j = 0; j < segs; j++) {
      const j1 = (j + 1) % segs;
      if (A.pole !== undefined) g.f.push([A.pole, B.start + j1, B.start + j]);
      else if (B.pole !== undefined) g.f.push([A.start + j, A.start + j1, B.pole]);
      else g.f.push([A.start + j, A.start + j1, B.start + j1, B.start + j]);
    }
  }
  const top = rings[0], bot = rings[rings.length - 1];
  if (capTop && top.start !== undefined) { const c = []; for (let j = segs - 1; j >= 0; j--) c.push(top.start + j); g.f.push(c); }
  if (capBot && bot.start !== undefined) { const c = []; for (let j = 0; j < segs; j++) c.push(bot.start + j); g.f.push(c); }
  return g;
}
function sphere(r, segs = 10, rings = 6) {
  const prof = []; for (let i = 0; i <= rings; i++) { const a = i / rings * PI; prof.push([Math.sin(a) * r, Math.cos(a) * r]); }
  return lathe(prof, segs);
}
/* tapered capsule hanging from y=0 down to y=-len (joint centres at both ends) */
function limb(r1, r2, len, segs = 10, cap = 2, mid = null) {
  const prof = [];
  for (let i = 0; i <= cap; i++) { const a = i / cap * PI / 2; prof.push([r1 * Math.sin(a), r1 * Math.cos(a)]); }
  if (mid) for (const [t, r] of mid) prof.push([r, -len * t]);
  for (let i = 0; i <= cap; i++) { const a = PI / 2 + i / cap * PI / 2; prof.push([r2 * Math.sin(a), -len + r2 * Math.cos(a)]); }
  return lathe(prof, segs);
}
/* cylinder along Y centred on origin */
const cyl = (r, h, segs = 12, phase = 0) => lathe([[r, h / 2], [r, -h / 2]], segs, true, true, phase);
/* extruded 2D outline (convex, CCW in xy) along z from z0 to z1; returns geo + index of front face */
function extrude(outline, z0, z1) {
  const g = { p: [], f: [] }, n = outline.length;
  for (const [x, y] of outline) g.p.push(x, y, z1);
  for (const [x, y] of outline) g.p.push(x, y, z0);
  const front = []; for (let i = 0; i < n; i++) front.push(i);
  const back = []; for (let i = n - 1; i >= 0; i--) back.push(n + i);
  for (let i = 0; i < n; i++) { const j = (i + 1) % n; g.f.push([i, n + i, n + j, j]); }
  g.f.push(back); g.f.push(front);
  return g;
}
/* rounded rectangle outline (CCW), half sizes w,h, corner radius r */
function rrect(w, h, r, steps = 4) {
  const pts = [], cs = [[w - r, h - r, 0], [-w + r, h - r, PI / 2], [-w + r, -h + r, PI], [w - r, -h + r, PI * 1.5]];
  for (const [cx, cy, a0] of cs) for (let i = 0; i <= steps; i++) { const a = a0 + i / steps * PI / 2; pts.push([cx + Math.cos(a) * r, cy + Math.sin(a) * r]); }
  return pts;
}

/* flip faces of a convex, origin-centred shape so normals point outward */
function orient(g) {
  for (const f of g.f) {
    let nx = 0, ny = 0, nz = 0, cx = 0, cy = 0, cz = 0;
    for (let k = 0; k < f.length; k++) {
      const a = f[k] * 3, b = f[(k + 1) % f.length] * 3, P = g.p;
      nx += (P[a + 1] - P[b + 1]) * (P[a + 2] + P[b + 2]); ny += (P[a + 2] - P[b + 2]) * (P[a] + P[b]); nz += (P[a] - P[b]) * (P[a + 1] + P[b + 1]);
      cx += P[a]; cy += P[a + 1]; cz += P[a + 2];
    }
    if (nx * cx + ny * cy + nz * cz < 0) f.reverse();
  }
  return g;
}
/* chamfered box: 6 faces, 12 bevels, 8 corner triangles (26 faces) */
function bevelBox(hx, hy, hz, b) {
  const g = { p: [], f: [] }, H = [hx, hy, hz], id = {};
  const v = (sg, a) => {
    const k = sg.join() + a; if (id[k] !== undefined) return id[k];
    const q = [sg[0] * hx, sg[1] * hy, sg[2] * hz];
    for (let i = 0; i < 3; i++) if (i !== a) q[i] -= sg[i] * Math.min(b, H[i] * .9);
    id[k] = g.p.length / 3; g.p.push(q[0], q[1], q[2]); return id[k];
  };
  for (let a = 0; a < 3; a++) for (const s of [-1, 1]) {
    const u = (a + 1) % 3, w = (a + 2) % 3, c = (su, sw) => { const sg = [0, 0, 0]; sg[a] = s; sg[u] = su; sg[w] = sw; return v(sg, a); };
    g.f.push([c(-1, -1), c(1, -1), c(1, 1), c(-1, 1)]);
  }
  for (let a = 0; a < 3; a++) for (let c = a + 1; c < 3; c++) {
    const d = 3 - a - c;
    for (const sa of [-1, 1]) for (const sc of [-1, 1]) {
      const sg = (sd) => { const o = [0, 0, 0]; o[a] = sa; o[c] = sc; o[d] = sd; return o; };
      g.f.push([v(sg(-1), a), v(sg(1), a), v(sg(1), c), v(sg(-1), c)]);
    }
  }
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) { const sg = [sx, sy, sz]; g.f.push([v(sg, 0), v(sg, 1), v(sg, 2)]); }
  return orient(g);
}
/* clip a convex polygon [[x,y],...] to x0 <= x <= x1 */
function clipX(poly, x0, x1) {
  const cut = (pts, keep, xc) => {
    const o = [];
    for (let i = 0; i < pts.length; i++) {
      const a = pts[i], b = pts[(i + 1) % pts.length], ka = keep(a[0]), kb = keep(b[0]);
      if (ka) o.push(a);
      if (ka !== kb) { const t = (xc - a[0]) / (b[0] - a[0]); o.push([xc, a[1] + (b[1] - a[1]) * t]); }
    }
    return o;
  };
  return cut(cut(poly, x => x >= x0, x0), x => x <= x1, x1);
}
/* screen panel: front split into vertical strips (local depth sorting), sides extruded back */
function panel(outline, z0, z1, strips) {
  const g = { p: [], f: [] }, xs = outline.map(q => q[0]), mn = Math.min(...xs), mx = Math.max(...xs);
  for (let i = 0; i < strips; i++) {
    const poly = clipX(outline, mn + (mx - mn) * i / strips, mn + (mx - mn) * (i + 1) / strips);
    const f = []; for (const [x, y] of poly) { f.push(g.p.length / 3); g.p.push(x, y, z1); } g.f.push(f);
  }
  const n = outline.length, b = g.p.length / 3;
  for (const [x, y] of outline) g.p.push(x, y, z1);
  for (const [x, y] of outline) g.p.push(x, y, z0);
  for (let i = 0; i < n; i++) { const j = (i + 1) % n; g.f.push([b + i, b + n + i, b + n + j, b + j]); }
  return g;
}
/* frame between an outer and an inner outline (same point count) at z1, outer sides back to z0 */
function ringFrame(outer, inner, z0, z1) {
  const g = { p: [], f: [] }, n = outer.length;
  for (const [x, y] of outer) g.p.push(x, y, z1);
  for (const [x, y] of inner) g.p.push(x, y, z1);
  for (const [x, y] of outer) g.p.push(x, y, z0);
  for (let i = 0; i < n; i++) { const j = (i + 1) % n; g.f.push([i, j, n + j, n + i]); g.f.push([i, 2 * n + i, 2 * n + j, j]); }
  return g;
}

/* ---------- quaternions [x,y,z,w] for rotation blending without gimbal trouble ---------- */
function quatFromMat(m) {
  const m00 = m[0], m01 = m[1], m02 = m[2], m10 = m[4], m11 = m[5], m12 = m[6], m20 = m[8], m21 = m[9], m22 = m[10], tr = m00 + m11 + m22;
  let x, y, z, w, s;
  if (tr > 0) { s = Math.sqrt(tr + 1) * 2; w = .25 * s; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s; }
  else if (m00 > m11 && m00 > m22) { s = Math.sqrt(1 + m00 - m11 - m22) * 2; w = (m21 - m12) / s; x = .25 * s; y = (m01 + m10) / s; z = (m02 + m20) / s; }
  else if (m11 > m22) { s = Math.sqrt(1 + m11 - m00 - m22) * 2; w = (m02 - m20) / s; x = (m01 + m10) / s; y = .25 * s; z = (m12 + m21) / s; }
  else { s = Math.sqrt(1 + m22 - m00 - m11) * 2; w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = .25 * s; }
  return [x, y, z, w];
}
function quatToMat(q, o) {
  const x = q[0], y = q[1], z = q[2], w = q[3];
  o[0] = 1 - 2 * (y * y + z * z); o[1] = 2 * (x * y - w * z); o[2] = 2 * (x * z + w * y);
  o[4] = 2 * (x * y + w * z); o[5] = 1 - 2 * (x * x + z * z); o[6] = 2 * (y * z - w * x);
  o[8] = 2 * (x * z - w * y); o[9] = 2 * (y * z + w * x); o[10] = 1 - 2 * (x * x + y * y);
  return o;
}
function nlerpQ(a, b, t) {
  const sg = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3] < 0 ? -1 : 1;
  const q = [a[0] + (sg * b[0] - a[0]) * t, a[1] + (sg * b[1] - a[1]) * t, a[2] + (sg * b[2] - a[2]) * t, a[3] + (sg * b[3] - a[3]) * t];
  const l = Math.hypot(q[0], q[1], q[2], q[3]) || 1; return [q[0] / l, q[1] / l, q[2] / l, q[3] / l];
}
/* blend two affine matrices: lerp translation, nlerp rotation */
function mBlend(a, b, t, o) {
  quatToMat(nlerpQ(quatFromMat(a), quatFromMat(b), t), o);
  o[3] = lerp(a[3], b[3], t); o[7] = lerp(a[7], b[7], t); o[11] = lerp(a[11], b[11], t); return o;
}
function mScale(m, s, o) { for (const i of [0, 1, 2, 4, 5, 6, 8, 9, 10]) o[i] = m[i] * s; o[3] = m[3]; o[7] = m[7]; o[11] = m[11]; return o; }

/* tube along a polyline of [x,y,z] points; rad is a number or per-point array */
function tube(pts, rad, sides = 6, caps = true) {
  const g = { p: [], f: [] }, n = pts.length, ctr = [];
  let N = null;
  for (let i = 0; i < n; i++) {
    const a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)], P = pts[i];
    const T = norm3([b[0] - a[0], b[1] - a[1], b[2] - a[2]]);
    if (!N) N = norm3(cross3(T, Math.abs(T[1]) < .9 ? [0, 1, 0] : [1, 0, 0]));
    else { const d = dot3(N, T); N = norm3([N[0] - T[0] * d, N[1] - T[1] * d, N[2] - T[2] * d]); }
    const B = cross3(T, N), r = Array.isArray(rad) ? rad[i] : rad;
    for (let j = 0; j < sides; j++) { const an = j / sides * TAU, c = Math.cos(an), s = Math.sin(an); g.p.push(P[0] + (N[0] * c + B[0] * s) * r, P[1] + (N[1] * c + B[1] * s) * r, P[2] + (N[2] * c + B[2] * s) * r); }
    ctr.push(P);
  }
  for (let i = 0; i < n - 1; i++) for (let j = 0; j < sides; j++) {
    const j1 = (j + 1) % sides, f = [i * sides + j, i * sides + j1, (i + 1) * sides + j1, (i + 1) * sides + j];
    /* orient outward from the local axis */
    const P = g.p, c = [0, 0, 0], ax = [(ctr[i][0] + ctr[i + 1][0]) / 2, (ctr[i][1] + ctr[i + 1][1]) / 2, (ctr[i][2] + ctr[i + 1][2]) / 2];
    let nx = 0, ny = 0, nz = 0;
    for (let k = 0; k < 4; k++) { const A = f[k] * 3, Bq = f[(k + 1) % 4] * 3; nx += (P[A + 1] - P[Bq + 1]) * (P[A + 2] + P[Bq + 2]); ny += (P[A + 2] - P[Bq + 2]) * (P[A] + P[Bq]); nz += (P[A] - P[Bq]) * (P[A + 1] + P[Bq + 1]); c[0] += P[A] / 4; c[1] += P[A + 1] / 4; c[2] += P[A + 2] / 4; }
    if (nx * (c[0] - ax[0]) + ny * (c[1] - ax[1]) + nz * (c[2] - ax[2]) < 0) f.reverse();
    g.f.push(f);
  }
  if (caps) {
    const c0 = [], c1 = [];
    for (let j = 0; j < sides; j++) { c0.push(j); c1.push((n - 1) * sides + j); }
    const t0 = norm3([pts[0][0] - pts[1][0], pts[0][1] - pts[1][1], pts[0][2] - pts[1][2]]);
    const chk = (f, out) => { const P = g.p; let nx = 0, ny = 0, nz = 0; for (let k = 0; k < f.length; k++) { const A = f[k] * 3, Bq = f[(k + 1) % f.length] * 3; nx += (P[A + 1] - P[Bq + 1]) * (P[A + 2] + P[Bq + 2]); ny += (P[A + 2] - P[Bq + 2]) * (P[A] + P[Bq]); nz += (P[A] - P[Bq]) * (P[A + 1] + P[Bq + 1]); } if (nx * out[0] + ny * out[1] + nz * out[2] < 0) f.reverse(); return f; };
    g.f.push(chk(c0, t0));
    const t1 = norm3([pts[n - 1][0] - pts[n - 2][0], pts[n - 1][1] - pts[n - 2][1], pts[n - 1][2] - pts[n - 2][2]]);
    g.f.push(chk(c1, t1));
  }
  return g;
}
/* merge several geos into one */
function merge(...gs) { const g = { p: [], f: [] }; for (const h of gs) { const b = g.p.length / 3; g.p.push(...h.p); for (const f of h.f) g.f.push(f.map(i => i + b)); } return g; }
/* extrude an arbitrary (possibly concave) outline along z, centred on z=0 with half-depth d */
const slab = (outline, d) => extrude(outline, -d, d);

/* apply a vertex function to a geo in place */
function warp(g, fn) { for (let i = 0; i < g.p.length; i += 3) { const v = fn(g.p[i], g.p[i + 1], g.p[i + 2]); g.p[i] = v[0]; g.p[i + 1] = v[1]; g.p[i + 2] = v[2]; } return g; }
/* place geometry with a 3x4 matrix */
function place(g, m) { return warp(g, (x, y, z) => mPt(m, x, y, z)); }
const E = (x, y, z, tx, ty, tz) => mEuler(x, y, z, tx, ty, tz, M());
/* =========================================================================
   Model: materials, skeleton, meshes, rig evaluation with leg IK.
   Conventions: +Y up, Cato faces +Z, its left side is +X. Units ≈ metres.
   Semantic joint angles: x<0 swings a limb forward; z>0 abducts (both sides);
   the rig mirrors y/z for the right side.
   ========================================================================= */
const toLin = c => Math.pow(c / 255, 2.2);
const hexLin = h => { const n = parseInt(h.slice(1), 16); return [toLin(n >> 16 & 255), toLin(n >> 8 & 255), toLin(n & 255)]; };
const MATS = [], MAT = {};
function defMat(name, hex, spec, pow, rim, extra = {}) { MAT[name] = MATS.length; MATS.push(Object.assign({ name, c: hexLin(hex), spec, pow, rim }, extra)); }
defMat('shell', '#ECEEF0', .40, 26, .50);
defMat('panel', '#CDD2D8', .34, 22, .45);
defMat('graphite', '#2B3038', .55, 36, .60);
defMat('accent', '#2E4BFF', .36, 22, .40);
defMat('rubber', '#17191D', .16, 10, .30);
defMat('metal', '#8E98A4', .95, 52, .65);
defMat('visor', '#0B111A', 1.25, 60, 0, { same: true });
defMat('bezel', '#1C2027', .45, 30, .50);
defMat('led', '#B9C9FF', 0, 1, 0, { led: true });

/* dimensions */
const PELVIS_Y = .915, HIP_X = .13, HIP_Y = -.06, THIGH = .37, SHIN = .39, ANKLE_H = .10;
const WAIST_Y = .10, NECK_Y = .54, HEAD_Y = .07, SHO_X = .30, SHO_Y = .42, SHO_OUT = .05, UPPER = .29, FORE = .27;
const ANT = { x: .105, y: .413, z: -.03, len: .19 };
const VISOR = { cy: .2, z: .238, w: .205, h: .121 };

/* ---------- skeleton ---------- */
const BONES = [], BN = {};
const boneNames = ['root', 'spine', 'neck', 'head', 'antenna'];
for (const s of ['L', 'R']) boneNames.push('clav' + s, 'arm' + s, 'fore' + s, 'hand' + s,
  'f0' + s, 'f0' + s + 'b', 'f1' + s, 'f1' + s + 'b', 'f2' + s, 'f2' + s + 'b', 'th' + s, 'th' + s + 'b',
  'thigh' + s, 'shin' + s, 'foot' + s, 'toe' + s);
function addBone(n) { BN[n] = BONES.length; BONES.push({ name: n, W: M() }); return BN[n]; }
for (const n of boneNames) addBone(n);

/* ---------- scene buffers ---------- */
const PART = { none: 0, head: 1, torso: 2, armL: 3, armR: 4, handL: 5, handR: 6, legL: 7, legR: 8, antenna: 9 };
const SC = { pos: [], meshes: [], fIdx: [], fStart: [], fLen: [], fMat: [], fPart: [], fBias: [], fMesh: [], anchors: {} };
function addPart(boneName, g, matName, o = {}) {
  const v0 = SC.pos.length / 3; for (const c of g.p) SC.pos.push(c);
  const mesh = { bone: BN[boneName], v0, v1: SC.pos.length / 3, shadow: o.shadow !== false, f0: SC.fStart.length, vis: true, prop: null };
  const mi = SC.meshes.length; SC.meshes.push(mesh);
  g.f.forEach((f, i) => {
    const fi = SC.fStart.length;
    SC.fStart.push(SC.fIdx.length); SC.fLen.push(f.length); for (const k of f) SC.fIdx.push(v0 + k);
    SC.fMat.push(MAT[o.matOf ? o.matOf(i, f) : matName]); SC.fPart.push(PART[o.part || 'none']); SC.fBias.push(o.bias || 0); SC.fMesh.push(mi);
    if (o.anchor && (o.anchorFaces ? o.anchorFaces.includes(i) : i === (o.anchorFace ?? g.f.length - 1))) (SC.anchors[o.anchor] ||= []).push(fi);
  });
  mesh.f1 = SC.fStart.length;
  return mesh;
}
const rotToZ = (g) => place(g, E(PI / 2, 0, 0, 0, 0, 0));   // lathe axis Y → Z (top → +Z)
const rotToX = (g) => place(g, E(0, 0, -PI / 2, 0, 0, 0));  // lathe axis Y → X (top → +X)

function buildCato() {
  /* pelvis + belt buckle + hip balls */
  addPart('root', place(roundBox(.20, .085, .15, .06, 2), E(0, 0, 0, 0, -.01, 0)), 'graphite', { part: 'torso' });
  addPart('root', place(bevelBox(.065, .035, .018, .011), E(0, 0, 0, 0, -.005, .148)), 'accent', { part: 'torso', bias: -.02 });
  for (const sg of [1, -1]) addPart('root', place(sphere(.074, 8, 5), E(0, 0, 0, sg * HIP_X, HIP_Y, 0)), 'graphite', { part: sg > 0 ? 'legL' : 'legR', shadow: false, bias: .03 });

  /* waist bellows */
  addPart('spine', lathe([[.118, .07], [.134, .05], [.118, .03], [.134, .01], [.118, -.01], [.13, -.03]], 14), 'graphite', { part: 'torso', shadow: false });
  /* torso: tapered rounded box */
  const torso = roundBox(.28, .25, .19, .11, 2, 1);
  warp(torso, (x, y, z) => { const t = (y + .25) / .5; const sx = lerp(.80, 1, smooth(t)), sz = lerp(.9, 1, t); return [x * sx, y + .29, z * sz]; });
  addPart('spine', torso, 'shell', { part: 'torso' });
  /* chest core housing (axis → Z), the core light is a decal on its front cap */
  addPart('spine', place(rotToZ(lathe([[.078, .028], [.084, .012], [.084, -.03]], 16)), E(0, 0, 0, 0, .33, .168)), 'graphite', { part: 'torso', anchor: 'core', anchorFace: 32, bias: -.02 });
  /* collar */
  addPart('spine', place(bevelBox(.17, .03, .11, .024), E(0, 0, 0, 0, .53, -.01)), 'panel', { part: 'torso' });
  /* backpack battery with accent stripes and exhaust caps */
  addPart('spine', place(bevelBox(.165, .15, .06, .036), E(0, 0, 0, 0, .30, -.215)), 'graphite', { part: 'torso', bias: -.01 });
  for (const sx of [-.07, .07]) addPart('spine', place(bevelBox(.022, .11, .012, .008), E(0, 0, 0, sx, .29, -.272)), 'accent', { part: 'torso', shadow: false, bias: -.03 });
  for (const sx of [-.1, .1]) addPart('spine', place(cyl(.026, .05, 8), E(0, 0, 0, sx, .465, -.215)), 'metal', { part: 'torso', shadow: false });

  /* neck */
  addPart('neck', lathe([[.055, .095], [.068, .078], [.056, .06], [.068, .042], [.056, .024], [.064, .0]], 10), 'metal', { part: 'head', shadow: false });
  /* head */
  /* head shell, minus the faces hidden behind the screen (keeps the painter's sort honest) */
  const head = place(roundBox(.28, .205, .227, .13, 3, 1), E(0, 0, 0, 0, .215, 0));
  const inRR = (x, y, W, H, r) => { x = Math.abs(x); y = Math.abs(y); return x <= W && y <= H && (x <= W - r || y <= H - r || (x - W + r) ** 2 + (y - H + r) ** 2 <= r * r); };
  head.f = head.f.filter(f => !f.every(i => head.p[i * 3 + 2] > .1 && inRR(head.p[i * 3], head.p[i * 3 + 1] - VISOR.cy, VISOR.w + .017, VISOR.h + .017, .09)));
  addPart('head', head, 'shell', { part: 'head' });
  const vOut = rrect(VISOR.w + .02, VISOR.h + .02, .094, 5), vIn = rrect(VISOR.w, VISOR.h, .076, 5);
  addPart('head', place(ringFrame(vOut, vIn, .186, VISOR.z - .004), E(0, 0, 0, 0, VISOR.cy, 0)), 'bezel', { part: 'head', bias: -.01 });
  addPart('head', place(panel(vIn, VISOR.z - .008, VISOR.z, 7), E(0, 0, 0, 0, VISOR.cy, 0)), 'visor', { part: 'head', anchor: 'visor', anchorFaces: [0, 1, 2, 3, 4, 5, 6], bias: -.012 });
  for (const sg of [1, -1]) {
    const pod = rotToX(lathe([[.05, .03], [.075, .022], [.078, -.022]], 14, true, false));
    addPart('head', place(sg > 0 ? pod : place(pod, E(0, 0, PI, 0, 0, 0)), E(0, 0, 0, sg * .282, .205, -.01)), 'accent', { part: 'head', bias: -.01 });
    addPart('head', place(rotToX(cyl(.034, .03, 10)), E(0, 0, 0, sg * .32, .205, -.01)), 'graphite', { part: 'head', shadow: false, bias: -.012 });
  }
  addPart('head', place(cyl(.032, .03, 10), E(0, 0, 0, ANT.x, ANT.y - .005, ANT.z)), 'graphite', { part: 'antenna', shadow: false });
  /* antenna (aimed bone, +Y along the stalk) */
  addPart('antenna', lathe([[.007, ANT.len - .02], [.011, .0]], 6), 'metal', { part: 'antenna', shadow: false });
  addPart('antenna', place(sphere(.03, 8, 5), E(0, 0, 0, 0, ANT.len, 0)), 'led', { part: 'antenna', shadow: false });

  for (const S of ['L', 'R']) {
    const sg = S === 'L' ? 1 : -1, pa = 'arm' + S, ph = 'hand' + S, pl = 'leg' + S;
    /* shoulder */
    addPart('clav' + S, place(sphere(.07, 8, 5), E(0, 0, 0, sg * SHO_OUT, 0, 0)), 'graphite', { part: pa, shadow: false, bias: .03 });
    const dome = lathe([[0, .1], [.055, .088], [.088, .058], [.104, .018], [.104, -.035]], 12, false, true);
    warp(dome, (x, y, z) => [x * 1.06 + sg * .012, y, z * .98]);
    addPart('arm' + S, place(dome, E(0, 0, 0, 0, .012, 0)), 'accent', { part: pa });
    addPart('arm' + S, limb(.066, .058, UPPER, 10, 2), 'shell', { part: pa });
    /* elbow + forearm (gauntlet) + cuff */
    addPart('fore' + S, sphere(.06, 8, 4), 'graphite', { part: pa, shadow: false, bias: .03 });
    addPart('fore' + S, limb(.056, .062, FORE, 10, 2, [[.55, .07], [.82, .068]]), 'shell', { part: pa });
    addPart('fore' + S, place(cyl(.077, .042, 12), E(0, 0, 0, 0, -.222, 0)), 'accent', { part: pa, shadow: false, bias: -.02 });
    /* hand: wrist, palm, three two-segment fingers, thumb */
    addPart('hand' + S, sphere(.04, 6, 4), 'graphite', { part: ph, shadow: false, bias: .02 });
    addPart('hand' + S, place(bevelBox(.056, .05, .029, .016), E(0, 0, 0, 0, -.062, 0)), 'graphite', { part: ph });
    for (let i = 0; i < 3; i++) {
      addPart(`f${i}${S}`, lathe([[.0165, .004], [.0165, -.045]], 6), 'graphite', { part: ph, shadow: false });
      addPart(`f${i}${S}b`, lathe([[.0155, .0], [.0155, -.028], [.009, -.043]], 6), 'graphite', { part: ph, shadow: false });
    }
    addPart('th' + S, lathe([[.018, .004], [.018, -.038]], 6), 'graphite', { part: ph, shadow: false });
    addPart('th' + S + 'b', lathe([[.016, .0], [.016, -.024], [.009, -.037]], 6), 'graphite', { part: ph, shadow: false });
    /* leg */
    addPart('thigh' + S, limb(.078, .068, THIGH, 10, 2, [[.3, .084]]), 'shell', { part: pl });
    addPart('shin' + S, sphere(.064, 8, 4), 'graphite', { part: pl, shadow: false, bias: .03 });
    addPart('shin' + S, place(bevelBox(.052, .056, .03, .020), E(-.12, 0, 0, 0, -.004, .07)), 'accent', { part: pl, shadow: false, bias: -.03 });
    addPart('shin' + S, limb(.064, .078, SHIN, 10, 2, [[.25, .074], [.6, .088]]), 'shell', { part: pl });
    addPart('shin' + S, place(bevelBox(.046, .09, .018, .013), E(-.07, 0, 0, 0, -.22, .086)), 'accent', { part: pl, shadow: false, bias: -.03 });
    /* ankle, foot, toe cap */
    addPart('foot' + S, sphere(.048, 6, 4), 'graphite', { part: pl, shadow: false, bias: .02 });
    addPart('foot' + S, place(bevelBox(.078, .049, .088, .029), E(0, 0, 0, 0, -.051, 0)), 'graphite', { part: pl });
    addPart('toe' + S, place(bevelBox(.076, .04, .05, .026), E(0, 0, 0, 0, .0, .03)), 'accent', { part: pl, bias: -.01 });
  }
}
buildCato();
const ROBOT_MESHES = SC.meshes.length;

/* ---------- rig evaluation ---------- */
const G = M(), T1 = M(), T2 = M(), T3 = M(), T4 = M(), T5 = M(), T6 = M(), T7 = M();
const look = { yaw: 0, pitch: 0, tYaw: 0, tPitch: 0, x: 0, y: 1.8, z: 3, w: 0, gx: 0, gy: 0 };
const ant = { p: null, v: [0, 0, 0] };
const rigOut = { gx: 0, gy: 0 };


/* 2-bone arm IK in the clavicle frame. Target = wrist, blended between body (G), spine and head frames.
   Pole (body frame) says which way the elbow points. Returns the upper-arm local rotation and elbow bend. */
const IKR = M();
function armIK(Sd, sg, p, C, H, S) {
  const x = p['h' + Sd + 'x'], y = p['h' + Sd + 'y'], z = p['h' + Sd + 'z'], fs = p['h' + Sd + 's'], fh = p['h' + Sd + 'h'];
  let t = mPt(G, x, y, z);
  if (fs > 0) { const q = mPt(S, x, y, z); t = [lerp(t[0], q[0], fs), lerp(t[1], q[1], fs), lerp(t[2], q[2], fs)]; }
  if (fh > 0) { const q = mPt(H, x, y, z); t = [lerp(t[0], q[0], fh), lerp(t[1], q[1], fh), lerp(t[2], q[2], fh)]; }
  const Tl = mDirT(C, t[0] - C[3], t[1] - C[7], t[2] - C[11]), s0 = sg * SHO_OUT, D = [Tl[0] - s0, Tl[1], Tl[2]];
  let d = Math.hypot(D[0], D[1], D[2]); if (d < 1e-5) return null;
  const u = [D[0] / d, D[1] / d, D[2] / d];
  d = clamp(d, .06, (UPPER + FORE) * .999);
  const ca = clamp((UPPER * UPPER + d * d - FORE * FORE) / (2 * UPPER * d), -1, 1), sa = Math.sqrt(1 - ca * ca);
  const e = PI - Math.acos(clamp((UPPER * UPPER + FORE * FORE - d * d) / (2 * UPPER * FORE), -1, 1));
  const pw = mDir(G, p['p' + Sd + 'x'], p['p' + Sd + 'y'], p['p' + Sd + 'z']), P = mDirT(C, pw[0], pw[1], pw[2]);
  const pd = dot3(P, u); let pp = [P[0] - u[0] * pd, P[1] - u[1] * pd, P[2] - u[2] * pd];
  const pl = Math.hypot(pp[0], pp[1], pp[2]);
  pp = pl < 1e-4 ? norm3(cross3(u, [1, 0, 0])) : [pp[0] / pl, pp[1] / pl, pp[2] / pl];
  const ua = [u[0] * ca + pp[0] * sa, u[1] * ca + pp[1] * sa, u[2] * ca + pp[2] * sa];
  const Ep = [ua[0] * UPPER, ua[1] * UPPER, ua[2] * UPPER], fd = norm3([u[0] * d - Ep[0], u[1] * d - Ep[1], u[2] * d - Ep[2]]);
  const fu = dot3(fd, ua);
  let zb = [fd[0] - ua[0] * fu, fd[1] - ua[1] * fu, fd[2] - ua[2] * fu];
  zb = Math.hypot(zb[0], zb[1], zb[2]) < 1e-4 ? [-pp[0], -pp[1], -pp[2]] : norm3(zb);
  const Y = [-ua[0], -ua[1], -ua[2]], X = cross3(Y, zb);
  IKR[0] = X[0]; IKR[1] = Y[0]; IKR[2] = zb[0]; IKR[3] = s0;
  IKR[4] = X[1]; IKR[5] = Y[1]; IKR[6] = zb[1]; IKR[7] = 0;
  IKR[8] = X[2]; IKR[9] = Y[2]; IKR[10] = zb[2]; IKR[11] = 0;
  return { R: IKR, e };
}

function setW(name, parentName, local) { const b = BONES[BN[name]]; mMul(BONES[BN[parentName]].W, local, b.W); return b.W; }

function evalRig(p, body, dt) {
  mRotY(body.heading, G); G[3] = body.x; G[11] = body.z;
  /* root: T · Ry(yaw) · Rx(pitch) · Rz(roll) */
  const R = BONES[BN.root].W;
  mMul(mRotY(p.yaw, T1), mRotX(p.pitch, T2), T3); mMul(T3, mRotZ(p.roll, T1), T2);
  T2[3] = p.rx; T2[7] = PELVIS_Y + p.ry; T2[11] = p.rz;
  mMul(G, T2, R);
  const S = setW('spine', 'root', mEuler(p.spX, p.spY, p.spZ, 0, WAIST_Y, 0, T1));

  /* look-at (smoothed, split between neck and head; eyes lead) */
  let lyaw = 0, lpitch = 0;
  {
    const np = mPt(S, 0, NECK_Y + .2, 0);
    const D = mDirT(S, look.x - np[0], look.y - np[1], look.z - np[2]);
    look.tYaw = clamp(Math.atan2(D[0], D[2]), -1.25, 1.25);
    look.tPitch = clamp(Math.atan2(D[1], Math.hypot(D[0], D[2])), -.55, .6);
    look.w = damp(look.w, p.look, 6, dt);
    look.yaw = damp(look.yaw, look.tYaw, 5.5, dt); look.pitch = damp(look.pitch, look.tPitch, 5.5, dt);
    lyaw = look.yaw * look.w; lpitch = look.pitch * look.w;
    const egx = clamp((look.tYaw - look.yaw) * 2.2 + look.tYaw * .35, -1, 1) * look.w;
    const egy = clamp((look.tPitch - look.pitch) * 2.2 + look.tPitch * .45, -1, 1) * look.w;
    rigOut.gx = lerp(p.gx, egx, look.w); rigOut.gy = lerp(p.gy, egy, look.w);
  }
  setW('neck', 'spine', mEuler(p.nkX - lpitch * .3, p.nkY + lyaw * .35, p.nkZ, 0, NECK_Y, 0, T1));
  const H = setW('head', 'neck', mEuler(p.hdX - lpitch * .7, p.hdY + lyaw * .65, p.hdZ, 0, HEAD_Y, 0, T1));

  /* antenna spring */
  {
    const base = mPt(H, ANT.x, ANT.y, ANT.z), rest = mPt(H, ANT.x, ANT.y + ANT.len, ANT.z);
    if (!ant.p) ant.p = rest.slice();
    const n = Math.max(1, Math.ceil(dt / .008)), h = Math.min(dt, .05) / n;
    for (let s = 0; s < n; s++) {
      for (let i = 0; i < 3; i++) { ant.v[i] += ((rest[i] - ant.p[i]) * 260 - ant.v[i] * 7.5) * h; ant.p[i] += ant.v[i] * h; }
      const d = norm3([ant.p[0] - base[0], ant.p[1] - base[1], ant.p[2] - base[2]]);
      for (let i = 0; i < 3; i++) ant.p[i] = base[i] + d[i] * ANT.len;
    }
    const y = norm3([ant.p[0] - base[0], ant.p[1] - base[1], ant.p[2] - base[2]]);
    const hz = [H[2], H[6], H[10]], x = norm3(cross3(y, hz)), z = cross3(x, y);
    const A = BONES[BN.antenna].W;
    A[0] = x[0]; A[1] = y[0]; A[2] = z[0]; A[3] = base[0];
    A[4] = x[1]; A[5] = y[1]; A[6] = z[1]; A[7] = base[1];
    A[8] = x[2]; A[9] = y[2]; A[10] = z[2]; A[11] = base[2];
  }

  for (const Sd of ['L', 'R']) {
    const sg = Sd === 'L' ? 1 : -1;
    /* arm chain */
    const C = setW('clav' + Sd, 'spine', mEuler(0, 0, 0, sg * SHO_X, SHO_Y + p['sh' + Sd] * .05, 0, T1));
    mEuler(p['a' + Sd + 'x'], sg * p['a' + Sd + 'y'], sg * p['a' + Sd + 'z'], sg * SHO_OUT, 0, 0, T1);
    let el = p['e' + Sd];
    const hw = p['h' + Sd + 'w'];
    if (hw > .001) { const ik = armIK(Sd, sg, p, C, H, S); if (ik) { mBlend(T1, ik.R, hw, T1); el = lerp(el, ik.e, hw); } }
    setW('arm' + Sd, 'clav' + Sd, T1);
    let twA = sg * p['tw' + Sd];
    let F = setW('fore' + Sd, 'arm' + Sd, mEuler(-el, twA, 0, 0, -UPPER, 0, T1));
    mMul(mEuler(0, -sg * PI / 2, 0, 0, -FORE, 0, T2), mEuler(p['w' + Sd + 'x'], 0, sg * p['w' + Sd + 'z'], 0, 0, 0, T3), T6);
    /* optional palm aim: twist the forearm so the palm faces a requested direction (body frame) */
    const nw = p['n' + Sd + 'w'];
    if (nw > .001) {
      mMul(F, T6, T5);
      const a = [F[1], F[5], F[9]], n0 = [T5[2], T5[6], T5[10]], dw = mDir(G, p['n' + Sd + 'x'], p['n' + Sd + 'y'], p['n' + Sd + 'z']);
      const na = dot3(n0, a), da = dot3(dw, a);
      const np = [n0[0] - a[0] * na, n0[1] - a[1] * na, n0[2] - a[2] * na], dp = [dw[0] - a[0] * da, dw[1] - a[1] * da, dw[2] - a[2] * da];
      const dl = Math.atan2(dot3(a, cross3(np, dp)), dot3(np, dp));
      twA += dl * nw;
      F = setW('fore' + Sd, 'arm' + Sd, mEuler(-el, twA, 0, 0, -UPPER, 0, T1));
    }
    mMul(F, T6, BONES[BN['hand' + Sd]].W);
    const c = p['c' + Sd], sp = p['sp' + Sd] || 0, tc = p['t' + Sd];
    for (let i = 0; i < 3; i++) {
      const fx = (i - 1) * .033 * sg, k = 1 + (i - 1) * .08 * sg;
      setW(`f${i}${Sd}`, 'hand' + Sd, mEuler(-c * 1.45 * k, 0, (i - 1) * sg * sp * .18, fx, -.102, 0, T1));
      setW(`f${i}${Sd}b`, `f${i}${Sd}`, mEuler(-c * 1.65, 0, 0, 0, -.047, 0, T1));
    }
    setW('th' + Sd, 'hand' + Sd, mEuler(-.3 - tc * .9, 0, sg * (.62 - tc * .75), sg * .048, -.034, .012, T1));
    setW('th' + Sd + 'b', 'th' + Sd, mEuler(-tc * 1.1, 0, 0, 0, -.04, 0, T1));

    /* leg chain: FK in actual angles, blended towards analytic IK */
    let lx = p['l' + Sd + 'x'], ly = sg * p['l' + Sd + 'y'], lz = sg * p['l' + Sd + 'z'], kn = p['k' + Sd];
    let ax = p['an' + Sd + 'x'], ay = 0, az = sg * p['an' + Sd + 'z'];
    const w = p['ik' + Sd];
    if (w > .001) {
      const hip = mPt(R, sg * HIP_X, HIP_Y, 0);
      const tg = mPt(G, p['f' + Sd + 'x'], p['f' + Sd + 'y'], p['f' + Sd + 'z']);
      const D = mDirT(R, tg[0] - hip[0], tg[1] - hip[1], tg[2] - hip[2]);
      let d = Math.hypot(D[0], D[1], D[2]); const u = [D[0] / d, D[1] / d, D[2] / d];
      d = clamp(d, .12, (THIGH + SHIN) * .9995);
      const x0 = -Math.asin(clamp(u[2], -1, 1)), z0 = Math.atan2(u[0], -u[1]);
      const al = Math.acos(clamp((THIGH * THIGH + d * d - SHIN * SHIN) / (2 * THIGH * d), -1, 1));
      const ki = Math.acos(clamp((THIGH * THIGH + SHIN * SHIN - d * d) / (2 * THIGH * SHIN), -1, 1));
      lx = lerp(lx, x0 - al, w); lz = lerpA(lz, z0, w); ly = lerp(ly, 0, w); kn = lerp(kn, PI - ki, w);
      /* foot: flat on the ground at the requested pitch/yaw */
      mMul(R, mEuler(lx, ly, lz, sg * HIP_X, HIP_Y, 0, T1), T2);
      mMul(T2, mEuler(kn, 0, 0, 0, -THIGH, 0, T1), T3);
      mMul(G, mMul(mRotY(p['f' + Sd + 'w'], T4), mRotX(p['f' + Sd + 'p'], T5), T6), T2);
      mRotT(T3, T7); mMul(T7, T2, T1);
      const e = eulerOf(T1);
      ax = lerp(ax, e[0], w); ay = lerp(0, e[1], w); az = lerp(az, e[2], w);
    }
    setW('thigh' + Sd, 'root', mEuler(lx, ly, lz, sg * HIP_X, HIP_Y, 0, T1));
    setW('shin' + Sd, 'thigh' + Sd, mEuler(kn, 0, 0, 0, -THIGH, 0, T1));
    setW('foot' + Sd, 'shin' + Sd, mEuler(ax, ay, az, 0, -SHIN, 0, T1));
    setW('toe' + Sd, 'foot' + Sd, mEuler(p['to' + Sd], 0, 0, 0, -.06, .07, T1));
  }

}
/* skin: transform every visible mesh by its bone */
function skin() {
  for (const m of SC.meshes) {
    if (!m.vis) continue;
    const W = BONES[m.bone].W;
    const a0 = W[0], a1 = W[1], a2 = W[2], a3 = W[3], a4 = W[4], a5 = W[5], a6 = W[6], a7 = W[7], a8 = W[8], a9 = W[9], a10 = W[10], a11 = W[11];
    for (let v = m.v0 * 3, e = m.v1 * 3; v < e; v += 3) {
      const x = LP[v], y = LP[v + 1], z = LP[v + 2];
      WP[v] = a0 * x + a1 * y + a2 * z + a3; WP[v + 1] = a4 * x + a5 * y + a6 * z + a7; WP[v + 2] = a8 * x + a9 * y + a10 * z + a11;
    }
  }
}
/* =========================================================================
   Props & costumes. Each prop is a set of meshes on its own bone. Scenes list
   the props they want; props pop in/out with a scale animation, and scenes may
   place dynamic props (a mug in hand, paper feeding into a fax) every frame.
   ========================================================================= */
defMat('woodTop', '#C9A47E', .30, 18, .35);
defMat('wood', '#9C6B43', .30, 18, .35);
defMat('woodDark', '#5B3A24', .28, 16, .35);
defMat('fabric', '#2F3B47', .10, 8, .45);
defMat('alu', '#C3C9D0', .80, 40, .55);
defMat('ink', '#16181C', .40, 30, .45);
defMat('mug', '#2C7A80', .50, 30, .40);
defMat('coffee', '#3B2416', .70, 40, .20);
defMat('beige', '#D7CDBC', .30, 18, .35);
defMat('paper', '#F4F4F1', .10, 8, .30);
defMat('felt', '#B98654', .14, 10, .40);
defMat('leather', '#4A2C1C', .30, 18, .35);
defMat('knit', '#2E4C5C', .08, 6, .45);
defMat('knit2', '#E9E2D2', .08, 6, .45);
defMat('beret', '#1E1F24', .10, 8, .45);
defMat('water', '#8FE3FF', 1.4, 60, .80);
defMat('canvas', '#F3F0E8', .06, 6, .30);
defMat('paint', '#2E4BFF', .60, 30, .30);

const PROPS = {}, PROP_LIST = [];
function defProp(id, at, build, extra = {}) {
  const bone = '@' + id; addBone(bone);
  const m0 = SC.meshes.length;
  build(bone);
  const m1 = SC.meshes.length;
  for (let i = m0; i < m1; i++) { SC.meshes[i].vis = false; SC.meshes[i].prop = id; }
  const P = Object.assign({ id, bone: BN[bone], m0, m1, at, pres: 0, want: 0, force: null, setNow: false, base: M(), placed: false, decals: null }, extra);
  PROPS[id] = P; PROP_LIST.push(P); return P;
}
/* polar helpers for outlines */
const arcPts = (cx, cy, r, a0, a1, n) => { const o = []; for (let i = 0; i <= n; i++) { const a = a0 + (a1 - a0) * i / n; o.push([cx + Math.cos(a) * r, cy + Math.sin(a) * r]); } return o; };
/* outline of a union of circles, star-shaped around (cx,cy) */
function radialUnion(circles, cx, cy, n = 40) {
  const o = [];
  for (let i = 0; i < n; i++) {
    const a = i / n * TAU, dx = Math.cos(a), dy = Math.sin(a); let best = 0;
    for (const [x, y, r] of circles) {
      const ox = cx - x, oy = cy - y, b = ox * dx + oy * dy, c = ox * ox + oy * oy - r * r, disc = b * b - c;
      if (disc >= 0) best = Math.max(best, -b + Math.sqrt(disc));
    }
    o.push([cx + dx * best, cy + dy * best]);
  }
  return o;
}

/* ---------------- office ---------------- */
defProp('chair', ['G', 0, 0, -.03, 0, 0, 0], b => {
  addPart(b, place(bevelBox(.22, .035, .21, .02), E(0, 0, 0, 0, .465, 0)), 'fabric', { shadow: true });
  addPart(b, place(bevelBox(.2, .16, .03, .02), E(-.1, 0, 0, 0, .83, -.34)), 'fabric');
  addPart(b, place(bevelBox(.03, .13, .018, .008), E(-.05, 0, 0, 0, .6, -.31)), 'graphite', { shadow: false });
  addPart(b, place(cyl(.028, .34, 8), E(0, 0, 0, 0, .28, 0)), 'metal', { shadow: false });
  for (let k = 0; k < 5; k++) {
    const a = k / 5 * TAU + .3;
    addPart(b, place(bevelBox(.022, .014, .19, .008), E(0, a, 0, Math.sin(a) * .12, .07, Math.cos(a) * .12)), 'graphite', { shadow: false });
    addPart(b, place(cyl(.022, .03, 6), E(0, 0, 0, Math.sin(a) * .29, .022, Math.cos(a) * .29)), 'ink', { shadow: false });
  }
});
defProp('desk', ['G', 0, 0, .46, 0, 0, 0], b => {
  addPart(b, place(roundBox(.62, .022, .29, .01, 1, 4), E(0, 0, 0, 0, .733, 0)), 'woodTop', { bias: .1 });
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) addPart(b, place(bevelBox(.022, .355, .022, .008), E(0, 0, 0, sx * .58, .357, sz * .25)), 'graphite', { shadow: false });
  addPart(b, place(bevelBox(.58, .21, .012, .006), E(0, 0, 0, 0, .49, .262)), 'woodDark', { bias: .02 });
  /* a little potted plant */
  addPart(b, place(lathe([[.045, .06], [.04, .0], [.032, -.0]], 8, true, true), E(0, 0, 0, .48, .79, -.1)), 'accent');
  for (let k = 0; k < 3; k++) addPart(b, place(warp(sphere(.03, 6, 4), (x, y, z) => [x * .7, y * 2.3, z * .7]), E(.25 * (k - 1), 0, .3 * (k - 1), .48 + (k - 1) * .02, .9 + (k === 1 ? .03 : 0), -.1)), 'mug', { shadow: false });
});
const LID = E(.26, 0, 0, 0, .008 + .118 * Math.cos(.26), .118 + .118 * Math.sin(.26));
defProp('laptop', ['G', 0, .757, .38, 0, 0, 0], b => {
  addPart(b, bevelBox(.17, .008, .118, .005), 'alu', { anchor: 'kbd', anchorFace: 3 });
  addPart(b, place(bevelBox(.17, .118, .005, .004), LID), 'alu', { anchor: 'lidBack', anchorFace: 5 });
  SC.anchors.lidScreen = [SC.anchors.lidBack[0] - 1];
}, {
  decals(t, s) {
    const W = BONES[this.bone].W;
    /* keyboard */
    const kb = (u, v) => [u, .0085, v];
    decal('kbd', W, [[[-.15, -.004], [.15, -.004], [.15, .1], [-.15, .1]]], kb, 'rgb(40,44,52)', 1, 0, 1);
    decal('kbd', W, [[[-.05, -.1], [.05, -.1], [.05, -.03], [-.05, -.03]]], kb, 'rgb(150,158,168)', .55, 0, 2);
    const key = []; for (let r = 0; r < 4; r++) for (let c = 0; c < 11; c++) { const u = -.135 + c * .027, v = .085 - r * .024; key.push([[u, v], [u + .02, v], [u + .02, v - .017], [u, v - .017]]); }
    decal('kbd', W, key, kb, 'rgb(78,84,96)', 1, 0, 2);
    /* screen (faces Cato) */
    const sc = (u, v) => mPt(LID, -u, v, -.0052);
    decal('lidScreen', W, [[[-.158, -.105], [.158, -.105], [.158, .105], [-.158, .105]]], sc, 'rgb(16,28,46)', 1, .25, 1);
    const ln = []; for (let i = 0; i < 8; i++) { const w = .05 + ((i * 37) % 9) * .02, x0 = -.14 + (i % 3) * .02; ln.push([[x0, .085 - i * .022], [x0 + w, .085 - i * .022], [x0 + w, .076 - i * .022], [x0, .076 - i * .022]]); }
    decal('lidScreen', W, ln, sc, 'rgb(110,200,255)', .8, .3, 2);
    /* lid sticker, facing the audience */
    const lb = (u, v) => mPt(LID, u, v, .0052);
    decal('lidBack', W, [circle(0, .01, .032, 16)], lb, 'rgb(46,75,255)', 1, 0, 1);
    decal('lidBack', W, [[[-.012, .03], [.012, .03], [.012, -.01], [-.012, -.01]], [[-.02, .022], [.02, .022], [.02, .014], [-.02, .014]]], lb, 'rgb(255,255,255)', .9, 0, 2);
  }
});
defProp('mug', ['G', -.25, .757, .31, 0, PI, 0], b => {
  addPart(b, lathe([[.035, .095], [.036, .0]], 12, true, true), 'mug', { matOf: (i, f) => i === 12 ? 'coffee' : 'mug' });
  const h = []; for (let i = 0; i <= 6; i++) { const a = -PI / 2 + i / 6 * PI; h.push([.036 + Math.cos(a) * .026, .048 + Math.sin(a) * .028, 0]); }
  addPart(b, tube(h, .0075, 6, false), 'mug', { shadow: false });
});
defProp('fax', ['G', -.43, .757, .33, 0, .35, 0], b => {
  addPart(b, place(bevelBox(.15, .045, .12, .018), E(0, 0, 0, 0, .045, 0)), 'beige', { anchor: 'faxTop', anchorFace: 3 });
  addPart(b, place(bevelBox(.11, .006, .07, .004), E(-.55, 0, 0, 0, .11, -.13)), 'ink', { shadow: false });
  addPart(b, place(bevelBox(.035, .03, .1, .015), E(0, 0, 0, .19, .05, 0)), 'ink', { shadow: false });
}, {
  decals(t) {
    const W = BONES[this.bone].W, top = (u, v) => [u, .0905, v];
    decal('faxTop', W, [[[-.13, -.02], [-.02, -.02], [-.02, -.09], [-.13, -.09]]], top, 'rgb(28,38,30)', 1, 0, 1);
    decal('faxTop', W, [[[-.12, -.03], [-.03, -.03], [-.03, -.06], [-.12, -.06]]], top, faxLcd(t), 1, .5, 2);
    const keys = []; for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) keys.push(circle(.02 + c * .03, -.03 - r * .025, .009, 8));
    decal('faxTop', W, keys, top, 'rgb(60,64,72)', 1, 0, 2);
    decal('faxTop', W, [circle(.11, -.075, .012, 10)], top, 'rgb(70,200,120)', 1, .4, 2);
  }
});
let FAX_LCD = 'rgb(140,190,120)';
const faxLcd = () => FAX_LCD;
defProp('paper', ['G', -.43, .87, .2, 0, 0, 0], b => {
  addPart(b, bevelBox(.075, .0012, .1, .0008), 'paper', { shadow: false, anchor: 'paper', anchorFace: 3, bias: -.01 });
}, {
  decals() {
    const W = BONES[this.bone].W, top = (u, v) => [u, .0013, v], lines = [];
    for (let i = 0; i < 7; i++) { const w = i % 3 === 2 ? .03 : .055; lines.push([[-.06, .075 - i * .02], [-.06 + w, .075 - i * .02], [-.06 + w, .07 - i * .02], [-.06, .07 - i * .02]]); }
    decal('paper', W, lines, top, 'rgb(90,96,106)', .8, 0, 1);
  }
});
defProp('phone', ['handR', 0, -.08, .03, 0, 0, 0], b => {
  addPart(b, bevelBox(.058, .118, .007, .004), 'ink', { shadow: false, anchor: 'phoneFront', anchorFace: 5 });
  addPart(b, place(bevelBox(.056, .116, .0016, .0012), E(0, 0, 0, 0, 0, -.0082)), 'accent', { shadow: false, anchor: 'phoneBack', anchorFace: 4 });
}, {
  decals(t) {
    const W = BONES[this.bone].W, fr = (u, v) => [u, v, .0072];
    decal('phoneFront', W, [rrect(.052, .11, .01, 2)], fr, 'rgb(22,32,48)', 1, .3, 1);
    const bk = (u, v) => [-u, v, -.0099];
    decal('phoneBack', W, [circle(.03, .085, .013, 10), circle(.03, .052, .013, 10)], bk, 'rgb(25,27,32)', 1, 0, 1);
  }
});

/* ---------------- costumes ---------------- */
defProp('cans', ['head', 0, 0, 0, 0, 0, 0], b => {
  for (const sg of [1, -1]) {
    const cup = rotToX(lathe([[.075, .03], [.098, .02], [.1, -.028]], 14, true, false));
    addPart(b, place(sg > 0 ? cup : place(cup, E(0, 0, PI, 0, 0, 0)), E(0, 0, 0, sg * .335, .205, -.02)), 'graphite', { bias: -.02 });
    addPart(b, place(rotToX(cyl(.06, .012, 12)), E(0, 0, 0, sg * .372, .205, -.02)), 'accent', { shadow: false, bias: -.025 });
  }
  const band = []; for (let i = 0; i <= 10; i++) { const a = PI * i / 10; band.push([Math.cos(a) * .345, .205 + Math.sin(a) * .27, -.085]); }
  addPart(b, tube(band, .016, 6, true), 'graphite', { shadow: false });
});
defProp('hatCowboy', ['head', 0, .39, -.01, -.1, 0, 0], b => {
  const crown = lathe([[0, .23], [.1, .226], [.165, .2], [.182, .1], [.19, .0]], 14, false, true);
  warp(crown, (x, y, z) => [x * 1.42, y - (y > .15 ? .04 * Math.exp(-(x * x) / .003) * ((y - .15) / .08) : 0), z * 1.22]);
  addPart(b, crown, 'felt');
  addPart(b, place(warp(cyl(.192, .03, 14), (x, y, z) => [x * 1.48, y, z * 1.25]), E(0, 0, 0, 0, .03, 0)), 'leather', { shadow: false, bias: -.01 });
  const brim = lathe([[.17, .012], [.38, .012], [.38, .0], [.17, .0]], 18, false, false);
  warp(brim, (x, y, z) => { const r = Math.hypot(x, z); return [x * 1.12, y + (Math.abs(x) / .42) ** 2 * .085 * Math.min(1, r / .3), z * .98]; });
  addPart(b, place(brim, E(0, 0, 0, 0, .0, 0)), 'felt');
});
defProp('beanie', ['head', 0, .35, -.01, -.05, 0, 0], b => {
  const dome = lathe([[0, .14], [.12, .13], [.22, .09], [.27, .03], [.285, -.02]], 12, false, false);
  addPart(b, warp(dome, (x, y, z) => [x * 1.06, y, z * .86]), 'knit', { bias: -.025 });
  addPart(b, warp(cyl(.29, .07, 12), (x, y, z) => [x * 1.06, y - .045, z * .87]), 'knit', { shadow: false, bias: -.035 });
  addPart(b, place(sphere(.045, 8, 5), E(0, 0, 0, 0, .165, 0)), 'knit2', { shadow: false });
});
defProp('beret', ['head', .02, .43, -.02, -.12, 0, .22], b => {
  const disc = lathe([[0, .05], [.13, .048], [.24, .03], [.27, .0], [.24, -.022], [.16, -.025]], 14, false, true);
  addPart(b, warp(disc, (x, y, z) => [x * 1.1, y, z]), 'beret');
  addPart(b, place(cyl(.012, .03, 6), E(0, 0, 0, 0, .065, 0)), 'beret', { shadow: false });
});
defProp('crest', ['head', 0, .41, 0, 0, 0, 0], b => {
  const fins = [[.14, .07], [.07, .1], [0, .12], [-.07, .11], [-.14, .085]];
  for (const [z, h] of fins) addPart(b, rotToZ0(slab([[-.045, 0], [.045, 0], [0, h]], .012), z), 'accent', { shadow: false });
});
function rotToZ0(g, z) { return place(g, E(0, PI / 2, 0, 0, 0, z)); }
defProp('headset', ['head', 0, 0, 0, 0, 0, 0], b => {
  addPart(b, place(rotToX(cyl(.05, .02, 10)), E(0, 0, 0, -.33, .205, -.01)), 'graphite', { shadow: false, bias: -.02 });
  const boom = [[-.335, .19, .03], [-.31, .12, .14], [-.22, .07, .23], [-.1, .055, .27]];
  addPart(b, tube(boom, .006, 5, true), 'graphite', { shadow: false, bias: -.02 });
  addPart(b, place(sphere(.018, 8, 5), E(0, 0, 0, -.085, .055, .272)), 'accent', { shadow: false, bias: -.03 });
});
defProp('drop', ['head', .2, .36, .19, 0, 0, 0], b => {
  addPart(b, warp(sphere(.022, 8, 5), (x, y, z) => [x, y < 0 ? y : y * (1 + 1.4 * (y / .022)), z * .6]), 'water', { shadow: false, bias: -.04 });
});
/* ---------------- instruments, DJ booth, stage pieces, art ---------------- */
defMat('spruce', '#DDA35E', .45, 26, .35);
defMat('maple', '#E2BE8C', .40, 24, .35);
defMat('gtrRed', '#C3262F', .85, 46, .50);
defMat('bassBody', '#2C6A5D', .80, 44, .50);
defMat('booth', '#22252C', .35, 24, .55);
defMat('boothTop', '#121418', .55, 34, .50);
defMat('deck', '#A9B0B9', .80, 40, .55);

/* build a matrix from a Y axis and a Z axis (re-orthonormalised) at a position */
function basis(Y, Z, pos, o = M()) {
  Y = norm3(Y); let X = norm3(cross3(Y, Z)); Z = cross3(X, Y);
  o[0] = X[0]; o[1] = Y[0]; o[2] = Z[0]; o[3] = pos[0];
  o[4] = X[1]; o[5] = Y[1]; o[6] = Z[1]; o[7] = pos[1];
  o[8] = X[2]; o[9] = Y[2]; o[10] = Z[2]; o[11] = pos[2];
  return o;
}
/* guitars: local X runs up the neck, Y across, Z out of the top. `at` places it in the spine frame. */
function guitar(id, o) {
  const AT = E(...o.rot, ...o.pos);
  const INV = (() => { const r = M(); mRotT(AT, r); const t = mDir(r, -AT[3], -AT[7], -AT[11]); r[3] = t[0]; r[7] = t[1]; r[11] = t[2]; return r; })();
  return defProp(id, ['spine', ...o.pos, ...o.rot], b => {
    const body = slab(o.outline, o.depth), nf = body.f.length;
    addPart(b, body, o.bodyMat, { anchor: id + 'Top', matOf: (i) => i === nf - 1 ? o.topMat : i === nf - 2 ? o.backMat : o.sideMat, bias: -.005 });
    addPart(b, place(bevelBox(o.neck[1] / 2, .02, .012, .005), E(0, 0, 0, o.neck[0] + o.neck[1] / 2, 0, o.depth + .012)), o.neckMat, { anchor: id + 'Neck', anchorFace: 5, shadow: false, bias: -.012 });
    addPart(b, place(slab(o.head, .009), E(0, .2, 0, o.neck[0] + o.neck[1] + .005, 0, o.depth + .006)), o.headMat, { shadow: false, bias: -.01 });
    /* strap: around Cato's back and over the left shoulder, authored in the spine frame */
    const strapS = [[-.29, .02, .17], [-.3, .06, -.02], [-.22, .16, -.3], [0, .36, -.31], [.19, .55, -.08], [.2, .5, .14], [.14, .34, .23]];
    const tail = mPt(AT, o.tail[0], o.tail[1], o.tail[2]), heel = mPt(AT, o.neck[0] + .02, .03, -o.depth);
    const pts = [tail, ...strapS.slice(1, -1), heel].map(q => mPt(INV, q[0], q[1], q[2]));
    addPart(b, tube(pts, .011, 4, false), 'leather', { shadow: false });
  }, Object.assign({ AT, g: o }, {
    decals(t) {
      const W = BONES[this.bone].W, g = this.g, top = (u, v) => [u, v, g.depth + .0008];
      g.paint(W, top, t);
      const nk = (u, v) => [u, v, g.depth + .0245];
      const fb = [[g.neck[0], -.017], [g.neck[0] + g.neck[1], -.016], [g.neck[0] + g.neck[1], .016], [g.neck[0], .017]];
      decal(id + 'Neck', W, [fb], nk, g.fretboard, 1, 0, 1);
      const frets = []; for (let i = 1; i < 16; i++) { const x = g.neck[0] + g.neck[1] * (1 - Math.pow(.94, i * 1.25)); frets.push([[x, -.016], [x + .0025, -.016], [x + .0025, .016], [x, .016]]); }
      decal(id + 'Neck', W, frets, nk, 'rgb(200,205,212)', .9, 0, 2);
      const s = [], n = g.strings, x0 = g.bridge, x1 = g.neck[0] + g.neck[1];
      for (let i = 0; i < n; i++) { const v = (i - (n - 1) / 2) * (n > 4 ? .0058 : .0075), th = .0007 + (n - i) * .00018, vib = Math.sin(t * 60 + i) * (STRUM.v > 0 ? .0012 * STRUM.v : 0); s.push([[x0, v * 1.5 - th + vib], [x1, v - th], [x1, v + th], [x0, v * 1.5 + th + vib]]); }
      decal(id + 'Neck', W, s, nk, 'rgb(236,238,240)', .95, 0, 3);
    }
  }));
}
const STRUM = { v: 0 };
/* acoustic */
guitar('gtrA', {
  rot: [-.15, 0, .44], pos: [-.06, .1, .235], depth: .045, tail: [-.33, 0, 0], bridge: -.17, strings: 6,
  outline: radialUnion([[-.13, 0, .2], [.14, 0, .152], [.02, 0, .118]], 0, 0, 52),
  neck: [.25, .44], head: [[-.05, -.032], [.06, -.03], [.06, .03], [-.05, .032]],
  bodyMat: 'woodDark', topMat: 'spruce', backMat: 'woodDark', sideMat: 'woodDark', neckMat: 'woodDark', headMat: 'woodDark', fretboard: 'rgb(48,30,22)',
  paint(W, top) {
    decal('gtrATop', W, [circle(.075, 0, .058, 22)], top, 'rgb(150,92,40)', 1, 0, 1);
    decal('gtrATop', W, [circle(.075, 0, .046, 22)], top, 'rgb(24,16,12)', 1, 0, 2);
    decal('gtrATop', W, [[[-.185, -.055], [-.155, -.055], [-.155, .055], [-.185, .055]]], top, 'rgb(40,26,18)', 1, 0, 1);
    decal('gtrATop', W, [[[.02, -.06], [.1, -.075], [.13, -.105], [.05, -.13], [.0, -.1]]], top, 'rgb(70,38,22)', .9, 0, 1);
  }
});
/* electric */
guitar('gtrE', {
  rot: [-.12, 0, .38], pos: [-.05, .06, .21], depth: .022, tail: [-.23, 0, 0], bridge: -.1, strings: 6,
  outline: radialUnion([[-.07, 0, .165], [.11, .088, .064], [.105, -.072, .058], [.03, 0, .118]], -.02, 0, 56),
  neck: [.19, .44], head: [[-.05, -.026], [.07, -.012], [.07, .03], [-.05, .028]],
  bodyMat: 'gtrRed', topMat: 'gtrRed', backMat: 'gtrRed', sideMat: 'gtrRed', neckMat: 'maple', headMat: 'ink', fretboard: 'rgb(214,182,138)',
  paint(W, top) {
    decal('gtrETop', W, [[[-.04, .02], [.16, .045], [.15, -.06], [.06, -.11], [-.08, -.1], [-.12, -.03]]], top, 'rgb(244,242,236)', 1, 0, 1);
    for (const x of [.08, -.04]) decal('gtrETop', W, [[[x - .02, -.04], [x + .02, -.04], [x + .02, .04], [x - .02, .04]]], top, 'rgb(26,27,30)', 1, 0, 2);
    decal('gtrETop', W, [[[-.115, -.035], [-.09, -.035], [-.09, .035], [-.115, .035]]], top, 'rgb(190,196,204)', 1, 0, 2);
    decal('gtrETop', W, [circle(-.1, -.1, .012, 10), circle(-.06, -.115, .012, 10), circle(-.14, -.075, .012, 10)], top, 'rgb(30,30,34)', 1, 0, 3);
  }
});
/* bass */
guitar('gtrB', {
  rot: [-.12, 0, .33], pos: [-.04, .04, .215], depth: .024, tail: [-.26, 0, 0], bridge: -.13, strings: 4,
  outline: radialUnion([[-.09, 0, .18], [.13, .095, .07], [.12, -.08, .062], [.03, 0, .13]], -.02, 0, 56),
  neck: [.21, .6], head: [[-.05, -.03], [.09, -.02], [.09, .034], [-.05, .03]],
  bodyMat: 'bassBody', topMat: 'bassBody', backMat: 'bassBody', sideMat: 'bassBody', neckMat: 'maple', headMat: 'maple', fretboard: 'rgb(60,38,26)',
  paint(W, top) {
    decal('gtrBTop', W, [[[-.06, .04], [.18, .06], [.17, -.07], [.04, -.12], [-.12, -.11], [-.15, -.02]]], top, 'rgb(28,30,32)', .92, 0, 1);
    decal('gtrBTop', W, [[[.02, -.03], [.06, -.03], [.06, .03], [.02, .03]], [[-.06, -.03], [-.03, -.03], [-.03, .03], [-.06, .03]]], top, 'rgb(14,14,16)', 1, 0, 2);
    decal('gtrBTop', W, [[[-.145, -.04], [-.115, -.04], [-.115, .04], [-.145, .04]]], top, 'rgb(190,196,204)', 1, 0, 2);
  }
});

/* microphone on a telescopic stand (two props so the height can change) */
defProp('standBase', ['G', 0, 0, .42, 0, 0, 0], b => {
  addPart(b, place(cyl(.13, .02, 14), E(0, 0, 0, 0, .01, 0)), 'ink');
  addPart(b, place(cyl(.014, .86, 8), E(0, 0, 0, 0, .44, 0)), 'ink', { shadow: false });
});
defProp('standTop', ['G', 0, 1.62, .42, 0, 0, 0], b => {
  addPart(b, place(cyl(.009, .8, 6), E(0, 0, 0, 0, -.42, 0)), 'alu', { shadow: false });
  addPart(b, place(cyl(.016, .11, 8), E(-.95, 0, 0, 0, .02, -.035)), 'ink', { shadow: false });
  addPart(b, place(sphere(.03, 8, 5), E(0, 0, 0, 0, .05, -.085)), 'deck', { shadow: false, bias: -.01 });
});
defProp('stool', ['G', 0, 0, -.03, 0, 0, 0], b => {
  addPart(b, place(cyl(.19, .045, 16), E(0, 0, 0, 0, .617, 0)), 'wood', { bias: .02 });
  for (const [sx, sz] of [[1, 1], [-1, 1], [1, -1], [-1, -1]]) addPart(b, tube([[sx * .11, .6, sz * .11], [sx * .17, 0, sz * .17]], .014, 5, true), 'woodDark', { shadow: false });
  const ring = []; for (let i = 0; i <= 12; i++) { const a = i / 12 * TAU; ring.push([Math.cos(a) * .158, .24, Math.sin(a) * .158]); }
  addPart(b, tube(ring, .01, 4, false), 'alu', { shadow: false });
});
/* sound system towers */
defProp('speakers', ['G', 0, 0, -.6, 0, 0, 0], b => {
  for (const sg of [1, -1]) {
    addPart(b, place(bevelBox(.2, .4, .17, .02), E(0, -sg * .25, 0, sg * .7, .4, 0)), 'ink', { anchor: sg > 0 ? 'spkL' : 'spkR', anchorFace: 5 });
    addPart(b, place(bevelBox(.17, .12, .15, .02), E(0, -sg * .25, 0, sg * .7, .93, 0)), 'ink', { anchor: sg > 0 ? 'spkL2' : 'spkR2', anchorFace: 5 });
  }
}, {
  decals(t) {
    const W = BONES[this.bone].W, k = SCENE.beatPulse;
    for (const sg of [1, -1]) {
      const R = E(0, -sg * .25, 0, sg * .7, .4, 0), R2 = E(0, -sg * .25, 0, sg * .7, .93, 0);
      const f = (u, v) => mPt(R, u, v, .1705), f2 = (u, v) => mPt(R2, u, v, .1505), A = sg > 0 ? 'spkL' : 'spkR', A2 = sg > 0 ? 'spkL2' : 'spkR2';
      for (const [cy, r] of [[.17, .13], [-.17, .13]]) {
        decal(A, W, [circle(0, cy, r, 22)], f, 'rgb(48,50,56)', 1, 0, 1);
        decal(A, W, [circle(0, cy, r * (.62 + .08 * k), 18)], f, 'rgb(22,23,26)', 1, 0, 2);
        decal(A, W, [circle(0, cy, r * .2, 10)], f, 'rgb(90,94,102)', 1, 0, 3);
      }
      decal(A2, W, [circle(0, 0, .07, 16)], f2, 'rgb(48,50,56)', 1, 0, 1);
      decal(A2, W, [circle(0, 0, .03 * (1 + .1 * k), 10)], f2, 'rgb(150,156,166)', 1, 0, 2);
    }
  }
});
/* guitar amp stack */
defProp('amp', ['G', -.78, 0, -.45, 0, .4, 0], b => {
  addPart(b, place(bevelBox(.3, .28, .16, .02), E(0, 0, 0, 0, .28, 0)), 'ink', { anchor: 'ampCab', anchorFace: 5 });
  addPart(b, place(bevelBox(.3, .085, .13, .02), E(0, 0, 0, 0, .655, -.01)), 'ink', { anchor: 'ampHead', anchorFace: 5 });
}, {
  decals(t) {
    const W = BONES[this.bone].W, cab = (u, v) => [u, v + .28, .1605], hd = (u, v) => [u, v + .655, .1205];
    decal('ampCab', W, [[[-.26, -.24], [.26, -.24], [.26, .24], [-.26, .24]]], cab, 'rgb(46,44,42)', 1, 0, 1);
    const weave = []; for (let i = -6; i <= 6; i++) weave.push([[i * .04 - .006, -.235], [i * .04 + .006, -.235], [i * .04 + .006 + .02, .235], [i * .04 - .006 + .02, .235]].map(([u, v]) => [clamp(u, -.255, .255), v]));
    decal('ampCab', W, weave, cab, 'rgb(64,62,58)', .6, 0, 2);
    decal('ampCab', W, [[[-.08, .19], [.08, .19], [.08, .215], [-.08, .215]]], cab, 'rgb(212,184,120)', 1, 0, 3);
    const knobs = []; for (let i = 0; i < 6; i++) knobs.push(circle(-.2 + i * .07, -.01, .014, 10));
    decal('ampHead', W, knobs, hd, 'rgb(212,184,120)', 1, 0, 1);
    decal('ampHead', W, [circle(.24, .03, .01, 8)], hd, 'rgb(255,70,60)', 1, .8, 1);
  }
});
/* DJ booth: table, two turntables, a mixer, and a front panel of LED meters */
defProp('booth', ['G', 0, 0, .4, 0, 0, 0], b => {
  addPart(b, place(roundBox(.62, .025, .21, .01, 1, 4), E(0, 0, 0, 0, .985, 0)), 'boothTop', { bias: .08 });
  addPart(b, place(bevelBox(.6, .48, .012, .006), E(0, 0, 0, 0, .49, .2)), 'booth', { anchor: 'boothFront', anchorFace: 5, bias: .01 });
  for (const sg of [1, -1]) addPart(b, place(bevelBox(.012, .48, .2, .006), E(0, 0, 0, sg * .6, .49, 0)), 'booth', { shadow: false });
  for (const sg of [1, -1]) {
    const x = sg * .33;
    addPart(b, place(bevelBox(.15, .02, .135, .01), E(0, 0, 0, x, 1.03, -.02)), 'deck', { shadow: false });
    addPart(b, place(cyl(.105, .014, 20), E(0, 0, 0, x, 1.057, -.02)), 'ink', { shadow: false, anchor: sg > 0 ? 'recL' : 'recR', anchorFace: 20, bias: -.01 });
    addPart(b, tube([[x + .115, 1.07, -.12], [x + .1, 1.085, -.02], [x + .05, 1.075, .05]], .005, 4, true), 'alu', { shadow: false, bias: -.03 });
  }
  addPart(b, place(bevelBox(.1, .025, .14, .01), E(0, 0, 0, 0, 1.035, -.02)), 'ink', { shadow: false, anchor: 'mixer', anchorFace: 3 });
}, {
  decals(t) {
    const W = BONES[this.bone].W, k = SCENE.beatPulse, beat = SCENE.beat;
    for (const sg of [1, -1]) {
      const x = sg * .33, rec = (u, v) => [x + u, 1.0645, -.02 - v], A = sg > 0 ? 'recL' : 'recR';
      decal(A, W, [circle(0, 0, .1, 24)], rec, 'rgb(14,14,16)', 1, 0, 1);
      decal(A, W, [circle(0, 0, .072, 22)], rec, 'rgb(30,30,34)', .9, 0, 2);
      decal(A, W, [circle(0, 0, .035, 16)], rec, sg > 0 ? 'rgb(46,75,255)' : 'rgb(120,240,255)', 1, .2, 3);
      const a = DJ.spin[sg > 0 ? 0 : 1], ca = Math.cos(a), sa = Math.sin(a);
      decal(A, W, [[[.04 * ca - .004 * sa, .04 * sa + .004 * ca], [.095 * ca - .004 * sa, .095 * sa + .004 * ca], [.095 * ca + .004 * sa, .095 * sa - .004 * ca], [.04 * ca + .004 * sa, .04 * sa - .004 * ca]]], rec, 'rgb(200,205,215)', .5, 0, 4);
      decal(A, W, [circle(0, 0, .005, 6)], rec, 'rgb(200,205,215)', 1, 0, 5);
    }
    const mx = (u, v) => [u, 1.0605, -.02 - v], kn = [];
    for (let r = 0; r < 3; r++) for (const c of [-1, 1]) kn.push(circle(c * .045, .1 - r * .035, .011, 8));
    decal('mixer', W, kn, mx, 'rgb(150,156,166)', 1, 0, 1);
    decal('mixer', W, [[[-.06, -.08], [.06, -.08], [.06, -.07], [-.06, -.07]], [[-.05, .0], [-.04, .0], [-.04, -.05], [-.05, -.05]], [[.04, .0], [.05, .0], [.05, -.05], [.04, -.05]]], mx, 'rgb(10,10,12)', 1, 0, 2);
    const cf = DJ.fader * .05;
    decal('mixer', W, [[[cf - .012, -.084], [cf + .012, -.084], [cf + .012, -.066], [cf - .012, -.066]]], mx, 'rgb(230,232,236)', 1, 0, 3);
    /* front: VU meters riding the beat */
    const fr = (u, v) => [u, v + .49, .2125], bars = [], hot = [];
    for (let i = 0; i < 16; i++) {
      const lv = clamp(.35 + .55 * k * (.6 + .4 * Math.sin(i * 1.7 + beat * 2.1)) + .1 * Math.sin(i + t * 3), 0, 1), x = -.5 + i * .0667;
      bars.push([[x, -.3], [x + .045, -.3], [x + .045, -.3 + .55 * lv], [x, -.3 + .55 * lv]]);
      if (lv > .75) hot.push([[x, -.3 + .55 * .75], [x + .045, -.3 + .55 * .75], [x + .045, -.3 + .55 * lv], [x, -.3 + .55 * lv]]);
    }
    decal('boothFront', W, bars, fr, SCENE.ledA, .95, .7, 1);
    decal('boothFront', W, hot, fr, 'rgb(46,75,255)', 1, .8, 2);
    decal('boothFront', W, [[[-.55, .36], [.55, .36], [.55, .38], [-.55, .38]]], fr, SCENE.ledB, 1, .9, 2);
  }
});
const DJ = { spin: [0, 0], fader: 0 };
/* painting: easel + canvas, palette, brush */
defProp('easel', ['G', -.88, 0, .3, 0, .62, 0], b => {
  addPart(b, tube([[-.24, 0, .12], [-.03, 1.6, .02]], .016, 5, true), 'wood', { shadow: true });
  addPart(b, tube([[.24, 0, .12], [.03, 1.6, .02]], .016, 5, true), 'wood', { shadow: true });
  addPart(b, tube([[0, 0, -.42], [0, 1.45, -.02]], .014, 5, true), 'wood', { shadow: false });
  addPart(b, place(bevelBox(.3, .012, .04, .006), E(-.08, 0, 0, 0, .94, .075)), 'wood', { shadow: false });
  addPart(b, place(bevelBox(.27, .205, .012, .006), E(-.08, 0, 0, 0, 1.2, .08)), 'canvas', { anchor: 'canvas', anchorFace: 5, bias: -.01 });
}, {
  decals(t) {
    const W = BONES[this.bone].W, R = E(-.08, 0, 0, 0, 1.2, .08), cv = (u, v) => mPt(R, u, v, .0128);
    paintCanvas(W, cv);
  }
});
defProp('palette', ['handL', 0, -.1, .06, 0, 0, 0], b => {
  addPart(b, slab(radialUnion([[0, 0, .1], [.075, .035, .065], [-.06, -.02, .07]], 0, 0, 30), .004), 'woodTop', { shadow: false });
  SC.anchors.palTop = [SC.fStart.length - 1];
}, {
  decals() {
    const W = BONES[this.bone].W, top = (u, v) => [u, v, .0045], blobs = [];
    decal('palTop', W, [circle(-.06, .0, .02, 12)], top, 'rgb(90,70,52)', 1, 0, 1);
    PAINTS.forEach((c, i) => { const a = .5 + i * .55; decal('palTop', W, [circle(Math.cos(a) * .072 + .01, Math.sin(a) * .06, .016, 10)], top, c, 1, 0, 2); });
  }
});
defProp('brush', ['handR', 0, -.085, .02, 0, 0, PI / 2], b => {
  addPart(b, tube([[0, -.07, 0], [0, .15, 0]], [.006, .0045], 6, true), 'woodDark', { shadow: false });
  addPart(b, tube([[0, .15, 0], [0, .18, 0]], .0062, 6, true), 'alu', { shadow: false });
  addPart(b, lathe([[.006, .18], [.0075, .2], [0, .225]], 6, false, false), 'paint', { shadow: false });
});
const PAINTS = ['rgb(46,75,255)', 'rgb(255,196,64)', 'rgb(58,170,140)', 'rgb(52,110,200)', 'rgb(240,240,236)', 'rgb(60,40,90)'];
/* ---------- freeze scene buffers ---------- */
const NV = SC.pos.length / 3, NF = SC.fStart.length;
const LP = new Float32Array(SC.pos);              // local positions
const WP = new Float32Array(NV * 3);              // world positions
const fIdx = Int32Array.from(SC.fIdx), fStart = Int32Array.from(SC.fStart), fLen = Uint8Array.from(SC.fLen);
const fMat = Uint8Array.from(SC.fMat), fPart = Uint8Array.from(SC.fPart), fBias = Float32Array.from(SC.fBias), fMesh = Uint16Array.from(SC.fMesh);
/* =========================================================================
   Renderer: perspective camera, 3-point lighting, back-face culling,
   painter's sort, pooled SVG paths, decals (eyes, mouth, core), shadows,
   floor plate, emissive glow, floating emotes.
   ========================================================================= */
const SVGNS = 'http://www.w3.org/2000/svg';
const $ = id => host.querySelector('#' + id);
const svg = $('view'), gFloor = $('gFloor'), gMesh = $('gMesh'), gGlow = $('gGlow'), gFx = $('gFx');
const pShadow = $('pShadow'), pContact = $('pContact');

/* ---------- camera ---------- */
const FOV = 26 * PI / 180;
const cam = { yaw: 0, pitch: .13, zoom: 1, fit: 6, ty: 1.1, tx: 0, tz: 0, sway: 0, swayP: 0, dirty: true };
let VW = 800, VH = 600, FOC = 1000, OX = 400, OY = 300;
const CB = { p: [0, 0, 6], r: [1, 0, 0], u: [0, 1, 0], f: [0, 0, -1] };
function fitCamera() {
  const t = Math.tan(FOV / 2), aspect = VW / VH;
  cam.fit = Math.max(1.42 / t, 1.1 / (t * aspect)) * 1.04;
  FOC = (VH / 2) / t; OX = VW / 2; OY = VH / 2; cam.dirty = true;
}
let camKey = '';
function updateCamera() {
  const yaw = cam.yaw + cam.sway, pitch = clamp(cam.pitch + cam.swayP, .03, 1.25), d = cam.fit * cam.zoom;
  const tx = cam.tx, ty = cam.ty, tz = cam.tz;
  CB.p = [tx + d * Math.sin(yaw) * Math.cos(pitch), ty + d * Math.sin(pitch), tz + d * Math.cos(yaw) * Math.cos(pitch)];
  CB.f = norm3([tx - CB.p[0], ty - CB.p[1], tz - CB.p[2]]);
  CB.r = norm3(cross3(CB.f, [0, 1, 0])); CB.u = cross3(CB.r, CB.f);
  const k = CB.p.map(v => v.toFixed(4)).join() + VW + 'x' + VH;
  if (k !== camKey) { camKey = k; cam.dirty = true; }
}
function proj(x, y, z, out) {
  const dx = x - CB.p[0], dy = y - CB.p[1], dz = z - CB.p[2];
  const X = dx * CB.r[0] + dy * CB.r[1] + dz * CB.r[2], Y = dx * CB.u[0] + dy * CB.u[1] + dz * CB.u[2];
  const Z = Math.max(.05, dx * CB.f[0] + dy * CB.f[1] + dz * CB.f[2]);
  out[0] = OX + X / Z * FOC; out[1] = OY - Y / Z * FOC; out[2] = Z; return out;
}
/* camera-relative lights → world */
const viewDir = (x, y, z) => norm3([CB.r[0] * x + CB.u[0] * y - CB.f[0] * z, CB.r[1] * x + CB.u[1] * y - CB.f[1] * z, CB.r[2] * x + CB.u[2] * y - CB.f[2] * z]);
const LIGHT = { key: [0, 1, 0], fill: [1, 0, 0], rim: [0, 0, -1], half: [0, 1, 0], shadow: [0, 1, 0] };
const KEY_C = [1.18, 1.10, 1.0], FILL_C = [.20, .26, .38], RIM_C = [.62, .72, .92], SKY = [.27, .31, .37], GND = [.17, .145, .13];
/* scene state shared with props and HUD: light preset, beat, LED colours, floor pool */
const SCENE = { light: 'default', beat: 0, beatPulse: 0, ledA: 'rgb(120,244,255)', ledB: 'rgb(46,75,255)', pool: [0, 0, 0, 0] };
const hsl = (h, s, l) => { const a = s * Math.min(l, 1 - l), f = n => { const k = (n + h / 30) % 12; return l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)); }; return [f(0), f(8), f(4)]; };
const LIGHTSETS = {
  default: () => ({ key: [1.18, 1.10, 1.0], fill: [.20, .26, .38], rim: [.62, .72, .92], sky: [.27, .31, .37], gnd: [.17, .145, .13], pool: [0, 0, 0, 0] }),
  desk: () => ({ key: [1.08, 1.02, .95], fill: [.16, .3, .5], rim: [.5, .62, .9], sky: [.24, .28, .34], gnd: [.16, .14, .12], pool: [120, 190, 255, .16] }),
  dj: (b, k) => { const c1 = hsl((b * 45) % 360 + 280, .9, .6), c2 = hsl((b * 45 + 160) % 360, .9, .6); return { key: c1.map(v => v * 1.15 + .25 * k), fill: c2.map(v => v * .5), rim: c2.map(v => v * 1.3), sky: [.2, .18, .3], gnd: [.12, .1, .16], pool: [...hsl((b * 45) % 360 + 280, .95, .6).map(v => v * 255), .3 + .25 * k] }; },
  rock: (b, k) => ({ key: [1.35, .72 + .2 * k, .5], fill: [.15, .1, .1], rim: [1.1, 1.1, 1.15], sky: [.28, .2, .2], gnd: [.16, .1, .08], pool: [255, 70, 40, .26 + .2 * k] }),
  pop: (b, k) => { const m = Math.floor(b) % 2; return { key: m ? [1.3, .75, 1.15] : [.8, 1.0, 1.35], fill: m ? [.2, .35, .45] : [.45, .2, .4], rim: [1, .9, 1.2], sky: [.3, .26, .34], gnd: [.16, .12, .16], pool: m ? [255, 110, 200, .3 + .2 * k] : [110, 200, 255, .3 + .2 * k] }; },
  reggae: (b, k) => ({ key: [1.3, 1.05, .62], fill: [.18, .32, .22], rim: [.6, 1.0, .65], sky: [.3, .28, .2], gnd: [.18, .14, .08], pool: [255, 190, 70, .24 + .12 * k] }),
  country: () => ({ key: [1.32, 1.06, .74], fill: [.28, .2, .14], rim: [1.1, .85, .6], sky: [.32, .27, .2], gnd: [.2, .15, .1], pool: [255, 180, 90, .22] }),
  folk: () => ({ key: [1.25, .98, .72], fill: [.07, .07, .1], rim: [.9, .7, .5], sky: [.16, .14, .13], gnd: [.1, .08, .07], pool: [255, 200, 120, .3] }),
  art: () => ({ key: [1.2, 1.12, 1.0], fill: [.24, .26, .3], rim: [.7, .72, .8], sky: [.3, .31, .33], gnd: [.18, .16, .14], pool: [255, 236, 200, .12] }),
  think: () => ({ key: [1.1, 1.08, 1.05], fill: [.16, .3, .46], rim: [.5, .75, 1.0], sky: [.25, .29, .36], gnd: [.15, .14, .14], pool: [120, 230, 255, .14] }),
};
function updateLightRig(dt) {
  const L = (LIGHTSETS[SCENE.light] || LIGHTSETS.default)(SCENE.beat, SCENE.beatPulse), r = 1 - Math.exp(-6 * dt);
  for (let i = 0; i < 3; i++) {
    KEY_C[i] += (L.key[i] - KEY_C[i]) * r; FILL_C[i] += (L.fill[i] - FILL_C[i]) * r; RIM_C[i] += (L.rim[i] - RIM_C[i]) * r;
    SKY[i] += (L.sky[i] - SKY[i]) * r; GND[i] += (L.gnd[i] - GND[i]) * r;
  }
  for (let i = 0; i < 4; i++) SCENE.pool[i] += (L.pool[i] - SCENE.pool[i]) * r;
}
function updateLights() {
  LIGHT.key = viewDir(-.52, .74, .56);
  LIGHT.fill = viewDir(.85, .12, .45);
  LIGHT.rim = viewDir(.35, .45, -.85);
  const v = viewDir(0, 0, 1);
  LIGHT.half = norm3([LIGHT.key[0] + v[0], LIGHT.key[1] + v[1], LIGHT.key[2] + v[2]]);
  LIGHT.shadow = norm3([LIGHT.key[0] * .38, 1, LIGHT.key[2] * .38]);
}
/* tonemap + gamma LUT */
const LUT = new Uint8Array(2048);
for (let i = 0; i < 2048; i++) { const x = i / 512; LUT[i] = Math.round(255 * Math.pow(1 - Math.exp(-x * 1.32), 1 / 2.2)); }
const tm = x => LUT[x <= 0 ? 0 : x >= 3.998 ? 2047 : (x * 512) | 0];

/* ---------- per-frame buffers ---------- */
const SX = new Float32Array(NV), SY = new Float32Array(NV), SZ = new Float32Array(NV);
const MAXD = NF + 900;
const dDepth = new Float64Array(MAXD), dOrder = new Uint32Array(MAXD);
const dPath = new Array(MAXD), dFill = new Array(MAXD), dFace = new Int32Array(MAXD), dAlpha = new Float32Array(MAXD);
const faceVis = new Uint8Array(NF), faceDepth = new Float64Array(NF);
let nDraw = 0, nVisibleFaces = 0;
const tmp3 = [0, 0, 0];

function projectAll() {
  const p0 = CB.p[0], p1 = CB.p[1], p2 = CB.p[2], r = CB.r, u = CB.u, f = CB.f;
  for (const m of SC.meshes) {
    if (!m.vis) continue;
    for (let i = m.v0, v = m.v0 * 3; i < m.v1; i++, v += 3) {
      const dx = WP[v] - p0, dy = WP[v + 1] - p1, dz = WP[v + 2] - p2;
      const Z = Math.max(.05, dx * f[0] + dy * f[1] + dz * f[2]);
      SX[i] = OX + (dx * r[0] + dy * r[1] + dz * r[2]) / Z * FOC;
      SY[i] = OY - (dx * u[0] + dy * u[1] + dz * u[2]) / Z * FOC;
      SZ[i] = Z;
    }
  }
}
const r1 = v => Math.round(v * 10) / 10;
let LED_RGB = [185, 201, 255];

function shadeFaces() {
  nDraw = 0; nVisibleFaces = 0; let sameMesh = -1, sameFill = '';
  const K = LIGHT.key, F = LIGHT.fill, Rm = LIGHT.rim, Hh = LIGHT.half, cp = CB.p;
  for (const mesh of SC.meshes) {
  if (!mesh.vis) { faceVis.fill(0, mesh.f0, mesh.f1); continue; }
  for (let f = mesh.f0; f < mesh.f1; f++) {
    const s = fStart[f], n = fLen[f];
    let nx = 0, ny = 0, nz = 0, cx = 0, cy = 0, cz = 0, dz = 0;
    for (let k = 0; k < n; k++) {
      const a = fIdx[s + k] * 3, b = fIdx[s + (k + 1) % n] * 3;
      const ax = WP[a], ay = WP[a + 1], az = WP[a + 2], bx = WP[b], by = WP[b + 1], bz = WP[b + 2];
      nx += (ay - by) * (az + bz); ny += (az - bz) * (ax + bx); nz += (ax - bx) * (ay + by);
      cx += ax; cy += ay; cz += az; dz += SZ[fIdx[s + k]];
    }
    cx /= n; cy /= n; cz /= n;
    const nl = Math.hypot(nx, ny, nz);
    faceVis[f] = 0;
    if (nl < 1e-9) continue;
    nx /= nl; ny /= nl; nz /= nl;
    let vx = cp[0] - cx, vy = cp[1] - cy, vz = cp[2] - cz;
    const vd = nx * vx + ny * vy + nz * vz;
    if (vd <= 0) continue;
    const vl = Math.hypot(vx, vy, vz); vx /= vl; vy /= vl; vz /= vl;
    faceVis[f] = 1; nVisibleFaces++;
    const depth = dz / n + fBias[f]; faceDepth[f] = depth;
    const m = MATS[fMat[f]];
    let fill;
    if (m.same && sameMesh === fMesh[f]) fill = sameFill;
    else if (m.led) {
      const k = .72 + .28 * (vd / vl);
      fill = `rgb(${(LED_RGB[0] * k) | 0},${(LED_RGB[1] * k) | 0},${(LED_RGB[2] * k) | 0})`;
    } else if (m.fill) {
      fill = m.fill;
    } else {
      const dk = Math.max(0, nx * K[0] + ny * K[1] + nz * K[2]);
      const df = Math.max(0, nx * F[0] + ny * F[1] + nz * F[2]);
      const dr = Math.max(0, nx * Rm[0] + ny * Rm[1] + nz * Rm[2]);
      const hm = .5 + .5 * ny;
      const sp = dk > 0 ? m.spec * Math.pow(Math.max(0, nx * Hh[0] + ny * Hh[1] + nz * Hh[2]), m.pow) : 0;
      const fr = m.rim * Math.pow(1 - Math.min(1, vd / vl), 3);
      const c = m.c;
      const R = c[0] * (GND[0] + (SKY[0] - GND[0]) * hm + KEY_C[0] * dk + FILL_C[0] * df + RIM_C[0] * dr) + sp * KEY_C[0] + fr * RIM_C[0] * .55;
      const Gc = c[1] * (GND[1] + (SKY[1] - GND[1]) * hm + KEY_C[1] * dk + FILL_C[1] * df + RIM_C[1] * dr) + sp * KEY_C[1] + fr * RIM_C[1] * .55;
      const B = c[2] * (GND[2] + (SKY[2] - GND[2]) * hm + KEY_C[2] * dk + FILL_C[2] * df + RIM_C[2] * dr) + sp * KEY_C[2] + fr * RIM_C[2] * .55;
      fill = `rgb(${tm(R)},${tm(Gc)},${tm(B)})`;
      if (m.same) { sameMesh = fMesh[f]; sameFill = fill; }
    }
    let d = `M${r1(SX[fIdx[s]])},${r1(SY[fIdx[s]])}`;
    for (let k = 1; k < n; k++) { const i = fIdx[s + k]; d += `L${r1(SX[i])},${r1(SY[i])}`; }
    dPath[nDraw] = d + 'Z'; dFill[nDraw] = fill; dFace[nDraw] = f; dAlpha[nDraw] = 1; dDepth[nDraw] = depth; nDraw++;
  }
  }
}

/* ---------- decals: 2D polygons pinned to an anchor face's plane ---------- */
const glowList = [];
function decal(anchor, boneW, polys, toLocal, fill, alpha = 1, glow = 0, order = 0) {
  if (!SC.anchors[anchor] || alpha <= .01) return;
  for (const poly of polys) {
    if (poly.length < 3) continue;
    const pts = [];
    for (let i = 0; i < poly.length; i++) { const l = toLocal(poly[i][0], poly[i][1]); pts.push(mPt(boneW, l[0], l[1], l[2])); }
    DECALS.push({ pts, fill, alpha, glow, order });
  }
}
const circle = (cx, cy, r, n = 18) => { const o = []; for (let i = 0; i < n; i++) { const a = i / n * TAU; o.push([cx + Math.cos(a) * r, cy + Math.sin(a) * r]); } return o; };

/* eye geometry in visor space (u right from the robot's point of view is -X, so we mirror) */
function eyePolys(side, p, gx, gy) {
  // side: +1 = robot's left eye (+X)
  const wide = p.wide, happy = p.happy, open = clamp(p.eye, 0, 1);
  const w = .037 * (1 + .22 * wide), h = .051 * (1 + .2 * wide);
  const cx = side * .08 + gx * .026, cy = .012 + gy * .02;
  const e = lerp(2.7, 2.1, wide);
  const N = 14, top = [], bot = [];
  for (let i = 0; i <= N; i++) {
    const t = -1 + 2 * i / N, x = t * w;
    const sh = h * Math.pow(Math.max(0, 1 - Math.pow(Math.abs(t), e)), 1 / e);
    let yt = sh, yb = -sh;
    const inner = -side * t; // +1 toward the nose
    if (p.angry > 0) yt -= p.angry * h * (.62 + .5 * inner);
    if (p.angry < 0) yt -= -p.angry * h * (.62 - .5 * inner) * .8;
    yb += p.squint * (yt - yb) * .5;
    yb += happy * (yt - yb) * .88 * Math.pow(Math.max(0, 1 - t * t), .6);
    const c0 = -h * .15;
    yt = c0 + (yt - c0) * open; yb = c0 + (yb - c0) * open;
    if (yt - yb < .0075) { const m = (yt + yb) / 2; yt = m + .00375; yb = m - .00375; }
    top.push([cx + x, cy + yt]); bot.push([cx + x, cy + yb]);
  }
  return [top.concat(bot.reverse())];
}
function xPolys(side, s) {
  const cx = side * .08, cy = .01, L = .04 * s, T = .009 * s, out = [];
  for (const a of [PI / 4, -PI / 4]) {
    const ca = Math.cos(a), sa = Math.sin(a);
    out.push([[-L, -T], [L, -T], [L, T], [-L, T]].map(([x, y]) => [cx + x * ca - y * sa, cy + x * sa + y * ca]));
  }
  return out;
}
function heartPoly(side, s) {
  const o = [], cx = side * .08, cy = .012;
  for (let i = 0; i < 28; i++) {
    const t = i / 28 * TAU, x = 16 * Math.sin(t) ** 3, y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
    o.push([cx + x * .0032 * s, cy + y * .0032 * s + .004]);
  }
  return [o];
}
/* mouth: jaw opening, width and rounding are what visemes drive (see VISEMES in the scenes file) */
function mouthPoly(p) {
  const open = clamp(p.mOpen, 0, 1.2), sm = p.mSmile, wide = clamp(p.mWide, .3, 1.5), rnd = clamp(p.mRound, 0, 1);
  const mw = (.03 + .012 * open + .008 * Math.abs(sm)) * wide * (1 - .38 * rnd), N = 14, top = [], bot = [];
  for (let i = 0; i <= N; i++) {
    const t = -1 + 2 * i / N, x = t * mw, bow = 1 - t * t, sb = Math.sqrt(bow);
    const y = -.078 - sm * .017 * bow * (1 - rnd);
    const th = .0065 + open * .03 * sb, lift = rnd * open * .011 * sb;
    top.push([x, y + .0035 + lift]); bot.push([x, y + .0035 - th]);
  }
  return [top.concat(bot.reverse())];
}
/* sunglasses drawn on the screen face */
function shadePolys(side) {
  const cx = side * .085, L = [[-.06, .045], [.058, .045], [.054, -.012], [.03, -.042], [-.035, -.04], [-.058, -.01]];
  const lens = L.map(([x, y]) => [cx + x * side, y + .01]);
  const rim = L.map(([x, y]) => [cx + x * side * 1.12, y * 1.16 + .01]);
  const glint = [[cx - .03 * side, .04], [cx - .012 * side, .04], [cx - .04 * side, -.02], [cx - .055 * side, -.0]];
  return { lens, rim, glint };
}
const mixRGB = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
const rgbStr = c => `rgb(${c[0] | 0},${c[1] | 0},${c[2] | 0})`;
const EYE_BASE = [185, 201, 255], EYE_WARM = [255, 128, 64], EYE_LOVE = [255, 120, 180], EYE_OFF = [24, 36, 44];

function buildDecals(p, gx, gy, time) {
  glowList.length = 0;
  const Hw = BONES[BN.head].W, Sw = BONES[BN.spine].W;
  const glow = clamp(p.glow, 0, 1);
  let col = mixRGB(EYE_BASE, EYE_WARM, clamp(p.warm, 0, 1));
  col = mixRGB(col, EYE_LOVE, clamp(p.heart, 0, 1));
  col = mixRGB(EYE_OFF, col, glow);
  const eyeFill = rgbStr(col);
  const vz = VISOR.z + .0012;
  const onVisor = (u, v) => [u, VISOR.cy + v, vz];
  /* glass sheen */
  decal('visor', Hw, [[[-.185, .108], [-.12, .108], [-.205, -.02], [-.215, .03]], [[-.098, .108], [-.076, .108], [-.162, -.035], [-.178, -.035]]], onVisor, 'rgb(170,200,230)', .07, 0, 0);
  /* eyes */
  const xs = clamp(p.xeye, 0, 1), hs = clamp(p.heart, 0, 1);
  if (p.shades > .5) {
    for (const side of [1, -1]) {
      const sp = shadePolys(side);
      decal('visor', Hw, [sp.rim], onVisor, eyeFill, 1, .7 * glow, 2);
      decal('visor', Hw, [sp.lens], onVisor, 'rgb(8,10,14)', 1, 0, 3);
      decal('visor', Hw, [sp.glint], onVisor, 'rgb(255,255,255)', .35, 0, 4);
    }
    decal('visor', Hw, [[[-.03, .05], [.03, .05], [.03, .04], [-.03, .04]]], onVisor, eyeFill, 1, .5 * glow, 2);
  }
  else for (const side of [1, -1]) {
    let polys;
    if (xs > .5) polys = xPolys(side, .6 + .4 * xs);
    else if (hs > .5) polys = heartPoly(side, .7 + .3 * hs + .06 * Math.sin(time * 9));
    else polys = eyePolys(side, p, gx, gy);
    decal('visor', Hw, polys, onVisor, eyeFill, 1, .85 * glow, 2);
  }
  if (p.mShow > .02) decal('visor', Hw, mouthPoly(p), onVisor, eyeFill, clamp(p.mShow, 0, 1), .6 * glow * p.mShow, 2);
  /* chest core */
  const pulse = .5 + .5 * Math.sin(time * 2.1);
  const core = clamp(p.core, 0, 1.5) * (.82 + .18 * pulse) * (.25 + .75 * glow);
  const onCore = (u, v) => [u, .33 + v, .1975];
  decal('core', Sw, [circle(0, 0, .062, 22)], onCore, rgbStr(mixRGB([20, 40, 48], mixRGB(col, [255, 255, 255], .0), clamp(core * .55, 0, 1))), 1, 0, 1);
  decal('core', Sw, [circle(0, 0, .044, 20)], onCore, rgbStr(mixRGB([18, 30, 36], col, clamp(core, 0, 1))), 1, .9 * clamp(core, 0, 1), 2);
  decal('core', Sw, [circle(-.012, .012, .016, 12)], onCore, 'rgb(240,255,255)', clamp(core, 0, 1) * .85, 0, 3);
  LED_RGB = mixRGB([40, 52, 60], p.ledWarm > .5 ? [255, 120, 60] : col, clamp(p.led, 0, 1));
  /* props paint their own screens, labels, records and canvases */
  for (const P of PROP_LIST) if (P.decals && P.pres > .3) P.decals(time);
}
/* coloured pool of stage light on the floor plate */
const pPool = $('pPool'), poolStops = $('gPool') ? $('gPool').querySelectorAll('stop') : [];
let poolKey = '';
function drawPool() {
  const c = SCENE.pool, a = clamp(c[3], 0, 1);
  if (a < .01) { if (poolKey !== '0') { pPool.setAttribute('d', ''); poolKey = '0'; } return; }
  let d = '';
  for (let i = 0; i < 40; i++) { const t = i / 40 * TAU; proj(body.x + Math.sin(t) * 1.1, 0, body.z + Math.cos(t) * 1.1, tmp3); d += (i ? 'L' : 'M') + r1(tmp3[0]) + ',' + r1(tmp3[1]); }
  pPool.setAttribute('d', d + 'Z');
  const col = `rgb(${c[0] | 0},${c[1] | 0},${c[2] | 0})`;
  poolStops[0].setAttribute('stop-color', col); poolStops[0].setAttribute('stop-opacity', a.toFixed(3));
  poolStops[1].setAttribute('stop-color', col);
  poolKey = '1';
}

/* ---------- SVG output ---------- */
const pool = [], poolState = [];
function ensurePool(arr, parent, n, state) {
  while (arr.length < n) {
    const el = document.createElementNS(SVGNS, 'path');
    if (state) { el.setAttribute('fill', 'currentColor'); el.setAttribute('stroke', 'currentColor'); state.push({ d: '', f: '', a: 1, u: false }); }
    parent.appendChild(el); arr.push(el);
  }
}
let lastDrawn = 0;
function flush() {
  for (let i = 0; i < nDraw; i++) dOrder[i] = i;
  const ord = dOrder.subarray(0, nDraw);
  ord.sort((a, b) => dDepth[b] - dDepth[a]);
  ensurePool(pool, gMesh, nDraw, poolState);
  for (let k = 0; k < nDraw; k++) {
    const i = ord[k], el = pool[k], st = poolState[k];
    if (st.d !== dPath[i]) { el.setAttribute('d', dPath[i]); st.d = dPath[i]; }
    if (st.f !== dFill[i]) {
      const f = dFill[i];
      if (f.charCodeAt(0) === 117) { el.setAttribute('fill', f); el.setAttribute('stroke', 'none'); st.u = true; }
      else { if (st.u) { el.setAttribute('fill', 'currentColor'); el.setAttribute('stroke', 'currentColor'); st.u = false; } el.setAttribute('color', f); }
      st.f = f;
    }
    const a = dAlpha[i];
    if (st.a !== a) { el.setAttribute('opacity', a); st.a = a; if (a < 1) el.setAttribute('stroke-width', 0); else el.removeAttribute('stroke-width'); }
    el.__face = dFace[i];
  }
  for (let k = nDraw; k < lastDrawn; k++) { pool[k].setAttribute('d', ''); poolState[k].d = ''; pool[k].__face = -1; }
  lastDrawn = nDraw;
}
const glowPool = [];
let glowDrawn = 0;
function flushGlow(extra) {
  const items = glowList.concat(extra);
  ensurePool(glowPool, gGlow, items.length);
  for (let k = 0; k < items.length; k++) {
    const [d, f, a] = items[k], el = glowPool[k];
    el.setAttribute('d', d); el.setAttribute('fill', f); el.setAttribute('opacity', a.toFixed(3));
  }
  for (let k = items.length; k < glowDrawn; k++) glowPool[k].setAttribute('d', '');
  glowDrawn = items.length;
}

/* ---------- shadows ---------- */
function hull(pts) {
  pts.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cr = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lo = [], up = [];
  for (const p of pts) { while (lo.length >= 2 && cr(lo[lo.length - 2], lo[lo.length - 1], p) <= 0) lo.pop(); lo.push(p); }
  for (let i = pts.length - 1; i >= 0; i--) { const p = pts[i]; while (up.length >= 2 && cr(up[up.length - 2], up[up.length - 1], p) <= 0) up.pop(); up.push(p); }
  lo.pop(); up.pop(); return lo.concat(up);
}
function drawShadows() {
  const L = LIGHT.shadow; let d = '';
  for (const m of SC.meshes) {
    if (!m.shadow || !m.vis) continue;
    const pts = [];
    for (let v = m.v0; v < m.v1; v += 1) {
      const i = v * 3, y = Math.max(0, WP[i + 1]), t = y / L[1];
      proj(WP[i] - L[0] * t, 0, WP[i + 2] - L[2] * t, tmp3); pts.push([tmp3[0], tmp3[1]]);
    }
    const h = hull(pts);
    if (h.length > 2) d += 'M' + h.map(q => r1(q[0]) + ',' + r1(q[1])).join('L') + 'Z';
  }
  pShadow.setAttribute('d', d);
  /* contact shadows under the feet, fading with height */
  let c = '';
  for (const S of ['L', 'R']) {
    const W = BONES[BN['foot' + S]].W, h = Math.max(0, W[7] - ANKLE_H), k = 1 - clamp(h / .3, 0, 1);
    if (k < .03) continue;
    let fx = W[2], fz = W[10]; const fl = Math.hypot(fx, fz) || 1; fx /= fl; fz /= fl;
    const sx = fz, sz = -fx, cx = W[3] + fx * .04, cz = W[11] + fz * .04, s = k * (1 + h * 1.5), pts = [];
    for (let i = 0; i < 16; i++) {
      const a = i / 16 * TAU, ca = Math.cos(a) * .095 * s, sa = Math.sin(a) * .155 * s;
      proj(cx + sx * ca + fx * sa, 0, cz + sz * ca + fz * sa, tmp3); pts.push(r1(tmp3[0]) + ',' + r1(tmp3[1]));
    }
    c += 'M' + pts.join('L') + 'Z';
  }
  pContact.setAttribute('d', c);
}

/* ---------- floor plate: a calibration turntable with degree ticks ---------- */
function drawFloor() {
  const ring = (r, n = 72) => { let d = ''; for (let i = 0; i <= n; i++) { const a = i / n * TAU; proj(Math.sin(a) * r, 0, Math.cos(a) * r, tmp3); d += (i ? 'L' : 'M') + r1(tmp3[0]) + ',' + r1(tmp3[1]); } return d + 'Z'; };
  let ticks = '';
  for (let i = 0; i < 72; i++) {
    const a = i / 72 * TAU, long = i % 6 === 0, r0 = long ? 1.36 : 1.43, r2 = 1.5;
    proj(Math.sin(a) * r0, 0, Math.cos(a) * r0, tmp3); ticks += 'M' + r1(tmp3[0]) + ',' + r1(tmp3[1]);
    proj(Math.sin(a) * r2, 0, Math.cos(a) * r2, tmp3); ticks += 'L' + r1(tmp3[0]) + ',' + r1(tmp3[1]);
  }
  let cross = '';
  for (const [ax, az] of [[1, 0], [0, 1]]) {
    proj(-ax * .16, 0, -az * .16, tmp3); cross += 'M' + r1(tmp3[0]) + ',' + r1(tmp3[1]);
    proj(ax * .16, 0, az * .16, tmp3); cross += 'L' + r1(tmp3[0]) + ',' + r1(tmp3[1]);
  }
  proj(0, 0, 1.6, tmp3); const lx = tmp3[0], ly = tmp3[1];
  gFloor.innerHTML = `<path class="plate" d="${ring(1.5, 96)}"/><path class="plate-ring" d="${ring(1.08)}"/><path class="plate-ring" d="${ring(.56)}"/>` +
    `<path class="plate-tick" d="${ticks}"/><path class="plate-tick" d="${cross}"/>` +
    `<text class="plate-label" x="${r1(lx)}" y="${r1(ly + 4)}" text-anchor="middle" dominant-baseline="hanging">0°</text>`;
}

/* ---------- emotes: glyphs that float up from a world point ---------- */
const FXL = [];
const fxPool = [];
function emote(text, pos, opt = {}) {
  FXL.push({ text, x: pos[0], y: pos[1], z: pos[2], vx: opt.vx ?? (Math.random() - .5) * .2, vy: opt.vy ?? .45, vz: 0, life: 0, max: opt.life ?? 1.6, size: opt.size ?? 1, color: opt.color ?? 'var(--ink)', spin: opt.spin ?? 0 });
  if (FXL.length > 24) FXL.shift();
}
function drawFx(dt) {
  for (let i = FXL.length - 1; i >= 0; i--) {
    const e = FXL[i]; e.life += dt; e.x += e.vx * dt; e.y += e.vy * dt; e.z += e.vz * dt;
    if (e.life > e.max) FXL.splice(i, 1);
  }
  while (fxPool.length < FXL.length) { const t = document.createElementNS(SVGNS, 'text'); t.setAttribute('text-anchor', 'middle'); gFx.appendChild(t); fxPool.push(t); }
  for (let k = 0; k < fxPool.length; k++) {
    const t = fxPool[k], e = FXL[k];
    if (!e) { if (t.textContent) t.textContent = ''; continue; }
    proj(e.x, e.y, e.z, tmp3);
    const a = e.life / e.max, op = Math.min(1, e.life * 6) * (1 - smooth(ramp(a, .6, 1)));
    const sc = (FOC / tmp3[2]) * .0042 * e.size * (1 + .25 * easeOut(a * 3));
    if (t.textContent !== e.text) t.textContent = e.text;
    t.setAttribute('transform', `translate(${r1(tmp3[0])},${r1(tmp3[1])}) scale(${sc.toFixed(3)}) rotate(${(Math.sin(e.life * 5) * 12 * e.spin).toFixed(1)})`);
    t.setAttribute('opacity', op.toFixed(3)); t.style.fill = e.color;
  }
}
/* =========================================================================
   Pose channels, blending helpers, and procedural locomotion with
   world-planted feet (no foot sliding): steering, gait phase, swing arcs,
   heel-strike / toe-off, pelvis bob, counter-rotation and arm swing.
   ========================================================================= */
const REST = {
  rx: 0, ry: 0, rz: 0, yaw: 0, pitch: 0, roll: 0,
  spX: 0, spY: 0, spZ: 0, nkX: 0, nkY: 0, nkZ: 0, hdX: 0, hdY: 0, hdZ: 0, shL: 0, shR: 0,
  aLx: .05, aLy: 0, aLz: .14, eL: .24, twL: 0, wLx: 0, wLz: 0, cL: .38, tL: .3, spL: 0,
  aRx: .05, aRy: 0, aRz: .14, eR: .24, twR: 0, wRx: 0, wRz: 0, cR: .38, tR: .3, spR: 0,
  lLx: 0, lLy: 0, lLz: 0, kL: 0, anLx: 0, anLz: 0, toL: 0,
  lRx: 0, lRy: 0, lRz: 0, kR: 0, anRx: 0, anRz: 0, toR: 0,
  ikL: 1, ikR: 1, fLx: .135, fLy: ANKLE_H, fLz: 0, fLp: 0, fLw: .07, fRx: -.135, fRy: ANKLE_H, fRz: 0, fRp: 0, fRw: -.07,
  eye: 1, happy: 0, wide: 0, angry: 0, squint: 0, xeye: 0, heart: 0, warm: 0, gx: 0, gy: 0,
  mOpen: 0, mSmile: 0, mShow: 0, mWide: 1, mRound: 0, glow: 1, core: .7, led: 1, ledWarm: 0, look: 1, shades: 0, sweat: 0,
  /* arm IK: wrist target (x,y,z), weight w, frame blend s (spine) / h (head); pole p (body frame) */
  hLx: .22, hLy: 1.0, hLz: .3, hLw: 0, hLs: 0, hLh: 0, pLx: .45, pLy: -1, pLz: -.45,
  hRx: -.22, hRy: 1.0, hRz: .3, hRw: 0, hRs: 0, hRh: 0, pRx: -.45, pRy: -1, pRz: -.45,
  /* palm aim (body frame) */
  nLx: 0, nLy: -1, nLz: 0, nLw: 0, nRx: 0, nRy: -1, nRz: 0, nRw: 0,
};
const KEYS = Object.keys(REST);
const ANGK = { yaw: 1, pitch: 1, roll: 1 };
const newPose = () => Object.assign({}, REST);
function copyPose(o, a) { for (const k of KEYS) o[k] = a[k]; return o; }
function blendPose(o, a, b, t) { for (const k of KEYS) o[k] = ANGK[k] ? lerpA(a[k], b[k], t) : a[k] + (b[k] - a[k]) * t; return o; }
/* move p toward the values in q by weight k (only listed keys) */
function mix(p, q, k) { if (k <= 0) return p; for (const key in q) p[key] = ANGK[key] ? lerpA(p[key], q[key], k) : p[key] + (q[key] - p[key]) * k; return p; }
/* add offsets scaled by k */
function add(p, q, k = 1) { for (const key in q) p[key] += q[key] * k; return p; }
/* mirror a one-sided pose spec: keys containing L ↔ R (feet x negated) */
function mirror(q) {
  const o = {};
  for (const k in q) {
    let m = k.replace(/^(a|e|f|w|c|t|tw|sp|sh|l|k|an|to|ik|h|p|n)(L|R)/, (_, a, s) => a + (s === 'L' ? 'R' : 'L'));
    let v = q[k];
    if (/^[fhpn][LR]x$/.test(k) || /^f[LR]w$/.test(k)) v = -v;
    if (['rx', 'yaw', 'roll', 'spY', 'spZ', 'nkY', 'nkZ', 'hdY', 'hdZ', 'gx'].includes(k)) v = -v;
    o[m] = v;
  }
  return o;
}

/* ---------- body placement on the floor ---------- */
const body = { x: 0, z: 0, heading: 0 };
const toLocalXZ = (wx, wz) => { const dx = wx - body.x, dz = wz - body.z, c = Math.cos(body.heading), s = Math.sin(body.heading); return [dx * c - dz * s, dx * s + dz * c]; };

/* ---------- locomotion ---------- */
const LOCO = {
  phase: 0, speed: 0, err: 0, moving: false, gait: 'walk',
  feet: [{ S: 'L', sg: 1 }, { S: 'R', sg: -1 }],
};
function homeOf(f, heading = body.heading, x = body.x, z = body.z, width = .135) {
  return [x + Math.cos(heading) * width * f.sg, z - Math.sin(heading) * width * f.sg, heading + .07 * f.sg];
}
function locoPlant() {
  for (const f of LOCO.feet) { const h = homeOf(f); Object.assign(f, { x: h[0], z: h[1], yaw: h[2], swing: false, h: 0, pitch: 0, tx: h[0], tz: h[1], tyaw: h[2] }); }
  LOCO.speed = 0; LOCO.moving = false;
}
/* plant feet from wherever the current pose has them (used when a move hands over to locomotion) */
function locoPlantFrom(p) {
  for (const f of LOCO.feet) {
    const lx = p['f' + f.S + 'x'], lz = p['f' + f.S + 'z'], c = Math.cos(body.heading), s = Math.sin(body.heading);
    const wx = body.x + lx * c + lz * s, wz = body.z - lx * s + lz * c;
    Object.assign(f, { x: wx, z: wz, yaw: body.heading + p['f' + f.S + 'w'], swing: false, h: 0, pitch: 0, tx: wx, tz: wz, tyaw: body.heading + p['f' + f.S + 'w'] });
  }
}
const GAIT = {
  walk: { T: 1.0, swing: .40, turn: 2.1, acc: 3.5, lift: .085 },
  run: { T: .64, swing: .62, turn: 2.6, acc: 2.4, lift: .17 },
};
/* cmd: { tx, tz, face, speed, gait, arrive } — returns distance to target */
function locoStep(dt, cmd) {
  const G = GAIT[cmd.gait || 'walk']; LOCO.gait = cmd.gait || 'walk';
  let want = body.heading, dist = 0;
  if (cmd.tx !== undefined) {
    const dx = cmd.tx - body.x, dz = cmd.tz - body.z; dist = Math.hypot(dx, dz);
    if (dist > .06) want = Math.atan2(dx, dz); else if (cmd.face !== undefined) want = cmd.face;
  } else if (cmd.face !== undefined) want = cmd.face;
  const err = wrapA(want - body.heading); LOCO.err = err;
  body.heading = wrapA(body.heading + clamp(err, -G.turn * dt, G.turn * dt));
  let v = (cmd.speed || 0) * clamp(1 - Math.abs(err) / 1.5, .12, 1);
  if (cmd.arrive) v *= clamp(dist / .5, 0, 1);
  if (cmd.tx !== undefined && dist <= .06) v = 0;
  LOCO.speed = damp(LOCO.speed, v, G.acc, dt);
  if (LOCO.speed < .004 && v === 0) LOCO.speed = 0;
  body.x += Math.sin(body.heading) * LOCO.speed * dt; body.z += Math.cos(body.heading) * LOCO.speed * dt;

  let far = false;
  for (const f of LOCO.feet) { const h = homeOf(f); if (Math.hypot(f.x - h[0], f.z - h[1]) > .05 || Math.abs(wrapA(f.yaw - h[2])) > .16) far = true; }
  const moving = LOCO.speed > .03 || Math.abs(err) > .1 || far;
  const swinging = LOCO.feet.some(f => f.swing);
  LOCO.moving = moving || swinging;
  if (LOCO.moving) LOCO.phase = (LOCO.phase + dt / G.T) % 1;

  const fwdx = Math.sin(body.heading), fwdz = Math.cos(body.heading);
  for (const f of LOCO.feet) {
    const q = ((LOCO.phase - (f.sg > 0 ? 0 : .5)) % 1 + 1) % 1;
    const inSwing = q < G.swing && (moving || f.swing);
    if (inSwing) {
      if (!f.swing) { f.swing = true; f.fx = f.x; f.fz = f.z; f.fyaw = f.yaw; }
      const s = q / G.swing, remain = (G.swing - q) * G.T;
      const turnAhead = clamp(err, -G.turn * remain, G.turn * remain);
      const lead = LOCO.speed * G.T * (1 - G.swing) * .5 + LOCO.speed * remain;
      const hm = homeOf(f, body.heading + turnAhead, body.x + fwdx * lead, body.z + fwdz * lead);
      f.tx = hm[0]; f.tz = hm[1]; f.tyaw = hm[2];
      const e = smoother(s), stride = Math.hypot(f.tx - f.fx, f.tz - f.fz);
      f.x = lerp(f.fx, f.tx, e); f.z = lerp(f.fz, f.tz, e); f.yaw = lerpA(f.fyaw, f.tyaw, e);
      const lift = LOCO.gait === 'run' ? G.lift : clamp(.035 + stride * .22, .035, G.lift);
      f.h = Math.sin(PI * Math.pow(s, .85)) * lift;
      f.pitch = LOCO.gait === 'run' ? lerp(.55, -.2, smooth(s)) : (s < .3 ? lerp(.35, 0, s / .3) : -.28 * smooth((s - .3) / .7));
    } else {
      if (f.swing) { f.swing = false; f.x = f.tx; f.z = f.tz; f.yaw = f.tyaw; }
      f.h = 0;
      const st = (q - G.swing) / (1 - G.swing);
      const go = clamp(LOCO.speed / .4, 0, 1);
      f.pitch = go * (st < .15 ? -.28 * (1 - st / .15) : st > .72 ? .42 * smooth((st - .72) / .28) : 0);
    }
  }
  return dist;
}
/* write locomotion state into a pose */
function locoPose(p, t, look = .4) {
  const run = LOCO.gait === 'run', ws = clamp(LOCO.speed / (run ? 1.4 : .5), 0, 1.2), ph = LOCO.phase;
  let fwdL = 0, fwdR = 0;
  for (const f of LOCO.feet) {
    const [lx, lz] = toLocalXZ(f.x, f.z), S = f.S;
    p['f' + S + 'x'] = lx; p['f' + S + 'z'] = lz; p['f' + S + 'w'] = wrapA(f.yaw - body.heading);
    p['f' + S + 'p'] = f.pitch;
    p['f' + S + 'y'] = ANKLE_H + f.h + Math.max(0, f.pitch) * .1 + Math.max(0, -f.pitch) * .025;
    p['to' + S] = -Math.max(0, f.pitch) * .9;
    if (S === 'L') fwdL = lz; else fwdR = lz;
  }
  const bob = Math.cos(TAU * 2 * (ph - .2));
  if (run) {
    p.ry = -.06 + .045 * bob * ws; p.pitch = .2 * ws; p.spX = .05 * ws; p.hdX = -.2 * ws;
    p.yaw = .14 * Math.sin(TAU * ph) * ws; p.spY = -p.yaw * 1.5; p.roll = .03 * Math.sin(TAU * ph) * ws;
    for (const S of ['L', 'R']) { p['e' + S] = 1.55; p['c' + S] = .9; p['t' + S] = .7; p['a' + S + 'z'] = .2; }
    p.aLx = -fwdR * 3.2 - .1; p.aRx = -fwdL * 3.2 - .1;
    p.eL += Math.max(0, -p.aLx) * .35; p.eR += Math.max(0, -p.aRx) * .35;
    p.wide = .25 * ws; p.angry = .35 * ws;
  } else {
    p.ry = (-.012 + .016 * bob) * ws; p.pitch = .045 * ws; p.hdX = -.04 * ws;
    p.yaw = .08 * Math.sin(TAU * ph) * ws; p.spY = -p.yaw * 1.3;
    p.roll = .035 * Math.sin(TAU * (ph - .1)) * ws; p.spZ = -p.roll * .6; p.rx = .018 * Math.sin(TAU * ph) * ws;
    p.aLx = -fwdR * 2.5 + .02; p.aRx = -fwdL * 2.5 + .02;
    p.eL = .3 + Math.max(0, -p.aLx) * .6; p.eR = .3 + Math.max(0, -p.aRx) * .6;
    p.aLz = p.aRz = .16;
  }
  p.look = look;
  return p;
}
/* =========================================================================
   Move library. Each move writes into a pose that starts at REST.
   t = seconds since the move began. One-shots have `dur`; loops run until replaced.
   ========================================================================= */
const MOVES = {};
const defMove = (id, o) => (o.id = id, MOVES[id] = o);
/* lift the feet while they shuffle into a new stance (k = transition progress 0..1) */
const shuffle = (p, k, h = .055) => { const l = Math.sin(PI * sat(k)) * h; p.fLy += l; p.fRy += l * .9; };

defMove('idle', {
  label: 'Idle', loop: true,
  run(p, t) {
    const s = noise1(t * .16 + 3.1);
    p.rx = s * .032; p.roll = -s * .03; p.spZ = s * .022;
    p.spY = noise1(t * .13 + 2) * .05; p.hdZ = noise1(t * .21 + 8) * .07;
    p.aLx += noise1(t * .3 + 1) * .035; p.aRx += noise1(t * .3 + 4) * .035;
    p.cL += noise1(t * .4) * .1; p.cR += noise1(t * .4 + 2) * .1;
  }
});

defMove('wave', {
  label: 'Wave', key: '1', group: 'Greet', dur: 2.8,
  ev: [[.35, c => c.say('Hi!', 'handL', { size: 1.25 })]],
  run(p, t) {
    const k = win(t, 0, .42, 2.25, 2.8), w = Math.sin((t - .3) * 10.5) * k;
    mix(p, { aLx: -.2, aLy: 1.45, aLz: 1.78, eL: 1.45, twL: .3, cL: .03, tL: .05, spL: .8, shL: .45, spZ: .06, roll: -.025, hdZ: -.12, hdY: .1, happy: .9, mShow: .85, mSmile: .85, mOpen: .15, aRz: .2, aRx: .1 }, k);
    p.eL += w * .42; p.wLz = -Math.sin((t - .3) * 10.5 - .8) * .3 * k;
  }
});

defMove('bow', {
  label: 'Bow', key: '2', group: 'Greet', dur: 3.1,
  run(p, t) {
    const k = win(t, 0, .45, 2.45, 3.1), b = win(t, .4, 1.05, 1.8, 2.5);
    mix(p, { aRx: -.42, aRy: -1.2, aRz: .14, eR: 1.9, twR: -.15, cR: .08, tR: .1, aLx: .55, aLy: -.95, aLz: .2, eL: 1.5, cL: .3 }, k);
    p.pitch += .56 * b; p.spX += .15 * b; p.rz -= .08 * b; p.ry -= .025 * b; p.hdX += .18 * b; p.nkX += .06 * b;
    p.happy = .9 * k; p.eye = lerp(1, .12, b); p.look = 1 - k; p.mShow = .55 * k * (1 - b); p.mSmile = .7;
  }
});

defMove('walk', {
  label: 'Walk', key: '3', group: 'Move', loop: true, loco: true,
  run(p, t, dt) {
    const R = .8, r = Math.hypot(body.x, body.z);
    let tx, tz;
    if (r < .4) { const a = Math.atan2(body.x, body.z) + .9; tx = Math.sin(a) * R; tz = Math.cos(a) * R; }
    else { const a = Math.atan2(body.x, body.z) + .62; tx = Math.sin(a) * R; tz = Math.cos(a) * R; }
    locoStep(dt, { tx, tz, speed: .5, gait: 'walk' });
    locoPose(p, t, .45); p.happy = .25;
  }
});

defMove('run', {
  label: 'Run', key: '4', group: 'Move', loop: true, loco: true,
  run(p, t, dt) {
    const R = .9, a = Math.atan2(body.x, body.z) + .55, r = Math.hypot(body.x, body.z);
    const tx = Math.sin(r < .4 ? a + .5 : a) * R, tz = Math.cos(r < .4 ? a + .5 : a) * R;
    locoStep(dt, { tx, tz, speed: 1.35, gait: 'run' });
    locoPose(p, t, .2);
  }
});

defMove('jump', {
  label: 'Jump', key: '5', group: 'Move', dur: 1.95,
  run(p, t) {
    const T0 = .44, TA = .58, LT = T0 + TA, air = t > T0 && t < LT, s = ramp(t, T0, LT);
    const crouch = win(t, 0, .34, .38, .48), land = win(t, LT, LT + .05, LT + .12, LT + .55);
    const y = air ? 4 * .5 * s * (1 - s) : 0, up = air ? win(s, 0, .3, .55, 1) : 0;
    p.ry = -.21 * crouch + y - .19 * land; p.pitch = .3 * crouch + .14 * land; p.spX = .14 * crouch + .1 * land; p.hdX = -.1 * crouch;
    const ax = .05 + .8 * crouch - 2.75 * up - .3 * land;
    p.aLx = p.aRx = ax; p.aLz = p.aRz = .14 + .25 * up + .55 * land; p.eL = p.eR = .3 + .5 * crouch - .2 * up;
    p.cL = p.cR = .6 * crouch + .1;
    const tuck = air ? Math.sin(PI * s) * .17 : 0;
    p.fLy = p.fRy = ANKLE_H + Math.max(0, y - .03) + tuck;
    p.fLp = p.fRp = .5 * win(t, .36, .44, .46, .56) - .15 * tuck;
    p.squint = .35 * crouch; p.angry = .25 * crouch; p.wide = .6 * up;
    p.mShow = .8 * (up + land); p.mOpen = .5 * up; p.mSmile = .9 * land; p.happy = .8 * land;
    p.look = 1 - .7 * (crouch + up);
  }
});

defMove('flip', {
  label: 'Backflip', key: '6', group: 'Move', dur: 2.6,
  ev: [[1.35, c => c.say('★', 'head', { size: 1.4, color: 'var(--accent)' })]],
  run(p, t) {
    const T0 = .52, TA = .8, LT = T0 + TA, air = t > T0 && t < LT, s = ramp(t, T0, LT);
    const crouch = win(t, .05, .42, .46, .56), land = win(t, LT, LT + .06, LT + .15, LT + .62);
    const y = air ? 4 * .8 * s * (1 - s) : 0;
    const rot = air ? -TAU * easeIO(ramp(s, .06, .9)) : t >= LT ? -TAU : 0;
    const d = .3; /* rotate about the centre of mass, not the pelvis */
    p.pitch = rot + .32 * crouch + .16 * land;
    p.ry = -.24 * crouch + y - .2 * land + d * (1 - Math.cos(rot)); p.rz = -d * Math.sin(rot) - .05 * crouch;
    p.spX = .1 * crouch + .1 * land;
    const tuck = air ? win(s, .06, .3, .68, .9) : 0, reach = air ? win(s, 0, .12, .2, .32) : 0;
    const ik = 1 - win(t, T0 - .02, T0 + .08, LT - .14, LT); p.ikL = p.ikR = ik;
    p.lLx = p.lRx = -1.8 * tuck; p.kL = p.kR = 2.2 * tuck; p.anLx = p.anRx = .35 * tuck;
    p.lLz = p.lRz = .12 * tuck;
    const ta = win(t, LT + .15, LT + .45, 2.15, 2.6);
    p.aLx = p.aRx = .05 + .85 * crouch - 2.9 * reach - .95 * tuck - .35 * ta;
    p.aLz = p.aRz = .14 + .25 * tuck + 1.1 * ta + .3 * land; p.eL = p.eR = .3 + .4 * crouch + 1.2 * tuck - .2 * ta;
    p.cL = p.cR = .4 + .6 * tuck - .35 * ta; p.twL = p.twR = .9 * ta;
    p.angry = .4 * crouch; p.squint = .3 * crouch; p.wide = .7 * (air ? 1 : 0) * (1 - tuck * .5);
    p.happy = .95 * Math.max(land, ta); p.mShow = Math.max(.7 * (air ? 1 : 0), ta); p.mOpen = air ? .55 : .25 * ta; p.mSmile = ta;
    p.look = 1 - win(t, .1, .3, LT + .1, LT + .4);
  }
});

/* --- dance: three figures cycling every 8 beats at 118 bpm --- */
const BPM = 118;
function danceFig(p, i, b) {
  const s = Math.sin(PI * b), c = Math.cos(PI * b), bounce = 1 - Math.pow(Math.abs(Math.sin(PI * b)), .7);
  p.ry = -.03 - .05 * bounce; p.hdX = .1 * bounce - .04; p.spX = .03 * bounce;
  if (i === 0) { /* side-to-side with arm swings */
    const sw = Math.sin(PI * b / 2);
    p.rx = .08 * sw; p.roll = -.07 * sw; p.spZ = .09 * sw; p.hdZ = -.12 * sw;
    p.fLx = .2 + .05 * Math.max(0, sw); p.fRx = -.2 + .05 * Math.min(0, sw);
    p.aLx = -.55 - .45 * Math.max(0, sw); p.aRx = -.55 - .45 * Math.max(0, -sw);
    p.aLz = p.aRz = .35; p.eL = 1.5 + .3 * s; p.eR = 1.5 - .3 * s; p.aLy = p.aRy = -.35;
    p.cL = p.cR = .9; p.yaw = .15 * sw;
  } else if (i === 1) { /* raise the roof */
    const pump = Math.abs(s);
    p.aLz = p.aRz = 2.35 - .25 * pump; p.aLx = p.aRx = -.35; p.aLy = p.aRy = 1.4; p.eL = p.eR = 1.25 + .45 * pump;
    p.twL = p.twR = 1.2; p.cL = p.cR = .05; p.spL = p.spR = .5;
    p.fLx = .2; p.fRx = -.2; p.yaw = .25 * Math.sin(PI * b / 4); p.spY = -.1 * Math.sin(PI * b / 4);
    p.hdY = .2 * Math.sin(PI * b / 4);
  } else { /* shoulder shimmy + step touch */
    const st = Math.sin(PI * b / 2), sh = Math.sin(PI * b * 2);
    p.shL = .5 + .5 * sh; p.shR = .5 - .5 * sh; p.spY = .16 * sh; p.yaw = -.05 * sh;
    p.aLx = p.aRx = -.35; p.aLz = p.aRz = .55; p.eL = p.eR = 1.6; p.aLy = p.aRy = -.6; p.cL = p.cR = 1;
    p.fLx = .16; p.fRx = -.16;
    const lift = Math.max(0, st), liftR = Math.max(0, -st);
    p.fLy += .07 * lift * Math.abs(s); p.fRy += .07 * liftR * Math.abs(s);
    p.fLz = .08 * lift; p.fRz = .08 * liftR; p.fLp = -.35 * lift; p.fRp = -.35 * liftR;
    p.rx = -.04 * st; p.hdZ = .1 * st;
  }
}
const _da = newPose(), _db = newPose();
defMove('dance', {
  label: 'Dance', key: '7', group: 'Groove', loop: true,
  run(p, t, dt, c) {
    const b = t * BPM / 60, bar = b / 8, i = Math.floor(bar) % 3, f = smooth(ramp(bar % 1, .88, 1));
    copyPose(_da, p); copyPose(_db, p);
    danceFig(_da, i, b); danceFig(_db, (i + 1) % 3, b);
    blendPose(p, _da, _db, f);
    const intro = smooth(ramp(t, 0, .5)); if (intro < 1) blendPose(p, REST, p, intro);
    p.happy = .85; p.squint = .2; p.mShow = .9; p.mSmile = .9; p.mOpen = .25 + .2 * Math.abs(Math.sin(PI * b));
    p.look = .3; p.core = .9 + .5 * Math.pow(1 - (b % 1), 3); p.led = .55 + .45 * Math.pow(1 - (b % 1), 2);
    c.tick('note', 120 / BPM, n => c.say(n % 2 ? '♫' : '♪', 'head', { side: n % 2 ? 1 : -1, color: 'var(--accent)' }));
  }
});

/* --- robot: pop-and-lock between held poses --- */
const RB = { aLx: 0, aLy: 0, aLz: .14, eL: .24, twL: 0, aRx: 0, aRy: 0, aRz: .14, eR: .24, twR: 0, hdY: 0, hdZ: 0, spY: 0, spZ: 0, ry: -.02, cL: 0, cR: 0, yaw: 0 };
const RPOSES = [
  { aLx: -.1, eL: 1.57, aRx: -.1, eR: 1.57, hdY: 0 },
  { aLz: 1.57, aLy: 1.57, eL: 1.57, aRx: -.1, eR: 1.57, hdY: .65, spY: .2 },
  { aLz: 1.57, aLy: 1.57, eL: 1.57, aRz: 1.57, aRy: -1.57, eR: 1.57, hdY: -.6, spY: -.15, ry: -.07 },
  { aLz: 1.57, eL: 0, aRz: 1.57, eR: 0, hdZ: .25, spZ: .12 },
  { aLz: 1.57, aLy: -1.57, eL: 1.57, aRz: 1.57, aRy: -1.57, eR: 1.57, hdY: 0, ry: -.1 },
  { aLx: -1.57, eL: 0, aRx: -1.57, eR: 0, hdZ: -.2, cL: 0, cR: 0 },
  { aLz: 2.4, eL: 0, aRz: .5, eR: 0, aRx: .1, hdY: .5, yaw: .25, spZ: -.1 },
  { aLz: .5, eL: 0, aRz: 2.4, eR: 0, hdY: -.5, yaw: -.25, spZ: .1 },
].map(q => Object.assign({}, RB, q));
const _ra = newPose();
defMove('robot', {
  label: 'Robot', key: '8', group: 'Groove', loop: true,
  run(p, t, dt, c) {
    const seg = .6, i = Math.floor(t / seg), f = (t % seg) / seg;
    const A = RPOSES[(i + RPOSES.length - 1) % RPOSES.length], B = RPOSES[i % RPOSES.length];
    const e = f < .2 ? backOut(f / .2, 2.4) : 1, buzz = Math.sin(f * 70) * Math.exp(-f * 9) * .025;
    const k = smooth(ramp(t, 0, .25));
    copyPose(_ra, p); mix(_ra, A, 1); mix(_ra, B, e); _ra.hdY += buzz; _ra.aLx += buzz; _ra.aRx -= buzz;
    blendPose(p, p, _ra, k);
    p.squint = .3; p.mShow = .7; p.mSmile = 0; p.mOpen = 0; p.look = 0; p.gx = -B.hdY * .6; p.core = 1;
    c.tick('click', seg, () => { });
  }
});

defMove('flex', {
  label: 'Flex', key: 'Q', group: 'Show off', dur: 3.4,
  ev: [[.9, c => c.say('★', 'handL', { color: 'var(--accent)' })], [1.4, c => c.say('★', 'handR', { color: 'var(--accent)' })]],
  run(p, t) {
    const k = win(t, 0, .42, 2.85, 3.4), pulse = Math.max(0, Math.sin((t - .55) * 7.5)) * win(t, .5, .7, 2.5, 2.8);
    mix(p, { aLx: -.08, aLy: 1.42, aLz: 1.52, eL: 2.1, cL: 1, tL: 1, twL: .35, aRx: -.08, aRy: 1.42, aRz: 1.52, eR: 2.1, cR: 1, tR: 1, twR: .35,
      ry: -.07, spX: -.09, hdX: -.08, fLx: .21, fRx: -.21, fLw: .25, fRw: -.25, shL: .25, shR: .25, angry: .4, happy: .3, mShow: .9, mSmile: 1, mOpen: .3, core: 1.5, look: .4 }, k);
    shuffle(p, ramp(t, 0, .42)); shuffle(p, ramp(t, 2.85, 3.4));
    p.eL += pulse * .14; p.eR += pulse * .14; p.core += pulse * .5; p.ry -= pulse * .02; p.spX -= pulse * .03;
  }
});

defMove('kungfu', {
  label: 'Kung fu', key: 'W', group: 'Show off', dur: 4.9,
  ev: [[2.48, c => c.say('HYA!', 'head', { size: 1.1, color: 'var(--accent)' })]],
  run(p, t) {
    const k = win(t, 0, .4, 4.3, 4.9), sal = win(t, 3.5, 3.8, 4.3, 4.7);
    const st = k * (1 - sal * .6);
    mix(p, { fLx: .2, fRx: -.21, fLz: .13, fRz: -.12, fLw: .1, fRw: -.5, ry: -.13, yaw: .32, spY: -.12, hdY: -.25,
      aLx: -1.0, aLy: -.55, aLz: .25, eL: 2.0, cL: 1, tL: 1, aRx: -.75, aRy: -.75, aRz: .2, eR: 2.2, cR: 1, tR: 1, angry: .55, look: 0, gx: .2 }, st);
    shuffle(p, ramp(t, 0, .4), .07);
    const jab = (t0) => win(t, t0, t0 + .08, t0 + .2, t0 + .34);
    const jl = jab(.85) + jab(1.6), jr = jab(1.22);
    mix(p, { aLx: -1.55, aLy: 0, aLz: .1, eL: .05, spY: -.25 }, jl); mix(p, { aRx: -1.55, aRy: 0, aRz: .05, eR: .05, spY: .3, yaw: .1 }, jr);
    const kick = win(t, 2.05, 2.3, 2.85, 3.15), snap = win(t, 2.32, 2.42, 2.6, 2.75);
    p.ikR = 1 - kick; p.lRx = -1.5 * kick - .2 * snap; p.kR = 1.9 * kick * (1 - snap * .95); p.anRx = .3 * kick; p.lRz = .05;
    p.pitch -= .12 * kick; p.ry += .03 * kick; p.rx += .03 * kick;
    p.wide = .4 * snap; p.mShow = .8 * snap + .7 * sal; p.mOpen = .6 * snap; p.mSmile = sal;
    mix(p, { aLx: -.85, aLy: -.95, aLz: .2, eL: 1.75, cL: 0, tL: .2, twL: .2, aRx: -.85, aRy: -.95, aRz: .2, eR: 1.75, cR: 1, yaw: 0, spY: 0, hdY: 0, hdX: .15, pitch: .2, angry: 0, happy: .9, eye: .6 }, sal);
  }
});

defMove('cheer', {
  label: 'Cheer', key: 'E', group: 'Show off', dur: 3.3,
  ev: [[.62, c => c.say('★', 'handL', { color: 'var(--accent)' })], [1.32, c => c.say('★', 'handR', { color: 'var(--accent)' })], [2.02, c => c.say('★', 'head', { size: 1.3, color: 'var(--accent)' })]],
  run(p, t) {
    const k = win(t, 0, .35, 2.75, 3.3);
    let y = 0, sq = 0;
    for (const h of [.45, 1.15, 1.85]) { const s = ramp(t, h, h + .32); if (s > 0 && s < 1) y += .15 * Math.sin(PI * s); sq += win(t, h - .14, h - .04, h, h + .05) + win(t, h + .32, h + .36, h + .4, h + .5); }
    const pump = Math.max(0, Math.sin(t * 9.5)) * win(t, .3, .5, 2.5, 2.8);
    mix(p, { aLx: -.25, aLz: 2.62, eL: .25, cL: 1, tL: 1, aRx: -.25, aRz: 2.62, eR: .25, cR: 1, tR: 1, happy: 1, mShow: 1, mOpen: .55, mSmile: 1, squint: .2, core: 1.3 }, k);
    p.eL += pump * .5; p.eR += pump * .5;
    p.ry += y - .1 * sq; p.fLy += Math.max(0, y - .02); p.fRy += Math.max(0, y - .02); p.fLp = p.fRp = .3 * (y > .02 ? 1 : 0);
    p.look = .5;
  }
});

defMove('think', {
  label: 'Think', key: 'R', group: 'Chill', dur: 4.8,
  ev: [[.8, c => c.say('?', 'head')], [2.0, c => c.say('…', 'head')], [3.3, c => c.say('!', 'head', { size: 1.5, color: 'var(--accent)' })]],
  run(p, t) {
    const k = win(t, 0, .5, 3.2, 3.45), idea = win(t, 3.2, 3.4, 4.2, 4.8);
    mix(p, { aRx: -.64, aRy: -1.25, aRz: -1.0, eR: 1.6, twR: .05, cR: .55, tR: .4, wRx: .23,
      aLx: -.22, aLy: -1.25, aLz: .1, eL: 1.55, cL: .3,
      hdZ: .12, hdX: -.1, spX: .06, look: 0, gx: .55, gy: .65, squint: .35, angry: -.35, mShow: .5, mSmile: -.15 }, k);
    p.hdZ += Math.sin(t * 1.4) * .03 * k; p.gx += Math.sin(t * .9) * .25 * k;
    mix(p, { aRx: -.3, aRz: 2.55, aRy: .2, eR: .15, cR: .9, tR: .9, wide: 1, happy: .2, mShow: .9, mOpen: .45, mSmile: .8, ledWarm: 1, look: 1, gy: .2, ry: .03 }, idea);
    if (idea > 0) p.ry += .03 * Math.sin(PI * ramp(t, 3.2, 3.6));
  }
});

defMove('shrug', {
  label: 'Shrug', key: 'T', group: 'Chill', dur: 2.5,
  ev: [[.5, c => c.say('?', 'head', { size: 1.2 })]],
  run(p, t) {
    const k = win(t, 0, .45, 1.75, 2.5), up = win(t, .2, .5, 1.5, 1.9);
    mix(p, { aLx: -.28, aLy: 1.05, aLz: .55, eL: 1.35, twL: 1.35, cL: 0, tL: 0, spL: .5, aRx: -.28, aRy: 1.05, aRz: .55, eR: 1.35, twR: 1.35, cR: 0, tR: 0, spR: .5,
      hdZ: .17, angry: -.65, mShow: .8, mSmile: -.35, look: .6 }, k);
    p.shL = p.shR = up; p.hdX -= .08 * up; p.spX -= .03 * up;
  }
});

defMove('spin', {
  label: 'Spin', key: 'Y', group: 'Groove', dur: 2.7,
  run(p, t) {
    const k = win(t, 0, .45, 2.1, 2.7), sp = easeIO(ramp(t, .5, 1.75)), leg = win(t, .38, .58, 1.7, 1.92), ta = win(t, 1.8, 2.05, 2.3, 2.7);
    const yaw = TAU * sp;
    p.yaw = yaw; p.fRx = lerp(-.135, -.015, k); p.fRw = -.07 + yaw; p.fRp = .25 * leg; p.fRy = ANKLE_H + .03 * leg;
    shuffle(p, ramp(t, 0, .35), .04);
    p.ikL = 1 - leg; p.lLx = -1.0 * leg; p.lLz = .55 * leg; p.lLy = -.6 * leg; p.kL = 1.9 * leg; p.anLx = .5 * leg;
    p.ry = -.03 * k + .02 * leg; p.rx = -.012 * k;
    mix(p, { aLx: -.35, aLy: -1.1, aLz: .3, eL: 1.85, aRx: -.35, aRy: -1.1, aRz: .3, eR: 1.85, cL: .7, cR: .7 }, leg);
    mix(p, { aLx: -.2, aLz: 1.35, eL: .25, twL: 1.3, aRx: -.2, aRz: 1.35, eR: .25, twR: 1.3, cL: .05, cR: .05, spL: .6, spR: .6 }, ta);
    p.happy = .9 * Math.max(leg, ta); p.mShow = Math.max(leg * .6, ta); p.mSmile = 1; p.mOpen = .35 * ta; p.look = 1 - leg;
    p.hdY = -wrapA(yaw) * .25 * leg;
  }
});

defMove('sleep', {
  label: 'Power down', key: 'Z', group: 'Chill', loop: true,
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, 1.5)), b = Math.sin(t * 1.15);
    mix(p, { ry: -.13, spX: .3, nkX: .22, hdX: .42, hdZ: .12, aLx: -.1, aRx: -.1, aLz: .07, aRz: .07, eL: .12, eR: .12, cL: .15, cR: .15, tL: .1, tR: .1,
      shL: -.35, shR: -.35, eye: 0, glow: .32, core: .12, led: .2, look: 0, gy: -.5, fLw: .2, fRw: -.2 }, k);
    p.spX += .03 * b * k; p.ry += .008 * b * k; p.hdX += .02 * b * k;
    p.led = lerp(p.led, Math.pow(.5 + .5 * Math.sin(t * 2.2), 8), k);
    if (t > 1.8) c.tick('z', 1.6, n => c.say(n % 2 ? 'z' : 'Z', 'head', { side: .6, size: .8 + (n % 3) * .2, color: 'var(--ink-2)', vx: .12, life: 2.2 }));
  }
});

/* --- internal moves: boot-up and poke reactions --- */
defMove('boot', {
  label: 'Booting', dur: 1.7,
  ev: [[.95, c => c.say('!', 'head', { size: 1.4, color: 'var(--accent)' })]],
  run(p, t) {
    const k = 1 - smooth(ramp(t, .5, 1.0));
    mix(p, { ry: -.13, spX: .3, nkX: .22, hdX: .42, hdZ: .12, aLz: .07, aRz: .07, eL: .12, eR: .12, shL: -.35, shR: -.35, look: 0 }, k);
    const fl = t < .9 ? (hash(Math.floor(t * 22)) > .45 ? 1 : .15) : 1;
    p.glow = fl * smooth(ramp(t, .1, .35)); p.core = .2 + .6 * smooth(ramp(t, .6, 1.2)); p.led = fl;
    p.wide = win(t, .85, .95, 1.2, 1.6); p.ry += .04 * Math.sin(PI * ramp(t, .85, 1.15));
    p.eye = t < .3 ? .15 : 1;
  }
});
defMove('bonk', {
  label: 'Bonk', dur: 2.3,
  ev: [[.05, c => { for (let i = 0; i < 3; i++) c.say('★', 'head', { side: i - 1, size: .8, color: 'var(--accent)', vy: .25, life: 1.4 }); }]],
  run(p, t) {
    const hit = win(t, 0, .06, .1, .5), dz = win(t, .08, .2, 1.6, 2.1), w = Math.sin(t * 7) * dz;
    p.hdX -= .35 * hit; p.nkX -= .15 * hit; p.spX -= .1 * hit; p.rz -= .03 * hit;
    p.hdZ += .16 * w; p.hdY += .12 * Math.cos(t * 7) * dz; p.spZ += .05 * w; p.rx += .02 * w;
    p.xeye = dz; p.mShow = dz * .8; p.mOpen = .2; p.mSmile = -.3; p.look = 1 - dz; p.shL = p.shR = .4 * hit;
    p.aLz += .3 * hit; p.aRz += .3 * hit;
  }
});
defMove('giggle', {
  label: 'Giggle', dur: 1.8,
  ev: [[.1, c => c.say('hee', 'head', { size: .9 })], [.6, c => c.say('hee', 'head', { size: .8, side: 1 })]],
  run(p, t) {
    const k = win(t, 0, .12, 1.3, 1.8), sh = Math.sin(t * 34) * k;
    p.spZ += .05 * sh; p.spX += .12 * k; p.ry -= .04 * k; p.hdZ += .06 * sh; p.shL += .4 * k; p.shR += .4 * k;
    mix(p, { aLx: -.5, aLy: -1.2, eL: 1.5, aRx: -.5, aRy: -1.2, eR: 1.5, happy: 1, squint: .4, mShow: 1, mOpen: .5, mSmile: 1 }, k);
    p.mOpen += .25 * Math.abs(Math.sin(t * 17)) * k;
  }
});
for (const S of ['L', 'R']) {
  const O = S === 'L' ? 'R' : 'L', sg = S === 'L' ? 1 : -1;
  defMove('five' + S, {
    label: 'High five', dur: 2.1,
    ev: [[.62, c => c.say('★', 'hand' + S, { size: 1.3, color: 'var(--accent)' })]],
    run(p, t) {
      const k = win(t, 0, .35, 1.5, 2.1), slap = win(t, .52, .6, .62, .8);
      mix(p, { ['a' + S + 'x']: -.5, ['a' + S + 'y']: 1.35, ['a' + S + 'z']: 2.2, ['e' + S]: .6, ['tw' + S]: 1.45, ['c' + S]: 0, ['t' + S]: 0, ['sp' + S]: .8,
        ['w' + S + 'x']: .1, spZ: -.07 * sg, roll: .03 * sg, hdZ: -.06 * sg, happy: 1, mShow: 1, mSmile: 1, mOpen: .3 }, k);
      p['a' + S + 'x'] -= .35 * slap; p['e' + S] -= .4 * slap; p.spY += .12 * sg * slap; p.pitch += .04 * slap;
    }
  });
  defMove('hop' + S, {
    label: 'Hop', dur: 2.1,
    ev: [[.05, c => c.say('!', 'head', { size: 1.2 })]],
    run(p, t) {
      const k = win(t, 0, .12, 1.55, 2.0);
      p['ik' + S] = 1 - k; p['l' + S + 'x'] = -1.1 * k; p['k' + S] = 1.8 * k; p['an' + S + 'x'] = .3 * k;
      p['f' + O + 'x'] = lerp(p['f' + O + 'x'], -.03 * sg, k); p.rx -= .05 * sg * k;
      let y = 0; for (const h of [.25, .7, 1.15]) { const s = ramp(t, h, h + .3); if (s > 0 && s < 1) y += .1 * Math.sin(PI * s); }
      p.ry += y; p['f' + O + 'y'] += Math.max(0, y - .015);
      mix(p, { aLz: .9, aRz: .9, eL: .5, eR: .5, wide: 1, mShow: .9, mOpen: .5, look: .4 }, k);
    }
  });
  defMove('flinch' + S, {
    label: 'Flinch', dur: 1.3,
    run(p, t) {
      const k = win(t, 0, .08, .55, 1.3);
      mix(p, { ['a' + S + 'x']: -.5, ['a' + S + 'y']: -1.2, ['a' + S + 'z']: .05, ['e' + S]: 2.0, ['c' + S]: .9, spZ: .1 * sg, roll: .05 * sg, hdZ: .1 * sg, rx: -.03 * sg, wide: .9, mShow: .7, mOpen: .4 }, k);
    }
  });
}
defMove('grumpy', {
  label: 'Grumpy', dur: 2.9,
  ev: [[.2, c => c.say('!!', 'head', { size: 1.3, color: 'var(--accent)' })]],
  run(p, t) {
    const k = win(t, 0, .35, 2.3, 2.9);
    mix(p, { aLx: -.45, aLy: -1.35, aLz: .22, eL: 1.75, aRx: -.42, aRy: -1.35, aRz: .22, eR: 1.85, cL: .9, cR: .9, angry: .9, warm: .85, ledWarm: 1, mShow: .8, mSmile: -.6, look: 1, spX: -.05, hdX: .06 }, k);
    const tap = Math.max(0, Math.sin(t * 13)) * win(t, .5, .6, 2.0, 2.2);
    p.fLp = -.35 * tap; p.fLy += .03 * tap; p.hdY += .08 * Math.sin(t * 4) * k;
  }
});
/* --- idle fidgets --- */
defMove('stretch', {
  label: 'Stretch', dur: 3.2,
  run(p, t) {
    const k = win(t, 0, .7, 2.2, 3.1), y = win(t, .5, .9, 1.8, 2.3);
    mix(p, { aLx: -.15, aLy: -.3, aLz: 2.7, eL: .5, aRx: -.15, aRy: -.3, aRz: 2.7, eR: .5, cL: .7, cR: .7, spX: -.12, hdX: -.25, ry: .02, look: 0, gy: .6 }, k);
    p.spZ += .08 * Math.sin(t * 2.4) * k; p.hdZ += .1 * Math.sin(t * 2.4 - .5) * k;
    p.eye = lerp(p.eye, .25, y); p.squint = .5 * y; p.mShow = y; p.mOpen = .8 * y; p.mSmile = -.1;
    p.fLp = p.fRp = .35 * win(t, .6, .9, 1.9, 2.2); p.fLy += .03 * win(t, .6, .9, 1.9, 2.2); p.fRy += .03 * win(t, .6, .9, 1.9, 2.2);
  }
});
defMove('scan', {
  label: 'Scanning', dur: 3.8,
  run(p, t) {
    const k = win(t, 0, .45, 3.1, 3.8), sw = Math.sin((t - .55) * 1.8) * win(t, .45, .85, 2.8, 3.3);
    mix(p, { aLx: .44, aLy: -.84, aLz: .45, eL: 1.6, twL: -.31, wLx: -.32, cL: .55, aRx: .44, aRy: -.84, aRz: .45, eR: 1.6, twR: -.31, wRx: -.32, cR: .55,
      spX: -.05, hdX: -.06, fLx: .17, fRx: -.17, look: 0, squint: .5, gy: .1, shL: .15, shR: .15 }, k);
    shuffle(p, ramp(t, 0, .45), .035);
    p.hdY += .6 * sw; p.nkY += .15 * sw; p.spY += .1 * sw; p.gx = .85 * sw; p.hdZ -= .06 * sw;
  }
});
defMove('tap', {
  label: 'Tapping', dur: 2.6,
  run(p, t) {
    const k = win(t, 0, .3, 2.1, 2.6), tap = Math.max(0, Math.sin(t * 12)) * win(t, .3, .45, 1.9, 2.1);
    mix(p, { aLx: .25, aLy: -.2, aLz: .32, eL: .7, aRx: .25, aRz: .32, eR: .7, wLx: -.4, wRx: -.4, rx: -.03, hdZ: .1, look: .7 }, k);
    p.fLp = -.4 * tap; p.fLy += .022 * tap; p.gx += .2 * Math.sin(t * 2) * k;
  }
});
const DOCK = ['wave', 'bow', 'walk', 'run', 'jump', 'flip', 'dance', 'robot', 'spin', 'flex', 'kungfu', 'cheer', 'think', 'shrug', 'sleep'];
/* =========================================================================
   Scenes. A scene is a move that also declares props, a light preset and a
   camera hint; `place(PL, t)` positions dynamic props after the rig is solved.
   Targets for hands are expressed in Cato's body (ground) frame.
   ========================================================================= */
/* Oculus viseme set → [jaw open, mouth width, lip rounding]. External lip-sync (e.g. HeadAudio) drives the same table. */
const VISEMES = {
  sil: [0, 1, 0], PP: [0, .78, 0], FF: [.12, .92, 0], TH: [.2, .9, 0], DD: [.3, .95, 0], kk: [.36, .9, 0], CH: [.3, .62, .45],
  SS: [.12, 1.05, 0], nn: [.24, .9, 0], RR: [.26, .62, .55], aa: [.92, .95, 0], E: [.55, 1.08, 0], I: [.36, 1.05, 0], O: [.66, .62, .8], U: [.42, .45, 1],
};
const VALIAS = { ih: 'I', oh: 'O', ou: 'U', e: 'E' };
const LETTER_VIS = { a: 'aa', e: 'E', i: 'I', o: 'O', u: 'U', y: 'I', p: 'PP', b: 'PP', m: 'PP', f: 'FF', v: 'FF', t: 'DD', d: 'DD', k: 'kk', g: 'kk', c: 'kk', q: 'kk', s: 'SS', z: 'SS', x: 'SS', n: 'nn', l: 'nn', r: 'RR', w: 'U', h: 'aa', j: 'CH' };
function speak(p, key, amt = 1, smile = .1) { const v = VISEMES[key] || VISEMES.sil; p.mOpen = v[0] * amt; p.mWide = v[1]; p.mRound = v[2]; p.mShow = Math.max(p.mShow, .95); p.mSmile = smile; }
/* read a line of text aloud as visemes: `rate` letters per second, returns the current letter */
function talk(p, text, t, rate = 13, amt = 1, smile = .15) {
  const i = Math.floor(t * rate);
  if (i < 0 || i >= text.length) { speak(p, 'sil', 0, smile); return null; }
  const ch = text[i].toLowerCase(), nx = (text[i + 1] || ' ').toLowerCase();
  const key = ch === ' ' || ch === ',' || ch === '.' ? 'sil' : (ch === 't' && nx === 'h') ? 'TH' : (ch === 's' && nx === 'h') || (ch === 'c' && nx === 'h') ? 'CH' : LETTER_VIS[ch] || 'nn';
  speak(p, key, amt, smile); return ch;
}
/* sung syllables: one viseme per half beat */
function sing(p, beats, lyric, amt = 1, smile = .25) { const i = Math.floor(beats * 2) % lyric.length; speak(p, lyric[i], amt * (.75 + .25 * Math.sin(PI * ((beats * 2) % 1))), smile); }

/* world point of a bone-local offset, expressed in Cato's body frame */
function bodyLocal(bone, x, y, z) {
  const w = mPt(BONES[typeof bone === 'number' ? bone : BN[bone]].W, x, y, z);
  const dx = w[0] - body.x, dz = w[2] - body.z, c = Math.cos(body.heading), s = Math.sin(body.heading);
  return [dx * c - dz * s, w[1], dx * s + dz * c];
}
const lerp3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
function toBody(w) { const dx = w[0] - body.x, dz = w[2] - body.z, c = Math.cos(body.heading), s = Math.sin(body.heading); return [dx * c - dz * s, w[1], dx * s + dz * c]; }
function dirToBody(v) { const c = Math.cos(body.heading), s = Math.sin(body.heading); return [v[0] * c - v[2] * s, v[1], v[0] * s + v[2] * c]; }
const nrm3 = v => norm3(v);
/* IK reach with optional pole and palm aim (all body frame) */
function reach(p, S, pt, w = 1, pole = null, palm = null, palmW = w) {
  p['h' + S + 'x'] = pt[0]; p['h' + S + 'y'] = pt[1]; p['h' + S + 'z'] = pt[2]; p['h' + S + 'w'] = w; p['h' + S + 's'] = 0; p['h' + S + 'h'] = 0;
  if (pole) { p['p' + S + 'x'] = pole[0]; p['p' + S + 'y'] = pole[1]; p['p' + S + 'z'] = pole[2]; }
  if (palm) { const n = norm3(palm); p['n' + S + 'x'] = n[0]; p['n' + S + 'y'] = n[1]; p['n' + S + 'z'] = n[2]; p['n' + S + 'w'] = palmW; }
}
/* sit down on a seat whose top is at `top` */
function seat(p, k, top, fz = .3, fx = .17) {
  const ry = top + .095 - PELVIS_Y, m = Math.sin(PI * sat(k)), e = smoother(k);
  p.ry = lerp(p.ry, ry, e); p.pitch += .32 * m + .05 * e; p.rz -= .05 * m; p.spX += .05 * e;
  p.fLz = lerp(p.fLz, fz, e); p.fRz = lerp(p.fRz, fz, e); p.fLx = lerp(p.fLx, fx, e); p.fRx = lerp(p.fRx, -fx, e);
  p.aLx -= .4 * m; p.aRx -= .4 * m;
  p.fLy += .03 * Math.sin(PI * sat(k * 1.6 - .3)); p.fRy += .03 * Math.sin(PI * sat(k * 1.6 - .5));
}
/* typing on the laptop keyboard */
const KB = { L: [.1, .792, .292], R: [-.1, .792, .292] };
function typing(p, t, w, intensity = 1) {
  for (const S of ['L', 'R']) {
    const sg = S === 'L' ? 1 : -1, ph = sg > 0 ? 0 : 1.7, k = KB[S];
    const tap = Math.max(0, Math.sin(t * 15 + ph)) * intensity, drift = Math.sin(t * 2.3 + ph) * .012 * intensity;
    reach(p, S, [k[0] + drift + sg * .006 * Math.sin(t * 5.1 + ph), k[1] + .012 * tap, k[2] + .008 * Math.sin(t * 3.3 + ph)], w, [sg * .8, -.7, -.35], [0, -1, .15]);
    p['c' + S] = lerp(p['c' + S], .32 + .22 * Math.abs(Math.sin(t * 19 + ph * 2)) * intensity, w);
    p['t' + S] = lerp(p['t' + S], .5, w); p['w' + S + 'x'] = lerp(p['w' + S + 'x'], -.15, w);
  }
}
/* desk look: head down at the screen */
function screenGaze(p, w) { p.look = lerp(p.look, 0, w); p.hdX += .13 * w; p.gy = lerp(p.gy, -.25, w); p.squint = Math.max(p.squint, .18 * w); }
const steam = (c, at) => c.say('~', 'mug', { side: 0, size: .7, color: 'var(--ink-2)', vy: .22, vx: (Math.random() - .5) * .05, life: 1.6, at });

/* ---------------- desk: typing, wiping sweat, sipping coffee ---------------- */
const DESK_SIT = 1.0;
const deskCycle = t => t < DESK_SIT ? t : DESK_SIT + ((t - DESK_SIT) % 11.4);
/* mug path: desk → mouth → desk, in world space */
const MUG_DESK = E(0, PI, 0, -.25, .757, .31), MUG_A = M(), MUG_B = M(), MUG_W = M();
function mugMatrix(cyc, out) {
  const lift = track(cyc, [[8.8, 0], [9.5, 1], [10.5, 1], [11.15, 0]]), tilt = -.75 * win(cyc, 9.25, 9.6, 10.2, 10.5);
  mMul(G, MUG_DESK, MUG_A);
  mMul(BONES[BN.head].W, E(tilt, PI, 0, -.03, .03, .29), MUG_B);
  return mBlend(MUG_A, MUG_B, smoother(lift), out);
}
defMove('desk', {
  label: 'Desk', key: 'A', group: 'Work', loop: true, scene: true, props: ['desk', 'chair', 'laptop', 'mug'], light: 'desk',
  cam: { yaw: .42, pitch: .2, zoom: .84, ty: .98 }, blendOut: .9,
  run(p, t, dt, c) {
    const cyc = deskCycle(t), sit = smooth(ramp(t, 0, DESK_SIT));
    seat(p, sit, .5);
    typing(p, t, sit * (1 - win(cyc, 7.9, 8.2, 11.2, 11.4) * .0));
    screenGaze(p, sit);
    /* sweat, then wipe it off with the back of the right hand */
    const worry = win(cyc, 2.6, 3.0, 4.6, 5.0), wipe = win(cyc, 4.0, 4.45, 5.55, 6.2), sw = ramp(cyc, 4.45, 5.4);
    p.angry = lerp(p.angry, -.55, worry); p.mShow = Math.max(p.mShow, .6 * worry); p.mSmile -= .4 * worry;
    if (wipe > 0) {
      const brow = bodyLocal('head', lerp(-.22, .1, smoother(sw)), .33 + .02 * Math.sin(PI * sw), .36);
      reach(p, 'R', lerp3(KB.R, brow, smoother(wipe)), sit, [-.9, -.2, -.5], [lerp(0, .2, wipe), lerp(-1, .1, wipe), lerp(.15, -1, wipe)]);
      p.cR = lerp(p.cR, .1, wipe); p.look = 0; p.hdX -= .08 * wipe; p.hdZ -= .06 * wipe;
    }
    const relief = win(cyc, 5.5, 5.8, 6.6, 7.2);
    p.happy = Math.max(p.happy, .8 * relief); p.mShow = Math.max(p.mShow, .8 * relief); p.mRound = .7 * relief; p.mOpen = Math.max(p.mOpen, .35 * relief);
    /* coffee */
    const cup = win(cyc, 8.0, 8.75, 11.1, 11.6);
    if (cup > 0) {
      mugMatrix(cyc, MUG_W);
      const handle = toBody(mPt(MUG_W, .064, .05, 0));
      reach(p, 'R', lerp3(KB.R, [handle[0] - .025, handle[1] - .03, handle[2] - .02], smoother(cup)), sit, [-.9, -.6, -.3], [1, .1, 0], cup);
      p.cR = lerp(p.cR, .85, cup); p.tR = lerp(p.tR, .8, cup);
      const sip = win(cyc, 9.3, 9.6, 10.2, 10.5);
      p.hdX -= .2 * sip + .1 * cup; p.look = lerp(p.look, .5, cup * (1 - sip)); p.eye = lerp(p.eye, .15, sip); p.happy = Math.max(p.happy, .9 * sip);
    }
    if (cyc > 10.3 && cyc < 11.8) c.tick('steam', .35, () => steam(c));
  },
  place(PL, t) {
    const cyc = deskCycle(t);
    PL.set('mug', mugMatrix(cyc, MUG_W));
    const dr = win(cyc, 3.0, 3.25, 5.0, 5.15);
    PL.force('drop', dr > .01 ? dr : 0);
    if (dr > .01) PL.set('drop', PL.mat('head', .21, .37 - .05 * ramp(cyc, 3.2, 5.0), .2, 0, 0, -.2));
  }
});

/* ---------------- email ---------------- */
const EMAIL_T = 9;
defMove('email', {
  label: 'Email', key: 'S', group: 'Talk', loop: true, scene: true, props: ['desk', 'chair', 'laptop'], light: 'desk',
  cam: { yaw: .42, pitch: .2, zoom: .84, ty: .98 }, blendOut: .9, hud: 'email',
  run(p, t, dt, c) {
    const sit = smooth(ramp(t, 0, 1)), cyc = t < 1 ? t : 1 + ((t - 1) % EMAIL_T);
    seat(p, sit, .5);
    const comp = win(cyc, 1, 1.3, 6.05, 6.2);
    typing(p, t, sit, .4 + .6 * comp);
    screenGaze(p, sit);
    /* hit send: lift and tap with the right hand */
    const lift = win(cyc, 6.0, 6.25, 6.3, 6.5);
    p.hRy += .07 * lift; p.hRz -= .03 * lift; p.hRx += .03 * lift;
    const done = win(cyc, 6.4, 6.6, 8.6, 9.6);
    p.happy = Math.max(p.happy, .9 * done); p.mShow = Math.max(p.mShow, .8 * done); p.mSmile = Math.max(p.mSmile, done);
    p.look = lerp(p.look, 1, done * .8); p.hdX -= .1 * done;
    if (cyc > 6.45 && cyc < 6.6) c.tick('sent', 50, () => c.say('Sent', 'head', { size: .9, color: 'var(--accent)' }));
    HUD.email = { cyc, comp: ramp(cyc, 1.1, 5.9), sent: cyc > 6.3 };
  }
});

/* ---------------- fax ---------------- */
const FAX_T = 11.5, FAXM = M(), PAPA = M(), PAPB = M();
defMove('fax', {
  label: 'Fax', key: 'D', group: 'Talk', loop: true, scene: true, props: ['desk', 'chair', 'fax', 'paper'], light: 'desk',
  cam: { yaw: .1, pitch: .24, zoom: .82, ty: .98 }, blendOut: .9, hud: 'fax',
  run(p, t, dt, c) {
    const sit = smooth(ramp(t, 0, 1)), cyc = t < 1 ? t : 1 + ((t - 1) % FAX_T);
    seat(p, sit, .5);
    /* look at the fax, push the page in with the right hand, dial with the left */
    p.look = lerp(p.look, 0, sit); p.hdY -= .42 * sit; p.nkY -= .12 * sit; p.hdX += .22 * sit; p.spY -= .12 * sit; p.gx = -.5 * sit; p.gy = -.4 * sit;
    const fx = PROPS.fax, push = ramp(cyc, 1.8, 3.2), hand = win(cyc, 1.2, 1.7, 3.3, 3.9);
    const tray = bodyLocal(fx.bone, 0, .16 - .05 * push, -.17 + .12 * push);
    reach(p, 'R', lerp3(KB.R, [tray[0] + .03, tray[1] + .02, tray[2] - .06], hand), sit * Math.max(hand, .001) + .0001, [-.9, -.5, -.4], [0, -1, .3]);
    p.hRw = sit * hand; p.cR = lerp(p.cR, .15, hand);
    const dial = win(cyc, 3.6, 3.9, 5.0, 5.4), tap = Math.max(0, Math.sin(cyc * 18)) * dial;
    const key = bodyLocal(fx.bone, .05, .11 + .02 * tap, -.02);
    reach(p, 'L', [key[0] + .02, key[1] + .03, key[2] - .07], sit * dial, [.9, -.5, -.4], [0, -1, .2]);
    p.cL = lerp(p.cL, .7, dial);
    p.hdY += .25 * dial; p.gx += .3 * dial;
    /* the machine sings, the page prints out the front */
    const busy = cyc > 5.4 && cyc < 9.4;
    FAX_LCD = cyc > 9.4 ? 'rgb(120,230,140)' : busy ? (Math.floor(cyc * 3) % 2 ? 'rgb(255,190,80)' : 'rgb(200,150,60)') : 'rgb(140,190,120)';
    if (busy) c.tick('fax', .9, n => c.say(['beep', 'krrr', 'bwee', 'ssshh', 'kchk'][n % 5], 'fax', { size: .7, color: 'var(--ink-2)', vy: .25, life: 1.4 }));
    const ok = win(cyc, 9.5, 9.8, 11.0, 11.5);
    p.happy = Math.max(p.happy, ok); p.mShow = Math.max(p.mShow, .8 * ok); p.mSmile = Math.max(p.mSmile, ok); p.hdY += .3 * ok; p.look = lerp(p.look, 1, ok);
    if (cyc > 9.5 && cyc < 9.65) c.tick('ok', 50, () => c.say('OK', 'head', { color: 'var(--accent)' }));
    HUD.fax = { cyc };
  },
  place(PL, t) {
    const cyc = t < 1 ? t : 1 + ((t - 1) % FAX_T), fx = BONES[PROPS.fax.bone].W;
    const push = smoother(ramp(cyc, 1.8, 3.2)), out = smoother(ramp(cyc, 5.6, 9.2));
    /* in the tray → fed in → printed out of the front */
    if (cyc < 5.5) {
      mMul(fx, E(-.55 + .4 * push, 0, 0, 0, .14 - .05 * push, -.15 + .13 * push, PAPA), PAPA);
      PL.set('paper', PAPA); PL.force('paper', 1 - ramp(cyc, 3.0, 3.25) * 0);
    } else {
      mMul(fx, E(.08, 0, 0, 0, .07, .03 + .19 * out, PAPB), PAPB);
      PL.set('paper', PAPB); PL.force('paper', cyc > 11.1 ? 0 : 1);
    }
  }
});

/* ---------------- video call ---------------- */
defMove('video', {
  label: 'Video call', key: 'G', group: 'Talk', loop: true, scene: true, props: ['desk', 'chair', 'laptop'], light: 'desk',
  cam: { yaw: .42, pitch: .2, zoom: .84, ty: .98 }, blendOut: .9, hud: 'video',
  run(p, t, dt, c) {
    const sit = smooth(ramp(t, 0, 1)), cyc = t < 1 ? t : 1 + ((t - 1) % 10);
    seat(p, sit, .5);
    const wave = win(cyc, 1.3, 1.7, 3.0, 3.5), type = 1 - win(cyc, 1.2, 1.5, 9.4, 9.9);
    typing(p, t, sit * (1 - (1 - type)) * .0 + sit * (1 - wave) * .35, .2);
    p.hLw *= type < 1 ? 1 - wave : 1;
    /* hands rest by the laptop while talking, right hand waves hello */
    p.look = lerp(p.look, 0, sit); p.hdX += .06 * sit; p.gy = -.12;
    mix(p, { aRx: -.25, aRy: 1.4, aRz: 1.35, eR: 1.5, twR: .2, cR: .05, spR: .8, hRw: 0, nRw: 0 }, wave * sit);
    p.eR += Math.sin(t * 10) * .35 * wave;
    p.happy = Math.max(p.happy, .9 * wave);
    const lines = [[1.6, 'Hi team!'], [3.8, 'Standup in five?'], [6.4, 'Shipping the flip patch today.']];
    for (const [t0, s] of lines) if (cyc >= t0 && cyc < t0 + s.length / 13 + .2) talk(p, s, cyc - t0, 13, 1, .35);
    const laugh = win(cyc, 8.6, 8.8, 9.3, 9.6);
    p.happy = Math.max(p.happy, laugh); p.mShow = Math.max(p.mShow, laugh); p.mOpen = Math.max(p.mOpen, .5 * laugh * (.6 + .4 * Math.abs(Math.sin(t * 16))));
    p.hdZ += .05 * Math.sin(t * 1.3) * sit; p.hdX += .05 * Math.max(0, Math.sin(cyc * 2.6)) * (cyc > 4 && cyc < 8 ? 1 : 0);
    HUD.video = { cyc, talking: p.mShow > .5 && p.mOpen > .1 };
  }
});

/* ---------------- phone call ---------------- */
const PHONE_EAR = M();
defMove('phone', {
  label: 'Phone call', key: 'H', group: 'Talk', loop: true, scene: true, props: ['phone'], light: 'default',
  cam: { yaw: -.25, pitch: .15, zoom: .9, ty: 1.12 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .6)), cyc = t % 12;
    const ear = bodyLocal('head', -.4, -.02, .06);
    reach(p, 'R', ear, k, [-.7, -1, -.1], [1, .25, .1]);
    p.cR = lerp(p.cR, .55, k); p.tR = lerp(p.tR, .6, k); p.hdZ += .1 * k; p.hdY -= .06 * k;
    /* free hand talks too */
    const g = Math.sin(t * 2.2), gest = win(cyc, 1, 1.5, 7, 7.6);
    mix(p, { aLx: -.55 - .15 * g, aLy: -.3, aLz: .35, eL: 1.35 + .2 * Math.sin(t * 3.1), twL: .9, cL: .1, spL: .5 }, gest * k);
    mix(p, { aLx: .44, aLy: -.84, aLz: .45, eL: 1.6, twL: -.31, wLx: -.32, cL: .55 }, win(cyc, 7.4, 8, 11.3, 11.9) * k);
    p.yaw += .18 * Math.sin(t * .5) * k; p.rx += .03 * Math.sin(t * .5) * k;
    const lines = [[.6, 'Hello?'], [2.2, 'Yes, it is Cato.'], [4.4, 'Sure, three o clock works.'], [8.2, 'Ha! See you then.']];
    for (const [t0, s] of lines) {
      if (cyc >= t0 && cyc < t0 + s.length / 12 + .2) talk(p, s, cyc - t0, 12, 1, .3);
      if (cyc >= t0 && cyc < t0 + .1) c.tick('l' + t0, 99, () => c.say(s, 'head', { size: .78, side: .9, vy: .16, life: 2.4, color: 'var(--ink)' }));
    }
    const listen = 1 - p.mShow; p.hdX += .06 * Math.max(0, Math.sin(t * 3.4)) * listen * (cyc > 5.5 && cyc < 8 ? 1 : 0);
    p.happy = Math.max(p.happy, .7 * win(cyc, 8.2, 8.4, 9.6, 10));
  },
  place(PL, t) {
    PL.set('phone', mMul(BONES[BN.head].W, basis([0, .78, -.62], [1, 0, 0], [-.37, .1, .09], PHONE_EAR), M()));
  }
});

/* ---------------- texting ---------------- */
const PHONE_TXT = M();
defMove('text', {
  label: 'Texting', key: 'J', group: 'Talk', loop: true, scene: true, props: ['phone'], light: 'default',
  cam: { yaw: .3, pitch: .2, zoom: .86, ty: 1.12 }, hud: 'text',
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .6)), cyc = t % 11;
    const L = bodyLocal('spine', .07, .28, .27), R = bodyLocal('spine', -.07, .28, .27);
    reach(p, 'L', L, k, [.8, -1, -.2], [-.6, .55, .3]);
    reach(p, 'R', R, k, [-.8, -1, -.2], [.6, .55, .3]);
    const typingNow = (cyc > 1 && cyc < 3.4) || (cyc > 6 && cyc < 8.2);
    p.tL = lerp(p.tL, typingNow ? .4 + .5 * Math.max(0, Math.sin(t * 16)) : .55, k);
    p.tR = lerp(p.tR, typingNow ? .4 + .5 * Math.max(0, Math.sin(t * 16 + 1.6)) : .55, k);
    p.cL = lerp(p.cL, .7, k); p.cR = lerp(p.cR, .7, k);
    p.look = lerp(p.look, 0, k); p.hdX += .5 * k; p.nkX += .16 * k; p.gy = -.85; p.spX += .06 * k;
    const lol = win(cyc, 4.2, 4.4, 5.2, 5.6);
    p.happy = Math.max(p.happy, lol); p.mShow = Math.max(p.mShow, lol); p.mOpen = Math.max(p.mOpen, .5 * lol * Math.abs(Math.sin(t * 15))); p.shL += .4 * lol; p.shR += .4 * lol;
    p.rx += .025 * Math.sin(t * .7) * k;
    HUD.text = { cyc };
  },
  place(PL, t) {
    PL.set('phone', mMul(BONES[BN.spine].W, basis([0, .5, .87], [0, .87, -.5], [0, .3, .3], PHONE_TXT), M()));
  }
});

/* ---------------- painting ---------------- */
const STROKES = (() => {
  const S = [], add = (col, w, pts) => S.push({ col, w, pts, len: pts.reduce((a, q, i) => i ? a + Math.hypot(q[0] - pts[i - 1][0], q[1] - pts[i - 1][1]) : 0, 0) });
  const zig = (y0, n) => { const o = []; for (let i = 0; i <= n; i++) o.push([-.22 + .44 * i / n, y0 + (i % 2) * .03]); return o; };
  add('rgb(150,200,240)', .055, zig(.15, 9)); add('rgb(170,214,244)', .05, zig(.09, 9).reverse());
  const spiral = (cx, cy, r, turns) => { const o = []; for (let i = 0; i <= 24; i++) { const a = i / 24 * TAU * turns, rr = r * (1 - i / 30); o.push([cx + Math.cos(a) * rr, cy + Math.sin(a) * rr]); } return o; };
  add('rgb(255,190,60)', .03, spiral(.13, .1, .045, 1.6));
  const hill = (y, a, ph) => { const o = []; for (let i = 0; i <= 12; i++) { const u = -.24 + .48 * i / 12; o.push([u, y + a * Math.sin(u * 9 + ph)]); } return o; };
  add('rgb(96,170,112)', .065, hill(-.03, .025, .3)); add('rgb(46,124,92)', .075, hill(-.11, .03, 2.2));
  add('rgb(110,70,40)', .022, [[-.12, -.13], [-.12, -.01]]);
  add('rgb(36,110,70)', .05, spiral(-.12, .03, .045, 1.4));
  add('rgb(46,75,255)', .009, [[.17, -.18], [.17, -.13], [.2, -.13], [.2, -.155], [.17, -.155], [.205, -.18]]);
  return S;
})();
const ART = { t: 0, total: 0, cur: -1, prog: 0, dip: 0, fade: 1 };
STROKES.forEach(s => { s.dur = .5 + s.len * 3.2; ART.total += s.dur + .7; });
function artState(t) {
  /* each stroke: 0.7 s dip at the palette, then paint; after the last, admire; then wipe the canvas */
  const intro = 1.0, cyc = (t - intro) % (ART.total + 4.5);
  if (t < intro) return { mode: 'ready', i: -1, f: 0 };
  let acc = 0;
  for (let i = 0; i < STROKES.length; i++) {
    const s = STROKES[i];
    if (cyc < acc + .7) return { mode: 'dip', i, f: (cyc - acc) / .7 };
    if (cyc < acc + .7 + s.dur) return { mode: 'paint', i, f: (cyc - acc - .7) / s.dur };
    acc += .7 + s.dur;
  }
  return { mode: 'admire', i: STROKES.length, f: (cyc - acc) / 4.5 };
}
function strokePoint(s, f) {
  const L = s.len * clamp(f, 0, 1); let acc = 0;
  for (let i = 1; i < s.pts.length; i++) { const a = s.pts[i - 1], b = s.pts[i], d = Math.hypot(b[0] - a[0], b[1] - a[1]); if (acc + d >= L) { const k = (L - acc) / (d || 1); return [lerp(a[0], b[0], k), lerp(a[1], b[1], k)]; } acc += d; }
  return s.pts[s.pts.length - 1];
}
let ART_ST = { mode: 'ready', i: -1, f: 0 };
function paintCanvas(W, cv) {
  const st = ART_ST, fade = st.mode === 'admire' ? 1 - smooth(ramp(st.f, .82, 1)) : 1;
  for (let i = 0; i < STROKES.length; i++) {
    const s = STROKES[i]; let f = i < st.i ? 1 : i === st.i && st.mode === 'paint' ? st.f : 0;
    if (st.mode === 'admire') f = 1;
    if (f <= 0) continue;
    const L = s.len * f, polys = []; let acc = 0;
    for (let j = 1; j < s.pts.length && acc < L; j++) {
      const a = s.pts[j - 1], b0 = s.pts[j], d = Math.hypot(b0[0] - a[0], b0[1] - a[1]), k = Math.min(1, (L - acc) / (d || 1)), b = [lerp(a[0], b0[0], k), lerp(a[1], b0[1], k)];
      const nx = -(b[1] - a[1]) / (d * k || 1) * s.w / 2, ny = (b[0] - a[0]) / (d * k || 1) * s.w / 2;
      polys.push([[a[0] + nx, a[1] + ny], [b[0] + nx, b[1] + ny], [b[0] - nx, b[1] - ny], [a[0] - nx, a[1] - ny]]);
      polys.push(circle(b[0], b[1], s.w / 2, 8));
      acc += d;
    }
    polys.unshift(circle(s.pts[0][0], s.pts[0][1], s.w / 2, 8));
    decal('canvas', W, polys, cv, s.col, .92 * fade, 0, 1 + i);
  }
}
const toLinRGB = str => str.match(/\d+/g).map(v => toLin(+v));
const BRUSH = M(), PAL = M();
function brushTip(st) {
  /* world position of the brush tip: on the canvas while painting, in the paint well while dipping */
  const E1 = BONES[PROPS.easel.bone].W, cvM = E(-.08, 0, 0, 0, 1.2, .08);
  const canvasPt = (u, v, off = .014) => { const l = mPt(cvM, u, v, off); return mPt(E1, l[0], l[1], l[2]); };
  if (st.mode === 'paint') { const q = strokePoint(STROKES[st.i], st.f); return canvasPt(q[0], q[1]); }
  const s = STROKES[Math.max(0, Math.min(STROKES.length - 1, st.i))], q = s.pts[0];
  if (st.mode === 'dip') {
    const pw = BONES[PROPS.palette.bone].W, idx = PAINTS.indexOf(s.col), a = .5 + Math.max(0, idx) * .55;
    const well = mPt(pw, Math.cos(a) * .072 + .01, Math.sin(a) * .06, .01);
    const arc = Math.sin(PI * st.f), start = canvasPt(q[0], q[1], .12);
    return st.f < .5 ? lerp3(start, [well[0], well[1] + .06 * arc, well[2]], smooth(st.f * 2)) : lerp3([well[0], well[1] + .06 * arc, well[2]], canvasPt(q[0], q[1], .04), smooth(st.f * 2 - 1));
  }
  return canvasPt(.05, -.05, .3);
}
defMove('art', {
  label: 'Paint', key: 'B', group: 'Create', loop: true, scene: true, props: ['easel', 'palette', 'brush', 'beret'], light: 'art',
  cam: { yaw: -.3, pitch: .16, zoom: .92, ty: 1.12 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .8)), st = artState(t); ART_ST = st;
    const admire = st.mode === 'admire' ? win(st.f, 0, .12, .78, .95) : 0;
    /* turn toward the easel on Cato's right, palette on the left arm */
    p.spY -= .26 * k; p.yaw -= .1 * k; p.hdY -= .3 * k; p.nkY -= .1 * k; p.look = lerp(p.look, 0, k); p.gx = -.85; p.gy = -.05; p.squint = .2 * k;
    p.fLx = lerp(p.fLx, .19, k); p.fRx = lerp(p.fRx, -.2, k); p.fRz = lerp(p.fRz, .06, k); p.fRw -= .3 * k;
    reach(p, 'L', [.2, 1.0, .26], k * (1 - admire * .3), [.9, -1, -.3], [0, 1, 0]);
    p.cL = lerp(p.cL, .55, k);
    const tip = toBody(brushTip(st)), sh = bodyLocal('clavR', -SHO_OUT, 0, 0);
    const dir = norm3([tip[0] - sh[0], tip[1] - sh[1] + .1, tip[2] - sh[2]]);
    reach(p, 'R', [tip[0] - dir[0] * .3, tip[1] - dir[1] * .3, tip[2] - dir[2] * .3], k * (1 - admire), [-.9, -1, -.4], [-dir[2], .3, dir[0]], .6 * k);
    p.cR = lerp(p.cR, .8, k); p.tR = lerp(p.tR, .7, k);
    if (st.mode === 'paint') { const s = STROKES[st.i]; MATS[MAT.paint].c = toLinRGB(s.col); p.hdY -= .05 * Math.sin(t * 2); }
    /* step back and admire */
    p.rz -= .07 * admire; p.hdZ += .14 * admire; p.happy = Math.max(p.happy, admire); p.mShow = Math.max(p.mShow, .7 * admire); p.mSmile = Math.max(p.mSmile, admire);
    p.hdY += .15 * admire;
    if (admire > .5) c.tick('art', 9, () => c.say('★', 'head', { color: 'var(--accent)', size: 1.2 }));
    ART.dir = dir;
  },
  place(PL, t) {
    const st = ART_ST, tip = brushTip(st), Hn = BONES[BN.handR].W, palm = mPt(Hn, 0, -.08, .03);
    const dir = norm3([tip[0] - palm[0], tip[1] - palm[1], tip[2] - palm[2]]);
    const up = Math.abs(dir[1]) > .9 ? [1, 0, 0] : [0, 1, 0];
    basis(dir, norm3(cross3(dir, up)), [tip[0] - dir[0] * .225, tip[1] - dir[1] * .225, tip[2] - dir[2] * .225], BRUSH);
    PL.set('brush', BRUSH);
    const L = BONES[BN.handL].W, lp = mPt(L, 0, -.07, .045), fw = mDir(G, 0, 0, 1);
    basis(fw, [0, 1, 0], [lp[0], lp[1] + .02, lp[2]], PAL);
    PL.set('palette', PAL);
  }
});
/* =========================================================================
   Stage scenes: DJ booth and five genres. Everything keys off a beat clock;
   an external beat tracker can drive it through rivet.beat(bpm).
   ========================================================================= */
const EXT_BEAT = { bpm: 0, t0: 0, until: 0 };
function beatClock(t, bpm) {
  const b = EXT_BEAT.bpm && time < EXT_BEAT.until ? (time - EXT_BEAT.t0) * EXT_BEAT.bpm / 60 : t * bpm / 60;
  SCENE.beat = b; SCENE.beatPulse = Math.pow(1 - (b % 1), 3);
  return b;
}
/* guitar points (guitar-local) → spine frame, and a reach in the spine frame */
const gpt = (id, u, v, w) => mPt(PROPS[id].AT, u, v, w);
function reachS(p, S, pt, w, pole, palm, palmW = w) {
  reach(p, S, pt, w, pole, palm, palmW); p['h' + S + 's'] = 1;
}
/* strum + fret for any guitar; `strum` in [-1,1] sweeps the picking hand across the strings */
function playGuitar(p, id, k, strum, fretU, opts = {}) {
  const g = PROPS[id].g, neck0 = g.neck[0], nl = g.neck[1];
  const fr = gpt(id, neck0 + nl * fretU, -.085, -.03);
  reachS(p, 'L', fr, k, [.9, -.9, -.6], opts.fretPalm || [-.35, .35, .85]);
  p.cL = lerp(p.cL, .72, k); p.tL = lerp(p.tL, .35, k); p.wLx = lerp(p.wLx, -.25, k);
  const sx = opts.pick ?? .0, sv = .06 + .07 * strum;
  const pk = gpt(id, sx, sv, .11);
  reachS(p, 'R', pk, k, [-.9, -.6, -.6], opts.pickPalm || [.15, -.25, -1]);
  p.cR = lerp(p.cR, .8, k); p.tR = lerp(p.tR, .85, k);
  STRUM.v = Math.max(STRUM.v * .9, Math.abs(strum) > .7 ? 1 : 0);
}
const LYRICS = {
  country: ['aa', 'E', 'O', 'nn', 'I', 'aa', 'U', 'sil', 'E', 'aa', 'O', 'DD', 'I', 'E', 'aa', 'sil'],
  pop: ['O', 'O', 'E', 'aa', 'I', 'E', 'aa', 'sil', 'U', 'aa', 'E', 'I', 'O', 'aa', 'E', 'sil'],
  reggae: ['O', 'sil', 'aa', 'E', 'sil', 'I', 'aa', 'sil'],
  folk: ['aa', 'aa', 'O', 'nn', 'E', 'E', 'I', 'sil', 'U', 'O', 'aa', 'nn', 'E', 'I', 'aa', 'sil'],
  rock: ['aa', 'sil', 'E', 'aa', 'sil', 'O', 'aa', 'sil'],
};
const notes = (c, b, id = 'n') => c.tick(id, 2, n => c.say(n % 2 ? '♫' : '♪', 'head', { side: n % 2 ? 1 : -1, color: 'var(--accent)' }));

/* ---------------- DJ ---------------- */
defMove('dj', {
  label: 'DJ booth', key: 'U', group: 'Stage', loop: true, scene: true, props: ['booth', 'cans'], light: 'dj',
  cam: { yaw: .3, pitch: .16, zoom: .9, ty: 1.12 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .7)), b = beatClock(t, 124), ph = b % 1, phrase = Math.floor(b / 16) % 2;
    const bounce = Math.pow(Math.abs(Math.sin(PI * b)), .6);
    p.ry -= (.025 + .04 * (1 - bounce)) * k; p.hdX += (.06 + .12 * (1 - bounce)) * k; p.spX += .04 * k * (1 - bounce);
    p.fLx = lerp(p.fLx, .17, k); p.fRx = lerp(p.fRx, -.17, k);
    /* right hand rides the record and scratches on the back half of each bar */
    const scr = (Math.floor(b) % 4 >= 2) ? Math.sin(TAU * b * 2) : 0;
    const rec = [-.31, 1.135, .33 + .045 * scr];
    reach(p, 'R', rec, k, [-.9, -.6, -.5], [0, -1, .2]);
    p.cR = lerp(p.cR, .25, k);
    DJ.spin[1] += dt * (scr !== 0 ? 3.5 + Math.cos(TAU * b * 2) * 28 : 3.5); DJ.spin[0] += dt * 3.5;
    /* left hand: cup the headphone on one phrase, ride the mixer on the next */
    const cup = phrase ? smooth(ramp(b % 16, 0, 1)) * (1 - smooth(ramp(b % 16, 14, 15.5))) : 0;
    const mixer = [.07 + .02 * Math.sin(b * PI / 2), 1.13, .35];
    const ear = bodyLocal('head', .4, .16, -.02);
    reach(p, 'L', lerp3(mixer, ear, cup), k, [.9, lerp(-.8, .3, cup), -.4], cup > .5 ? [-1, .1, 0] : [0, -1, .2]);
    p.cL = lerp(p.cL, lerp(.3, .5, cup), k);
    p.hdZ += .16 * cup * k; p.hdY -= .1 * cup;
    /* fist pump on the drop */
    const drop = (b % 32) > 28 ? win(b % 32, 28, 28.5, 31.5, 32) : 0;
    mix(p, { hLw: 0, nLw: 0, aLx: -.2, aLz: 2.5, eL: .4, cL: 1, tL: 1 }, drop * k);
    p.eL += drop * .5 * Math.max(0, Math.sin(TAU * b));
    p.look = .2; p.happy = .4 + .4 * drop; p.squint = .3; p.mShow = .6; p.mSmile = .8; p.mOpen = .2 * (1 - bounce) + .5 * drop;
    DJ.fader = Math.sin(b * PI / 4);
    SCENE.ledA = `hsl(${(b * 45 + 160) % 360},90%,62%)`; SCENE.ledB = `hsl(${(b * 45 + 280) % 360},90%,62%)`;
    if (t > .8) notes(c, b);
  }
});

/* ---------------- country ---------------- */
defMove('country', {
  label: 'Country', key: 'I', group: 'Stage', loop: true, scene: true, props: ['gtrA', 'hatCowboy'], light: 'country',
  cam: { yaw: .15, pitch: .13, zoom: .92, ty: 1.15 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .7)), b = beatClock(t, 104), ph = b % 1, bar = Math.floor(b / 4);
    const strum = Math.cos(TAU * b * 2) * (ph < .5 ? 1 : .6);
    playGuitar(p, 'gtrA', k, strum, [.18, .3, .12, .3][bar % 4]);
    /* toe tap with the left foot, sway every two beats */
    const tap = Math.max(0, Math.sin(TAU * b - .6));
    p.fLp = -.4 * tap * k; p.fLy += .02 * tap * k; p.fLz = lerp(p.fLz, .06, k);
    const sw = Math.sin(PI * b / 2);
    p.rx += .03 * sw * k; p.roll -= .03 * sw * k; p.spZ += .03 * sw * k; p.hdZ -= .07 * sw * k;
    p.hdX += .06 * Math.max(0, Math.sin(TAU * b)) * k; p.spX -= .02 * k;
    sing(p, b, LYRICS.country, .9 * k, .45); p.happy = .35; p.look = .55;
    if (t > .8) notes(c, b);
  }
});

/* ---------------- rock ---------------- */
defMove('rock', {
  label: 'Rock', key: 'O', group: 'Stage', loop: true, scene: true, props: ['gtrE', 'amp', 'crest'], light: 'rock',
  cam: { yaw: .2, pitch: .1, zoom: .9, ty: 1.12 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .7)), b = beatClock(t, 140), ph = b % 1, phr = b % 32;
    const wind = phr > 24 && phr < 30 ? win(phr, 24, 24.5, 29.3, 30) : 0, leap = phr >= 30 ? Math.sin(PI * ramp(phr, 30, 31.4)) : 0;
    const strum = ph < .25 ? lerp(1, -1, smooth(ph / .25)) : lerp(-1, 1, smooth((ph - .25) / .75));
    playGuitar(p, 'gtrE', k * (1 - wind), strum * (1 - wind), [.14, .22, .14, .3][Math.floor(b / 2) % 4], { pickPalm: [.2, -.4, -1] });
    if (wind > 0) {
      const a = TAU * b;
      mix(p, { hRw: 0, nRw: 0, aRx: -1.4 * Math.cos(a), aRz: 1.4 + 1.2 * Math.sin(a), aRy: 0, eR: .15, cR: .9 }, wind);
      reachS(p, 'L', gpt('gtrE', .19 + .44 * .2, -.085, -.03), k);
    }
    /* wide stance, headbang on the beat, jump at the end of the phrase */
    p.fLx = lerp(p.fLx, .25, k); p.fRx = lerp(p.fRx, -.25, k); p.fLw += .2 * k; p.fRw -= .2 * k;
    p.ry -= .08 * k; p.pitch -= .03 * k;
    const bang = Math.pow(Math.abs(Math.sin(PI * b)), 1.5);
    p.hdX += (.32 * (1 - bang) - .05) * k; p.nkX += .14 * (1 - bang) * k; p.spX += .06 * (1 - bang) * k;
    p.ry += .32 * leap; p.fLy += .26 * leap; p.fRy += .26 * leap; p.fLz += .05 * leap;
    p.shades = 1; p.angry = .45; p.look = 0;
    sing(p, b, LYRICS.rock, k * (phr % 8 < 4 ? 1 : .3), -.1); p.mOpen *= 1.2;
    if (leap > .9) c.tick('yeah', 8, () => c.say('YEAH!', 'head', { size: 1.1, color: 'var(--accent)' }));
    if (t > .8) notes(c, b);
  }
});

/* ---------------- pop ---------------- */
defMove('pop', {
  label: 'Pop', key: 'P', group: 'Stage', loop: true, scene: true, props: ['headset'], light: 'pop',
  cam: { yaw: 0, pitch: .12, zoom: .95, ty: 1.12 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .5)), b = beatClock(t, 118), cnt = b % 8, ph = b % 1, A = Math.floor(b / 8) % 2 === 0;
    const bounce = Math.pow(Math.abs(Math.sin(PI * b)), .7);
    p.ry -= .045 * (1 - bounce) * k;
    const side = Math.sin(PI * b / 2);
    p.rx += .07 * side * k; p.fLx = lerp(p.fLx, .19 + .05 * Math.max(0, side), k); p.fRx = lerp(p.fRx, -.19 + .05 * Math.min(0, side), k);
    if (A) {
      /* point up right, point up left, roll, hip pop */
      const pR = win(cnt, 0, .3, 1.7, 2), pL = win(cnt, 2, 2.3, 3.7, 4), roll = win(cnt, 4, 4.3, 5.7, 6), hip = win(cnt, 6, 6.2, 7.7, 8);
      mix(p, { aRx: -.5, aRz: 2.3, eR: .1, cR: .9, tR: .2, spZ: .1, hdY: -.25, hdZ: .1 }, pR * k);
      mix(p, { aLx: -.5, aLz: 2.3, eL: .1, cL: .9, tL: .2, spZ: -.1, hdY: .25, hdZ: -.1 }, pL * k);
      const r = TAU * b * 2;
      mix(p, { aLx: -.9 + .3 * Math.sin(r), aLz: .3, eL: 1.8, aLy: -.8, aRx: -.9 - .3 * Math.sin(r), aRz: .3, eR: 1.8, aRy: -.8, cL: 1, cR: 1 }, roll * k);
      mix(p, { aLx: .44, aLy: -.84, aLz: .45, eL: 1.6, twL: -.31, aRx: .44, aRy: -.84, aRz: .45, eR: 1.6, twR: -.31, roll: .08 * Math.sin(PI * b * 2), rx: .05 * Math.sin(PI * b * 2) }, hip * k);
    } else {
      /* wave overhead, then spin and strike a pose */
      const wave = win(cnt, 0, .4, 5.6, 6), spin = ramp(cnt, 6, 7.2), pose = win(cnt, 7.1, 7.3, 7.8, 8);
      mix(p, { aLx: -.2, aLz: 2.6, eL: .4, aRx: -.2, aRz: 2.6, eR: .4, cL: .1, cR: .1, spL: .7, spR: .7 }, wave * k);
      p.aLz += .25 * Math.sin(PI * b) * wave; p.aRz -= .25 * Math.sin(PI * b) * wave; p.spZ += .1 * Math.sin(PI * b) * wave;
      if (cnt >= 6) { p.yaw = TAU * easeIO(spin); p.fRx = lerp(p.fRx, -.03, win(cnt, 6, 6.2, 7.2, 7.4)); p.fRw = -.07 + p.yaw; }
      mix(p, { aRx: -1.4, aRz: .9, eR: .1, cR: .1, aLx: .44, aLy: -.84, aLz: .45, eL: 1.6, hdZ: .15 }, pose * k);
    }
    sing(p, b, LYRICS.pop, k, .55); p.happy = .6; p.look = A ? .6 : .3;
    c.tick('spark', 1, n => { if (n % 2 === 0) c.say('✦', n % 4 ? 'handL' : 'handR', { color: 'var(--accent)', size: .8, vy: .3 }); });
  }
});

/* ---------------- reggae ---------------- */
defMove('reggae', {
  label: 'Reggae', key: 'K', group: 'Stage', loop: true, scene: true, props: ['gtrB', 'speakers'], light: 'reggae',
  cam: { yaw: -.12, pitch: .12, zoom: .97, ty: 1.12 },
  run(p, t, dt, c) {
    const k = smooth(ramp(t, 0, .7)), b = beatClock(t, 76), ph = b % 1;
    /* skank: drop into the knees on the off-beat, nod on three (the one drop) */
    const off = Math.pow(Math.max(0, Math.sin(PI * ((b + .5) % 1))), 2), three = Math.floor(b) % 4 === 2 ? Math.sin(PI * ph) : 0;
    p.ry -= (.02 + .045 * off) * k; p.shL += .25 * off * k; p.shR += .25 * off * k;
    p.hdX += (.05 + .12 * three) * k; p.spX += .03 * three * k;
    const sw = Math.sin(PI * b / 2);
    p.rx += .035 * sw * k; p.roll -= .025 * sw * k; p.hdZ -= .06 * sw * k; p.spY += .06 * sw * k;
    const pluck = Math.max(0, Math.sin(TAU * b * 2)) * .6 - .2;
    playGuitar(p, 'gtrB', k, pluck, .12 + .1 * (.5 + .5 * Math.sin(PI * b / 4)), { pick: -.04, pickPalm: [.1, -.5, -1] });
    sing(p, b * .5, LYRICS.reggae, .8 * k, .5); p.happy = .55; p.squint = .35; p.look = .45;
    if (t > .8) notes(c, b);
  }
});

/* ---------------- singer-songwriter ---------------- */
defMove('folk', {
  label: 'Songwriter', key: 'N', group: 'Stage', loop: true, scene: true, props: ['stool', 'gtrA', 'beanie', 'standBase', 'standTop'], light: 'folk',
  cam: { yaw: .22, pitch: .14, zoom: .86, ty: .98 }, blendOut: .9,
  run(p, t, dt, c) {
    const sit = smooth(ramp(t, 0, 1)), b = beatClock(t, 84), ph = b % 1;
    seat(p, sit, .64, .22, .15);
    p.fLy = lerp(p.fLy, .27 + ANKLE_H * 0, sit); p.fLz = lerp(p.fLz, .12, sit);
    const pick = Math.sin(TAU * b) * .5;
    playGuitar(p, 'gtrA', sit, pick, [.16, .24, .16, .1][Math.floor(b / 4) % 4], { pick: .05 });
    const sw = Math.sin(PI * b / 4);
    p.hdZ += .08 * sw * sit; p.spZ += .03 * sw * sit; p.hdX -= .04 * sit;
    sing(p, b, LYRICS.folk, .85 * sit, .3);
    p.eye = lerp(p.eye, .2, sit * (Math.floor(b / 8) % 2 ? 1 : .6)); p.happy = .55; p.look = 0; p.gy = .1;
    if (t > 1) c.tick('n', 4, n => c.say(n % 2 ? '♫' : '♪', 'head', { side: 1, color: 'var(--accent)' }));
  },
  place(PL, t) {
    const H = BONES[BN.head].W, mouth = toBody(mPt(H, 0, .12, .24));
    PL.set('standTop', PL.mat('G', 0, mouth[1] - .055, .42, 0, 0, 0));
  }
});

/* ---------------- think: hand on chin while a reasoning trace streams in ---------------- */
defMove('think', {
  label: 'Think', key: 'R', group: 'Chill', dur: 8.2, light: 'think', hud: 'think',
  ev: [[6.25, c => c.say('!', 'head', { size: 1.5, color: 'var(--accent)' })]],
  run(p, t) {
    const k = win(t, 0, .5, 6.1, 6.35), idea = win(t, 6.1, 6.3, 7.4, 8.2);
    mix(p, { aRx: -.64, aRy: -1.25, aRz: -1.0, eR: 1.6, twR: .05, cR: .55, tR: .4, wRx: .23,
      aLx: -.22, aLy: -1.25, aLz: .1, eL: 1.55, cL: .3,
      hdZ: .12, hdX: -.1, spX: .06, look: 0, gx: .55, gy: .65, squint: .35, angry: -.35, mShow: .5, mSmile: -.15 }, k);
    p.hdZ += Math.sin(t * 1.4) * .03 * k; p.gx += Math.sin(t * .9) * .25 * k;
    /* glance toward the trace panel as each step lands */
    p.gx += .25 * Math.sin(t * 2.4) * k;
    mix(p, { aRx: -.3, aRz: 2.55, aRy: .2, eR: .15, cR: .9, tR: .9, wide: 1, happy: .2, mShow: .9, mOpen: .45, mSmile: .8, ledWarm: 1, look: 1, gy: .2, ry: .03 }, idea);
    if (idea > 0) p.ry += .03 * Math.sin(PI * ramp(t, 6.1, 6.5));
    p.led = .6 + .4 * Math.max(0, Math.sin(t * 9)) * k;
    HUD.think = { t };
  }
});
/* =========================================================================
   HUD: screen-space cards anchored to Cato. The reasoning trace streams
   tokens into Cato's antenna while it thinks; screen cards mirror what is on
   the laptop or phone (email, fax, video call, texting).
   ========================================================================= */
const gHud = $('gHud');
const HUD = { think: null, email: null, fax: null, video: null, text: null };
const H$ = { kind: null, el: null, a: 0, refs: {}, scroll: 0 };
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
const TRACE = [
  { tag: 'goal', c: 'acc', text: 'Pick an encore the crowd loves' },
  { tag: 'recall', c: 'cy', text: 'Backflip got the loudest cheer' },
  { tag: 'tool', c: 'cy', text: 'venue.ceiling() → 3.1 m' },
  { tag: 'observe', c: 'mut', text: 'Flip apex 2.6 m · 0.5 m spare' },
  { tag: 'check', c: 'ok', text: 'Battery 71% · servos nominal' },
  { tag: 'weigh', c: 'amb', text: '', bars: [['Backflip', .82], ['Robot dance', .64], ['Bow', .31]] },
  { tag: 'decide', c: 'acc', text: 'Backflip, then bow.' },
];
const STEP0 = .45, STEP = .8;

function hudBuild(kind) {
  gHud.innerHTML = ''; H$.refs = {}; H$.scroll = 0; H$.kind = kind;
  if (!kind) { H$.el = null; return; }
  const g = document.createElementNS(SVGNS, 'g'); g.setAttribute('class', 'ovl'); gHud.appendChild(g); H$.el = g;
  let html = '';
  if (kind === 'think') {
    const W = 340, Hh = 244; H$.W = W; H$.H = Hh;
    let rows = '', y = 52;
    TRACE.forEach((s, i) => {
      const h = s.bars ? 64 : 30;
      rows += `<g class="trow" data-i="${i}" transform="translate(0,${y})" opacity="0">
        <circle class="tnode ${s.c}" cx="20" cy="0" r="4.5"/>
        <rect class="tpill ${s.c}" x="32" y="-9" width="62" height="18" rx="9"/>
        <text class="ttag" x="63" y="4" text-anchor="middle">${s.tag.toUpperCase()}</text>
        <text class="ttext" x="104" y="4"></text>
        ${s.bars ? s.bars.map((bb, j) => `<text class="tlab" x="104" y="${22 + j * 15}">${esc(bb[0])}</text><rect class="tbarbg" x="196" y="${14 + j * 15}" width="96" height="8" rx="4"/><rect class="tbar ${j ? 'mut' : 'acc'}" x="196" y="${14 + j * 15}" width="0" height="8" rx="4"/><text class="tval" x="${W - 14}" y="${22 + j * 15}" text-anchor="end">${bb[1].toFixed(2)}</text>`).join('') : ''}
      </g>`;
      s.y = y; y += h;
    });
    H$.content = y;
    html = `<path class="flow" d=""/><g class="flowdots">${'<circle r="2.6"/>'.repeat(9)}</g>
      <g class="card"><rect class="hud-bg" width="${W}" height="${Hh}" rx="12"/>
      <circle class="live" cx="18" cy="19" r="4"/><text class="hud-h" x="30" y="23">REASONING TRACE</text>
      <text class="hud-m tstat" x="${W - 14}" y="23" text-anchor="end">step 0/7</text>
      <line class="hud-rule" x1="12" y1="34" x2="${W - 12}" y2="34"/>
      <clipPath id="hudClip"><rect x="0" y="36" width="${W}" height="${Hh - 42}"/></clipPath>
      <g clip-path="url(#hudClip)"><g class="scroller"><line class="tspine" x1="20" y1="52" x2="20" y2="52"/>${rows}</g></g>
      <rect class="hud-fade-top" x="1" y="35" width="${W - 2}" height="14"/><rect class="hud-fade-bot" x="1" y="${Hh - 18}" width="${W - 2}" height="17" rx="0"/>
      <text class="hud-m tfoot" x="14" y="${Hh - 8}">thinking · 0 tokens</text></g>`;
  } else if (kind === 'email') {
    const W = 290, Hh = 196; H$.W = W; H$.H = Hh;
    html = `<path class="lead" d=""/><g class="card"><rect class="hud-bg" width="${W}" height="${Hh}" rx="12"/>
      <circle class="dot r" cx="18" cy="17" r="4"/><circle class="dot y" cx="31" cy="17" r="4"/><circle class="dot g" cx="44" cy="17" r="4"/>
      <text class="hud-h" x="${W / 2}" y="21" text-anchor="middle">New message</text><line class="hud-rule" x1="0" y1="32" x2="${W}" y2="32"/>
      <text class="hud-m" x="14" y="52">To</text><text class="hud-t" x="64" y="52">hi@cato-labs.com</text><line class="hud-rule" x1="14" y1="61" x2="${W - 14}" y2="61"/>
      <text class="hud-m" x="14" y="78">Subject</text><text class="hud-t" x="64" y="78">Flip patch shipped</text><line class="hud-rule" x1="14" y1="87" x2="${W - 14}" y2="87"/>
      <text class="hud-t ebody" x="14" y="108"></text><text class="hud-t ebody" x="14" y="124"></text><text class="hud-t ebody" x="14" y="140"></text>
      <g class="send"><rect class="btn" x="${W - 84}" y="${Hh - 40}" width="70" height="28" rx="14"/><text class="btn-t" x="${W - 49}" y="${Hh - 21}" text-anchor="middle">Send</text></g></g>
      <g class="plane"><path d="M0,0 L26,-10 L12,4 Z M12,4 L10,12 L16,6 Z"/></g>`;
  } else if (kind === 'fax') {
    const W = 260, Hh = 128; H$.W = W; H$.H = Hh;
    html = `<path class="lead" d=""/><g class="card"><rect class="hud-bg" width="${W}" height="${Hh}" rx="12"/>
      <text class="hud-h" x="14" y="23">FAX · TX</text><text class="hud-m" x="${W - 14}" y="23" text-anchor="end">+1 555 0142</text>
      <line class="hud-rule" x1="12" y1="34" x2="${W - 12}" y2="34"/>
      <text class="hud-t fstat" x="14" y="58">Dialing…</text><text class="hud-m fbaud" x="${W - 14}" y="58" text-anchor="end"></text>
      <rect class="tbarbg" x="14" y="72" width="${W - 28}" height="10" rx="5"/><rect class="tbar acc fbar" x="14" y="72" width="0" height="10" rx="5"/>
      <text class="hud-m fpage" x="14" y="104">Page 1 of 1 · Flip patch.pdf</text></g>`;
  } else if (kind === 'video') {
    const W = 312, Hh = 214; H$.W = W; H$.H = Hh;
    const tile = (x, y, name, i) => `<g transform="translate(${x},${y})"><rect class="tile" width="140" height="74" rx="8"/><rect class="tring" data-i="${i}" x="1" y="1" width="138" height="72" rx="7"/><g class="av av${i}"></g><text class="hud-m" x="8" y="66">${name}</text></g>`;
    html = `<path class="lead" d=""/><g class="card"><rect class="hud-bg" width="${W}" height="${Hh}" rx="12"/>
      <circle class="live red" cx="18" cy="18" r="4"/><text class="hud-h" x="30" y="22">Stand-up</text><text class="hud-m vtime" x="${W - 14}" y="22" text-anchor="end">00:00</text>
      ${tile(12, 32, 'Ada', 0)}${tile(160, 32, 'Kit', 1)}${tile(12, 112, 'Mo', 2)}${tile(160, 112, 'Cato (you)', 3)}
      <g transform="translate(${W / 2 - 54},${Hh - 22})"><circle class="ctl" cx="10" cy="6" r="9"/><circle class="ctl" cx="54" cy="6" r="9"/><rect class="end" x="86" y="-3" width="26" height="18" rx="9"/></g></g>`;
  } else if (kind === 'text') {
    const W = 236, Hh = 236; H$.W = W; H$.H = Hh;
    html = `<path class="lead" d=""/><g class="card"><rect class="hud-bg" width="${W}" height="${Hh}" rx="14"/>
      <circle class="avc" cx="24" cy="22" r="10"/><text class="hud-h" x="42" y="26">Ada</text><line class="hud-rule" x1="0" y1="40" x2="${W}" y2="40"/>
      <g class="thread"></g></g>`;
  }
  g.innerHTML = html;
  if (kind === 'video') {
    const av = g.querySelectorAll('.av');
    av[0].innerHTML = `<circle class="p1" cx="70" cy="30" r="13"/><path class="p1" d="M46,62 a24,20 0 0,1 48,0 Z"/>`;
    av[1].innerHTML = `<rect class="b1" x="50" y="14" width="40" height="32" rx="9"/><rect class="b2" x="55" y="20" width="30" height="18" rx="5"/><circle class="b3" cx="64" cy="29" r="3"/><circle class="b3" cx="76" cy="29" r="3"/>`;
    av[2].innerHTML = `<circle class="p2" cx="70" cy="34" r="20"/><text class="ini" x="70" y="40" text-anchor="middle">MO</text>`;
    av[3].innerHTML = `<rect class="rv" x="48" y="10" width="44" height="36" rx="11"/><rect class="rvv" x="53" y="16" width="34" height="22" rx="7"/><rect class="rve e1" x="60" y="21" width="6" height="11" rx="3"/><rect class="rve e2" x="74" y="21" width="6" height="11" rx="3"/>`;
  }
}

/* place a card beside Cato's head, inside the stage */
function hudLayout() {
  const hc = mPt(BONES[BN.head].W, 0, .22, 0); proj(hc[0], hc[1], hc[2], tmp3);
  const hx = tmp3[0], hy = tmp3[1], headR = FOC / tmp3[2] * .3;
  const narrow = VW < 620, s = narrow ? Math.min(.86, (VW - 28) / H$.W) : Math.min(1, VH / 560);
  const W = H$.W * s, Hh = H$.H * s;
  let x, y;
  if (narrow) { x = (VW - W) / 2; y = VH - Hh - 34; }
  else {
    const left = hx - headR - 36 - W, right = hx + headR + 36;
    x = right + W <= VW - 14 ? right : left > HUD_MIN_X ? left : Math.min(right, VW - W - 14);
    y = clamp(hy - Hh * .55, 12, VH - Hh - 40);
  }
  return { x, y, s, W, Hh, hx, hy, headR };
}
const bez = (a, b, c, d, t) => { const u = 1 - t; return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d; };

function hudUpdate(dt) {
  const want = D.cur.hud && HUD[D.cur.hud] ? D.cur.hud : null;
  if (want !== H$.kind) {
    if (H$.kind && H$.a > .02 && want !== H$.kind) { H$.a = damp(H$.a, 0, 10, dt); if (H$.el) H$.el.setAttribute('opacity', H$.a.toFixed(3)); if (H$.a > .03) return; }
    hudBuild(want); H$.a = 0;
  }
  if (!H$.el) return;
  const st = HUD[H$.kind] || {};
  let vis = 1;
  if (H$.kind === 'think') vis = win(st.t || 0, .2, .55, 7.2, 7.9);
  H$.a = damp(H$.a, vis, 8, dt);
  const L = hudLayout(), g = H$.el;
  g.setAttribute('opacity', H$.a.toFixed(3));
  const card = g.querySelector('.card');
  const pop = .94 + .06 * smooth(H$.a);
  card.setAttribute('transform', `translate(${r1(L.x)},${r1(L.y)}) scale(${(L.s * pop).toFixed(3)})`);
  const lead = g.querySelector('.lead');
  if (lead) {
    /* dotted leader from the card to the device it mirrors */
    let tgt = null;
    if (PROPS.laptop.pres > .5 && (H$.kind === 'email' || H$.kind === 'video')) tgt = mPt(BONES[PROPS.laptop.bone].W, 0, .2, .15);
    if (H$.kind === 'fax') tgt = mPt(BONES[PROPS.fax.bone].W, 0, .1, 0);
    if (H$.kind === 'text') tgt = mPt(BONES[PROPS.phone.bone].W, 0, 0, 0);
    if (tgt) {
      proj(tgt[0], tgt[1], tgt[2], tmp3);
      const ax = L.x + (tmp3[0] > L.x + L.W / 2 ? L.W : 0), ay = L.y + L.Hh * .6;
      lead.setAttribute('d', `M${r1(ax)},${r1(ay)} C${r1((ax + tmp3[0]) / 2)},${r1(ay)} ${r1(tmp3[0])},${r1((ay + tmp3[1]) / 2)} ${r1(tmp3[0])},${r1(tmp3[1])}`);
    }
  }
  if (H$.kind === 'think') hudThink(st, L);
  else if (H$.kind === 'email') hudEmail(st, L);
  else if (H$.kind === 'fax') hudFax(st);
  else if (H$.kind === 'video') hudVideo(st);
  else if (H$.kind === 'text') hudText(st);
}

function hudThink(st, L) {
  const t = st.t || 0, g = H$.el, rows = g.querySelectorAll('.trow');
  let shown = 0, tokens = 0, active = -1;
  TRACE.forEach((s, i) => {
    const t0 = STEP0 + i * STEP, a = smooth(ramp(t, t0, t0 + .25)), row = rows[i];
    row.setAttribute('opacity', a.toFixed(3));
    if (a <= 0) return;
    shown = i + 1; active = i;
    const typed = s.text ? Math.floor(clamp((t - t0) / .55, 0, 1) * s.text.length) : 0;
    tokens += Math.ceil(typed / 3.6) + (s.bars ? 9 : 0);
    const tx = row.querySelector('.ttext'), cur = typed < s.text.length && t < t0 + .7;
    const str = s.text.slice(0, typed) + (cur ? '▍' : '');
    if (tx.textContent !== str) tx.textContent = str;
    if (s.bars) row.querySelectorAll('.tbar').forEach((b, j) => b.setAttribute('width', (96 * s.bars[j][1] * smooth(ramp(t, t0 + .1 + j * .15, t0 + .6 + j * .15))).toFixed(1)));
    row.querySelector('.tnode').setAttribute('r', (4.5 + 2 * Math.max(0, 1 - (t - t0) * 3)).toFixed(2));
    if (s.tag === 'decide') row.classList.toggle('hot', t > 6.1);
  });
  const sp = g.querySelector('.tspine'); if (active >= 0) sp.setAttribute('y2', TRACE[active].y);
  const visH = H$.H - 60, last = active >= 0 ? TRACE[active].y + (TRACE[active].bars ? 50 : 16) : 0;
  H$.scroll = damp(H$.scroll, Math.max(0, last - 36 - visH), 6, 1 / 60);
  g.querySelector('.scroller').setAttribute('transform', `translate(0,${(-H$.scroll).toFixed(1)})`);
  g.querySelector('.tstat').textContent = `step ${shown}/${TRACE.length} · ${t.toFixed(1)} s`;
  g.querySelector('.tfoot').textContent = (t > 6.1 ? 'decided · ' : 'thinking · ') + tokens + ' tokens';
  /* token stream from the card into the antenna */
  const tip = ant.p || mPt(BONES[BN.head].W, 0, .6, 0); proj(tip[0], tip[1], tip[2], tmp3);
  const right = tmp3[0] > L.x + L.W / 2, sx = right ? L.x + L.W : L.x, sy = L.y + L.Hh * .42, ex = tmp3[0], ey = tmp3[1];
  const c1x = sx + (right ? 1 : -1) * 70, c1y = sy, c2x = ex + (right ? -1 : 1) * 10, c2y = ey - 90;
  g.querySelector('.flow').setAttribute('d', `M${r1(sx)},${r1(sy)} C${r1(c1x)},${r1(c1y)} ${r1(c2x)},${r1(c2y)} ${r1(ex)},${r1(ey)}`);
  const flowOn = t > .5 && t < 6.4 ? 1 : 0;
  g.querySelector('.flow').style.strokeDashoffset = (-t * 40).toFixed(1);
  g.querySelectorAll('.flowdots circle').forEach((c, i) => {
    const u = (t * .7 + i / 9) % 1, x = bez(sx, c1x, c2x, ex, u), y = bez(sy, c1y, c2y, ey, u);
    c.setAttribute('cx', r1(x)); c.setAttribute('cy', r1(y)); c.setAttribute('opacity', (flowOn * Math.sin(PI * u) * .95).toFixed(3));
  });
}
const EMAIL_LINES = ['Hi team — the backflip patch is live.', 'Landing is 40 ms softer and quieter.', 'Ping me if anything wobbles. — R'];
function hudEmail(st, L) {
  const g = H$.el, n = EMAIL_LINES.join('').length, typed = Math.floor((st.comp || 0) * n), lines = g.querySelectorAll('.ebody');
  let left = typed;
  EMAIL_LINES.forEach((ln, i) => { const k = clamp(left, 0, ln.length); left -= ln.length; const s = ln.slice(0, k) + (k > 0 && k < ln.length ? '▍' : ''); if (lines[i].textContent !== s) lines[i].textContent = s; });
  const sent = st.sent, f = sent ? smooth(ramp(st.cyc, 6.3, 7.1)) : 0;
  g.querySelector('.send').classList.toggle('on', st.cyc > 6.1 && st.cyc < 6.5);
  const card = g.querySelector('.card');
  if (f > 0) card.setAttribute('transform', `translate(${r1(L.x + L.W * .5 * f)},${r1(L.y - 40 * f)}) scale(${(L.s * (1 - .8 * f)).toFixed(3)})`);
  card.setAttribute('opacity', (1 - f * .9 + (st.cyc > 8.2 ? smooth(ramp(st.cyc, 8.2, 8.8)) * .9 : 0)).toFixed(3));
  const pl = g.querySelector('.plane'), u = ramp(st.cyc, 6.4, 7.8);
  if (u > 0 && u < 1) {
    const x = L.x + L.W * .7 + u * 260, y = L.y + 40 - Math.sin(u * PI) * 70 - u * 60;
    pl.setAttribute('transform', `translate(${r1(x)},${r1(y)}) rotate(${(-20 + u * 30).toFixed(1)}) scale(${(L.s * 1.2).toFixed(2)})`); pl.setAttribute('opacity', (1 - smooth(ramp(u, .7, 1))).toFixed(2));
  } else pl.setAttribute('opacity', 0);
}
function hudFax(st) {
  const g = H$.el, c = st.cyc || 0;
  const s = c < 3.6 ? 'Paper loaded' : c < 5.4 ? 'Dialing +1 555 0142…' : c < 6.4 ? 'Handshake…' : c < 9.4 ? 'Sending page 1 of 1' : 'Delivered ✓';
  const fs = g.querySelector('.fstat'); if (fs.textContent !== s) fs.textContent = s;
  g.querySelector('.fbaud').textContent = c > 6.4 && c < 9.4 ? '14,400 bps' : '';
  g.querySelector('.fbar').setAttribute('width', ((260 - 28) * (c < 6.4 ? 0 : smooth(ramp(c, 6.4, 9.3)))).toFixed(1));
  g.querySelector('.fstat').classList.toggle('ok', c >= 9.4);
}
function hudVideo(st) {
  const g = H$.el, c = st.cyc || 0, secs = Math.floor(time % 3600);
  g.querySelector('.vtime').textContent = `${String(Math.floor(secs / 60)).padStart(2, '0')}:${String(secs % 60).padStart(2, '0')}`;
  const spk = st.talking ? 3 : c % 4 < 2 ? 0 : 1;
  g.querySelectorAll('.tring').forEach((r, i) => r.classList.toggle('on', i === spk));
  const eh = clamp(pose.eye, .1, 1) * 11;
  g.querySelectorAll('.rve').forEach(e => { e.setAttribute('height', eh.toFixed(1)); e.setAttribute('y', (26.5 - eh / 2).toFixed(1)); });
  const wave = win(c, 1.3, 1.6, 3.0, 3.4);
  g.querySelector('.av1').setAttribute('transform', `rotate(${(Math.sin(time * 10) * 8 * wave).toFixed(1)},70,40)`);
}
const THREAD = [[.8, 0, 'demo at 3?'], [3.4, 1, 'charging now'], [4.0, 0, 'lol same'], [8.2, 1, 'see you there']];
function hudText(st) {
  const g = H$.el, c = st.cyc || 0, th = g.querySelector('.thread');
  const items = THREAD.filter(m => c >= m[0]);
  const typingNow = (c > 1 && c < 3.4) || (c > 6 && c < 8.2);
  const key = items.length + (typingNow ? 't' : '');
  if (th.dataset.k !== key) {
    th.dataset.k = key; let y = 56, html = '';
    const all = items.map(m => ({ me: m[1], s: m[2] }));
    if (typingNow) all.push({ me: 1, s: '•••', typing: true });
    for (const m of all.slice(-5)) {
      const w = Math.max(40, m.s.length * 7.4 + 22), x = m.me ? 236 - 12 - w : 12;
      html += `<g class="bub ${m.me ? 'me' : 'them'}${m.typing ? ' typing' : ''}" transform="translate(${x},${y})"><rect width="${w}" height="28" rx="14"/><text x="${w / 2}" y="18.5" text-anchor="middle">${esc(m.s)}</text></g>`;
      y += 36;
    }
    th.innerHTML = html;
  }
}
/* =========================================================================
   Director (move switching, crossfades, settling, reactions, showreel),
   layers (breathing, blinking, look-at), input, dock UI and the frame loop.
   ========================================================================= */
const RM = matchMedia('(prefers-reduced-motion: reduce)');
const pose = newPose(), work = newPose();
const D = {
  cur: MOVES.idle, t: 0, prev: newPose(), blendT: 9, blendDur: .35,
  settle: null, settleT: 0, resume: null, reel: false, reelI: 0, reelT: 0,
  fired: new Set(), ticks: {}, pokes: [], idleT: 0, lastInput: 0, attract: true, fidgetAt: 8, fidgetN: 0,
};
let time = 0;
const ctx = {
  say(text, at, o = {}) {
    let base;
    if (PROPS[at]) base = mPt(BONES[PROPS[at].bone].W, 0, .13, 0);
    else { const Wb = BONES[BN[at]].W; base = at === 'head' ? mPt(Wb, (o.side || 0) * .17, .5, .05) : mPt(Wb, 0, -.1, 0); }
    emote(text, base, Object.assign({ vx: (o.side || 0) * .1 + (Math.random() - .5) * .08 }, o));
  },
  tick(id, period, fn) { const n = Math.floor(D.t / period); if (D.ticks[id] !== n) { D.ticks[id] = n; fn(n); } },
};

function play(id, o = {}) {
  let mv = MOVES[id]; if (!mv) return;
  if (D.cur.id === 'sleep' && id !== 'sleep' && id !== 'boot') { D.resume = mv.loop ? id : null; D.after = mv.loop ? null : id; mv = MOVES.boot; }
  if (D.cur.loco && !mv.loco && !o.force) { D.settle = mv.id; D.settleT = 0; updateUI(mv.id); return; }
  if (mv.scene && !o.here) {
    const off = Math.hypot(body.x, body.z) > .12 || Math.abs(wrapA(cam.yaw + cam.sway - body.heading)) > .35;
    if (off) { D.after = mv.id; if (D.cur.id !== 'home') { if (D.cur.loco) { D.settle = null; } mv = MOVES.home; } else return; }
  }
  if (!D.cur.loco && mv.loco) locoPlantFrom(pose);
  copyPose(D.prev, pose);
  const prevMv = D.cur;
  D.prevMove = prevMv;
  if (mv.cam && !prevMv.cam) D.camSaved = { yaw: cam.yaw, pitch: cam.pitch, zoom: cam.zoom };
  if (mv.cam) { D.camHint = Object.assign({}, mv.cam, { yaw: mv.cam.yaw + body.heading }); D.camUser = false; }
  else if (prevMv.cam && D.camSaved && !D.camUser) { D.camHint = Object.assign({ ty: 1.1, restore: true }, D.camSaved); }
  else if (!mv.cam) D.camHint = null;
  D.cur = mv; D.t = 0; D.blendT = 0; D.blendDur = o.blend ?? Math.max(mv.loco ? .3 : .36, mv.blendIn || 0, prevMv.blendOut || 0);
  D.fired.clear(); D.ticks = {}; D.settle = null; D.idleT = 0;
  updateUI(mv.id);
}
function finish() {
  const next = D.after; D.after = null;
  if (next) return play(next);
  if (D.resume && MOVES[D.resume]) { const r = D.resume; D.resume = null; return play(r); }
  if (D.reel) return play('idle');
  toIdle();
}
function toIdle() { play(Math.hypot(body.x, body.z) > .3 ? 'home' : 'idle'); }
defMove('home', {
  label: 'Returning', loop: true, loco: true,
  run(p, t, dt) {
    const face = wrapA(cam.yaw + cam.sway);
    const dist = locoStep(dt, { tx: 0, tz: 0, face, speed: .45, gait: 'walk', arrive: true });
    locoPose(p, t, .6);
    if (t > .4 && dist < .07 && !LOCO.moving && Math.abs(wrapA(face - body.heading)) < .12) { D.settle = null; const nx = D.after; D.after = null; play(nx || 'idle', { force: true, here: true }); }
  }
});
defMove('turn', {
  label: 'Turning', loop: true, loco: true,
  run(p, t, dt) {
    const face = wrapA(cam.yaw + cam.sway);
    locoStep(dt, { face, speed: 0, gait: 'walk' });
    locoPose(p, t, 1);
    if (t > .3 && !LOCO.moving) play('idle', { force: true });
  }
});

/* poke reactions by body part */
function poke(part) {
  const now = time; D.pokes = D.pokes.filter(x => now - x < 3); D.pokes.push(now);
  stopReel(); D.lastInput = now;
  if (D.cur.id === 'sleep') return play('boot');
  if (D.cur.scene || D.cur.id === 'home') { D.react = { part, t: time }; ctx.say(part === 1 || part === 9 ? '!?' : '!', 'head', { size: 1.1, color: 'var(--accent)' }); return; }
  const keep = D.cur.loop && !D.cur.loco && D.cur.id !== 'idle' ? D.cur.id : null;
  let r;
  if (D.pokes.length >= 4) { r = 'grumpy'; D.pokes = []; }
  else r = ({ 1: 'bonk', 9: 'bonk', 2: 'giggle', 3: 'flinchL', 4: 'flinchR', 5: 'fiveL', 6: 'fiveR', 7: 'hopL', 8: 'hopR' })[part];
  if (!r) return;
  play(r, { force: true, blend: .14 });
  D.resume = keep;
}

/* ---------- showreel ---------- */
const REEL = ['wave', 'dj', 'desk', 'jump', 'rock', 'think', 'phone', 'flip', 'country', 'art', 'pop', 'video', 'walk', 'reggae', 'text', 'kungfu', 'folk', 'email', 'dance', 'fax', 'flex', 'robot', 'spin', 'run', 'cheer', 'shrug', 'bow'];
function startReel() { D.reel = true; D.reelI = 0; D.reelT = 0; updateUI(D.cur.id); }
function stopReel() { if (!D.reel) return; D.reel = false; updateUI(D.cur.id); }
function reelStep(dt) {
  if (!D.reel) return;
  D.reelT += dt;
  const id = D.cur.id;
  if (id === 'idle' && D.reelT > 1.1) { play(REEL[D.reelI % REEL.length]); D.reelI++; D.reelT = 0; }
  else if (D.cur.loop && !['idle', 'home', 'turn', 'sleep'].includes(id) && D.reelT > (id === 'run' ? 5 : D.cur.scene ? 9.5 : 7)) { D.reelT = 0; play(Math.hypot(body.x, body.z) > .3 ? 'home' : 'idle'); }
  else if (id === 'home' || id === 'turn') D.reelT = 0;
}

/* ---------- layers ---------- */
const blink = { next: 2, t: -1, dbl: false };
const MOUTH = { o: 0, w: 1, r: 0, ext: {}, extT: -9 };
function layers(p, dt) {
  const br = D.cur.id === 'sleep' ? 0 : D.cur.loco ? .4 : 1, b = Math.sin(time * 1.75);
  p.spX += .014 * b * br; p.shL += .07 * b * br; p.shR += .07 * b * br; p.ry += .004 * b * br; p.hdX -= .01 * b * br;
  p.aLz += .012 * b * br; p.aRz += .012 * b * br;
  /* a cursor resting on Cato's head reads as a pat: content, half-closed eyes */
  const pat = followOn && !dragging && pointer.active && (pointer.part === 1 || pointer.part === 9) && D.cur.id === 'idle';
  pointer.hover = damp(pointer.hover, pat ? 1 : 0, pat ? 1.6 : 4, dt);
  if (pointer.hover > .01) { const h = smooth(pointer.hover); p.happy = Math.max(p.happy, h); p.squint += .3 * h; p.mShow = Math.max(p.mShow, .7 * h); p.mSmile = Math.max(p.mSmile, h); p.hdZ += .1 * h * Math.sin(time * 2); p.core += .4 * h; }
  /* scene pokes: a quick startle without leaving the scene */
  if (D.react) {
    const a = time - D.react.t, k = win(a, 0, .06, .5, 1.1);
    if (a > 1.2) D.react = null;
    else { p.wide = Math.max(p.wide, k); p.hdX -= .1 * k; p.shL += .4 * k; p.shR += .4 * k; if (D.react.part === 1 || D.react.part === 9) p.xeye = Math.max(p.xeye, k > .6 ? k : 0); p.mShow = Math.max(p.mShow, k); p.mRound = Math.max(p.mRound, k); p.mOpen = Math.max(p.mOpen, .4 * k); }
  }
  /* mouth: external visemes (e.g. HeadAudio) win while they stream; everything is smoothed */
  if (time - MOUTH.extT < .3) {
    let o = 0, w = 0, r = 0, tot = 0;
    for (const k in MOUTH.ext) { const v = VISEMES[k]; if (!v) continue; const a = clamp(MOUTH.ext[k], 0, 1); o += v[0] * a; w += v[1] * a; r += v[2] * a; tot += a; }
    p.mShow = Math.max(p.mShow, .95); p.mOpen = clamp(o, 0, 1.1);
    if (tot > .01) { p.mWide = w / tot; p.mRound = r / tot; }
  }
  MOUTH.o = damp(MOUTH.o, p.mOpen, 26, dt); MOUTH.w = damp(MOUTH.w, p.mWide, 20, dt); MOUTH.r = damp(MOUTH.r, p.mRound, 20, dt);
  p.mOpen = MOUTH.o; p.mWide = MOUTH.w; p.mRound = MOUTH.r;
  /* blink */
  blink.next -= dt;
  if (blink.next <= 0 && blink.t < 0) { blink.t = 0; blink.dbl = Math.random() < .2; }
  if (blink.t >= 0) {
    blink.t += dt; const L = .19, x = blink.t / L, c = x < .35 ? x / .35 : 1 - (x - .35) / .65;
    if (p.xeye < .5 && p.heart < .5) p.eye *= 1 - sat(c) * .95;
    if (blink.t > L) { blink.t = -1; blink.next = blink.dbl ? .12 : 1.8 + Math.random() * 3.6; blink.dbl = false; }
  }
}

/* ---------- look target ---------- */
const pointer = { x: 0, y: 0, active: false, t: -9, part: 0, hover: 0 };
let followOn = true;
function updateLook(dt) {
  const head = mPt(BONES[BN.head].W, 0, .215, 0);
  let tx = CB.p[0], ty = CB.p[1], tz = CB.p[2];
  if (followOn && pointer.active && time - pointer.t < 4) {
    const rx = (pointer.x - OX) / FOC, ry = -(pointer.y - OY) / FOC;
    const dir = norm3([CB.f[0] + CB.r[0] * rx + CB.u[0] * ry, CB.f[1] + CB.r[1] * rx + CB.u[1] * ry, CB.f[2] + CB.r[2] * rx + CB.u[2] * ry]);
    const pp = [head[0] - CB.f[0] * 1.3, head[1] - CB.f[1] * 1.3, head[2] - CB.f[2] * 1.3];
    const den = dot3(dir, CB.f);
    if (Math.abs(den) > 1e-4) {
      const s = ((pp[0] - CB.p[0]) * CB.f[0] + (pp[1] - CB.p[1]) * CB.f[1] + (pp[2] - CB.p[2]) * CB.f[2]) / den;
      tx = CB.p[0] + dir[0] * s; ty = CB.p[1] + dir[1] * s; tz = CB.p[2] + dir[2] * s;
    }
  } else {
    /* glance around now and then, mostly at the viewer */
    const g = noise1(time * .23 + 11), g2 = noise1(time * .19 + 40);
    const amt = Math.max(0, Math.abs(g) - .35) * 2.2;
    tx += CB.r[0] * g * amt * 1.6; tz += CB.r[2] * g * amt * 1.6; ty += g2 * amt * .9;
  }
  look.x = damp(look.x, tx, 9, dt); look.y = damp(look.y, ty, 9, dt); look.z = damp(look.z, tz, 9, dt);
}

/* ---------- frame ---------- */
function step(dt) {
  time += dt; D.t += dt; D.blendT += dt;
  reelStep(dt);
  /* attract mode: start the showreel after a quiet spell */
  if (D.attract && !D.reel && !RM.matches && time - D.lastInput > 9 && D.cur.id === 'idle' && D.t > 2) startReel();
  copyPose(work, REST);
  if (D.settle) {
    D.settleT += dt;
    locoStep(dt, { speed: 0, face: body.heading });
    locoPose(work, D.t, .5);
    if (!LOCO.moving || D.settleT > 1.1) { const id = D.settle; D.settle = null; play(id, { force: true }); copyPose(work, REST); D.cur.run(work, 0, dt, ctx); }
  } else D.cur.run(work, D.t, dt, ctx);
  if (D.cur.ev) for (const [et, fn] of D.cur.ev) if (D.t >= et && !D.fired.has(et)) { D.fired.add(et); fn(ctx); }
  const b = smooth(D.blendT / D.blendDur);
  if (b < 1) blendPose(pose, D.prev, work, b); else copyPose(pose, work);
  layers(pose, dt);
  SCENE.light = D.cur.light || 'default'; SCENE.beatPulse *= .9;
  updateLightRig(dt);
  if (D.cur.dur && D.t >= D.cur.dur) finish();
  /* idle upkeep: turn to face the viewer after they orbit around */
  if (D.cur.id === 'idle' && !D.reel) {
    D.idleT += dt;
    if (followOn && D.idleT > 2.6 && !dragging && Math.abs(wrapA(cam.yaw + cam.sway - body.heading)) > 1.15) play('turn');
    else if (opts.fidget && D.idleT > D.fidgetAt) { D.fidgetAt = 7 + Math.random() * 6; play(['stretch', 'scan', 'tap'][D.fidgetN++ % 3]); }
  }
  /* camera follows the robot loosely */
  cam.tx = damp(cam.tx, body.x * .55, 2, dt); cam.tz = damp(cam.tz, body.z * .55, 2, dt);
  const hint = D.camHint && !D.camUser ? D.camHint : null;
  cam.ty = damp(cam.ty, (hint && hint.ty ? hint.ty : 1.1) + Math.max(0, pose.ry) * .5, 2.5, dt);
  if (hint) {
    cam.yaw = dampA(cam.yaw, hint.yaw, 1.6, dt); cam.pitch = damp(cam.pitch, hint.pitch, 1.6, dt); cam.zoom = damp(cam.zoom, hint.zoom, 1.6, dt);
    if (hint.restore && Math.abs(wrapA(cam.yaw - hint.yaw)) < .01 && Math.abs(cam.zoom - hint.zoom) < .01) D.camHint = null;
  }
  if (!dragging) {
    cam.yaw += camVel * dt; camVel = damp(camVel, 0, 3.5, dt);
    const swayOn = !RM.matches && time - D.lastInput > 3;
    cam.sway = damp(cam.sway, swayOn ? Math.sin(time * .13) * .2 : 0, .6, dt);
    cam.swayP = damp(cam.swayP, swayOn ? Math.sin(time * .09 + 1) * .035 : 0, .6, dt);
  }
  updateCamera(); updateLights(); updateLook(dt);
  evalRig(pose, body, dt);
  placeProps(dt);
  skin();
}
/* props: presence (pop in/out), default placement, scene overrides */
const FMS = M();
function frameMat(frame, x, y, z, rx, ry, rz, out) { return mMul(frame === 'G' ? G : BONES[BN[frame]].W, mEuler(rx, ry, rz, x, y, z, FMS), out); }
const PL = {
  mat: (frame, x, y, z, rx = 0, ry = 0, rz = 0) => frameMat(frame, x, y, z, rx, ry, rz, M()),
  set(id, m) { const P = PROPS[id]; mCopy(m, P.base); P.setNow = true; P.placed = true; },
  force(id, w) { PROPS[id].force = w; },
};
function placeProps(dt) {
  const want = new Set(D.cur.props || []);
  if (D.blendT < D.blendDur && D.prevMove && D.prevMove.props) for (const id of D.prevMove.props) want.add(id);
  for (const P of PROP_LIST) { P.force = null; P.setNow = false; }
  if (D.cur.place) D.cur.place(PL, D.t);
  for (const P of PROP_LIST) {
    const w = P.force !== null ? P.force : want.has(P.id) ? 1 : 0;
    P.want = w; P.pres = damp(P.pres, w, w > P.pres ? 7 : 10, dt); if (w === 0 && P.pres < .01) P.pres = 0;
    const vis = P.pres > .004;
    for (let i = P.m0; i < P.m1; i++) SC.meshes[i].vis = vis;
    if (!vis) continue;
    if (!P.setNow && (w > 0 || !P.placed)) { const a = P.at; frameMat(a[0], a[1], a[2], a[3], a[4] || 0, a[5] || 0, a[6] || 0, P.base); P.placed = true; }
    const s = w > 0 ? backOut(P.pres, 1.3) : smooth(P.pres);
    mScale(P.base, Math.max(.001, s), BONES[P.bone].W);
  }
}
function render(dt) {
  projectAll(); shadeFaces();
  buildDecals(pose, rigOut.gx, rigOut.gy, time);
  /* antenna LED glow, faded when the head hides it */
  const ex = [];
  if (ant.p && pose.led > .05) {
    const hc = mPt(BONES[BN.head].W, 0, .215, 0); proj(hc[0], hc[1], hc[2], tmp3); const zh = tmp3[2];
    proj(ant.p[0], ant.p[1], ant.p[2], tmp3);
    const occ = clamp(1 - (tmp3[2] - zh - .02) / .1, 0, 1), r = FOC / tmp3[2] * .055;
    if (occ > .02) ex.push([`M${r1(tmp3[0] - r)},${r1(tmp3[1])}a${r1(r)},${r1(r)} 0 1,0 ${r1(2 * r)},0a${r1(r)},${r1(r)} 0 1,0 ${r1(-2 * r)},0Z`, rgbStr(LED_RGB), .75 * occ * clamp(pose.led, 0, 1)]);
  }
  flush(); flushGlow(ex); drawShadows(); drawPool();
  if (cam.dirty) { drawFloor(); cam.dirty = false; }
  drawFx(dt); hudUpdate(dt);
}

function updateUI(id) { if (hooks.onMode) hooks.onMode(D.settle || id); }
let dragging = false, camVel = 0;
function resetView() {}
function resize() {
  const r = stage.getBoundingClientRect();
  VW = Math.max(200, Math.round(r.width)); VH = Math.max(200, Math.round(r.height));
  svg.setAttribute('viewBox', `0 0 ${VW} ${VH}`);
  fitCamera();
}
VW = 800; VH = 600; fitCamera();

/* ---------- boot ---------- */
locoPlant();

D.attract = !!opts.attract;
/* align the engine's projection with the three.js camera (Cato-local space, CSS px),
   so the SVG cards and emotes it draws land on the WebGL body */
function syncView(v) {
  for (let i = 0; i < 3; i++) { CB.p[i] = v.p[i]; CB.f[i] = v.f[i]; CB.r[i] = v.r[i]; CB.u[i] = v.u[i]; }
  if (VW !== v.w || VH !== v.h) { VW = v.w; VH = v.h; svg.setAttribute('viewBox', '0 0 ' + VW + ' ' + VH); }
  FOC = (VH / 2) / Math.tan(v.fov / 2); OX = VW / 2; OY = VH / 2;
  HUD_MIN_X = v.minX ?? 14;
}
function frame(dt, view) {
  if (scrub) {
    const mv = MOVES[scrub.id];
    if (D.cur !== mv) { copyPose(D.prev, pose); D.cur = mv; D.blendT = 0; D.blendDur = .2; D.fired.clear(); D.ticks = {}; D.settle = null; D.after = null; D.resume = null; updateUI(mv.id); }
    D.t = Math.min(scrub.t, (mv.dur || 1e9) - .001) - dt;
  }
  step(dt);
  if (view) syncView(view);
  DECALS.length = 0;
  buildDecals(pose, rigOut.gx, rigOut.gy, time);
  if (view) { drawFx(dt); hudUpdate(dt); }
}
return {
  frame,
  meshes: SC.meshes, fIdx: SC.fIdx, fStart: SC.fStart, fLen: SC.fLen, fMat: SC.fMat, LP, MATS, BONES,
  decals: DECALS,
  lights: { key: KEY_C, fill: FILL_C, rim: RIM_C, sky: SKY, gnd: GND, scene: SCENE },
  led: () => LED_RGB,
  svg,
  body,
  play: id => { scrub = null; play(id); },
  scrub(id, t) { if (MOVES[id]) scrub = { id, t: Math.max(0, t) }; },
  release() { if (!scrub) return; scrub = null; toIdle(); },
  viseme(key, value) { key = String(key).replace(/^viseme_/, ''); key = VALIAS[key] || key; MOUTH.ext[key] = +value || 0; MOUTH.extT = time; },
  beat(bpm, phase = 0) { if (!(bpm > 0)) { EXT_BEAT.bpm = 0; return; } EXT_BEAT.bpm = bpm; EXT_BEAT.t0 = time - phase * 60 / bpm; EXT_BEAT.until = time + 6; },
  pointerAt(x, y, active) { pointer.x = x * VW; pointer.y = y * VH; pointer.active = active; pointer.t = time; },
  moves: () => Object.keys(MOVES),
  duration: id => (MOVES[id] && MOVES[id].dur) || 0,
  isLoop: id => !!(MOVES[id] && MOVES[id].loop),
  mode: () => D.settle || D.cur.id,
  onMode(fn) { hooks.onMode = fn; },
};
}
