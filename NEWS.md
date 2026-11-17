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
- HQ numbers the items of a text list sent as an array from 0 (Survey Solutions test `AssignmentsImportServiceTests.Simple`). List-roster ids in the data, either positions or export item codes, are mapped to those numbers.

## Live checks (demo server, 2026-11-17)

These were checked against a Survey Solutions demo server, with the Weather Stations Census questionnaire (124 questions, a list roster and a nested multi-select roster):

- Connecting and listing questionnaires work.
- The questionnaire document downloads and parses.
- Every request built by `create`/`upload` passed HQ's answer validation. That covers main-level, list-roster and nested-roster identities, multi-select and list JSON, GPS, ISO dates and `ProtectedVariables`.
- HQ's error messages are reported per row: PL0014, PL0016, PL0018 and PL0026, and "Question(s) not found".

The server had no interviewer accounts, so no assignment was actually created. A full create, read-back and archive cycle is part of `susoassign_cert.do` (live part).
