"""Read candidate Maven files over GET, compare the approved hashes, and archive the received bytes."""
import argparse
import base64
import contextlib
import datetime
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def safe_url(value):
    p = urllib.parse.urlsplit(value)
    if (p.scheme not in {"http", "https"} or not p.hostname or p.username is not None or
            p.password is not None or p.query or p.fragment or
            (p.scheme == "http" and p.hostname not in {"127.0.0.1", "localhost", "::1"})):
        raise ValueError("Use a repository HTTPS URL without credentials/query, or a loopback HTTP URL")
    return p


@contextlib.contextmanager
def rehearsal(archive, scratch, fault):
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(scratch)
    prefix, = Path(scratch).rglob("android-playback-sdk/*/*.pom")
    repo = prefix.parents[5]
    if fault == "missing":
        prefix.with_suffix(".aar").unlink()
    elif fault == "mismatch":
        with prefix.open("ab") as stream:
            stream.write(b"\n<!-- synthetic readback mismatch -->\n")

    class Files(http.server.SimpleHTTPRequestHandler):
        pending = fault == "delayed"

        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(repo), **kwargs)

        def do_GET(self):
            if Files.pending and self.path.endswith(".aar"):
                Files.pending = False
                self.send_error(404)
                return
            super().do_GET()

        def log_message(self, *args):
            pass

    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Files) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}"
        finally:
            server.shutdown()
            thread.join()


