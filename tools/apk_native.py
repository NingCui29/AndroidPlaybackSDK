"""Read native payloads and ZIP alignment in the APK or AAR actually delivered."""
import hashlib
import struct
import zipfile


def libs_and_alignment(payload, prefix):
    found = {}
    with zipfile.ZipFile(payload) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix) or not entry.filename.endswith(".so"):
                continue
            archive.fp.seek(entry.header_offset + 26)
            name_len, extra_len = struct.unpack("<HH", archive.fp.read(4))
            offset = entry.header_offset + 30 + name_len + extra_len
            name = entry.filename[len(prefix):]
            if name in found:
                raise ValueError(f"Duplicate native file: {entry.filename}")
            with archive.open(entry) as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            found[name] = (digest, entry.compress_type, offset)
    return found


def check_apk(payload, expected):
    libs = libs_and_alignment(payload, "lib/")
    if {k: v[0] for k, v in libs.items()} != expected:
        raise ValueError("Installed native inventory differs from the pinned AAR")
    for name, (_, compression, offset) in libs.items():
        if compression != zipfile.ZIP_STORED or offset % 16384:
            raise ValueError(f"{name}: compression={compression} offset={offset} (16 KB required)")
    return {k: {"sha256": v[0], "zipDataOffset": v[2]} for k, v in libs.items()}
