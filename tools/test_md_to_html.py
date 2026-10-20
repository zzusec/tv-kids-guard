#!/usr/bin/env python3
"""Regression tests for the standalone README renderer."""

from html.parser import HTMLParser
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("md_to_html", Path(__file__).with_name("md_to_html.py"))
renderer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(renderer)


class Elements(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.tags = []
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        self.tags.append((tag, dict(attrs)))


class MarkdownTests(unittest.TestCase):
    def test_local_links_and_images(self):
        source = '[Guide](docs/guide.md) ![Panel "preview"](docs/images/panel.png)'
        elements = Elements(renderer.inline(source)).tags
        self.assertIn(("a", {"href": "docs/guide.md"}), elements)
        self.assertIn(("img", {"src": "docs/images/panel.png", "alt": 'Panel "preview"'}), elements)

    def test_https_links_escape_attributes_and_support_formatted_labels(self):
        output = renderer.inline('[**Download**](https://example.com/?a=1&b="test")')
        self.assertIn('<strong>Download</strong>', output)
        self.assertIn('&amp;b=&quot;test&quot;', output)
        self.assertEqual(Elements(output).tags[0], ("a", {"href": 'https://example.com/?a=1&b="test"'}))

    def test_unsafe_urls_and_raw_html_are_not_active(self):
        for url in ('javascript:alert', 'data:text/html,unsafe', '//example.com/a', r'\\example.com/a'):
            for marker in ("", "!"):
                with self.subTest(url=url, marker=marker):
                    output = renderer.inline(f'{marker}[label]({url})')
                    self.assertFalse(Elements(output).tags)
        self.assertEqual(renderer.inline('<script>alert(1)</script>'), '&lt;script&gt;alert(1)&lt;/script&gt;')

    def test_code_spans_and_blocks_preserve_markdown_literals(self):
        literal = '**bold** [link](https://example.com) <tag>'
        self.assertEqual(renderer.inline(f'`{literal}`'), f'<code>**bold** [link](https://example.com) &lt;tag&gt;</code>')
        output = renderer.render(f'```bash\n{literal}\n```')
        self.assertEqual(output, f'<pre><code>**bold** [link](https://example.com) &lt;tag&gt;</code></pre>')

    def test_table_headers_cells_and_following_paragraph(self):
        output = renderer.render(
            '| Code | Action |\n| --- | --- |\n| `00` | **Close** |\n| `22` | [Guide](guide.md) |\n\nAfter table.'
        )
        elements = Elements(output).tags
        self.assertEqual(sum(tag == 'table' for tag, _ in elements), 1)
        self.assertEqual(sum(tag == 'tr' for tag, _ in elements), 3)
        self.assertEqual(sum(tag == 'th' and attrs.get('scope') == 'col' for tag, attrs in elements), 2)
        self.assertEqual(sum(tag == 'td' for tag, _ in elements), 4)
        self.assertIn('<code>22</code>', output)
        self.assertIn('<strong>Close</strong>', output)
        self.assertTrue(output.endswith('</table></div>\n<p>After table.</p>'))
        self.assertNotIn('<table', renderer.render('a | b\nnot a table'))

    def test_standalone_generation_and_local_readme_assets(self):
        readme = renderer.SRC.read_text(encoding='utf-8')
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'README.html'
            with patch.object(renderer, 'DST', target), patch('sys.stdout', new_callable=io.StringIO):
                renderer.main()
                first = target.read_text(encoding='utf-8')
                renderer.main()
                self.assertEqual(first, target.read_text(encoding='utf-8'))
        self.assertEqual(first, renderer.DST.read_text(encoding='utf-8'), 'Regenerate README.html after editing README.md')
        self.assertIn('<html lang="zh-CN">', first)
        self.assertIn('@media print', first)
        self.assertIn(renderer.render(readme), first)
        elements = Elements(first).tags
        self.assertTrue(any(tag == 'table' for tag, _ in elements))
        self.assertTrue(any(tag == 'img' for tag, _ in elements))
        for tag, attrs in elements:
            url = attrs.get('src' if tag == 'img' else 'href', '')
            if url and not url.startswith(('https://', '#')):
                with self.subTest(url=url):
                    self.assertTrue((renderer.ROOT / url).is_file(), f'Missing local asset: {url}')


if __name__ == '__main__':
    unittest.main()
