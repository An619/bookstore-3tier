const express = require('express');
const cors = require('cors');

function createApp(pool) {
    const app = express();
    const allowedOrigins = process.env.CORS_ORIGIN
        ? process.env.CORS_ORIGIN.split(',').map((origin) => origin.trim()).filter(Boolean)
        : [];

    if (allowedOrigins.length > 0) {
        app.use(cors({ origin: allowedOrigins }));
    }

    app.use(express.json({ limit: '1mb' }));

    // Liveness: confirms that the Node.js process can answer HTTP requests.
    app.get('/api/health', (req, res) => {
        res.status(200).json({
            status: 'OK',
            message: 'Bookstore backend is running'
        });
    });

    // Readiness: confirms that the service can reach PostgreSQL.
    app.get('/api/ready', async (req, res) => {
        try {
            await pool.query('SELECT 1');
            res.status(200).json({ status: 'READY' });
        } catch (error) {
            res.status(503).json({ status: 'NOT_READY', reason: 'Database unavailable' });
        }
    });

    app.get('/api/books', async (req, res) => {
        try {
            const result = await pool.query(
                'SELECT id, title, author, price FROM books ORDER BY id'
            );
            res.json(result.rows);
        } catch (error) {
            console.error('Database query failed:', error.message);
            res.status(500).json({ error: 'Unable to fetch books' });
        }
    });

    app.use((req, res) => {
        res.status(404).json({ error: 'Not found' });
    });

    app.use((error, req, res, next) => {
        if (res.headersSent) {
            return next(error);
        }
        console.error('Unhandled request error:', error.message);
        return res.status(500).json({ error: 'Internal server error' });
    });

    return app;
}

module.exports = { createApp };
