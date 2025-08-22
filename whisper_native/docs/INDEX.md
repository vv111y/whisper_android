# Specstory Guide and Index

A lightweight place to keep searchable Markdown transcripts and decisions for this project.

## Purpose

- Make decisions/actions easy to find later.
- Give teammates and CI a single place to look for context.
- Keep sensitive data out of chats that will be committed.

## Where files live

- Directory: `.specstory/history/`
- Naming convention (recommended): `YYYY-MM-DD_HH-mm[-tag].md`
  - Example: `2025-08-20_15-30-vad-tuning.md`
  - Use 24h time; include a short tag when helpful.

## Standard format for a transcript

- Title: short and specific (e.g., "VAD tuning and CI artifact wiring")
- Optional YAML front matter (if you like):

  ```yaml
  date: 2025-08-20
  time: 15:30
  tz: PDT
  branch: test_and_set_frag
  tags: [vad, tuner, ci]
  participants: [will, copilot]
  ```

- Summary (5–10 lines)
- Decisions (bullets)
- Actions (checkbox bullets)
- Open items (bullets)
- Notes / Context (optional)
- Files changed (paths)
- Commands (bash fenced)
- Artifacts/Links (e.g., CI artifacts, PRs)
- Next steps

## Quick start

1) Duplicate `TEMPLATE.md` to `.specstory/history/YYYY-MM-DD_HH-mm-<topic>.md`.
2) Fill in sections as you go or right after the session.
3) Link it below in the Index and commit (if safe).

## Git policy

- Commit if content is safe and useful to share/search.
- If private/ephemeral, keep local and add `.specstory/**` to `.gitignore` (but consider keeping this INDEX committed).

## Search tips

- Prefer clear section headers and keywords.
- Link to relevant files/PRs.
- Keep commands in fenced blocks for easy copy/paste.

## Today’s example (if present)

- 2025-08-20 session: `.specstory/history/2025-08-20_3-30pm.md`

## Index

- Add links here as you create new transcripts:
  - (add newest on top)

Or generate automatically:

- Run `./gradlew generateSpecstoryIndex` then open `.specstory/INDEX.generated.md`.
