const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { createApp } = require('../src/app');

async function withServer(pool, run) {
    const server = createApp(pool).listen(0, '127.0.0.1');
    await once(server, 'listening');
    const address = server.address();
    const baseUrl = `http://127.0.0.1:${address.port}`;

    try {
        await run(baseUrl);
    } finally {
        await new Promise((resolve, reject) => {
            server.close((error) => error ? reject(error) : resolve());
        });
    }
}

test('GET /api/health returns liveness status without querying the database', async () => {
    const pool = { query: async () => { throw new Error('should not query database'); } };

    await withServer(pool, async (baseUrl) => {
        const response = await fetch(`${baseUrl}/api/health`);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), {
            status: 'OK',
            message: 'Bookstore backend is running'
        });
    });
});

test('GET /api/ready returns ready when the database responds', async () => {
    const pool = { query: async () => ({ rows: [{ '?column?': 1 }] }) };

    await withServer(pool, async (baseUrl) => {
        const response = await fetch(`${baseUrl}/api/ready`);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), { status: 'READY' });
    });
});

test('GET /api/ready returns 503 when the database is unavailable', async () => {
    const pool = { query: async () => { throw new Error('database offline'); } };

    await withServer(pool, async (baseUrl) => {
        const response = await fetch(`${baseUrl}/api/ready`);
        assert.equal(response.status, 503);
        assert.deepEqual(await response.json(), {
            status: 'NOT_READY',
            reason: 'Database unavailable'
        });
    });
});

test('GET /api/books returns database rows', async () => {
    const books = [{ id: 1, title: 'DevOps for Beginners', author: 'John Doe', price: '499.00' }];
    const pool = { query: async (sql) => {
        assert.match(sql, /SELECT id, title, author, price FROM books ORDER BY id/);
        return { rows: books };
    } };

    await withServer(pool, async (baseUrl) => {
        const response = await fetch(`${baseUrl}/api/books`);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), books);
    });
});

test('GET /api/books returns a safe error when the database query fails', async () => {
    const pool = { query: async () => { throw new Error('sensitive database detail'); } };

    await withServer(pool, async (baseUrl) => {
        const response = await fetch(`${baseUrl}/api/books`);
        assert.equal(response.status, 500);
        assert.deepEqual(await response.json(), { error: 'Unable to fetch books' });
    });
});

test('unknown API paths return JSON 404', async () => {
    const pool = { query: async () => ({ rows: [] }) };

    await withServer(pool, async (baseUrl) => {
        const response = await fetch(`${baseUrl}/api/does-not-exist`);
        assert.equal(response.status, 404);
        assert.deepEqual(await response.json(), { error: 'Not found' });
    });
});
