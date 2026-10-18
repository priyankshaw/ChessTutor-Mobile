"""Disk-backed views of the exact desktop opening data; bounded phone memory."""
from collections.abc import Mapping, Set
from functools import lru_cache
import json
from pathlib import Path
import sqlite3
import threading


class BookStore:
    def __init__(self, path):
        self.connection = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro&immutable=1',
                                          uri=True, check_same_thread=False)
        self.connection.execute('PRAGMA cache_size=-2048')
        self.lock = threading.RLock()
        self.counts = dict(self.connection.execute('SELECT name, count FROM metadata'))

    @lru_cache(maxsize=2048)
    def lookup(self, table, key):
        with self.lock:
            row = self.connection.execute(f'SELECT value FROM {table} WHERE key=?', (key,)).fetchone()
        return None if row is None else row[0]

    def keys(self, table):
        with self.lock:
            for row in self.connection.execute(f'SELECT key FROM {table}'):
                yield row[0]


class BookMap(Mapping):
    def __init__(self, store, table):
        self.store, self.table = store, table

    def __getitem__(self, key):
        value = self.store.lookup(self.table, key)
        if value is None:
            raise KeyError(key)
        return json.loads(value)

    def __contains__(self, key):
        return isinstance(key, str) and self.store.lookup(self.table, key) is not None

    def __iter__(self):
        return self.store.keys(self.table)

    def __len__(self):
        return self.store.counts[self.table]


class BookSet(Set):
    def __init__(self, store, table):
        self.store, self.table = store, table

    def __contains__(self, key):
        return isinstance(key, str) and self.store.lookup(self.table, key) is not None

    def __iter__(self):
        return self.store.keys(self.table)

    def __len__(self):
        return self.store.counts[self.table]


def load_book(path):
    store = BookStore(path)
    return (BookMap(store, 'openings'), BookSet(store, 'prefix_list'),
            BookMap(store, 'fen_to_opening'), BookSet(store, 'book_fens'))
