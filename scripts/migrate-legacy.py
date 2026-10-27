#!/usr/bin/env python3
"""Copy legacy `books` rows into the Catalog service's database.

The legacy Node API only ever owned a `books` table, so that is all there is to
migrate. Orders and inventory in the microservices stack were created new.

The migration is idempotent: every row is inserted only when the same
title+author is absent, so it is safe to re-run. It never deletes or updates
existing catalog rows, and it runs in a single transaction.

  python3 scripts/migrate-legacy.py --dry-run     # print the SQL and row count
  python3 scripts/migrate-legacy.py --apply       # run it against Compose
"""
import argparse
import decimal
import subprocess
import sys

SEPARATOR = '\x1f'
TITLE_LIMIT = 160
AUTHOR_LIMIT = 120
CATEGORY_LIMIT = 80


class MigrationError(RuntimeError):
    pass


def docker_compose(container, database, user, sql, compose_file=None):
    """Runs SQL in a Compose database container and returns stdout."""
    command = ['docker', 'compose']
    if compose_file:
        command += ['-f', compose_file]
    command += ['exec', '-T', container, 'psql', '-v', 'ON_ERROR_STOP=1',
                '-U', user, '-d', database, '-tA', '-F', SEPARATOR, '-c', sql]
    try:
        result = subprocess.run(command, capture_output=True, text=True)
    except FileNotFoundError as error:
        raise MigrationError(f'docker is required: {error}') from error
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        raise MigrationError(detail[-1] if detail else 'psql failed')
    return result.stdout


def quote(value):
    """Quotes a string literal for PostgreSQL (doubling single quotes)."""
    return "'" + value.replace("'", "''") + "'"


def parse_books(raw):
    """Parses psql -tA output into validated (title, author, price) tuples."""
    books = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        fields = line.split(SEPARATOR)
        if len(fields) != 4:
            raise MigrationError(f'unexpected row with {len(fields)} columns: {line!r}')
        legacy_id, title, author, price = (field.strip() for field in fields)
        if not legacy_id.isdigit():
            raise MigrationError(f'legacy row with an invalid id: {legacy_id!r}')
        if not title or not author:
            raise MigrationError('legacy row with an empty title or author')
        if len(title) > TITLE_LIMIT or len(author) > AUTHOR_LIMIT:
            raise MigrationError(
                f'legacy title/author too long for the catalog schema: {title[:40]!r}')
        try:
            amount = decimal.Decimal(price)
        except decimal.InvalidOperation as error:
            raise MigrationError(f'legacy price is not a number: {price!r}') from error
        if amount < 0 or amount >= decimal.Decimal('1e8'):
            raise MigrationError(f'legacy price out of range: {price!r}')
        books.append((title, author, amount))
    return books


def insert_statement(title, author, price, category):
    """An idempotent insert: the same title+author is never duplicated."""
    if len(category) > CATEGORY_LIMIT:
        raise MigrationError(f'category too long: {category!r}')
    return (
        'INSERT INTO catalog_books (title, author, category, price)\n'
        f'SELECT {quote(title)}, {quote(author)}, {quote(category)}, {price}\n'
        f'WHERE NOT EXISTS (SELECT 1 FROM catalog_books '
        f'WHERE title = {quote(title)} AND author = {quote(author)});'
    )


def build_script(books, category):
    statements = [insert_statement(title, author, price, category)
                  for title, author, price in books]
    return 'BEGIN;\n' + '\n'.join(statements) + '\nCOMMIT;\n'


def count_books(container, database, user, compose_file=None):
    raw = docker_compose(container, database, user, 'SELECT count(*) FROM catalog_books;',
                         compose_file).strip()
    try:
        return int(raw)
    except ValueError as error:
        raise MigrationError(f'could not read the catalog row count: {raw!r}') from error


def migrate(args):
    if not args.dry_run and not args.apply:
        raise MigrationError('choose --dry-run to inspect or --apply to write')

    raw = docker_compose(args.source_container, args.source_db, args.source_user,
                         'SELECT id, title, author, price FROM books ORDER BY id;',
                         args.compose_file)
    books = parse_books(raw)
    print(f'Legacy books found: {len(books)}')
    if not books:
        print('Nothing to migrate.')
        return 0

    script = build_script(books, args.category)
    if args.dry_run:
        print(script)
        print('Dry run: nothing was written. Re-run with --apply to migrate.')
        return 0

    before = count_books(args.target_container, args.target_db, args.target_user, args.compose_file)
    docker_compose(args.target_container, args.target_db, args.target_user, script,
                   args.compose_file)
    after = count_books(args.target_container, args.target_db, args.target_user, args.compose_file)
    inserted = after - before
    print(f'Catalog rows before: {before}, after: {after}, inserted: {inserted}')
    if inserted < 0:
        raise MigrationError('the catalog lost rows during migration; investigate before retrying')
    if inserted < len(books):
        print('Some legacy books were already in the catalog (matched by title+author).')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--dry-run', action='store_true', help='print the SQL without writing')
    mode.add_argument('--apply', action='store_true', help='run the migration')
    parser.add_argument('--source-container', default='database')
    parser.add_argument('--source-db', default='bookstore')
    parser.add_argument('--source-user', default='bookuser')
    parser.add_argument('--target-container', default='catalog-database')
    parser.add_argument('--target-db', default='catalogdb')
    parser.add_argument('--target-user', default='cataloguser')
    parser.add_argument('--category', default='General',
                        help='category to assign, because the legacy schema has none')
    parser.add_argument('--compose-file')
    args = parser.parse_args(argv)
    try:
        return migrate(args)
    except MigrationError as error:
        print(f'error: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
