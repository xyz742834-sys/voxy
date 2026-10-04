"""Read the bounded analytic fixture's PNGs without third-party dependencies."""
import struct
import zlib

WIDTH, HEIGHT = 256, 192


def read_rgb(path):
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
            if (w, h) != (WIDTH, HEIGHT) or depth != 8 or color not in (2, 6) or any((compression, filtering, interlace)):
                raise ValueError("Expected a non-interlaced 256x192 RGB/RGBA8 PNG")
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
    stride = WIDTH * channels
    decoder = zlib.decompressobj()
    raw = decoder.decompress(compressed, (stride + 1) * HEIGHT + 1)
    if len(raw) != (stride + 1) * HEIGHT or not decoder.eof or decoder.unused_data:
        raise ValueError("Invalid PNG scanline stream")
    pixels, previous = [], bytearray(stride)
    for y in range(HEIGHT):
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
