# Security Policy

## Supported versions

Only the latest release is supported. The mod targets Minecraft 1.21.1 /
NeoForge 21.1.x.

## Reporting a vulnerability

Please do **not** open a public issue for security problems. Use GitHub's
private vulnerability reporting ("Report a vulnerability" on the Security tab)
or contact the maintainer directly.

## Deployment notes for server operators

- Keep `identity.environment` set correctly; outside `local` the mod enforces
  deployment gates (UUID-pinned commissioner, real coin provider, debug
  commands disabled).
- Never run a production server with `online-mode=false`.
- Test/debug commands are gated to local environments and permission level 2;
  do not weaken these gates on a live server.
- The dev harness (`run/`, `tools/rcon.py`) uses a throwaway RCON password —
  never reuse it or expose RCON publicly.
