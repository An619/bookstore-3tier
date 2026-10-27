import { useCallback, useEffect, useState } from 'react';
import './App.css';

async function requestJson(url, options) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(body?.message || body?.error || `Request failed (${response.status})`);
  }
  return body;
}

const CHECKOUT_POLL_INTERVAL_MS = 1000;
const CHECKOUT_POLL_ATTEMPTS = 20;

function delay(ms) {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

// Checkout is asynchronous: the orders service accepts the request with 202 and
// confirms it from its outbox. Poll the order until the worker settles it.
async function awaitCheckoutResult(orderId) {
  for (let attempt = 0; attempt < CHECKOUT_POLL_ATTEMPTS; attempt += 1) {
    const order = await requestJson(`/api/orders/${orderId}`);
    if (order.status !== 'PENDING') return order;
    await delay(CHECKOUT_POLL_INTERVAL_MS);
  }
  return null;
}

const CHECKOUT_MESSAGES = {
  CONFIRMED: (title) => `Demo order confirmed for “${title}”. Inventory was reserved by the orders service.`,
  CANCELLED: () => 'Checkout was cancelled by the orders service — the stock could not be reserved. Refresh and try again.',
  NEEDS_RECONCILIATION: () => 'Checkout needs manual reconciliation; the inventory reservation could not be confirmed.',
};

function App() {
  const [books, setBooks] = useState([]);
  const [inventory, setInventory] = useState([]);
  const [orders, setOrders] = useState([]);
  const [busyBookId, setBusyBookId] = useState(null);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  const loadData = useCallback(async () => {
    const [bookData, inventoryData, orderData] = await Promise.all([
      requestJson('/api/catalog/books'),
      requestJson('/api/inventory'),
      requestJson('/api/orders'),
    ]);
    setBooks(bookData);
    setInventory(inventoryData);
    setOrders(orderData);
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([
      requestJson('/api/catalog/books'),
      requestJson('/api/inventory'),
      requestJson('/api/orders'),
    ]).then(([bookData, inventoryData, orderData]) => {
      if (!active) return;
      setBooks(bookData);
      setInventory(inventoryData);
      setOrders(orderData);
    }).catch((err) => {
      if (active) setError(err.message);
    });
    return () => { active = false; };
  }, []);

  async function placeDemoOrder(book) {
    setBusyBookId(book.id);
    setNotice('');
    setError('');
    try {
      const stock = inventory.find((item) => item.bookId === book.id);
      if (!stock || stock.availableQuantity < 1) {
        throw new Error('This book is currently out of stock.');
      }

      // The orders service owns reservation and compensation. A fresh idempotency
      // key per click makes an explicit retry safe without double-reserving.
      // 202 means the order is queued; the worker confirms it asynchronously.
      const accepted = await requestJson('/api/checkout', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': `demo-${crypto.randomUUID()}`,
        },
        body: JSON.stringify({
          customerName: 'Portfolio Demo Customer',
          bookId: book.id,
          quantity: 1,
        }),
      });
      const settled = await awaitCheckoutResult(accepted.id);
      if (settled) {
        const message = CHECKOUT_MESSAGES[settled.status];
        if (message) {
          if (settled.status === 'CONFIRMED') {
            setNotice(message(book.title));
          } else {
            setError(message());
          }
        } else {
          setNotice(`Order #${settled.id} finished with status ${settled.status}.`);
        }
      } else {
        setNotice(`Order #${accepted.id} is queued; the orders service is still confirming it. Refresh in a moment.`);
      }
      loadData().catch(() => setError('Order was submitted, but the page could not refresh. Reload to see the latest stock and orders.'));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyBookId(null);
    }
  }

  return (
    <div className="app">
      <header>
        <h1>📚 Bookstore</h1>
        <p>Microservices-enabled DevOps Application</p>
      </header>
      <main>
        <h2>Available Books</h2>
        <p>Catalog, Orders and Inventory are separate services with independent databases.</p>
        {error && <p className="error" role="alert">{error}</p>}
        {notice && <p role="status">{notice}</p>}
        <div className="books">
          {books.map((book) => {
            const stock = inventory.find((item) => item.bookId === book.id);
            return (
              <div className="book" key={book.id}>
                <h3>{book.title}</h3>
                <p>Author: {book.author}</p>
                <strong>₹{Number(book.price).toFixed(2)}</strong>
                <p>Available stock: {stock?.availableQuantity ?? 'Loading…'}</p>
                <button
                  type="button"
                  disabled={busyBookId !== null || !stock || stock.availableQuantity < 1}
                  onClick={() => placeDemoOrder(book)}
                >
                  {busyBookId === book.id ? 'Placing order…' : 'Place demo order'}
                </button>
              </div>
            );
          })}
        </div>
        <section aria-labelledby="orders-heading">
          <h2 id="orders-heading">Recent Orders</h2>
          {orders.length === 0 ? <p>No orders yet.</p> : (
            <ul>
              {orders.slice().reverse().slice(0, 5).map((order) => (
                <li key={order.id}>Order #{order.id}: Book #{order.bookId}, quantity {order.quantity} — {order.status}</li>
              ))}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
}

export default App;
