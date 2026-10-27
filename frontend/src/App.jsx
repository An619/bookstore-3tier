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
      await requestJson('/api/checkout', {
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
      setNotice(`Demo order created for “${book.title}”. Inventory was reserved by the orders service.`);
      loadData().catch(() => setError('Order was created, but the page could not refresh. Reload to see the latest stock and orders.'));
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
