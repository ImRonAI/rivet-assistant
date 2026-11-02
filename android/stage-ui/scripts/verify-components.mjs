// Proves the ElevenLabs UI components in src/components/ui are byte-identical to upstream elevenlabs/ui at the commit
// they were installed from (https://ui.elevenlabs.io/r/<name>.json, the official registry). The hashes are those of the
// registry items' file contents, as the elevenlabs-ui-components skill's manifest records them.
import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"

const COMMIT = "3a28ebab197f8cfa599934f52db12ad1396e54f4"
const UPSTREAM = {
  "orb.tsx": "16fdb804e37c495806091d34712e689f89c02b4638bb173ab5bef5e5eac52b91",
  "shimmering-text.tsx": "c746b3a1d29d9c666bd01681d512b88aeb4a3ff6f589e4055870580ced4eea5b",
  "matrix.tsx": "f074f26d5d9060b53c794893993c2ab8b7c13d6de0c1a4135cad069d0ecf0141",
}

let changed = 0
for (const [file, want] of Object.entries(UPSTREAM)) {
  const got = createHash("sha256").update(readFileSync(new URL(`../src/components/ui/${file}`, import.meta.url))).digest("hex")
  const ok = got === want
  if (!ok) changed++
  console.log(`${ok ? "OK     " : "CHANGED"} src/components/ui/${file} ${got}`)
}
console.log(changed ? `\n${changed} component(s) differ from elevenlabs/ui@${COMMIT.slice(0, 8)}.` : `\nAll components match elevenlabs/ui@${COMMIT.slice(0, 8)}.`)
process.exit(changed ? 1 : 0)
