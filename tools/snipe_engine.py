#!/usr/bin/env python3
"""
Bedwars snipe engine.

Watches a target's Hypixel status and, on every IN_GAME -> LOBBY transition,
drops the matching /play command into a bridge file that the Forge mod executes.

    OFFLINE -> LOBBY -> IN_GAME -> LOBBY -> ...
                        ^^^^^^^^^^^^^^^^^
                        the only window where a join is possible

Env vars
--------
HYPIXEL_API_KEY   required, from https://developer.hypixel.net/dashboard
DISCORD_WEBHOOK   optional
BRIDGE_FILE       default ./snipe_cmd.txt
POLL_SECONDS      default 4

Use as-is; there is nothing to configure in code except the target list below.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

API = "https://api.hypixel.net/v2"
MOJANG = "https://api.mojang.com/users/profiles/minecraft/{}"
PLAYERDB = "https://playerdb.co/api/player/minecraft/{}"
TIMEOUT = 10

# ---------------------------------------------------------------- config bits

TARGETS: list[str] = [
    "TargetPlayer1",
]

# status mode -> in-game command
QUEUE = {
    "BEDWARS_EIGHT_ONE": "play bedwars_eight_one",
    "BEDWARS_EIGHT_TWO": "play bedwars_eight_two",
    "BEDWARS_FOUR_THREE": "play bedwars_four_three",
    "BEDWARS_FOUR_FOUR": "play bedwars_four_four",
    "BEDWARS_TWO_FOUR": "play bedwars_two_four",
    "BEDWARS_CASTLE": "play bedwars_castle",
}

LOBBY_MODES = {"BEDWARS", "BEDWARS_LOBBY", "LOBBY"}
DEFAULT_COMMAND = "play bedwars_eight_one"

BRIDGE = Path(os.environ.get("BRIDGE_FILE", "./snipe_cmd.txt"))
POLL = max(3, int(os.environ.get("POLL_SECONDS", "4")))
KEY = os.environ.get("HYPIXEL_API_KEY", "").strip()
HOOK = os.environ.get("DISCORD_WEBHOOK", "").strip()

# ------------------------------------------------------------------- helpers


def get(url: str, headers: dict | None = None) -> dict | None:
    try:
        req = urllib.request.Request(url, headers=headers or {})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 429:
            print("[!] 429 rate limited - sleeping 60s")
            time.sleep(60)
        return None
    except Exception:
        return None


def uuid_of(name: str) -> str | None:
    data = get(MOJANG.format(name))
    if data and data.get("id"):
        return data["id"]
    data = get(PLAYERDB.format(name))
    if data and data.get("success"):
        try:
            return data["data"]["player"]["raw_id"]
        except KeyError:
            return None
    return None


def status(uuid: str) -> dict | None:
    data = get(f"{API}/status?uuid={uuid}", {"API-Key": KEY})
    if not data or not data.get("success"):
        return None
    return data.get("session")


def recent_games(uuid: str) -> list[dict]:
    data = get(f"{API}/recentGames?uuid={uuid}", {"API-Key": KEY})
    if not data or not data.get("success"):
        return []
    return data.get("games") or []


def notify(text: str) -> None:
    if not HOOK:
        return
    payload = json.dumps({"content": text}).encode()
    req = urllib.request.Request(
        HOOK,
        data=payload,
        headers={"Content-Type": "application/json", "User-Agent": "snipe-engine/1.2"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT):
            pass
    except Exception as e:
        print(f"[!] webhook: {e}")


def push_command(cmd: str) -> None:
    """Hand the command to the Forge mod, which polls this file every tick."""
    try:
        tmp = BRIDGE.with_suffix(".tmp")
        tmp.write_text(f"/{cmd}\n", encoding="utf-8")
        tmp.replace(BRIDGE)
        print(f"[bridge] -> /{cmd}")
    except OSError as e:
        print(f"[!] bridge write failed: {e}")


def classify(session: dict | None) -> tuple[str, str | None, str | None]:
    """Map a raw session object onto (state, mode, map)."""
    if session is None or not session.get("online"):
        return "OFFLINE", None, None

    game = session.get("gameType") or ""
    mode = session.get("mode") or ""
    mapname = session.get("map")

    if game == "BEDWARS" and (mode in LOBBY_MODES or "LOBBY" in mode or not mode):
        return "LOBBY", mode or "BEDWARS_LOBBY", mapname
    if mode in QUEUE:
        return "IN_GAME", mode, mapname
    return "IN_GAME", mode or None, mapname


# --------------------------------------------------------------------- state


@dataclass
class Target:
    name: str
    uuid: str | None = None
    state: str = "UNKNOWN"
    mode: str | None = None
    map: str | None = None
    last_queue_at: float = 0.0
    history: list[dict] = field(default_factory=list)


def on_lobby(t: Target) -> None:
    now = time.time()
    if now - t.last_queue_at < 20:
        return  # anti double-fire
    t.last_queue_at = now

    games = recent_games(t.uuid) if t.uuid else []
    last_map = None
    last_mode = t.mode

    if games:
        g = games[0]
        last_map = g.get("map")
        last_mode = g.get("mode") or last_mode
        t.history.append({"date": g.get("date"), "mode": last_mode, "map": last_map})

    cmd = QUEUE.get(last_mode) if last_mode else None
    if not cmd:
        cmd = DEFAULT_COMMAND

    print(f"[WINDOW] {t.name} back in lobby (last map={last_map}, mode={last_mode})")
    push_command(cmd)
    notify(
        f"**{t.name}** finished a game\n"
        f"last map: `{last_map}`\nmode: `{last_mode}`\nqueueing `/{cmd}`"
    )


def step(t: Target) -> None:
    session = status(t.uuid)
    state, mode, mapname = classify(session)

    if session is None:
        if t.state != "OFFLINE":
            print(f"[-] {t.name}: session hidden (API disabled) or offline")
        t.state, t.mode, t.map = "OFFLINE", None, None
        return

    if state != t.state:
        print(f"[~] {t.name}: {t.state} -> {state} ({mode} / {mapname})")

    previous = t.state
    t.state, t.mode, t.map = state, mode, mapname

    if previous == "IN_GAME" and state == "LOBBY":
        on_lobby(t)


def main() -> int:
    if not KEY:
        print("[!] HYPIXEL_API_KEY is not set", file=sys.stderr)
        return 1

    if len(TARGETS) > 5:
        print("[!] more than 5 targets at poll=4s will hit the rate limit", file=sys.stderr)

    targets = [Target(name=n) for n in TARGETS]

    print(f"[*] resolving {len(targets)} target(s)")
    for t in targets:
        t.uuid = uuid_of(t.name)
        print(f"    {t.name} -> {t.uuid or 'NOT FOUND'}")

    live = [t for t in targets if t.uuid]
    if not live:
        print("[!] no UUIDs resolved", file=sys.stderr)
        return 1

    print(f"[*] polling every {POLL}s, bridge={BRIDGE.resolve()}")
    while True:
        for t in live:
            step(t)
            time.sleep(0.6)  # stays under 2 req/s
        time.sleep(POLL)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[*] stopped")
        sys.exit(0)
