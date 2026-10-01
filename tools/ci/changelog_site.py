"""Generate the static tester download/changelog site for GitHub Pages.

Reads the repository's GitHub releases (stable + RC prereleases) through the
`gh` CLI and emits a self-contained `index.html`: tester install instructions,
per-release changelog notes, and direct links to the JAR + checksum assets.

Usage:
    python3 tools/ci/changelog_site.py --repo OWNER/REPO --out site/

GH_TOKEN must be set. No third-party dependencies.
"""
import argparse
import datetime
import html
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def gh_api(path: str) -> list:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("GH_TOKEN or GITHUB_TOKEN is required")
    out = subprocess.run(
        ["gh", "api", path],
        capture_output=True, text=True, check=False)
    if out.returncode != 0:
        sys.exit(f"gh api {path} failed: {out.stderr.strip()}")
    return json.loads(out.stdout)


def esc(text) -> str:
    return html.escape(text or "", quote=True)


def asset_links(assets: list) -> str:
    """JAR first, then checksums/SBOM manifests, as download buttons."""
    jars = [a for a in assets if a["name"].endswith(".jar")]
    others = [a for a in assets if not a["name"].endswith(".jar")]
    parts = []
    for a in jars + others:
        size = a.get("size", 0) / 1024
        label = f"{esc(a['name'])} ({size:.0f} KB)"
        css = "btn jar" if a["name"].endswith(".jar") else "btn"
        parts.append(f'<a class="{css}" href="{esc(a["browser_download_url"])}" '
                     f'download>{label}</a>')
    return "\n        ".join(parts) if parts else '<span class="muted">no assets</span>'


def render_release(rel: dict) -> str:
    tag = rel.get("tag_name", "?")
    name = rel.get("name") or tag
    date = (rel.get("published_at") or "")[:10]
    body = rel.get("body") or ""
    prerelease = rel.get("prerelease", False)
    badge = ('<span class="badge rc">RC</span>' if prerelease
             else '<span class="badge stable">stable</span>')
    notes = esc(body).strip() or "(no notes)"
    # Simple markdown-lite: keep the raw text readable in a pre block.
    return f"""    <section class="release" id="{esc(tag)}">
      <h3>{esc(name)} {badge} <span class="muted">{esc(date)}</span></h3>
      <div class="assets">
        {asset_links(rel.get("assets", []))}
      </div>
      <details><summary>Changelog</summary><pre>{notes}</pre></details>
    </section>"""


PAGE = """<!DOCTYPE html>
<html lang="ro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Straja — Descărcări &amp; Changelog</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, sans-serif; max-width: 860px; margin: 0 auto;
         padding: 2rem 1rem; line-height: 1.5; }
  h1 { margin-bottom: .2rem; }
  .muted { color: #888; font-weight: normal; font-size: .85em; }
  .card { border: 1px solid #8884; border-radius: 8px; padding: 1rem 1.25rem;
          margin: 1.25rem 0; }
  .release { border-top: 1px solid #8884; padding: 1rem 0; }
  .release h3 { margin: .2rem 0 .6rem; }
  .badge { font-size: .7em; border-radius: 4px; padding: .15em .5em;
           vertical-align: middle; }
  .badge.stable { background: #2da44e; color: #fff; }
  .badge.rc { background: #bf8700; color: #fff; }
  .btn { display: inline-block; border: 1px solid #8888; border-radius: 6px;
         padding: .35em .8em; margin: .15em .3em .15em 0; text-decoration: none;
         font-size: .9em; }
  .btn.jar { border-color: #0969da; font-weight: 600; }
  pre { white-space: pre-wrap; font-size: .85em; }
  details summary { cursor: pointer; color: #0969da; }
  code { background: #8882; border-radius: 4px; padding: 0 .3em; }
  ol, ul { padding-left: 1.4rem; }
</style>
</head>
<body>
<h1>Straja — Descărcări &amp; Changelog</h1>
<p class="muted">Generat __GENERATED__ · sursa:
<a href="https://github.com/__REPO__">github.com/__REPO__</a></p>

<section class="card">
<h2>Pentru testeri</h2>
<ol>
  <li><strong>Minecraft 1.21.1</strong> + <strong>NeoForge 21.1.x</strong> +
      <strong>Java 21</strong>.</li>
  <li>Instalează dependența obligatorie
      <a href="https://modrinth.com/mod/envelope">Envelope 0.6.2+</a>.</li>
  <li>Descarcă <code>straja-&lt;versiune&gt;.jar</code> din release-ul dorit de
      mai jos și pune-l în <code>mods/</code>.</li>
  <li>Opțional (economie): iteme-monedă — implicit monedele Ady's Decorations;
      configurabile în <code>world/serverconfig/straja-server.toml</code>,
      secțiunea <code>[economy]</code>.</li>
</ol>
<p>Ghizi de pornire:
  <a href="https://github.com/__REPO__/blob/main/docs/admin-quickstart.md">admin quick-start</a> ·
  <a href="https://github.com/__REPO__/blob/main/docs/player-quickstart.md">player quick-start</a>.
</p>
<p>Admini: după instalare rulează <code>/straja setup</code> și urmează
checklist-ul; criteriul de „gata" este <code>/straja setup verify</code> →
<em>„Instalare funcțională — gata pentru jucători."</em></p>
</section>

<h2>Release-uri</h2>
__RELEASES__
</body>
</html>
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    releases = gh_api(f"repos/{args.repo}/releases?per_page=100")
    releases = [r for r in releases if not r.get("draft")]
    releases.sort(key=lambda r: r.get("published_at") or "", reverse=True)
    if not releases:
        sys.exit("no public releases found — nothing to publish")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    generated = datetime.datetime.now(datetime.timezone.utc).strftime(
        "%Y-%m-%d %H:%M UTC")
    body = "\n".join(render_release(r) for r in releases)
    (out_dir / "index.html").write_text(
        PAGE.replace("__REPO__", args.repo)
            .replace("__GENERATED__", generated)
            .replace("__RELEASES__", body),
        encoding="utf-8")
    print(f"wrote {out_dir / 'index.html'} with {len(releases)} releases")


if __name__ == "__main__":
    main()
