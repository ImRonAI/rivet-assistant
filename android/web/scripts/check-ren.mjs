// Verifies that ren desktop's files under src/ren and src/data are the ones their SHA256SUMS record (see
// src/ren/SOURCE.txt).
import { createHash } from "node:crypto"
import { readFileSync, readdirSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src")

const problems = []
for (const folder of ["ren", "data"]) {
  const root = path.join(src, folder)
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
  for (const name of present) {
    const hash = createHash("sha256").update(readFileSync(path.join(root, name))).digest("hex")
    if (recorded.get(name) !== hash) problems.push(`src/${folder}/${name} differs from ren desktop's file`)
  }
  for (const name of recorded.keys()) if (!present.includes(name)) problems.push(`src/${folder}/${name} is missing`)
  if (problems.length === 0) console.log(`src/${folder}: ${present.length} files match ren desktop`)
}
if (problems.length > 0) {
  console.error(problems.join("\n"))
  process.exit(1)
}
