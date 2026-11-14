// Type-checks the pages (`tsc -b`). ren desktop's own files (src/ren) are checked with ren desktop's compiler options,
// and at the commit they come from ren desktop's own `tsc --noEmit` reports errors in two of them; those are listed in
// ren-tsc-known.txt, as ren desktop's compiler prints them. The check passes only when tsc reports exactly those: any
// other error fails it, and so does one of them going away (a new copy of ren desktop: list its errors afresh).
import { spawnSync } from "node:child_process"
import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const known = readFileSync(path.join(here, "ren-tsc-known.txt"), "utf8").trim().split("\n").map((line) => `src/ren/${line}`)
const run = spawnSync("npx", ["tsc", "-b"], { cwd: path.join(here, ".."), encoding: "utf8" })
const errors = run.stdout.split("\n").filter((line) => line.includes("error TS"))
const extra = errors.filter((line) => !known.includes(line))
const gone = known.filter((line) => !errors.includes(line))
if (extra.length > 0 || gone.length > 0 || (errors.length === 0 && run.status !== 0)) {
  process.stdout.write(run.stdout)
  process.stderr.write(run.stderr)
  if (gone.length > 0) console.error(`No longer reported (update ren-tsc-known.txt):\n${gone.join("\n")}`)
  process.exit(1)
}
console.log(`tsc: no errors beyond ren desktop's own ${known.length}`)
