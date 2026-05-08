# Contributing to Mercurygram

Thanks for considering a contribution. Mercurygram is a rebase fork of upstream
Telegram. Read [`AGENTS.md`](AGENTS.md) before you start — the rebase workflow
shapes how patches must be structured.

## Translations

Mercurygram-only strings (those introduced by `[MG]` commits) are kept in the
same Android resource files as upstream Telegram. There is no Crowdin / Weblate
/ Transifex — translations land via GitHub pull requests.

### What needs translating

The MG-only string keys are prefixed with `Mercurygram` (and a few `mg_…` for
SharedPreferences-related labels) inside
[`TMessagesProj/src/main/res/values/strings.xml`](TMessagesProj/src/main/res/values/strings.xml).

Run the helper to see exactly which keys are still missing in each locale:

```sh
./scripts/check-mg-translations.sh
```

Currently shipped locales:

```
ar  de  es  it  ko  nl  pt-rBR  ru  uk
```

### How to translate (app strings)

1. Fork the repo, branch off `master`.
2. Edit `TMessagesProj/src/main/res/values-<locale>/strings.xml`. Add the
   missing `<string name="Mercurygram…">…</string>` entries (the script output
   lists the key names to copy).
3. Use the English value in
   `TMessagesProj/src/main/res/values/strings.xml` as the source of truth.
4. Mind Android XML escaping:
   - apostrophe `'` → `\'`
   - double-quote `"` → `\"`
   - ampersand `&` → `&amp;`
   - Keep proper nouns untranslated: `Mercurygram`, `UnifiedPush`, `GitHub`,
     `Live Photos`, `Live Photo`, `Google Motion Photo`.
5. Re-run `./scripts/check-mg-translations.sh` — your locale should now show
   no missing keys (or fewer than before).
6. Open a PR titled `[MG] translations(<locale>): <short note>`.

You do **not** need to know which `[MG] …` commit originally introduced a
string. The maintainer folds translation PRs back into the originating commit
via `git rebase --autosquash` before the next upstream rebase, to keep the
patch series small (see [`AGENTS.md`](AGENTS.md), "Keep the commit count low").

### Adding a new locale

Only locales that already exist in upstream Telegram make sense — otherwise
none of the ~10.6k upstream strings will be translated and the app will fall
back to English everywhere except the MG screens. Open an issue first if
unsure.

To add one: create `TMessagesProj/src/main/res/values-<locale>/strings.xml`
with at least the 15 MG keys, then translate as above.

### Translation status

The current MG translations for `de`, `es`, `pt-rBR`, `nl`, `ru`, `uk`, `ko`,
`ar` were seeded by AI and are explicitly considered drafts awaiting
native-speaker review. Italian (`it`) was reviewed by a native speaker.
Corrections via PR are very welcome — don't assume anything is locked in.

## F-Droid metadata translations

The store listings under [`metadata/`](metadata) follow the standard fastlane
F-Droid layout:

```
metadata/<locale>/
    name.txt          # short app name
    summary.txt       # one-line summary
    description.txt   # long description
    changelogs/<vercode>.txt   # English-only, do not translate
```

Source of truth: `metadata/en-US/`. Translators may add or update
`name.txt`, `summary.txt`, `description.txt` for any locale. **Changelogs stay
English** — they describe code commits and are written by the maintainer.

## Code contributions

For non-translation code changes, follow the conventions in
[`AGENTS.md`](AGENTS.md):

- Tag commits `[MG]` (Mercurygram features) or `[TF]` (Telegram-FOSS / de-googling).
- Prefer adding new files in `it.belloworld.mercurygram.*` over modifying
  upstream files.
- New `SharedConfig` flags use the `mg_` SharedPreferences prefix.
- Bug fixes against an existing `[MG]` feature should be folded into the
  introducing commit (`git commit --fixup` + `git rebase --autosquash`),
  not added as new follow-up commits.
- Keep `AGENTS.md` / `README.md` in sync with any change to build, config,
  or workflow.

