#!/bin/sh
# Installs the native jpm binary (and `jvm`, the same binary under another name) into ~/.jpm/bin.
#
#   curl -fsSL https://github.com/NonSwag/jpm/releases/latest/download/install.sh | sh
#
# JPM_VERSION=0.1.0 picks a version, JPM_HOME changes the install root, and JPM_INSTALL_BASE replaces the
# download URL (a mirror, or file:///path for testing). Nothing outside JPM_HOME is changed.
set -eu

repo="${JPM_REPO:-NonSwag/jpm}"
if [ -n "${JPM_INSTALL_BASE:-}" ]; then
  base="$JPM_INSTALL_BASE"
elif [ -n "${JPM_VERSION:-}" ]; then
  base="https://github.com/$repo/releases/download/v$JPM_VERSION"
else
  base="https://github.com/$repo/releases/latest/download"
fi

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) echo "jpm: unsupported OS $(uname -s); on Windows run install.ps1" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *) echo "jpm: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
asset="jpm-$os-$arch"

dir="${JPM_HOME:-$HOME/.jpm}/bin"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

echo "Downloading $asset from $base"
curl -fsSL -o "$tmp/jpm" "$base/$asset"
curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS"

expected="$(awk -v a="$asset" '$2 == a || $2 == "*" a { print $1 }' "$tmp/SHA256SUMS")"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$tmp/jpm" | cut -d' ' -f1)"
else
  actual="$(shasum -a 256 "$tmp/jpm" | cut -d' ' -f1)"
fi
if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
  echo "jpm: checksum mismatch for $asset (expected ${expected:-none}, got $actual)" >&2
  exit 1
fi

mkdir -p "$dir"
chmod +x "$tmp/jpm"
mv -f "$tmp/jpm" "$dir/jpm"
ln -sf jpm "$dir/jvm"

echo "Installed $("$dir/jpm" --version) to $dir (with jvm)"
case ":$PATH:" in
  *":$dir:"*) ;;
  *) echo "Add it to your PATH, for example in ~/.profile:  export PATH=\"$dir:\$PATH\"" ;;
esac
