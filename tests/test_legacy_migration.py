"""Exercise the legacy -> catalog migration with a fake Docker CLI."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

FAKE_DOCKER = '''#!/bin/sh
printf '%s\\n' "$*" >> "$DOCKER_TEST_LOG"
case "$*" in
  *"SELECT count(*) FROM catalog_books"*)
    if grep -q 'INSERT INTO catalog_books' "$DOCKER_TEST_LOG"; then
      printf '%s\\n' "${CATALOG_ROWS:-5}"
    else
      printf '%s\\n' "${CATALOG_ROWS_BEFORE:-3}"
    fi ;;
  *"FROM books ORDER BY id"*) printf '%s' "$BOOKS_OUTPUT" ;;
  *"psql"*) exit "${PSQL_STATUS:-0}" ;;
esac
exit 0
'''

BOOKS = ('\n'.join([
    '1\x1fDevOps for Beginners\x1fJohn Doe\x1f499.00',
    '2\x1fIt\'s Complicated\x1fO\'Brien\x1f199.50',
    '',
]))


class LegacyMigrationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        docker = root / 'docker'
        docker.write_text(FAKE_DOCKER)
        docker.chmod(0o700)
        self.log = root / 'docker.log'
        self.env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                        DOCKER_TEST_LOG=str(self.log), BOOKS_OUTPUT=BOOKS)

    def run_migration(self, *args, **env):
        return subprocess.run(
            ['python3', str(ROOT / 'scripts' / 'migrate-legacy.py'), *args],
            env=dict(self.env, **env), capture_output=True, text=True)

    def calls(self):
        return self.log.read_text().splitlines() if self.log.exists() else []

    def log_text(self):
        # psql arguments contain newlines, so multi-statement SQL spans lines.
        return self.log.read_text() if self.log.exists() else ''

    def test_requires_an_explicit_mode(self):
        result = self.run_migration()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('--dry-run', result.stderr + result.stdout)

    def test_dry_run_prints_sql_and_writes_nothing(self):
        result = self.run_migration('--dry-run')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Legacy books found: 2', result.stdout)
        self.assertIn('INSERT INTO catalog_books', result.stdout)
        self.assertIn('BEGIN;', result.stdout)
        self.assertIn('COMMIT;', result.stdout)
        # Only the read query ran; no INSERT and no count reached the database.
        self.assertEqual(1, len(self.calls()), self.calls())
        self.assertIn('FROM books ORDER BY id', self.calls()[0])

    def test_apply_is_idempotent_and_transactional(self):
        result = self.run_migration('--apply')
        self.assertEqual(0, result.returncode, result.stderr)
        log = self.log_text()
        self.assertEqual(1, log.count('BEGIN;'), 'one transaction for the whole migration')
        self.assertEqual(1, log.count('COMMIT;'))
        self.assertIn('ON_ERROR_STOP=1', log)
        self.assertIn('WHERE NOT EXISTS (SELECT 1 FROM catalog_books', log)
        self.assertIn("'It''s Complicated'", log)
        self.assertIn("'O''Brien'", log)
        # Three round trips: read legacy rows, count before, migrate, count after.
        self.assertEqual(4, log.count('psql'))
        self.assertIn('inserted: 2', result.stdout)

    def test_already_migrated_rows_are_reported_not_duplicated(self):
        result = self.run_migration('--apply', CATALOG_ROWS='3')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('inserted: 0', result.stdout)
        self.assertIn('already in the catalog', result.stdout)

    def test_failure_stops_with_a_readable_error(self):
        result = self.run_migration('--apply', PSQL_STATUS='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('error:', result.stderr)
        self.assertNotIn('Traceback', result.stderr)

    def test_oversized_legacy_title_is_rejected(self):
        long_title = 'x' * 200
        result = self.run_migration('--dry-run', BOOKS_OUTPUT=f'1\x1f{long_title}\x1fA\x1f10.00\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('too long', result.stderr)
        self.assertEqual(1, len(self.calls()))

    def test_malformed_row_is_rejected(self):
        result = self.run_migration('--dry-run', BOOKS_OUTPUT='1\x1fonly-two\x1f10.00\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('unexpected row', result.stderr)

    def test_non_numeric_price_is_rejected(self):
        result = self.run_migration('--dry-run', BOOKS_OUTPUT='1\x1fTitle\x1fAuthor\x1fcheap\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('not a number', result.stderr)


class LegacyMigrationUnitTest(unittest.TestCase):
    """Pure functions, imported directly."""

    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            'migrate_legacy', ROOT / 'scripts' / 'migrate-legacy.py')
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_quotes_and_normalises_prices(self):
        parsed = self.module.parse_books('7\x1fTitle\x1fAuthor\x1f499.00\n')
        self.assertEqual([('Title', 'Author', self.module.decimal.Decimal('499.00'))], parsed)
        self.assertNotIn('7', parsed[0][0], 'the legacy id must not leak into the title')
        statement = self.module.insert_statement('Title', "O'Brien", self.module.decimal.Decimal('499.00'), 'General')
        self.assertIn("'O''Brien'", statement)
        self.assertIn('General', statement)
        self.assertTrue(statement.rstrip().endswith(';'))

    def test_category_is_required_to_fit_the_schema(self):
        with self.assertRaises(self.module.MigrationError):
            self.module.insert_statement('Title', 'Author', self.module.decimal.Decimal('1'), 'c' * 81)

    def test_negative_and_huge_prices_are_rejected(self):
        for price in ('-1.00', '999999999999.00'):
            with self.assertRaises(self.module.MigrationError):
                self.module.parse_books(f'1\x1fTitle\x1fAuthor\x1f{price}\n')


if __name__ == '__main__':
    unittest.main()
