# AI Agent Guide

Reading order at the start of every task
1) Open `docs/README-DEV.md` and follow the “Current Milestone” link (this is the live source of truth for the current sprint).
2) Read that milestone document fully; follow its tasks, conventions, and decisions.
3) If anything is unclear, defer to `docs/README-DEV.md` as the single source of truth for project status, decisions, and management.
4) If `docs/README-DEV.md` is missing information you need, consult me or add it yourself at your discretion. 

Persistence rules (repo-first, not chat)
- Update the Current Milestone doc with decisions, progress, and artifact paths.
- When a milestone completes, update `docs/README-DEV.md` to point to the next milestone.
- For discrete work units, file a Change Report via `docs/TEMPLATE.md` under `docs/Reports/`.
- commit the work units with meaningful message.
- commit any meaningful changes to the project docs, including `docs/README-DEV.md`, the current milestone doc, and reports. 
- Optional: add short tips to `docs/AGENT_NOTES.md` (dated entries) if helpful.

Gotchas (stable)
- Connected tests require an unlocked device/emulator and `adb` on PATH.