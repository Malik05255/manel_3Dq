"""Compatibility no-op for the legacy Render build command.

Floor-plan inference is now performed exclusively by the Modal reader.  The
existing Render service still invokes this script from its provisioned build
command, so keep the file as a harmless no-op until that dashboard command is
removed.
"""

from __future__ import annotations


def main() -> int:
    print("Local floor-plan model download disabled: cloud-only reader is authoritative.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
