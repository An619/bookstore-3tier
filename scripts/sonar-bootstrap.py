#!/usr/bin/env python3
"""Prepare a local SonarQube: password, project, quality gate and Jenkins token.

Prints ONLY the token on stdout so the caller can store it; all progress and
errors go to stderr. Local lab use only.
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PROJECT_KEY = 'bookstore-3tier'
PROJECT_NAME = 'Bookstore microservices'
GATE_NAME = 'Bookstore lab gate'
# New-code coverage threshold enforced by the quality gate. Override with
# --coverage if your team's policy differs.
DEFAULT_COVERAGE = 60.0


def log(message):
    print(message, file=sys.stderr)


def call(base, path, params=None, method='POST', user='admin', password='admin'):
    data = None if params is None else urllib.parse.urlencode(params).encode()
    request = urllib.request.Request(base + path, data=data, method=method)
    token = base64.b64encode(f'{user}:{password}'.encode()).decode()
    request.add_header('Authorization', f'Basic {token}')
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read()
            return response.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        body = error.read()
        return error.code, (json.loads(body) if body else {})


def wait_until_up(base, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            status, payload = call(base, '/api/system/status', method='GET')
            if status == 200 and payload.get('status') == 'UP':
                return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(5)
    raise SystemExit('error: SonarQube did not become ready in time')


def change_admin_password(base, new_password):
    """Return the password to use next. SonarQube forces a change on first login."""
    status, _ = call(base, '/api/users/change_password', {
        'login': 'admin', 'previousPassword': 'admin', 'password': new_password,
    })
    if status in (200, 204):
        log('set the SonarQube admin password')
        return new_password
    # 401 means the password was already changed (for example on a rerun).
    status, _ = call(base, '/api/system/status', method='GET', password=new_password)
    if status == 200:
        log('reusing the existing SonarQube admin password')
        return new_password
    raise SystemExit('error: could not authenticate to SonarQube as admin')


def ensure_project(base, password):
    status, payload = call(base, '/api/projects/search', {
        'projects': PROJECT_KEY}, password=password, method='GET')
    if status == 200 and any(p['key'] == PROJECT_KEY for p in payload.get('components', [])):
        log(f'reusing SonarQube project {PROJECT_KEY}')
        return
    status, _ = call(base, '/api/projects/create', {
        'name': PROJECT_NAME, 'project': PROJECT_KEY}, password=password)
    if status not in (200, 204):
        raise SystemExit(f'error: could not create project (HTTP {status})')
    log(f'created SonarQube project {PROJECT_KEY}')


def ensure_gate(base, password, coverage):
    status, payload = call(base, '/api/qualitygates/list', method='GET', password=password)
    gate = next((g for g in payload.get('qualitygates', []) if g.get('name') == GATE_NAME), None)
    if gate:
        gate_id = gate['id']
        log(f'reusing quality gate {GATE_NAME}')
    else:
        status, payload = call(base, '/api/qualitygates/create', {'name': GATE_NAME}, password=password)
        if status not in (200, 204) or 'id' not in payload:
            raise SystemExit(f'error: could not create quality gate (HTTP {status})')
        gate_id = payload['id']
        log(f'created quality gate {GATE_NAME}')
    condition = {
        'gateId': gate_id, 'metric': 'new_coverage', 'op': 'LT', 'error': coverage,
    }
    status, payload = call(base, '/api/qualitygates/list', method='GET', password=password)
    gate = next(g for g in payload['qualitygates'] if g['id'] == gate_id)
    existing = {c['metric'] for c in gate.get('conditions', [])}
    if 'new_coverage' not in existing:
        status, _ = call(base, '/api/qualitygates/create_condition', condition, password=password)
        if status not in (200, 204):
            raise SystemExit(f'error: could not add coverage condition (HTTP {status})')
        log(f'set new-code coverage threshold to {coverage:.0f}%')
    call(base, '/api/qualitygates/select', {
        'gateId': gate_id, 'projectKey': PROJECT_KEY}, password=password)
    log(f'assigned {GATE_NAME} to {PROJECT_KEY}')


def issue_token(base, password):
    status, payload = call(base, '/api/user_tokens/generate', {
        'name': 'jenkins-lab'}, password=password)
    if status not in (200, 201) or 'token' not in payload:
        # An existing token with this name blocks re-creation: reuse is not
        # possible, so tell the operator exactly what to do.
        raise SystemExit('error: could not generate SonarQube token '
                         '(delete the existing "jenkins-lab" token and retry)')
    log('generated a SonarQube token for Jenkins')
    return payload['token']


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--url', default='http://localhost:9000')
    parser.add_argument('--admin-password', required=True)
    parser.add_argument('--coverage', type=float, default=DEFAULT_COVERAGE)
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args(argv)
    base = args.url.rstrip('/')
    wait_until_up(base, args.timeout)
    password = change_admin_password(base, args.admin_password)
    ensure_project(base, password)
    ensure_gate(base, password, args.coverage)
    token = issue_token(base, password)
    print(token)
    return 0


if __name__ == '__main__':
    sys.exit(main())
