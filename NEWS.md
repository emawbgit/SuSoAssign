# SuSoAssign 0.1.0 (2026-11-17)

First release.

- `susoassign setup` / `questionnaires` / `create` / `upload` / `archive`.
- Preloading of every type the HQ API accepts (single, numeric, text, barcode, date, GPS, multi-select, ordered, yes/no, text list), at main level and inside numeric, list, multi-select and fixed rosters, nested to any depth.
- Protected answers (`protect()` or `protected__variables.tab`).
- Web mode, email, password, audio audit and its scope, comment, target area.
- `create` writes the Survey Solutions preload layout, so its zip can also be uploaded by hand in HQ.
- Survey Solutions exports can be used as they are, for panel preloading.
- Safe uploads: parallel requests; retries only for requests that never reached HQ; `unknown` outcomes never re-sent blindly; `resume`, `retryunknown`, Break, and `archive` to undo.

## How the encodings were established

The request format comes from the Survey Solutions source code: `AssignmentsController.Create`, `PreloadingExtensions` and `ServiceColumns`. It is summarised in the README.

- Answers are sent as strings.
- Multi-select, yes/no and text-list answers are JSON arrays inside that string.
- GPS is `lat$lon`.
- Roster answers use `<questionGUID>_<rosterVector>`.
- Text-list items sent as an array are numbered from 1.
