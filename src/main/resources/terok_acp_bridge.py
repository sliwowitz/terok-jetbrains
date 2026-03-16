#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jiri Vyskocil
# SPDX-License-Identifier: Apache-2.0

"""ACP bridge: JSON-RPC-aware proxy between JetBrains IDE and an in-container agent.

Reads the target container from a state file written by the terok plugin.
Rewrites ``cwd`` in ``session/new`` (host path → /workspace) and file paths in
``session/update`` notifications (container path → host path).
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading


# Agent ACP commands by provider (what to exec inside the container).
# Default is claude-code-acp (installed via npm in terok containers).
DEFAULT_AGENT_CMD = ["claude-code-acp"]


def read_active_task(state_file: str) -> str:
    """Read the active container name from the state file."""
    try:
        return open(state_file).read().strip()  # noqa: SIM115
    except FileNotFoundError:
        print(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "error": {
                        "code": -1,
                        "message": (
                            "No active terok task. "
                            "Click 'Chat' on a task in the Services panel first."
                        ),
                    },
                }
            ),
            flush=True,
        )
        sys.exit(1)


def resolve_host_workspace(container_name: str) -> str:
    """Derive the host workspace path from the container name."""
    # Convention: container name is <project>-<mode>-<task>
    parts = container_name.rsplit("-", 2)
    if len(parts) >= 3:
        project_id = "-".join(parts[:-2])
        task_id = parts[-1]
    else:
        project_id = container_name
        task_id = "unknown"

    state_root = os.environ.get(
        "TEROK_STATE_DIR",
        os.path.expanduser("~/.local/share/terok"),
    )
    return os.path.join(state_root, "tasks", project_id, task_id, "workspace-dangerous")


def rewrite_ide_to_agent(line: str, host_workspace: str) -> str:
    """Rewrite messages from IDE to agent: fix cwd in session/new."""
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        return line

    method = msg.get("method", "")
    if method in ("session/new", "session/load") and "params" in msg:
        params = msg["params"]
        if "cwd" in params:
            params["cwd"] = "/workspace"
            return json.dumps(msg, separators=(",", ":"))

    return line


def rewrite_agent_to_ide(line: str, host_workspace: str) -> str:
    """Rewrite messages from agent to IDE: translate container paths to host paths."""
    if "/workspace/" not in line and '"/workspace"' not in line:
        return line

    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        return line

    text = json.dumps(msg, separators=(",", ":"))
    text = text.replace("/workspace/", f"{host_workspace}/")
    text = text.replace('"/workspace"', f'"{host_workspace}"')
    return text


def main() -> None:
    """Bridge entry point."""
    parser = argparse.ArgumentParser(description="ACP bridge for terok containers")
    parser.add_argument("--state-file", required=True, help="Path to active-task state file")
    parser.add_argument("--container", help="Container name (overrides state file)")
    parser.add_argument("--agent-cmd", help="Agent command override (space-separated)")
    args = parser.parse_args()

    container_name = args.container or read_active_task(args.state_file)
    host_workspace = resolve_host_workspace(container_name)
    agent_cmd = args.agent_cmd.split() if args.agent_cmd else DEFAULT_AGENT_CMD

    # TODO: replace direct podman call with `terokctl task exec`
    proc = subprocess.Popen(
        ["podman", "exec", "-i", container_name, *agent_cmd],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=sys.stderr,
    )

    def forward_stdin() -> None:
        """Forward IDE stdin to container agent, rewriting messages."""
        assert proc.stdin is not None
        try:
            for raw_line in sys.stdin.buffer:
                line = raw_line.decode("utf-8", errors="replace").rstrip("\n")
                rewritten = rewrite_ide_to_agent(line, host_workspace)
                proc.stdin.write((rewritten + "\n").encode("utf-8"))
                proc.stdin.flush()
        except (BrokenPipeError, OSError):
            pass
        finally:
            try:
                proc.stdin.close()
            except OSError:
                pass

    stdin_thread = threading.Thread(target=forward_stdin, daemon=True)
    stdin_thread.start()

    assert proc.stdout is not None
    try:
        for raw_line in proc.stdout:
            line = raw_line.decode("utf-8", errors="replace").rstrip("\n")
            rewritten = rewrite_agent_to_ide(line, host_workspace)
            sys.stdout.buffer.write((rewritten + "\n").encode("utf-8"))
            sys.stdout.buffer.flush()
    except (BrokenPipeError, OSError):
        pass

    sys.exit(proc.wait())


if __name__ == "__main__":
    main()
