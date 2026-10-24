#!/usr/bin/env python3
"""Send sequential HTTP requests through the P5 test Gateway and count replies."""

import argparse
import collections
import json
import time
import urllib.error
import urllib.request


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:18080/")
    parser.add_argument("--host", default="p5.example.test")
    parser.add_argument("--count", type=int, default=300)
    parser.add_argument("--duration-seconds", type=float, default=0)
    args = parser.parse_args()
    if args.count < 1:
        parser.error("--count must be positive")
    if args.duration_seconds < 0:
        parser.error("--duration-seconds cannot be negative")

    results: collections.Counter[str] = collections.Counter()
    deadline = time.monotonic() + args.duration_seconds
    while True:
        if args.duration_seconds == 0 and results.total() >= args.count:
            break
        if args.duration_seconds > 0 and time.monotonic() >= deadline:
            break
        request = urllib.request.Request(args.url, headers={"Host": args.host})
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                version = response.read().decode("utf-8").strip()
                results[f"{response.status} {version}"] += 1
        except urllib.error.HTTPError as response:
            version = response.read().decode("utf-8").strip()
            results[f"{response.code} {version}"] += 1

    print(json.dumps({"requests": results.total(), "responses": dict(results)}, sort_keys=True))


if __name__ == "__main__":
    main()
