#!/usr/bin/env python3
"""Create reviewable, immutable-image Argo CD manifests (never push or deploy)."""
import argparse
import json
from pathlib import Path
import re

COMPONENTS = ('frontend', 'catalog-service', 'orders-service', 'inventory-service')


def release(registry, revision, branch):
    if not re.fullmatch(r'[0-9a-f]{12,40}', revision):
        raise ValueError('revision must be a 12–40 character lowercase Git SHA')
    if not re.fullmatch(r'[a-z0-9][a-z0-9.\-:/]*[a-z0-9]', registry):
        raise ValueError('registry must be a lowercase registry/namespace path, without a trailing slash')
    if not branch or any(c.isspace() for c in branch):
        raise ValueError('a nonempty GitOps branch without whitespace is required')
    apps = []
    for component in COMPONENTS:
        name = 'bookstore-frontend' if component == 'frontend' else component
        apps.append({
            'apiVersion': 'argoproj.io/v1alpha1', 'kind': 'Application',
            'metadata': {'name': 'bookstore-' + component, 'namespace': 'argocd'},
            'spec': {
                'project': 'default',
                'source': {
                    'repoURL': 'https://github.com/An619/bookstore-3tier.git',
                    'targetRevision': branch, 'path': 'helm/' + component,
                    'helm': {'releaseName': name, 'parameters': [
                        {'name': 'image.repository', 'value': registry + '/bookstore-' + component},
                        {'name': 'image.tag', 'value': revision},
                    ]},
                },
                'destination': {'server': 'https://kubernetes.default.svc', 'namespace': 'bookstore'},
                'syncPolicy': {'syncOptions': ['CreateNamespace=true']},
            },
        })
    return {'apiVersion': 'v1', 'kind': 'List', 'items': apps}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--registry', required=True, help='e.g. docker.io/your-account')
    parser.add_argument('--revision', required=True, help='published image commit SHA')
    parser.add_argument('--branch', required=True, help='branch containing charts and approved release')
    parser.add_argument('--output', default='deploy/releases/bookstore.json')
    args = parser.parse_args()
    try:
        manifest = release(args.registry, args.revision, args.branch)
    except ValueError as error:
        parser.error(str(error))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Wrote {output}; review and commit it before applying with kubectl.')


if __name__ == '__main__':
    main()
