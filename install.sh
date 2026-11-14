#!/bin/sh
# Installs the native jpm binary (and `jvm`, the same binary under another name) into ~/.jpm/bin.
#
#   curl -fsSL https://github.com/NonSwag/jpm/releases/latest/download/install.sh | sh
#
# JPM_VERSION=0.3.0 picks a version, JPM_HOME changes the install root, and JPM_INSTALL_BASE replaces the
# download URL (a mirror, or file:///path for testing). The install folder is added to PATH in your shell's
# startup file (one marked line, added once); JPM_NO_MODIFY_PATH=1 skips that.
set -eu

repo="${JPM_REPO:-NonSwag/jpm}"
if [ -n "${JPM_INSTALL_BASE:-}" ]; then
  base="$JPM_INSTALL_BASE"
elif [ -n "${JPM_VERSION:-}" ]; then
  base="https://github.com/$repo/releases/download/$JPM_VERSION"
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

# java, javac, ... shims: inside a jpm project they run its JDK, elsewhere the `jvm use` default, otherwise the
# java that was already on PATH. JPM_NO_SHIMS=1 skips them.
if [ -z "${JPM_NO_SHIMS:-}" ]; then
  if "$dir/jpm" jvm setup >/dev/null 2>&1; then
    echo "Added java, javac, ... shims that follow your project's JDK (see \`jvm current\`)"
  else
    echo "warning: could not create the java shims; run \`jvm setup\` to see why" >&2
  fi
fi

# Put the install folder on PATH for future shells.
case "$dir" in
  "$HOME"/*) shown="\$HOME${dir#"$HOME"}" ;;
  *) shown="$dir" ;;
esac
add_line() { # file line
  mkdir -p "$(dirname "$1")"
  if [ -f "$1" ] && grep -qsF -e "$dir" -e "$shown" "$1"; then
    return 1
  fi
  if [ -f "$1" ] && [ -s "$1" ] && [ -n "$(tail -c 1 "$1")" ]; then printf '\n' >> "$1"; fi
  printf '\n# Added by the jpm installer\n%s\n' "$2" >> "$1"
  echo "Added $shown to PATH in $1"
  return 0
}
case ":$PATH:" in
  *":$dir:"*) on_path=yes ;;
  *) on_path=no ;;
esac
if [ -n "${JPM_NO_MODIFY_PATH:-}" ]; then
  [ "$on_path" = yes ] || echo "PATH not changed (JPM_NO_MODIFY_PATH). Add it yourself:  export PATH=\"$shown:\$PATH\""
elif [ "$on_path" = no ]; then
  posix_line="export PATH=\"$shown:\$PATH\""
  changed=no
  case "$(basename "${SHELL:-sh}")" in
    zsh)
      add_line "${ZDOTDIR:-$HOME}/.zshrc" "$posix_line" && changed=yes ;;
    bash)
      add_line "$HOME/.bashrc" "$posix_line" && changed=yes
      # macOS terminals start login shells, which read .bash_profile rather than .bashrc
      if [ "$os" = macos ] || [ -f "$HOME/.bash_profile" ]; then
        add_line "$HOME/.bash_profile" "$posix_line" && changed=yes
      fi ;;
    fish)
      add_line "${XDG_CONFIG_HOME:-$HOME/.config}/fish/conf.d/jpm.fish" "fish_add_path \"$dir\"" && changed=yes ;;
    *)
      add_line "$HOME/.profile" "$posix_line" && changed=yes ;;
  esac
  if [ "$changed" = yes ]; then
    echo "Open a new terminal, or run this to use jpm right away:  export PATH=\"$dir:\$PATH\""
  else
    echo "Your shell startup file already adds $shown to PATH; open a new terminal to use jpm."
  fi
fi
