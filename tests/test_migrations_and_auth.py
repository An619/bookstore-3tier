"""Guard rails for versioned migrations, the dev identity provider and SLOs.

These are cheap structural checks: the real proof that migrations run is the
Compose smoke test in CI, where the databases start empty and the services
create their own schema with Flyway.
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

EXPECTED_TABLES = {
    'catalog-service': ['catalog_books'],
    'orders-service': ['customer_orders', 'checkout_attempts', 'checkout_outbox'],
    'inventory-service': ['inventory_items', 'inventory_operations'],
}


def migrations(service):
    return sorted((ROOT / 'services' / service / 'src/main/resources/db/migration').glob('*.sql'))


class MigrationTest(unittest.TestCase):
    def test_every_service_ships_ordered_migrations(self):
        for service in EXPECTED_TABLES:
            with self.subTest(service=service):
                files = migrations(service)
                self.assertGreaterEqual(len(files), 2, f'{service} needs a schema and a seed migration')
                versions = []
                for path in files:
                    match = re.fullmatch(r'V(\d+)__[a-z0-9_]+\.sql', path.name)
                    self.assertIsNotNone(match, f'{path.name} is not a Flyway versioned migration')
                    versions.append(int(match.group(1)))
                    self.assertTrue(path.read_text().strip(), f'{path.name} is empty')
                self.assertEqual(sorted(set(versions)), versions, f'{service} versions must be unique and ordered')
                self.assertEqual(list(range(1, len(versions) + 1)), versions,
                                 f'{service} versions must start at 1 without gaps')

    def test_migrations_create_the_tables_hibernate_validates(self):
        for service, tables in EXPECTED_TABLES.items():
            sql = '\n'.join(path.read_text() for path in migrations(service))
            for table in tables:
                with self.subTest(service=service, table=table):
                    self.assertIn(f'CREATE TABLE IF NOT EXISTS {table}', sql,
                                  f'{service} never creates {table}')

    def test_migrations_stay_rerunnable(self):
        # Flyway runs them once, but Helm still ships the old init scripts and a
        # lab database may exist already, so every statement must be idempotent.
        for service in EXPECTED_TABLES:
            sql = '\n'.join(path.read_text() for path in migrations(service))
            creates = re.findall(r'CREATE (?:TABLE|INDEX)(?! IF NOT EXISTS)', sql)
            self.assertEqual([], creates, f'{service} has a CREATE without IF NOT EXISTS')
            for statement in re.findall(r'INSERT INTO ([a-z_]+)', sql):
                self.assertIn('WHERE NOT EXISTS', sql,
                              f'{service} seeds {statement} without an idempotency guard')

    def test_services_validate_and_flyway_owns_the_schema(self):
        for service in EXPECTED_TABLES:
            with self.subTest(service=service):
                properties = (ROOT / 'services' / service / 'src/main/resources/application.properties').read_text()
                self.assertIn('spring.jpa.hibernate.ddl-auto=validate', properties)
                self.assertIn('spring.flyway.enabled=', properties)
                self.assertIn('spring.flyway.baseline-on-migrate=true', properties)

    def test_compose_lets_the_services_create_their_own_schema(self):
        compose = (ROOT / 'docker-compose.yml').read_text()
        for script in ('catalog-init.sql', 'orders-service-init.sql', 'inventory-service-init.sql'):
            self.assertNotIn(script, compose,
                             'the service databases must start empty so Flyway runs in CI')
        # The legacy API keeps its own init script; it has no migrations.
        self.assertIn('database/init.sql', compose)


class DevIdentityProviderTest(unittest.TestCase):
    def setUp(self):
        self.realm = json.loads((ROOT / 'deploy/local/auth/bookstore-realm.json').read_text())

    def test_realm_exposes_exactly_the_documented_scopes(self):
        names = {scope['name'] for scope in self.realm['clientScopes']}
        self.assertEqual({'orders.read', 'orders.write', 'inventory.read', 'inventory.write'}, names)

    def test_clients_are_configured_for_the_lab_flows(self):
        clients = {client['clientId']: client for client in self.realm['clients']}
        web = clients['bookstore-web']
        self.assertTrue(web['publicClient'])
        self.assertTrue(web['directAccessGrantsEnabled'], 'scripts/auth-token.sh uses the password grant')
        self.assertEqual({'orders.read', 'orders.write', 'inventory.read', 'inventory.write'},
                         set(web['defaultClientScopes']) & {'orders.read', 'orders.write',
                                                            'inventory.read', 'inventory.write'})
        self.assertTrue(clients['orders-service']['serviceAccountsEnabled'])

    def test_realm_has_a_demo_user_matching_the_token_script(self):
        users = {user['username']: user for user in self.realm['users']}
        self.assertIn('demo', users)
        self.assertFalse(users['demo']['credentials'][0]['temporary'])
        script = (ROOT / 'scripts/auth-token.sh').read_text()
        self.assertIn('demo-password', script)
        self.assertIn('KEYCLOAK_USER', script)

    def test_keycloak_runs_only_in_the_auth_profile(self):
        compose = (ROOT / 'docker-compose.yml').read_text()
        self.assertIn('profiles: [auth]', compose)
        self.assertIn('bookstore-realm.json', compose)
        self.assertIn('8090', compose)

    def test_makefile_exposes_the_auth_shortcuts(self):
        makefile = (ROOT / 'Makefile').read_text()
        self.assertIn('auth:', makefile)
        self.assertIn('auth-token:', makefile)


class ObservabilityConfigTest(unittest.TestCase):
    def test_dashboard_queries_the_checkout_gauges(self):
        dashboard = json.loads((ROOT / 'monitoring/bookstore-dashboard.json').read_text())
        expressions = ' '.join(
            target['expr']
            for panel in dashboard['panels']
            for target in panel.get('targets', [])
        )
        self.assertIn('checkout_outbox_pending', expressions)
        self.assertIn('checkout_attempts_needs_reconciliation', expressions)
        self.assertIn('bookstore:availability:ratio_5m', expressions)
        self.assertGreaterEqual(len(dashboard['panels']), 5)

    def test_slo_rules_reference_metrics_the_services_export(self):
        rules = (ROOT / 'monitoring/slo-rules.yaml').read_text()
        for metric in ('http_server_requests_seconds_count', 'http_server_requests_seconds_bucket',
                       'bookstore_checkout_confirmed_total', 'bookstore_checkout_cancelled_total'):
            self.assertIn(metric, rules)
        self.assertIn('bookstore:availability:ratio_1h', rules)
        self.assertIn('BookstoreAvailabilitySloBreach', rules)

    def test_checkout_counters_are_wired_in_the_services(self):
        metrics = (ROOT / 'services/orders-service/src/main/java/com/anas/bookstore/orders/CheckoutMetrics.java').read_text()
        self.assertIn('bookstore.checkout.confirmed', metrics)
        self.assertIn('bookstore.checkout.cancelled', metrics)
        worker = (ROOT / 'services/orders-service/src/main/java/com/anas/bookstore/orders/CheckoutWorker.java').read_text()
        reconciler = (ROOT / 'services/orders-service/src/main/java/com/anas/bookstore/orders/CheckoutReconciler.java').read_text()
        self.assertIn('metrics.checkoutConfirmed()', worker)
        self.assertIn('metrics.checkoutCancelled()', worker)
        self.assertIn('metrics.checkoutConfirmed()', reconciler)
        self.assertIn('metrics.checkoutCancelled()', reconciler)


if __name__ == '__main__':
    unittest.main()
