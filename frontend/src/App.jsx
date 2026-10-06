import { useEffect, useState } from 'react';
import './App.css';

function App() {
  const [books, setBooks] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    fetch('/api/books')
      .then((response) => {
        if (!response.ok) {
          throw new Error('Failed to fetch books');
        }
        return response.json();
      })
      .then((data) => setBooks(data))
      .catch((err) => setError(err.message));
  }, []);

  return (
    <div className="app">
      <header>
        <h1>📚 Bookstore</h1>
        <p>3-Tier DevOps Application</p>
      </header>

      <main>
        <h2>Available Books</h2>

        {error && <p className="error">{error}</p>}

        <div className="books">
          {books.map((book) => (
            <div className="book" key={book.id}>
              <h3>{book.title}</h3>
              <p>Author: {book.author}</p>
              <strong>₹{book.price}</strong>
            </div>
          ))}
        </div>
      </main>
    </div>
  );
}

export default App;
