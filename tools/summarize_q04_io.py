"""Decode adb and Windows PowerShell evidence across native/text redirection versions."""


def text_file(path):
    raw = path.read_bytes()
    return raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
