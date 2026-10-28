#!/usr/bin/env python3
"""Generate and read local lab secrets without printing or committing them.

Commands:
  ensure --file FILE KEY [KEY ...]   create FILE (mode 0600) with random values
  get --file FILE KEY                print one value (for scripts)
  write --file FILE KEY PATH         write one value to PATH (mode 0600)
"""
import argparse
import os
import secrets
import sys
from pathlib import Path

WORDS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'


def read_values(path):
    values = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, _, value = line.partition('=')
        values[key.strip()] = value.strip()
    return values


def write_values(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.touch(mode=0o600, exist_ok=True)
    # Create/keep restrictive permissions before writing any secret.
    os.chmod(path, 0o600)
    temporary = path.with_name(path.name + '.partial')
    temporary.touch(mode=0o600, exist_ok=True)
    os.chmod(temporary, 0o600)
    temporary.write_text(''.join(f'{key}={value}\n' for key, value in sorted(values.items())))
    os.replace(temporary, path)
    os.chmod(path, 0o600)


def ensure(path, keys):
    values = read_values(path)
    if path.exists() and (path.stat().st_mode & 0o077):
        print(f'warning: tightening permissions on {path}', file=sys.stderr)
        os.chmod(path, 0o600)
    created = []
    for key in keys:
        if not values.get(key):
            values[key] = ''.join(secrets.choice(WORDS) for _ in range(32))
            created.append(key)
    if created:
        write_values(path, values)
        print('generated: ' + ', '.join(created), file=sys.stderr)
    return 0


def lookup(path, key):
    values = read_values(path)
    if key not in values:
        print(f'error: {key} is not present in {path}', file=sys.stderr)
        return 1
    print(values[key])
    return 0


def write_one(path, key, destination):
    values = read_values(path)
    if key not in values:
        print(f'error: {key} is not present in {path}', file=sys.stderr)
        return 1
    target = Path(destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.touch(mode=0o600, exist_ok=True)
    os.chmod(target, 0o600)
    target.write_text(values[key])
    os.chmod(target, 0o600)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('ensure', 'get', 'write'):
        command = commands.add_parser(name)
        command.add_argument('--file', required=True, type=Path)
        if name == 'ensure':
            command.add_argument('keys', nargs='+')
        elif name == 'get':
            command.add_argument('--key', required=True)
        else:
            command.add_argument('--key', required=True)
            command.add_argument('--path', required=True, type=Path)
    args = parser.parse_args(argv)
    if args.command == 'ensure':
        return ensure(args.file, args.keys)
    if args.command == 'get':
        return lookup(args.file, args.key)
    return write_one(args.file, args.key, args.path)


if __name__ == '__main__':
    sys.exit(main())
