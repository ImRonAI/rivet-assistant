#!/bin/sh
# The developer tools of the laptop and the computer images, so the owner's terminal, the agents' Desktop Commander and
# the hooks' OpenCode jobs all have the same ones. Every download is pinned and checked against its project's checksum.
# Rust lives in the shared home instead (rustup's default ~/.rustup and ~/.cargo), because rustup needs RUSTUP_HOME at
# run time and Desktop Commander and the hooks jobs only pass HOME and PATH through.
set -eu

NODE=26.13.0 # Only where the base image has no Node (the laptop); the computer image is node:26.13.0-bookworm.
NODE_SHA256=d28a96af6c38596f6d9d97c0ee531c13d238d757742387d1928de822d959e997
GO=1.27.3
GO_SHA256=65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40
UV=0.13.6
UV_SHA256=f29c3e94ffabd7e6f3ba521dba169a9b7ec12100bf3257087f0f24f6123f3645
BUN=1.4.4
BUN_SHA256=bf8ccbfb2b0b80159e0f9a6d15c55317add8077534dbc6130a3edb52fccb395c
DENO=2.9.8
DENO_SHA256=e890975bc2549807bbcc0b584ffafda72cc7c93349dd99c80483dd784f6c4609

apt-get update
apt-get install -y --no-install-recommends \
    build-essential cmake pkg-config ca-certificates curl wget gnupg xz-utils zip unzip rsync tree less \
    openssh-client jq ripgrep fd-find sqlite3 ffmpeg python3-pip python3-venv python-is-python3 pipx
# GitHub CLI from GitHub's own apt repository (cli/cli docs/install_linux.md).
mkdir -p -m 755 /etc/apt/keyrings
wget -nv -O /etc/apt/keyrings/githubcli-archive-keyring.gpg https://cli.github.com/packages/githubcli-archive-keyring.gpg
chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
    > /etc/apt/sources.list.d/github-cli.list
apt-get update
apt-get install -y --no-install-recommends gh
rm -rf /var/lib/apt/lists/*
ln -sf /usr/bin/fdfind /usr/local/bin/fd

work=$(mktemp -d)
export npm_config_cache="$work/npm-cache"
cd "$work"
if ! command -v node > /dev/null; then
    curl -fsSLO "https://nodejs.org/dist/v$NODE/node-v$NODE-linux-x64.tar.xz"
    echo "$NODE_SHA256  node-v$NODE-linux-x64.tar.xz" | sha256sum -c -
    tar -xJf "node-v$NODE-linux-x64.tar.xz" -C /usr/local --strip-components=1 \
        --exclude CHANGELOG.md --exclude LICENSE --exclude README.md
fi
# Node 25 and later ship without Corepack, so pnpm and yarn come from npm.
command -v yarn > /dev/null || npm install -g yarn@1.22.22
npm install -g pnpm@12.16.1 vercel@65.9.0 typescript@7.0.2 tsx@4.23.15

curl -fsSLO "https://go.dev/dl/go$GO.linux-amd64.tar.gz"
echo "$GO_SHA256  go$GO.linux-amd64.tar.gz" | sha256sum -c -
tar -xzf "go$GO.linux-amd64.tar.gz" -C /usr/local
ln -sf /usr/local/go/bin/go /usr/local/go/bin/gofmt /usr/local/bin/

curl -fsSLO "https://github.com/astral-sh/uv/releases/download/$UV/uv-x86_64-unknown-linux-gnu.tar.gz"
echo "$UV_SHA256  uv-x86_64-unknown-linux-gnu.tar.gz" | sha256sum -c -
tar -xzf uv-x86_64-unknown-linux-gnu.tar.gz
install -m 755 uv-x86_64-unknown-linux-gnu/uv uv-x86_64-unknown-linux-gnu/uvx /usr/local/bin/

curl -fsSLO "https://github.com/oven-sh/bun/releases/download/bun-v$BUN/bun-linux-x64.zip"
echo "$BUN_SHA256  bun-linux-x64.zip" | sha256sum -c -
unzip -q bun-linux-x64.zip
install -m 755 bun-linux-x64/bun /usr/local/bin/
ln -sf /usr/local/bin/bun /usr/local/bin/bunx

curl -fsSLO "https://github.com/denoland/deno/releases/download/v$DENO/deno-x86_64-unknown-linux-gnu.zip"
echo "$DENO_SHA256  deno-x86_64-unknown-linux-gnu.zip" | sha256sum -c -
unzip -q deno-x86_64-unknown-linux-gnu.zip
install -m 755 deno /usr/local/bin/

cd /
rm -rf "$work"
