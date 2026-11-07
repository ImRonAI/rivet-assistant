// Proves the registry components in src/components are byte-identical to what their official registries serve. The
// hashes are those of each registry item's file content:
// - ElevenLabs UI (https://ui.elevenlabs.io/r/<name>.json) at the commit they were installed from, as the
//   elevenlabs-ui-components skill's manifest records them;
// - AI Elements (https://elements.ai-sdk.dev/api/registry/<name>.json), whose files import shadcn primitives from
//   @/registry/default/ui/, which the CLI rewrites to @/components/ui/ on install, so that one rewrite is undone here;
// - shadcn's radix-nova primitives they build on (https://ui.shadcn.com/r/styles/radix-nova/<name>.json).
import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"

const COMMIT = "3a28ebab197f8cfa599934f52db12ad1396e54f4"
const ELEVENLABS = {
  "ui/orb.tsx": "16fdb804e37c495806091d34712e689f89c02b4638bb173ab5bef5e5eac52b91",
  "ui/shimmering-text.tsx": "c746b3a1d29d9c666bd01681d512b88aeb4a3ff6f589e4055870580ced4eea5b",
  "ui/matrix.tsx": "f074f26d5d9060b53c794893993c2ab8b7c13d6de0c1a4135cad069d0ecf0141",
  "ui/conversation.tsx": "2912150fa0cf25dd1533276f4fb44cfc1158da8847ebaf9c2dac076297b57bc2",
  "ui/message.tsx": "9b8c155dea2a034dae80a804a0648427e5f9e0344cd0dab5c13d61ee452a1fc1",
  "ui/response.tsx": "129a7e7acd1344af1ee0406e555d378c3c2c85b626ac52b350dad0fb3698a09b",
}
const AI_ELEMENTS = {
  "ai-elements/chain-of-thought.tsx": "5ccfddc925c98161c2508f63632380bb9976bfe95ffede4b9332451dcb6f0b70",
  "ai-elements/shimmer.tsx": "24bcb9b1dd45d616bd50dc6500e9f414ccabf440efb249e8d92522344b9057d6",
}
const SHADCN = {
  "ui/badge.tsx": "59953420d01d23d172b4e32583426db153ff09226e2d460d53526b9f25282801",
  "ui/button.tsx": "08e5f87833c5db8436e89337ebde0f776bcdbf649d3de69fb89be6441ca2e366",
  "ui/collapsible.tsx": "c6598e5584f4258b5d9fefadbc5ad6a66888ad58729f4b634a0bb2d9c513188b",
  "ui/avatar.tsx": "036b8f39d9f42962a9eedd097103c90450d58a50ea55c693896c4cea4b5953a4",
}

const read = (file) => readFileSync(new URL(`../src/components/${file}`, import.meta.url), "utf8")
const asServed = {
  elevenlabs: read,
  "ai-elements": (file) => read(file).replaceAll("@/components/ui/", "@/registry/default/ui/"),
  shadcn: read,
}

let changed = 0
for (const [source, files] of [["elevenlabs", ELEVENLABS], ["ai-elements", AI_ELEMENTS], ["shadcn", SHADCN]]) {
  for (const [file, want] of Object.entries(files)) {
    const got = createHash("sha256").update(asServed[source](file)).digest("hex")
    const ok = got === want
    if (!ok) changed++
    console.log(`${ok ? "OK     " : "CHANGED"} src/components/${file} ${got}`)
  }
}
console.log(
  changed
    ? `\n${changed} component(s) differ from their registries.`
    : `\nAll components match their registries (ElevenLabs UI at elevenlabs/ui@${COMMIT.slice(0, 8)}).`
)
process.exit(changed ? 1 : 0)
