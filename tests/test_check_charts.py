"""Guard script/CI contracts that do not need Helm locally."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class CheckChartsTest(unittest.TestCase):
    def test_render_and_lint_do_not_shadow_caller_variables(self):
        text = (ROOT / 'scripts/check-charts.sh').read_text()
        for function in ('lint()', 'render()'):
            body = text[text.index(function):]
            body = body[:body.index('\n}\n')]
            # A plain `chart=`, `name=` or `expected=` assignment would clobber
            # the caller's loop variable (this was a real bug).
            self.assertNotRegex(body, r'^\s{2}(chart|name|expected|forbidden|out)=', function)
            self.assertIn('_chart=', body)

    def test_chart_paths_are_used_verbatim(self):
        text = (ROOT / 'scripts/check-charts.sh').read_text()
        self.assertIn('for chart in catalog-service orders-service inventory-service; do', text)
        self.assertIn('render "$chart-default" "helm/$chart"', text)

    def test_ci_runs_the_chart_checker(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('sh scripts/check-charts.sh', workflow)


if __name__ == '__main__':
    unittest.main()
