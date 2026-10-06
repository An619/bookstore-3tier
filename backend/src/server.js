const express = require('express');
const cors = require('cors');
const { Pool } = require('pg');

const app = express();
const PORT = process.env.PORT || 5000;

app.use(cors());
app.use(express.json());

const pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    database: process.env.DB_NAME || 'bookstore',
    user: process.env.DB_USER || 'bookuser',
    password: process.env.DB_PASSWORD || 'bookpass'
});

app.get('/api/health', (req, res) => {
    res.status(200).json({
        status: 'OK',
        message: 'Bookstore backend is running'
    });
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

app.listen(PORT, '0.0.0.0', () => {
    console.log(`Bookstore backend listening on port ${PORT}`);
});
