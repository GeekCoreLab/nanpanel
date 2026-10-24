#!/usr/bin/env python3
"""Wait until Prometheus has successfully scraped a canary Pod at least once."""

import argparse
import json
import time
import urllib.parse
import urllib.request


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:19090")
    parser.add_argument("--hash", required=True)
    parser.add_argument("--timeout-seconds", type=float, default=90)
    args = parser.parse_args()
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be positive")

    target_url = args.url.rstrip("/") + "/api/v1/targets?" + urllib.parse.urlencode(
        {"state": "active"}
    )
    deadline = time.monotonic() + args.timeout_seconds
    while time.monotonic() < deadline:
        with urllib.request.urlopen(target_url, timeout=3) as response:
            targets = json.load(response)["data"]["activeTargets"]
        matches = [
            target
            for target in targets
            if target["labels"].get("rollout_hash") == args.hash
            and target["health"] == "up"
            and target["lastScrape"]
        ]
        if matches:
            print(
                json.dumps(
                    {
                        "rolloutHash": args.hash,
                        "scrapedTargets": [target["labels"]["instance"] for target in matches],
                    },
                    sort_keys=True,
                )
            )
            return
        time.sleep(1)

    raise SystemExit(f"no successful Prometheus scrape for rollout hash {args.hash}")


if __name__ == "__main__":
    main()
