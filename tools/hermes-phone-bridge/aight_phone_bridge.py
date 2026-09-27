#!/usr/bin/env python3
"""Stdio MCP server that Hermes starts locally, forwarding to aight's phone control over Tailscale.

Hermes could reach the phone's MCP endpoint directly, but after a few failed reconnects it parks an
HTTP server, drops its tools and only probes again every five minutes. A phone leaves the tailnet all
the time (network switches, flight mode, low battery), so the tools would keep vanishing. This bridge
never goes down from Hermes' side: it answers the handshake itself, serves the tool list from a cache
while the phone is away, and turns an unreachable phone into a tool error the agent can pass on.

Environment (set under mcp_servers.phone.env in ~/.hermes/config.yaml):
  AIGHT_PHONE_URL    http://<phone's Tailscale IP>:8643/mcp
  AIGHT_PHONE_TOKEN  the key from aight's Settings > Phone control > Copy Hermes setup
  AIGHT_PHONE_CACHE  optional; where the last tool list is kept (default: next to this file)

Python 3.8+, standard library only.
"""

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

URL = os.environ.get("AIGHT_PHONE_URL", "").strip()
TOKEN = os.environ.get("AIGHT_PHONE_TOKEN", "").strip()
CACHE = Path(os.environ.get("AIGHT_PHONE_CACHE") or Path(__file__).with_name("aight_phone_tools.json"))

# The phone answers in well under a second; a longer wait means it isn't there.
TIMEOUT_SECONDS = 8
# Straight to the phone: a proxy from the environment would get the token and can't reach 100.x anyway.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PROTOCOL_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]
INSTRUCTIONS = "These tools act on the user's Android phone, the device running the aight app."


def log(text):
    print(f"aight-phone-bridge: {text}", file=sys.stderr, flush=True)


def result(msg_id, value):
    return {"jsonrpc": "2.0", "id": msg_id, "result": value}


def error(msg_id, code, text):
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": text}}


def tool_error(msg_id, text):
    return result(msg_id, {"content": [{"type": "text", "text": text}], "isError": True})


def post(message):
    """Sends one JSON-RPC message to the phone and returns its reply."""
    request = urllib.request.Request(
        URL,
        data=json.dumps(message).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    with OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def describe_failure(exc):
    """A sentence for the agent to pass on."""
    if isinstance(exc, urllib.error.HTTPError) and exc.code == 401:
        return "The phone refused the key. Copy the Hermes setup from aight again (Settings > Phone control)."
    if isinstance(exc, urllib.error.HTTPError):
        return f"The phone answered with HTTP {exc.code}."
    reason = getattr(exc, "reason", exc)
    return (
        f"The phone isn't reachable ({reason}). It may be off Tailscale, switched off, "
        "or have phone control turned off in aight."
    )


def list_tools(message):
    try:
        reply = post(message)
        tools = reply["result"]["tools"]
    except Exception as exc:  # noqa: BLE001 - any failure falls back to the cache
        log(f"tools/list from the phone failed, using the cache: {exc}")
    else:
        try:
            CACHE.write_text(json.dumps(tools), encoding="utf-8")
        except OSError as exc:
            log(f"could not cache the tool list at {CACHE}: {exc}")
        return reply
    try:
        return result(message.get("id"), {"tools": json.loads(CACHE.read_text(encoding="utf-8"))})
    except (OSError, ValueError):
        return error(message.get("id"), -32603, "The phone isn't reachable and no tool list is cached yet.")


def call_tool(message):
    try:
        return post(message)
    except Exception as exc:  # noqa: BLE001 - the agent gets every failure as a tool error
        log(f"tools/call failed: {exc}")
        return tool_error(message.get("id"), describe_failure(exc))


def handle(message):
    method = message.get("method")
    if "id" not in message or method is None:
        return None  # notifications, and responses we never asked for
    msg_id = message["id"]
    if method == "initialize":
        requested = (message.get("params") or {}).get("protocolVersion")
        return result(msg_id, {
            "protocolVersion": requested if requested in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "aight-phone-bridge", "version": "1"},
            "instructions": INSTRUCTIONS,
        })
    if method == "ping":
        return result(msg_id, {})
    if method == "tools/list":
        return list_tools(message)
    if method == "tools/call":
        return call_tool(message)
    return error(msg_id, -32601, f"Method not found: {method}")


def main():
    # MCP's stdio transport is UTF-8, whatever locale Hermes starts this under.
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
    sys.stdout.reconfigure(encoding="utf-8")
    if not URL or not TOKEN:
        log("AIGHT_PHONE_URL and AIGHT_PHONE_TOKEN must be set")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            reply = error(None, -32700, "Parse error")
        else:
            reply = handle(message) if isinstance(message, dict) else error(None, -32600, "Invalid request")
        if reply is not None:
            sys.stdout.write(json.dumps(reply) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
