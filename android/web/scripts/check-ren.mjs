// Verifies that ren desktop's files under src/ren are the ones SHA256SUMS records (see src/ren/SOURCE.txt).
import { createHash } from "node:crypto"
import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src/ren")
const recorded = new Map(
  readFileSync(path.join(root, "SHA256SUMS"), "utf8")
    .trim()
    .split("\n")
    .map((line) => {
      const [hash, name] = line.split(/\s+/)
      return [name, hash]
    }),
)
const present = readdirSync(root, { recursive: true, withFileTypes: true })
  .filter((entry) => entry.isFile())
  .map((entry) => path.relative(root, path.join(entry.parentPath, entry.name)))
  .filter((name) => name !== "SHA256SUMS" && name !== "SOURCE.txt")

const problems = []
for (const name of present) {
  const hash = createHash("sha256").update(readFileSync(path.join(root, name))).digest("hex")
  if (recorded.get(name) !== hash) problems.push(`${name} differs from ren desktop's file`)
}
for (const name of recorded.keys()) if (!present.includes(name)) problems.push(`${name} is missing`)
if (problems.length > 0) {
  console.error(problems.join("\n"))
  process.exit(1)
}
console.log(`src/ren: ${present.length} files match ren desktop`)
