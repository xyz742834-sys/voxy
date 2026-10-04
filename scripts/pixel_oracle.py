"""Read the bounded analytic fixture's PNGs without third-party dependencies."""
import struct
import zlib

WIDTH, HEIGHT = 256, 192


def read_rgb(path, expected_size=(WIDTH, HEIGHT), validate_only=False):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid PNG signature")
    offset, compressed, header, ended = 8, bytearray(), None, False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("Truncated PNG chunk")
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + size]
        if offset + size + 12 > len(data):
            raise ValueError("Truncated PNG payload")
        checksum = struct.unpack_from(">I", data, offset + size + 8)[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != checksum:
            raise ValueError("PNG checksum mismatch")
        if kind == b"IHDR":
            if header is not None or offset != 8 or size != 13:
                raise ValueError("Invalid PNG header")
            header = struct.unpack(">IIBBBBB", payload)
            w, h, depth, color, compression, filtering, interlace = header
            if (w, h) != expected_size or not 0 < w * h <= 32_000_000 or depth != 8 or color not in (2, 6) or any((compression, filtering, interlace)):
                raise ValueError(f"Expected a non-interlaced {expected_size} RGB/RGBA8 PNG")
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            if size != 0 or offset + 12 != len(data):
                raise ValueError("Invalid PNG end")
            ended = True
            break
        offset += size + 12
    if header is None or not ended or not compressed:
        raise ValueError("Incomplete PNG")
    channels = 4 if header[3] == 6 else 3
    width, height = header[:2]
    stride = width * channels
    decoder = zlib.decompressobj()
    raw = decoder.decompress(compressed, (stride + 1) * height + 1)
    if len(raw) != (stride + 1) * height or not decoder.eof or decoder.unused_data:
        raise ValueError("Invalid PNG scanline stream")
    if validate_only:
        if any(raw[y * (stride + 1)] > 4 for y in range(height)):
            raise ValueError("Invalid PNG filter")
        return True
    pixels, previous = [], bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind, row = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        if kind > 4:
            raise ValueError("Invalid PNG filter")
        for i in range(stride):
            a = row[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if kind == 1:
                prediction = a
            elif kind == 2:
                prediction = b
            elif kind == 3:
                prediction = (a + b) // 2
            elif kind == 4:
                p = a + b - c
                distances = (abs(p - a), abs(p - b), abs(p - c))
                prediction = (a, b, c)[distances.index(min(distances))]
            else:
                prediction = 0
            row[i] = (row[i] + prediction) & 255
        pixels.extend(tuple(row[i:i + 3]) for i in range(0, stride, channels))
        previous = row
    return pixels


def expected_rgb():
    # PNG rows run from top to bottom. This literal mask is independent of Java's
    # matrix/projection helpers and of both the GPU image and its metrics JSON.
    return [(192, 0, 55) if 64 <= x < 128 and 48 <= y < 96 else (0, 0, 0)
            for y in range(HEIGHT) for x in range(WIDTH)]


def mismatches(pixels):
    if len(pixels) != WIDTH * HEIGHT:
        raise ValueError("Wrong pixel count")
    return sum(a != b for a, b in zip(pixels, expected_rgb()))


def top_rows_rgb(path, rows):
    """Decode only the first `rows` scanlines of any PNG, as a list of (r, g, b) rows.

    The full decoder above reconstructs every scanline in Python, which is far too slow
    for a 1920x1080 screenshot. A PNG's filters only ever reference the previous row, so
    the top of an image can be reconstructed without touching the rest: feed the zlib
    stream incrementally and stop once enough scanlines are out. Used to look for the
    bounded native marker, which is drawn in the top-left corner.
    """
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid PNG signature")
    offset, header, compressed, ended = 8, None, bytearray(), False
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("Truncated PNG chunk")
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + size]
        if offset + size + 12 > len(data):
            raise ValueError("Truncated PNG payload")
        if kind == b"IHDR":
            if header is not None or size != 13:
                raise ValueError("Invalid PNG header")
            header = struct.unpack(">IIBBBBB", payload)
            if header[2] != 8 or header[3] not in (2, 6) or any(header[4:]):
                raise ValueError("Expected a non-interlaced RGB/RGBA8 PNG")
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            ended = True
            break
        offset += size + 12
    if header is None or not ended or not compressed:
        raise ValueError("Incomplete PNG")
    width, height, _, colour = header[0], header[1], header[2], header[3]
    channels = 4 if colour == 6 else 3
    stride = width * channels
    wanted = max(0, min(rows, height))
    raw = zlib.decompressobj().decompress(bytes(compressed), (stride + 1) * wanted)
    if len(raw) < (stride + 1) * wanted:
        raise ValueError("Could not decode the requested scanlines")
    out, previous = [], bytearray(stride)
    for y in range(wanted):
        start = y * (stride + 1)
        kind, row = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        if kind > 4:
            raise ValueError("Invalid PNG filter")
        for i in range(stride):
            a = row[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if kind == 1:
                prediction = a
            elif kind == 2:
                prediction = b
            elif kind == 3:
                prediction = (a + b) // 2
            elif kind == 4:
                p = a + b - c
                distances = (abs(p - a), abs(p - b), abs(p - c))
                prediction = (a, b, c)[distances.index(min(distances))]
            else:
                prediction = 0
            row[i] = (row[i] + prediction) & 255
        out.append([tuple(row[i:i + 3]) for i in range(0, stride, channels)])
        previous = row
    return out, (width, height)


def png_size(path):
    """(width, height) from the IHDR alone, without decoding any pixels."""
    head = path.read_bytes()[:24]
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        raise ValueError("Invalid PNG header")
    return struct.unpack_from(">II", head, 16)
