#!/usr/bin/env python3
"""Copy this portable skill into an explicitly selected agent skills directory."""

import argparse
import shutil
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skills-dir", required=True, type=Path,
        help="Parent skills directory; installs a dudan-hermes subdirectory",
    )
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    skills_dir = args.skills_dir.expanduser().resolve()
    target = skills_dir / source.name

    if not (source / "SKILL.md").is_file():
        parser.error(f"Source skill is incomplete: {source}")
    if target.exists() or target.is_symlink():
        parser.error(f"Destination already exists; no files changed: {target}")
    if skills_dir == source or source in skills_dir.parents:
        parser.error("Choose a skills directory outside this skill's source folder")

    try:
        skills_dir.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, target, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    except OSError as exc:
        print(f"Installation failed: {exc}", file=sys.stderr)
        print("A partial destination may remain; inspect it before retrying.", file=sys.stderr)
        return 1

    print(f"Installed {target}")
    print("Open a new agent session to discover dudan-hermes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
