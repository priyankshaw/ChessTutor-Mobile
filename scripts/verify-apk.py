"""Reject accidental web/React payloads and missing offline runtime assets."""
import io
from pathlib import Path
import sys
import zipfile
import struct

apk = Path(sys.argv[1] if len(sys.argv) > 1 else 'artifacts/native-release/ChessTutor-native-arm64.apk')
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    required = {'lib/arm64-v8a/libstockfish.so', 'assets/licenses/Stockfish-GPL-3.0.txt',
                'assets/licenses/python-chess-GPL-3.0.txt', 'assets/licenses/Chesslib-Apache-2.0.txt'}
    assert required <= names, required - names
    assert archive.read('lib/arm64-v8a/libstockfish.so')[:4] == b'\x7fELF'
    for name in names:
        if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
            elf = archive.read(name)
            offset = struct.unpack_from('<Q', elf, 32)[0]
            size, count = struct.unpack_from('<HH', elf, 54)
            alignments = [struct.unpack_from('<Q', elf, offset + i * size + 48)[0] for i in range(count)
                          if struct.unpack_from('<I', elf, offset + i * size)[0] == 1]
            assert alignments and min(alignments) >= 16384, (name, alignments)
            segments = [struct.unpack_from('<IIQQQQQQ', elf, offset + i * size) for i in range(count)]
            for fields in segments:
                if fields[0] == 0x6474e552:
                    end = fields[3] + fields[6]
                    if name.endswith('/libstockfish.so'):
                        assert end % 16384 == 0, (name, 'unaligned RELRO')
                    # Upstream runtimes may pad the next writable segment instead.
                    # Rounded RELRO protection must never cover writable data.
                    protected_end = (end + 16383) // 16384 * 16384
                    for segment in segments:
                        if segment[0] == 1 and segment[1] & 2 and segment[3] + segment[6] > end:
                            assert max(segment[3], end) >= protected_end, (name, 'RELRO overlaps writable data')
    assert not any(name.startswith(('lib/x86', 'lib/armeabi')) for name in names)
    assert not any(name.endswith(('.js', '.html', '.wasm', '.bundle')) for name in names)
    dex = b''.join(archive.read(name) for name in names if name.endswith('.dex'))
    assert b'Lexpo/' not in dex
    assert b'Lcom/facebook/react/' not in dex
    modules = set()
    for name in names:
        if name.startswith('assets/chaquopy/') and name.endswith('.imy'):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as packed:
                modules.update(packed.namelist())
    for prefix in ('mobile_runtime.', 'mobile_book.', 'backend/mobile_endpoints.', 'backend/engine/purpose.', 'chess/__init__.'):
        assert any(name.startswith(prefix) for name in modules), prefix
    assert 'backend/data/openings.sqlite' in modules
    assert not any(name.endswith('openings_compiled.json') or name.startswith('fastapi/') for name in modules)
print(f'Native APK verified: Kotlin UI, local Python/Stockfish, complete book, notices; no React/Expo/web payload. {apk.stat().st_size / 1024 / 1024:.1f} MB.')
