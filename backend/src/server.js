const { Pool } = require('pg');
const { createApp } = require('./app');

const PORT = Number(process.env.PORT || 5000);
const pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    database: process.env.DB_NAME || 'bookstore',
    user: process.env.DB_USER || 'bookuser',
    password: process.env.DB_PASSWORD || 'bookpass',
    max: Number(process.env.DB_POOL_MAX || 10),
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 5000
});

const app = createApp(pool);
const server = app.listen(PORT, '0.0.0.0', () => {
    console.log(`Bookstore backend listening on port ${PORT}`);
});

let shuttingDown = false;

async function shutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`${signal} received; shutting down gracefully`);

    server.close(async (serverError) => {
        try {
            await pool.end();
        } catch (poolError) {
            console.error('Error closing database pool:', poolError.message);
            process.exitCode = 1;
        }

        if (serverError) {
            console.error('Error closing HTTP server:', serverError.message);
            process.exitCode = 1;
        }
    });
}

process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));
