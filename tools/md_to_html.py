#!/usr/bin/env python3
"""Render README.md as a standalone README.html (same directory, print friendly)."""
import html
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "README.md"
DST = ROOT / "README.html"

CSS = """  :root { color-scheme: light dark; }
  body {
    margin: 0 auto; max-width: 46rem; padding: 2.5rem 1.25rem 4rem;
    font: 16px/1.75 -apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif;
    color: #1c1f23; background: #fff;
  }
  h1 { font-size: 1.9rem; margin: 0 0 1.2rem; letter-spacing: -0.01em; }
  h2 { font-size: 1.25rem; margin: 2.4rem 0 .8rem; padding-bottom: .35rem; border-bottom: 1px solid #e5e8eb; }
  h3 { font-size: 1.05rem; margin: 1.6rem 0 .5rem; }
  p, li { color: #33383e; }
  code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: .88em; background: #f2f4f6; padding: .15em .4em; border-radius: 4px;
  }
  pre {
    background: #f6f8fa; border: 1px solid #e5e8eb; border-radius: 8px;
    padding: 1rem 1.1rem; overflow-x: auto; line-height: 1.6;
  }
  pre code { background: none; padding: 0; font-size: .85em; }
  ul { padding-left: 1.3rem; }
  li { margin: .35rem 0; }
  strong { color: #14181c; }
  @media (prefers-color-scheme: dark) {
    body { color: #e6e9ec; background: #14171a; }
    p, li { color: #c3c9d0; }
    strong { color: #fff; }
    h2 { border-color: #2a3037; }
    code { background: #23282e; color: #e6e9ec; }
    pre { background: #1b1f24; border-color: #2a3037; }
  }
  @media print {
    body { max-width: none; padding: 0; }
    pre { page-break-inside: avoid; }
    h2 { page-break-after: avoid; }
  }"""


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"`(.+?)`", r"<code>\1</code>", text)
    return text


def render(md):
    out = []
    lines = md.splitlines()
    i = 0
    in_list = False
    in_ol = False
    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            if in_list:
                out.append("</ul>")
                in_list = False
            if in_ol:
                out.append("</ol>")
                in_ol = False
            i += 1
            block = []
            while i < len(lines) and not lines[i].startswith("```"):
                block.append(lines[i])
                i += 1
            i += 1
            out.append("<pre><code>" + html.escape("\n".join(block)) + "</code></pre>")
            continue
        if line.startswith("- "):
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append("  <li>" + inline(line[2:]) + "</li>")
            i += 1
            continue
        if in_list and line.startswith("  ") and line.strip():
            # Wrapped continuation of the previous bullet: keep it inside the same <li>.
            out[-1] = out[-1][:-len("</li>")] + inline(line.strip()) + "</li>"
            i += 1
            continue
        if in_list:
            out.append("</ul>")
            in_list = False
        ordered = re.match(r"^(\d+)\. (.+)$", line)
        if ordered:
            if not in_ol:
                out.append("<ol>")
                in_ol = True
            out.append("  <li>" + inline(ordered.group(2)) + "</li>")
            i += 1
            continue
        if in_ol:
            out.append("</ol>")
            in_ol = False
        if line.startswith("### "):
            out.append("<h3>" + inline(line[4:]) + "</h3>")
        elif line.startswith("## "):
            out.append("<h2>" + inline(line[3:]) + "</h2>")
        elif line.startswith("# "):
            out.append("<h1>" + inline(line[2:]) + "</h1>")
        elif line.strip():
            out.append("<p>" + inline(line) + "</p>")
        i += 1
    if in_list:
        out.append("</ul>")
    if in_ol:
        out.append("</ol>")
    return "\n".join(out)


def main():
    md = SRC.read_text(encoding="utf-8")
    title = md.splitlines()[0].lstrip("# ").strip()
    body = render(md)
    DST.write_text(
        "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
        f"<title>{html.escape(title)}</title>\n<style>\n{CSS}\n</style>\n</head>\n<body>\n\n"
        f"{body}\n\n</body>\n</html>\n",
        encoding="utf-8",
    )
    print(f"wrote {DST}")


if __name__ == "__main__":
    main()
