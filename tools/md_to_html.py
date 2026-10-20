#!/usr/bin/env python3
"""Render README.md as a standalone README.html (same directory, print friendly)."""
import html
import re
import pathlib
from urllib.parse import urlsplit

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
  a { color: #2057ad; overflow-wrap: anywhere; }
  img { display: block; max-width: 100%; height: auto; border-radius: 8px; }
  .table-scroll { overflow-x: auto; margin: 1.2rem 0; }
  table { width: 100%; border-collapse: collapse; text-align: left; font-size: .92em; }
  th, td { min-width: 5rem; padding: .65rem .8rem; border: 1px solid #e5e8eb; vertical-align: top; }
  th { background: #f6f8fa; }
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
    a { color: #9bbaff; }
    th, td { border-color: #2a3037; }
    th { background: #1b1f24; }
    h2 { border-color: #2a3037; }
    code { background: #23282e; color: #e6e9ec; }
    pre { background: #1b1f24; border-color: #2a3037; }
  }
  @media print {
    :root { color-scheme: light; }
    body { max-width: none; padding: 0; color: #000; background: #fff; }
    p, li, strong, a { color: #000; }
    code, pre, th { color: #000; background: #f6f8fa; }
    h2, th, td, pre { border-color: #ccc; }
    .table-scroll { overflow: visible; }
    th, td { min-width: 0; }
    pre { white-space: pre-wrap; overflow-wrap: anywhere; }
    pre, img, tr { page-break-inside: avoid; }
    h2, h3 { page-break-after: avoid; }
  }"""

INLINE = re.compile(r"`([^`\n]+)`|\*\*(.+?)\*\*|(!?)\[([^\]\n]+)\]\(([^)\s]+)\)")


def safe_url(url):
    if "\\" in url or any(ord(char) < 32 for char in url):
        return False
    try:
        parsed = urlsplit(url)
    except ValueError:
        return False
    return (parsed.scheme == "https" and bool(parsed.netloc)) or (
        not parsed.scheme and not url.startswith("/")
    )


def inline(text):
    out = []
    position = 0
    for match in INLINE.finditer(text):
        out.append(html.escape(text[position:match.start()], quote=False))
        code, bold, image, label, url = match.groups()
        if code is not None:
            out.append("<code>" + html.escape(code, quote=False) + "</code>")
        elif bold is not None:
            out.append("<strong>" + inline(bold) + "</strong>")
        elif not safe_url(url):
            out.append(html.escape(match.group(), quote=False))
        elif image:
            out.append(f'<img src="{html.escape(url)}" alt="{html.escape(label)}">')
        else:
            out.append(f'<a href="{html.escape(url)}">{inline(label)}</a>')
        position = match.end()
    out.append(html.escape(text[position:], quote=False))
    return "".join(out)


def table_cells(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


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
        if "|" in line and i + 1 < len(lines):
            headers = table_cells(line)
            separators = table_cells(lines[i + 1])
            if len(headers) == len(separators) and all(re.fullmatch(r":?-{3,}:?", cell) for cell in separators):
                out.append('<div class="table-scroll" tabindex="0"><table><thead><tr>')
                out.extend('<th scope="col">' + inline(cell) + '</th>' for cell in headers)
                out.append('</tr></thead><tbody>')
                i += 2
                while i < len(lines) and "|" in lines[i]:
                    cells = table_cells(lines[i])
                    if len(cells) != len(headers):
                        break
                    out.append('<tr>' + ''.join('<td>' + inline(cell) + '</td>' for cell in cells) + '</tr>')
                    i += 1
                out.append('</tbody></table></div>')
                continue
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
