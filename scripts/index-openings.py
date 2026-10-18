"""Convert opening dictionaries/sets into equivalent read-only SQLite tables."""
import json
from pathlib import Path
import sqlite3


def index_openings(source, destination):
    data = json.loads(Path(source).read_text(encoding='utf-8'))
    temporary = Path(destination).with_suffix('.tmp')
    if temporary.exists():
        temporary.unlink()
    with sqlite3.connect(temporary) as database:
        database.execute('PRAGMA journal_mode=OFF')
        database.execute('PRAGMA synchronous=OFF')
        database.execute('CREATE TABLE metadata(name TEXT PRIMARY KEY, count INTEGER) WITHOUT ROWID')
        for name in ('openings', 'prefix_list', 'fen_to_opening', 'book_fens'):
            value = data[name]
            database.execute(f'CREATE TABLE {name}(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID')
            entries = ((key, json.dumps(item, ensure_ascii=False, separators=(',', ':'))) for key, item in value.items()) if isinstance(value, dict) else ((key, '1') for key in set(value))
            database.executemany(f'INSERT INTO {name} VALUES(?,?)', entries)
            count = database.execute(f'SELECT COUNT(*) FROM {name}').fetchone()[0]
            database.execute('INSERT INTO metadata VALUES(?,?)', (name, count))
    database.close()
    temporary.replace(destination)
    return {key: len(value) for key, value in data.items()}
