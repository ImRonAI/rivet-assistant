'use client';

import React, { forwardRef, useEffect, useImperativeHandle, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { useFrame } from '@react-three/fiber';
import { createCatoCore } from './timmy-core';
import './cato.css';

/**
 * CatoBody: draws the owner's Timmy rig (timmy-core.js, verbatim geometry,
 * rig and moves) with three.js.
 *
 * - Every engine mesh is rigid on one bone. Its faces are split per engine
 *   material into "pieces"; all pieces sharing a material live in one
 *   THREE.BatchedMesh (one multi-draw call per material instead of one draw
 *   per mesh per material group). Each piece is a batch instance whose matrix
 *   is set every frame from the bone's 3x4 world matrix W, and whose
 *   visibility mirrors the engine's mesh.vis (props, hidden parts). Shading is
 *   a port of Timmy's own lighting (see FRAG); the batch matrix is applied in
 *   the vertex stage only, so the tonal result is unchanged.
 * - Face decals (eyes, mouth, chest core, prop screens) are rebuilt every
 *   frame into one dynamic vertex-coloured mesh.
 * - Timmy's SVG cards (think trace, email, fax, video, texting) and floating
 *   emotes stay SVG: the engine's own <svg> is mounted over the canvas and its
 *   projection is synced to the three.js camera every frame (frame(dt, view)).
 * - A soft contact shadow follows his feet.
 * - The parent drives behaviour through the handle (play/scrub/viseme…); the
 *   engine steps inside this component's useFrame.
 */

export type { CatoCore } from "./timmy-core";
import type { CatoCore } from "./timmy-core";
export interface CatoBodyHandle {
  core: CatoCore | null;
}

const MAX_DECAL_TRIS = 12000;

/*
 * Timmy's own face shading (shadeFaces in timmy-core.js), per pixel:
 * hemisphere sky/ground + key/fill/rim diffuse + Blinn key specular + fresnel
 * rim, lights placed relative to the camera, then Timmy's tonemap
 * 255·(1 − e^(−1.32x))^(1/2.2). The composer expects linear output, so the
 * shader writes 1 − e^(−1.32x) and the output pass applies the sRGB curve.
 * Faces are flat (normal from screen-space derivatives), as in the original.
 */
const LIGHTS = {
  uKeyD: { value: new THREE.Vector3() },
  uFillD: { value: new THREE.Vector3() },
  uRimD: { value: new THREE.Vector3() },
  uHalfD: { value: new THREE.Vector3() },
  uKeyC: { value: new THREE.Vector3() },
  uFillC: { value: new THREE.Vector3() },
  uRimC: { value: new THREE.Vector3() },
  uSky: { value: new THREE.Vector3() },
  uGnd: { value: new THREE.Vector3() },
  uLed: { value: new THREE.Vector3() },
};

/*
 * Vertex stage: three's batching chunks resolve the per-piece (bone) matrix
 * from the BatchedMesh matrices texture when the material is drawn through a
 * BatchedMesh (USE_BATCHING); the chunks compile to nothing otherwise. The
 * world position feeding the fragment shading is identical to the former
 * per-mesh `modelMatrix` path (group matrix × bone W).
 */
const VERT = /* glsl */ `
#include <batching_pars_vertex>
varying vec3 vW;
void main() {
  #include <batching_vertex>
  vec4 lp = vec4(position, 1.0);
  #ifdef USE_BATCHING
  lp = batchingMatrix * lp;
  #endif
  vec4 w = modelMatrix * lp;
  vW = w.xyz;
  gl_Position = projectionMatrix * viewMatrix * w;
}`;

const FRAG = /* glsl */ `
uniform vec3 uC, uKeyD, uFillD, uRimD, uHalfD, uKeyC, uFillC, uRimC, uSky, uGnd, uLed;
uniform float uSpec, uPow, uRimK, uLedMode;
varying vec3 vW;
void main() {
  vec3 n = normalize(cross(dFdx(vW), dFdy(vW)));
  vec3 v = normalize(cameraPosition - vW);
  if (dot(n, v) < 0.0) n = -n;
  float vd = max(dot(n, v), 0.0);
  vec3 x;
  if (uLedMode > 0.5) {
    x = uLed * (0.72 + 0.28 * vd);
    gl_FragColor = vec4(x, 1.0);
    return;
  }
  float dk = max(dot(n, uKeyD), 0.0);
  float df = max(dot(n, uFillD), 0.0);
  float dr = max(dot(n, uRimD), 0.0);
  float hm = 0.5 + 0.5 * n.y;
  float sp = dk > 0.0 ? uSpec * pow(max(dot(n, uHalfD), 0.0), uPow) : 0.0;
  float fr = uRimK * pow(1.0 - min(1.0, vd), 3.0);
  x = uC * (uGnd + (uSky - uGnd) * hm + uKeyC * dk + uFillC * df + uRimC * dr) + sp * uKeyC + fr * uRimC * 0.55;
  gl_FragColor = vec4(1.0 - exp(-x * 1.32), 1.0);
}`;

function materialFor(m: { name: string; c: number[]; spec: number; pow: number; rim: number; led?: boolean; fill?: string }) {
  const c = m.fill ? parseRgb(m.fill) : m.c;
  return new THREE.ShaderMaterial({
    vertexShader: VERT,
    fragmentShader: FRAG,
    side: THREE.DoubleSide,
    toneMapped: false,
    uniforms: {
      ...LIGHTS,
      uC: { value: new THREE.Vector3(c[0], c[1], c[2]) },
      uSpec: { value: m.spec },
      uPow: { value: m.pow },
      uRimK: { value: m.rim },
      uLedMode: { value: m.led ? 1 : 0 },
    },
  });
}

/* Timmy's camera-relative light directions (updateLights), from three's camera basis */
const _r = new THREE.Vector3();
const _u = new THREE.Vector3();
const _f = new THREE.Vector3();
function viewDir(out: THREE.Vector3, x: number, y: number, z: number) {
  return out.set(0, 0, 0).addScaledVector(_r, x).addScaledVector(_u, y).addScaledVector(_f, -z).normalize();
}
const _v = new THREE.Vector3();
function updateLights(camera: THREE.Camera, core: CatoCore) {
  camera.matrixWorld.extractBasis(_r, _u, _f);
  _f.negate(); // three cameras look down −z
  viewDir(LIGHTS.uKeyD.value, -0.52, 0.74, 0.56);
  viewDir(LIGHTS.uFillD.value, 0.85, 0.12, 0.45);
  viewDir(LIGHTS.uRimD.value, 0.35, 0.45, -0.85);
  viewDir(_v, 0, 0, 1);
  LIGHTS.uHalfD.value.copy(LIGHTS.uKeyD.value).add(_v).normalize();
  const L = core.lights;
  LIGHTS.uKeyC.value.fromArray(L.key);
  LIGHTS.uFillC.value.fromArray(L.fill);
  LIGHTS.uRimC.value.fromArray(L.rim);
  LIGHTS.uSky.value.fromArray(L.sky);
  LIGHTS.uGnd.value.fromArray(L.gnd);
  const led = core.led();
  LIGHTS.uLed.value.set((led[0] / 255) ** 2.2, (led[1] / 255) ** 2.2, (led[2] / 255) ** 2.2);
}

const _inv = new THREE.Matrix4();
const _bone = new THREE.Matrix4();
const _cp = new THREE.Vector3();
const _cx = new THREE.Vector3();
const _cy = new THREE.Vector3();
const _cz = new THREE.Vector3();
const VIEW = { p: [0, 0, 0], f: [0, 0, -1], r: [1, 0, 0], u: [0, 1, 0], w: 800, h: 600, fov: 0.5, minX: 14 };

function shadowTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 128;
  const g = c.getContext('2d')!;
  const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  grad.addColorStop(0, 'rgba(11,16,51,0.34)');
  grad.addColorStop(0.55, 'rgba(11,16,51,0.12)');
  grad.addColorStop(1, 'rgba(11,16,51,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 128, 128);
  return new THREE.CanvasTexture(c);
}

const rgbCache = new Map<string, [number, number, number]>();
function parseRgb(s: string): [number, number, number] {
  let c = rgbCache.get(s);
  if (!c) {
    const m = s.match(/\d+(\.\d+)?/g) ?? ['0', '0', '0'];
    const col = new THREE.Color().setRGB(+m[0] / 255, +m[1] / 255, +m[2] / 255, THREE.SRGBColorSpace);
    c = [col.r, col.g, col.b];
    rgbCache.set(s, c);
  }
  return c;
}

const CatoBody = forwardRef<CatoBodyHandle, { paused?: boolean }>(function CatoBody({ paused = false }, ref) {
  const core = useMemo(() => (typeof document === 'undefined' ? null : createCatoCore()), []);
  useImperativeHandle(ref, () => ({ core }), [core]);

  /*
   * Static geometry: every engine mesh is split per material into pieces
   * (same fan triangulation and face set as before, in engine face order),
   * and all pieces of one material are packed into one BatchedMesh. Batch
   * capacities are exactly the piece count and vertex count of that material,
   * derived from the generated geometry.
   */
  const built = useMemo(() => {
    if (!core) return null;
    const mats = core.MATS.map(materialFor);
    const { fStart, fLen, fMat, fIdx, LP } = core;

    /* per material: the pieces (triangle positions) it will hold */
    const perMat: Float32Array[][] = mats.map(() => []);
    /* pieces in mesh order; pieceRange[i]..pieceRange[i+1] are mesh i's pieces */
    const pieceMat: number[] = [];
    const pieceSlot: number[] = [];
    const pieceRange: number[] = [0];
    const tris = new Map<number, number>();
    core.meshes.forEach((m, mi) => {
      tris.clear();
      for (let f = m.f0; f < m.f1; f++) {
        const mat = fMat[f];
        tris.set(mat, (tris.get(mat) ?? 0) + Math.max(0, fLen[f] - 2));
      }
      const fill = new Map<number, { pos: Float32Array; n: number }>();
      tris.forEach((count, mat) => fill.set(mat, { pos: new Float32Array(count * 9), n: 0 }));
      for (let f = m.f0; f < m.f1; f++) {
        const s = fStart[f];
        const n = fLen[f];
        const piece = fill.get(fMat[f])!;
        const a = fIdx[s];
        for (let k = 1; k < n - 1; k++) {
          const b = fIdx[s + k];
          const c = fIdx[s + k + 1];
          const o = piece.n;
          const p = piece.pos;
          p[o] = LP[a * 3]; p[o + 1] = LP[a * 3 + 1]; p[o + 2] = LP[a * 3 + 2];
          p[o + 3] = LP[b * 3]; p[o + 4] = LP[b * 3 + 1]; p[o + 5] = LP[b * 3 + 2];
          p[o + 6] = LP[c * 3]; p[o + 7] = LP[c * 3 + 1]; p[o + 8] = LP[c * 3 + 2];
          piece.n = o + 9;
        }
      }
      /* keep each material's pieces in first-use order, as the old groups were */
      for (let f = m.f0; f < m.f1; f++) {
        const mat = fMat[f];
        const piece = fill.get(mat);
        if (!piece) continue;
        fill.delete(mat);
        if (piece.n === 0) continue;
        pieceMat.push(mat);
        pieceSlot.push(perMat[mat].length);
        perMat[mat].push(piece.pos);
      }
      pieceRange.push(pieceMat.length);
    });

    /* one BatchedMesh per material that has faces */
    const batches: (THREE.BatchedMesh | null)[] = perMat.map((pieces, mat) => {
      if (pieces.length === 0) return null;
      let verts = 0;
      for (const p of pieces) verts += p.length / 3;
      const batch = new THREE.BatchedMesh(pieces.length, verts, 0, mats[mat]);
      /* same culling/order semantics as the former per-mesh Meshes */
      batch.frustumCulled = false;
      batch.perObjectFrustumCulled = false;
      batch.sortObjects = false;
      return batch;
    });
    /* add geometries and instances; piece order within a batch == slot order */
    const objs = batches.filter((b): b is THREE.BatchedMesh => b !== null);
    const pieceBatch: THREE.BatchedMesh[] = new Array(pieceMat.length);
    const pieceId = new Int32Array(pieceMat.length);
    const pieceObj = new Int32Array(pieceMat.length); // index into objs
    for (let p = 0; p < pieceMat.length; p++) {
      const mat = pieceMat[p];
      const batch = batches[mat]!;
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(perMat[mat][pieceSlot[p]], 3));
      const gid = batch.addGeometry(g);
      g.dispose();
      pieceBatch[p] = batch;
      pieceId[p] = batch.addInstance(gid);
      pieceObj[p] = objs.indexOf(batch);
    }
    /* piece visibility mirror + per-batch visible-piece count (a batch with none is hidden outright) */
    const pieceVis = new Uint8Array(pieceMat.length).fill(1);
    const objVis = new Int32Array(objs.length);
    for (let p = 0; p < pieceMat.length; p++) objVis[pieceObj[p]]++;

    /* decals: one dynamic RGBA mesh */
    const dg = new THREE.BufferGeometry();
    const dPos = new Float32Array(MAX_DECAL_TRIS * 9);
    const dCol = new Float32Array(MAX_DECAL_TRIS * 12);
    dg.setAttribute('position', new THREE.BufferAttribute(dPos, 3).setUsage(THREE.DynamicDrawUsage));
    dg.setAttribute('color', new THREE.BufferAttribute(dCol, 4).setUsage(THREE.DynamicDrawUsage));
    const dm = new THREE.MeshBasicMaterial({
      vertexColors: true,
      transparent: true,
      depthWrite: false,
      toneMapped: false,
      side: THREE.DoubleSide,
      polygonOffset: true,
      polygonOffsetFactor: -2,
      polygonOffsetUnits: -4,
    });
    const shadowTex = shadowTexture();
    const shadowMat = new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, depthWrite: false });
    const shadow = new THREE.Mesh(new THREE.PlaneGeometry(1.3, 1.3), shadowMat);
    shadow.rotation.x = -Math.PI / 2;
    shadow.position.y = 0.004;
    const decalMesh = new THREE.Mesh(dg, dm);
    decalMesh.frustumCulled = false;
    decalMesh.renderOrder = 2;
    return { mats, objs, pieceBatch, pieceId, pieceObj, pieceRange, pieceVis, objVis, dg, dPos, dCol, dm, decalMesh, shadow, shadowTex, shadowMat };
  }, [core]);

  const resourceLease = useRef(0);
  const currentBuilt = useRef(built);
  useEffect(
    () => {
      currentBuilt.current = built;
      const lease = ++resourceLease.current;
      return () => queueMicrotask(() => {
        // Strict-mode effect replay retains the same live GPU resources.
        if (resourceLease.current !== lease && currentBuilt.current === built) return;
        if (!built) return;
        /* BatchedMesh.dispose frees its merged geometry and matrix/indirect textures */
        built.objs.forEach((o) => o.dispose());
        built.mats.forEach((m) => m.dispose());
        built.dg.dispose();
        built.dm.dispose();
        built.shadow.geometry.dispose();
        built.shadowMat.dispose();
        built.shadowTex.dispose();
      });
    },
    [built],
  );

  const group = useRef<THREE.Group>(null);
  const settled = useRef(0);

  /* the engine's SVG (cards + emotes) sits over the canvas, under the page */
  useEffect(() => {
    if (!core) return;
    const stage = document.querySelector('.gl-stage');
    if (!stage) return;
    stage.appendChild(core.svg);
    return () => core.svg.remove();
  }, [core]);

  useFrame((state, delta) => {
    if (!core || !built) return;
    /* camera in Cato-local space, for the engine's SVG projection */
    const g0 = group.current;
    let view: typeof VIEW | undefined;
    if (g0) {
      _inv.copy(g0.matrixWorld).invert();
      state.camera.matrixWorld.extractBasis(_cx, _cy, _cz);
      _cp.setFromMatrixPosition(state.camera.matrixWorld).applyMatrix4(_inv);
      _cx.transformDirection(_inv);
      _cy.transformDirection(_inv);
      _cz.transformDirection(_inv).negate();
      VIEW.p[0] = _cp.x; VIEW.p[1] = _cp.y; VIEW.p[2] = _cp.z;
      VIEW.r[0] = _cx.x; VIEW.r[1] = _cx.y; VIEW.r[2] = _cx.z;
      VIEW.u[0] = _cy.x; VIEW.u[1] = _cy.y; VIEW.u[2] = _cy.z;
      VIEW.f[0] = _cz.x; VIEW.f[1] = _cz.y; VIEW.f[2] = _cz.z;
      VIEW.w = state.size.width;
      VIEW.h = state.size.height;
      VIEW.fov = THREE.MathUtils.degToRad((state.camera as THREE.PerspectiveCamera).fov);
      VIEW.minX = state.size.width * 0.5;
      view = VIEW;
    }
    /* paused (reduced motion): still settle the rest pose for the first frames */
    if (!paused || settled.current < 30) {
      core.frame(Math.min(0.05, Math.max(0.001, delta)), view);
      settled.current++;
    }
    updateLights(state.camera, core);
    built.shadow.position.x = core.body.x;
    built.shadow.position.z = core.body.z;

    /* rigid skinning: every piece of mesh i takes bone W; hidden meshes hide their pieces */
    const { objs, pieceBatch, pieceId, pieceObj, pieceRange, pieceVis, objVis } = built;
    const meshes = core.meshes;
    for (let i = 0; i < meshes.length; i++) {
      const m = meshes[i];
      const p0 = pieceRange[i];
      const p1 = pieceRange[i + 1];
      if (!m.vis) {
        for (let p = p0; p < p1; p++) {
          if (pieceVis[p]) {
            pieceVis[p] = 0;
            objVis[pieceObj[p]]--;
            pieceBatch[p].setVisibleAt(pieceId[p], false);
          }
        }
        continue;
      }
      const W = core.BONES[m.bone].W;
      _bone.set(W[0], W[1], W[2], W[3], W[4], W[5], W[6], W[7], W[8], W[9], W[10], W[11], 0, 0, 0, 1);
      for (let p = p0; p < p1; p++) {
        const batch = pieceBatch[p];
        const id = pieceId[p];
        if (!pieceVis[p]) {
          pieceVis[p] = 1;
          objVis[pieceObj[p]]++;
          batch.setVisibleAt(id, true);
        }
        batch.setMatrixAt(id, _bone);
      }
    }
    /* a batch whose pieces are all hidden is skipped by the renderer entirely */
    for (let b = 0; b < objs.length; b++) objs[b].visible = objVis[b] > 0;

    /* decals: fan-triangulate each polygon */
    const { dPos, dCol, dg } = built;
    let t = 0;
    for (const d of core.decals) {
      const [r, g, b] = parseRgb(d.fill);
      const a = Math.min(1, d.alpha);
      const p = d.pts;
      for (let k = 1; k < p.length - 1 && t < MAX_DECAL_TRIS; k++, t++) {
        for (let v = 0; v < 3; v++) {
          const P = v === 0 ? p[0] : v === 1 ? p[k] : p[k + 1];
          const o3 = t * 9 + v * 3;
          const o4 = t * 12 + v * 4;
          dPos[o3] = P[0]; dPos[o3 + 1] = P[1]; dPos[o3 + 2] = P[2];
          /* glowing decals (eyes, screens) are pushed past 1 so the global bloom catches them */
          const boost = 1 + d.glow * 1.4;
          dCol[o4] = r * boost; dCol[o4 + 1] = g * boost; dCol[o4 + 2] = b * boost; dCol[o4 + 3] = a;
        }
      }
    }
    dg.setDrawRange(0, t * 3);
    (dg.attributes.position as THREE.BufferAttribute).needsUpdate = true;
    (dg.attributes.color as THREE.BufferAttribute).needsUpdate = true;
  });

  if (!built) return null;
  return (
    <group ref={group} dispose={null}>
      {built.objs.map((o, i) => (
        <primitive key={i} object={o} />
      ))}
      <primitive object={built.decalMesh} />
      <primitive object={built.shadow} />
    </group>
  );
});

export default CatoBody;
