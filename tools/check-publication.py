#!/usr/bin/env python3
"""Check the publishable tree, not a user's device or historical Git commits."""
from pathlib import Path
import hashlib
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
FORBIDDEN_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.apk', '.aab', '.pyc'}
PATTERNS = {
    'private key': re.compile(r'-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----'),
    'GitHub token': re.compile(r'\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b'),
    'AWS access key': re.compile(r'\bAKIA[0-9A-Z]{16}\b'),
    'Google API key': re.compile(r'\bAIza[0-9A-Za-z_-]{30,}\b'),
    'JWT-shaped credential': re.compile(r'\beyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\b'),
}
WRAPPER_SHA256 = '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'


# User-provided screenshots inspected for this public source release.
SCREENSHOTS = {
    "docs/screenshots/apps.webp": "74088db42ad70a331b0d1e5d62cdb8a2f775814773f6c80893c60499584f1174",
    "docs/screenshots/devices.webp": "78a8b0fe57feb4cc1672790665963a7dc60d8e70e7b73331a5ca16de32cd2157",
    "docs/screenshots/favorites.webp": "0c265bf9a3e369dc802440b249c1a0270f039f5d9fc3594a6eb8cba4ed8ea47a",
    "docs/screenshots/home.webp": "333918a207244022ac056892a7e0ad261c6a0684caab2cf4e4d5d46b758194f5",
    "docs/screenshots/movies.webp": "ea668f9751fadf6abaa955e4239ee97c894f998f825af50e90e6d734b3ed7fc4"
}

def inspect(relative, raw):
    path = Path(relative)
    findings = []
    if path.suffix.lower() in FORBIDDEN_SUFFIXES or path.name in {
        '.env', 'local.properties', 'keystore.properties', 'secrets.properties',
        'sashatv-settings.json', 'tmdb-token-import.txt',
    } or any(part in {'.idea', '.gradle', '.jvm-check', '__pycache__', '.sashatv-backups', 'build'} for part in path.parts):
        findings.append('private/generated file')
    if path.name in {'reference_screen.png', 'hero_desert.png'}:
        findings.append('obsolete unverified artwork')
    if path.suffix == '.jar':
        if relative != 'gradle/wrapper/gradle-wrapper.jar' or hashlib.sha256(raw).hexdigest() != WRAPPER_SHA256:
            findings.append('unrecognized binary JAR')
        return findings
    if path.suffix.lower() in {'.png', '.webp'}:
        if relative != 'app/src/main/res/drawable-nodpi/tmdb_logo.png' and SCREENSHOTS.get(relative) != hashlib.sha256(raw).hexdigest():
            findings.append('unreviewed raster image')
        return findings
    try:
        text = raw.decode('utf-8')
    except UnicodeDecodeError:
        findings.append('unexpected binary file')
        return findings
    for label, pattern in PATTERNS.items():
        if pattern.search(text):
            findings.append(label)
    return findings


def main():
    # Never accidentally scan an enclosing unrelated checkout.
    tracked = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', '--show-toplevel'],
                             capture_output=True, text=True)
    if tracked.returncode == 0 and Path(tracked.stdout.strip()).resolve() == ROOT:
        result = subprocess.run(['git', '-C', str(ROOT), 'ls-files', '-z'],
                                capture_output=True, check=True)
        files = [name for name in result.stdout.decode().split('\0') if name]
        if not files:
            print('FAIL: no tracked files; stage the intended publication tree first')
            return 1
    else:
        files = (ROOT / 'update-files.txt').read_text().splitlines() + [
            'install.sh', 'restore.sh', 'update-files.txt', 'SHA256SUMS']
    errors = []
    for name in sorted(set(files)):
        path = ROOT / name
        if not path.is_file():
            errors.append((name, 'missing file'))
            continue
        errors.extend((name, issue) for issue in inspect(name, path.read_bytes()))
    for name, issue in errors:
        print(f'FAIL: {name}: {issue}')  # Do not print credential values.
    if errors:
        return 1
    print(f'PASS: publication tree ({len(set(files))} files); no recognized secret patterns')
    print('This is a bounded pattern/tree check, not a Git-history audit or a security guarantee.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