def read_candidate(url, baseline, out, role, visibility):
    endpoint = safe_url(url)

    class RepositoryRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            target = urllib.parse.urlsplit(newurl)
            same_origin = target[:2] == urllib.parse.urlsplit(req.full_url)[:2]
            if target.username or target.password or (not same_origin and target.scheme != "https"):
                raise ValueError("Unexpected insecure repository redirect")
            follow = super().redirect_request(req, fp, code, msg, headers, newurl)
            if follow is not None and not same_origin:
                follow.remove_header("Authorization")
            return follow

    client = urllib.request.build_opener(RepositoryRedirect())
    headers = {"Cache-Control": "no-cache"}
    user, secret = os.getenv("PLAYBACK_MAVEN_USER"), os.getenv("PLAYBACK_MAVEN_PASSWORD")
    if role != "loopback" and (user or secret):
        if not user or not secret or endpoint.scheme != "https":
            raise ValueError("Basic read-only identity requires both environment variables and HTTPS")
        headers["Authorization"] = "Basic " + base64.b64encode(f"{user}:{secret}".encode()).decode()
    group, artifact, version = baseline["coordinate"].split(":")
    if any(not re.fullmatch(r"[A-Za-z0-9_-]+", part) for part in group.split(".")) or any(
            not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*", part) for part in (artifact, version)):
        raise ValueError("Invalid Maven coordinate")
    leaf = artifact + "-" + version
    required = {leaf + suffix for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")}
    if (not required.issubset(baseline["maven"]) or baseline["maven"][leaf + ".aar"] != baseline["aar"]["sha256"] or
            any(not re.fullmatch(r"[a-f0-9]{64}", value) for value in baseline["maven"].values())):
        raise ValueError("Candidate lacks the required artifacts or a matching AAR SHA-256")
    relative = Path(*group.split(".")) / artifact / version
    path = out / "readback" / relative
    path.mkdir(parents=True)
    review = {"channel": role, "coordinate": baseline["coordinate"], "repository": url,
              "approvalRecordedInCandidate": baseline.get("releaseApproval"),
              "publishedByThisTool": False, "expectedSha256": baseline["maven"],
              "startedUTC": datetime.datetime.now(datetime.timezone.utc).isoformat(), "polls": []}
    started = time.monotonic()
    scheduled = 0
    while True:
        current = {"elapsedSeconds": round(time.monotonic() - started, 3), "files": {}}
        fatal = False
        for name, expected in baseline["maven"].items():
            if Path(name).name != name or not name.startswith(artifact + "-" + version):
                raise ValueError("Unexpected Maven filename in candidate")
            request = urllib.request.Request(url.rstrip("/") + "/" + relative.as_posix() + "/" + name, headers=headers)
            item = current["files"][name] = {}
            try:
                with client.open(request, timeout=20) as reply, (path / (name + ".partial")).open("wb") as store:
                    item["httpStatus"] = reply.status
                    shutil.copyfileobj(reply, store)
                partial = path / (name + ".partial")
                with partial.open("rb") as stream:
                    actual = hashlib.file_digest(stream, "sha256").hexdigest()
                item.update(sha256=actual, bytes=partial.stat().st_size, matched=actual == expected)
                if item["matched"]:
                    partial.replace(path / name)
                else:
                    fatal = True
                    break
            except urllib.error.HTTPError as error:
                item["httpStatus"] = error.code
                if error.code not in {404, 408, 429, 500, 502, 503, 504}:
                    fatal = True
                    break
            except (urllib.error.URLError, TimeoutError) as error:
                item["error"] = type(error).__name__
        review["polls"].append(current)
        complete = (len(current["files"]) == len(baseline["maven"]) and
                    all(entry.get("matched") for entry in current["files"].values()))
        review["outcome"] = "PASS" if complete else "ERROR" if fatal else "PENDING_VISIBILITY"
        (out / "readback.json").write_text(json.dumps(review, indent=2) + "\n", encoding="utf-8")
        if complete or fatal or scheduled >= visibility * 60:
            return complete
        scheduled = min(scheduled + 300, visibility * 60)
        time.sleep(max(0, started + scheduled - time.monotonic()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--candidate", type=Path, required=True)
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--rehearse", action="store_true", help="Serve the candidate's offline-maven.zip on 127.0.0.1")
    source.add_argument("--repository", help="Explicit read-only consumer endpoint")
    p.add_argument("--rehearse-fault", choices=["missing", "mismatch", "delayed"],
                   help="Exercise missing files, digest mismatch, or visibility after one real five-minute interval")
    p.add_argument("--channel", choices=["staging", "published"], default="staging")
    p.add_argument("--visibility-minutes", type=int, choices=[0, 30], default=30)
    p.add_argument("--output", type=Path, default=ROOT / "artifacts/consumers")
    args = p.parse_args()
    if args.rehearse_fault and not args.rehearse:
        p.error("Synthetic faults require --rehearse")
    if args.rehearse_fault == "delayed" and not args.visibility_minutes:
        p.error("Delayed visibility requires the normal 30-minute observation window")
    baseline = json.loads(args.candidate.read_text(encoding="utf-8"))
    out = args.output / datetime.datetime.now().strftime("readback-%Y%m%d-%H%M%S-%f")
    out.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="candidate-repo-") as tmp:
        role = "loopback" if args.rehearse else args.channel
        context = (rehearsal(args.candidate.with_name("offline-maven.zip"), tmp, args.rehearse_fault)
                   if args.rehearse else contextlib.nullcontext(args.repository))
        with context as url:
            visibility = args.visibility_minutes if not args.rehearse or args.rehearse_fault == "delayed" else 0
            ok = read_candidate(url, baseline, out, role, visibility)
    if args.rehearse_fault:
        report = out / "readback.json"
        record = json.loads(report.read_text(encoding="utf-8"))
        rows = record["polls"][-1]["files"]
        if args.rehearse_fault == "delayed":
            first = record["polls"][0]
            match = (ok and len(record["polls"]) == 2 and first["files"][baseline["aar"]["name"]]["httpStatus"] == 404 and
                     record["polls"][1]["elapsedSeconds"] >= 300 and
                     all(f.get("matched") for f in record["polls"][1]["files"].values()))
        elif args.rehearse_fault == "missing":
            match = (record["outcome"] == "PENDING_VISIBILITY" and
                     rows[baseline["aar"]["name"]].get("httpStatus") == 404)
        else:
            pom, = [name for name in baseline["maven"] if name.endswith(".pom")]
            match = record["outcome"] == "ERROR" and rows[pom].get("matched") is False
        record.update(rehearsalFault=args.rehearse_fault, expectationMatched=match and (ok == (args.rehearse_fault == "delayed")))
        report.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        ok = record["expectationMatched"]
    print(str(out) + (" PASS" if ok else " NOT MATCHED"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
