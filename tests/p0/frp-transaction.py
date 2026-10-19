#!/usr/bin/env python3
"""Isolated P0 FRP config transaction helper; deliberately not a production importer."""

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib


def digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def read_manifest(state: Path) -> dict:
    return json.loads((state / "manifest.json").read_text())


def write_manifest(state: Path, value: dict) -> None:
    target = state / "manifest.json"
    temporary = state / ".manifest.json.tmp"
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, target)


def atomic_write(target: Path, content: bytes) -> None:
    temporary = target.with_name(f".{target.name}.tmp")
    with temporary.open("wb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, target)


def validate_toml(path: Path) -> bytes:
    content = path.read_bytes()
    parsed = tomllib.loads(content.decode("utf-8"))
    if not isinstance(parsed.get("proxies", []), list):
        raise ValueError("proxies must be represented by [[proxies]] entries")
    return content


def import_config(args: argparse.Namespace) -> None:
    source = args.source.resolve(strict=True)
    content = validate_toml(source)
    args.state_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    atomic_write(args.state_dir / "original.toml", content)
    atomic_write(args.state_dir / "active.toml", content)
    write_manifest(args.state_dir, {
        "source": source.name,
        "imported_sha256": digest(content),
        "active_sha256": digest(content),
        "staged_sha256": None,
        "applied_sha256": None,
    })
    print(f"IMPORTED {source.name} sha256={digest(content)}")


def stage_config(args: argparse.Namespace) -> None:
    state = args.state_dir
    original = (state / "original.toml").read_bytes()
    parsed = tomllib.loads(original.decode("utf-8"))
    proxies = parsed.get("proxies", [])
    matches = [proxy for proxy in proxies if proxy.get("name") == args.proxy]
    if len(matches) > 1:
        raise ValueError(f"expected at most one proxy named {args.proxy!r}, found {len(matches)}")
    if not 1 <= args.remote_port <= 65535:
        raise ValueError("remote port must be between 1 and 65535")

    text = original.decode("utf-8")
    lines = text.splitlines(keepends=True)
    starts = [i for i, line in enumerate(lines) if re.match(r"\s*\[\[proxies\]\]\s*(?:#.*)?$", line)]
    target = None
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(lines)
        block = "".join(lines[start:end])
        name_match = re.search(r"(?m)^\s*name\s*=\s*(['\"])(.*?)\1\s*(?:#.*)?$", block)
        if name_match and name_match.group(2) == args.proxy:
            target = (start, end)
            break
    if target is None and matches:
        raise ValueError("proxy could not be mapped to a supported [[proxies]] table")
    if target is None:
        if not args.local_ip or not args.local_port:
            raise ValueError("adding a proxy requires --local-ip and --local-port")
        if any(proxy.get("remotePort") == args.remote_port for proxy in proxies):
            raise ValueError(f"remote port {args.remote_port} is already used by another proxy")
        suffix = "" if not text or text.endswith("\n\n") else "\n" if text.endswith("\n") else "\n\n"
        candidate = (text + suffix +
            "[[proxies]]\n"
            f'name = "{args.proxy}"\n'
            'type = "tcp"\n'
            f'localIP = "{args.local_ip}"\n'
            f"localPort = {args.local_port}\n"
            f"remotePort = {args.remote_port}\n").encode("utf-8")
    else:
        start, end = target
        port_lines = [i for i in range(start + 1, end) if re.match(r"\s*remotePort\s*=", lines[i])]
        if len(port_lines) != 1:
            raise ValueError("target proxy must have exactly one remotePort setting")
        line_index = port_lines[0]
        lines[line_index], count = re.subn(
            r"(^\s*remotePort\s*=\s*)\d+",
            lambda match: match.group(1) + str(args.remote_port),
            lines[line_index],
            count=1,
        )
        if count != 1:
            raise ValueError("target proxy remotePort must be a single integer")
        candidate = "".join(lines).encode("utf-8")
    tomllib.loads(candidate.decode("utf-8"))
    atomic_write(state / "candidate.toml", candidate)
    manifest = read_manifest(state)
    manifest["staged_sha256"] = digest(candidate)
    write_manifest(state, manifest)
    print(f"STAGED proxy={args.proxy} remotePort={args.remote_port} sha256={digest(candidate)}")


def show_diff(args: argparse.Namespace) -> None:
    state = args.state_dir
    original = (state / "original.toml").read_text().splitlines(keepends=True)
    candidate = (state / "candidate.toml").read_text().splitlines(keepends=True)
    sys.stdout.writelines(difflib.unified_diff(original, candidate, fromfile="imported/frpc.toml", tofile="desired/frpc.toml"))


def verify(args: argparse.Namespace) -> None:
    state = args.state_dir.resolve(strict=True)
    config = (state / args.file).resolve(strict=True)
    if config.parent != state or not config.is_file():
        raise ValueError("config file must be a regular file inside the imported state directory")
    validate_toml(config)
    result = subprocess.run(["podman", "exec", args.container, "frpc", "verify", "-c", f"/p0/{config.name}"], check=False)
    if result.returncode:
        raise RuntimeError(f"frpc verify rejected {config.name} with exit status {result.returncode}")
    print(f"VERIFIED {config.name}")


def apply(args: argparse.Namespace) -> None:
    state = args.state_dir
    manifest = read_manifest(state)
    original = (state / "original.toml").read_bytes()
    active = (state / "active.toml").read_bytes()
    candidate = validate_toml(state / "candidate.toml")
    if digest(original) != manifest["imported_sha256"]:
        raise ValueError("imported baseline changed after import")
    if digest(active) == digest(candidate) and manifest.get("applied_sha256") == digest(candidate):
        verify(argparse.Namespace(state_dir=state, file="candidate.toml", container=args.container))
        print(f"ALREADY_APPLIED sha256={digest(candidate)}")
        return
    if digest(active) != manifest["active_sha256"]:
        raise ValueError("active configuration changed outside this transaction")
    if digest(candidate) != manifest["staged_sha256"]:
        raise ValueError("staged configuration changed after diff review")
    verify(argparse.Namespace(state_dir=state, file="candidate.toml", container=args.container))
    atomic_write(state / "active.toml", candidate)
    manifest["active_sha256"] = digest(candidate)
    manifest["applied_sha256"] = digest(candidate)
    write_manifest(state, manifest)
    print(f"APPLIED sha256={digest(candidate)}; restart the isolated frpc process to activate it")


def compensate(args: argparse.Namespace) -> None:
    state = args.state_dir
    manifest = read_manifest(state)
    active = (state / "active.toml").read_bytes()
    original = (state / "original.toml").read_bytes()
    if not manifest.get("applied_sha256") or digest(active) != manifest["applied_sha256"]:
        raise ValueError("active configuration does not match this transaction; refusing compensation")
    atomic_write(state / "active.toml", original)
    manifest["active_sha256"] = digest(original)
    write_manifest(state, manifest)
    verify(argparse.Namespace(state_dir=state, file="active.toml", container=args.container))
    print(f"COMPENSATED original_sha256={digest(original)}; restart the isolated frpc process to restore it")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    imported = subparsers.add_parser("import")
    imported.add_argument("--source", type=Path, required=True)
    imported.add_argument("--state-dir", type=Path, required=True)
    imported.set_defaults(run=import_config)
    staged = subparsers.add_parser("stage")
    staged.add_argument("--state-dir", type=Path, required=True)
    staged.add_argument("--proxy", required=True)
    staged.add_argument("--remote-port", type=int, required=True)
    staged.add_argument("--local-ip")
    staged.add_argument("--local-port", type=int)
    staged.set_defaults(run=stage_config)
    diff = subparsers.add_parser("diff")
    diff.add_argument("--state-dir", type=Path, required=True)
    diff.set_defaults(run=show_diff)
    verified = subparsers.add_parser("verify")
    verified.add_argument("--state-dir", type=Path, required=True)
    verified.add_argument("--file", default="active.toml")
    verified.add_argument("--container", required=True)
    verified.set_defaults(run=verify)
    applied = subparsers.add_parser("apply")
    applied.add_argument("--state-dir", type=Path, required=True)
    applied.add_argument("--container", required=True)
    applied.set_defaults(run=apply)
    compensated = subparsers.add_parser("compensate")
    compensated.add_argument("--state-dir", type=Path, required=True)
    compensated.add_argument("--container", required=True)
    compensated.set_defaults(run=compensate)
    args = parser.parse_args()
    try:
        args.state_dir = getattr(args, "state_dir", Path("."))
        args.run(args)
    except Exception as error:
        parser.exit(1, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()
