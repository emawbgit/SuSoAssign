# SuSoAssign <img src="man/figures/logo.png" align="right" height="139" alt="SuSoAssign logo" />

**Create and upload Survey Solutions assignments from Stata, with prefilled and protected answers.**

SuSoAssign turns a Stata dataset into [Survey Solutions](https://mysurvey.solutions) (SuSo) assignments and creates them on Headquarters (HQ) through the HQ API. Before anything is sent, it checks the data against the questionnaire. You can preload answers on the cover page, in hidden questions and inside rosters, and you can protect them so that interviewers can only add to them. It runs in Stata's bundled Java, so there is no R, Python or curl to install. It works the same way on Windows, macOS and Linux.

With it you can:

- Create thousands of assignments, each with its responsible, quantity, preloaded answers and roster rows, in one command.
- Run a panel from last round's Stata export. The export layout is also the preload layout.
- Protect preloaded member lists, numbers and selections so interviewers cannot delete them.
- Stop and resume an upload without creating duplicates, and archive an upload in one line if it was wrong.

---

## Contents

1. [Features](#features)
2. [Installation](#installation)
3. [Quick start](#quick-start)
4. [Preparing your data](#preparing-your-data)
5. [Protected answers](#protected-answers)
6. [Uploading, resuming and undoing](#uploading-resuming-and-undoing)
7. [Web (CAWI) assignments](#web-cawi-assignments)
8. [How it works](#how-it-works)
9. [Testing the package locally](#testing-the-package-locally)
10. [Limitations](#limitations)
11. [Troubleshooting](#troubleshooting)
12. [Package structure](#package-structure)
13. [Prior art](#prior-art)
14. [Reporting bugs](#reporting-bugs)
15. [Citation](#citation)
16. [Authors](#authors)
17. [License](#license)

---

## Features

| Area | What it does |
|---|---|
| **Create** | `susoassign create` builds assignments from the data in memory plus one `.dta` per roster. It validates them against the questionnaire, which is downloaded from HQ or read from a `document.json`, and writes a zip in SuSo's own preload layout. You can upload that zip with `susoassign upload`, or by hand in HQ |
| **Upload** | `susoassign upload` turns each assignment into a `POST /api/v1/assignments` request and sends the requests in parallel. Every assignment is logged with its outcome, the new assignment id, the web link and HQ's message |
| **Question types** | Single-select, numeric (integer and decimal), text, barcode, date and date-time, GPS, multi-select (plain, ordered, yes/no, negative codes), text list |
| **Rosters** | Numeric, list, multi-select, yes/no and fixed rosters, nested to any depth. Rosters that share a trigger are merged into one level, as in SuSo |
| **Protection** | `protect()` or `protected__variables.tab`. It is checked that only numeric, list and multi-select questions are protected |
| **Service fields** | Responsible, quantity (with -1 for unlimited), web mode, email, password (`?` generates one), audio audit on/off and its scope, comment, target area |
| **Validation** | Before anything is sent, it checks: unknown columns; questions that can't be preloaded; category codes; integers; ISO dates; GPS ranges; roster ids against their triggers; orphan roster rows; duplicate keys; the web-mode rules. The full list is in [Preparing your data](#preparing-your-data) |
| **Safety** | Requests are retried only when they certainly did not reach the server. A request with no answer is logged as `unknown` and never re-sent blindly. `resume` skips what is done, Break stops cleanly, and `archive` undoes an upload. The password stays in memory and is never stored in a macro or file |
| **Panel** | Export conventions are accepted as they are: `var__1`, `var__0`, `__Latitude`, `.a`/`-999999999`/`##N/A##`, `interview__id`. SuSo export system columns are dropped quietly |
| **Platforms** | Stata 16 or newer on Windows, macOS and Linux. It needs only the Java that Stata bundles |

---

## Installation

### Requirements

- Stata 16 or newer. Stata 16 and later ship their own Java, which SuSoAssign uses; check it with `java query`.
- An **API user** on your HQ, added to the workspace. Only API users and administrators can create assignments through the API. An HQ administrator creates API users under *Teams and Roles → API Users*.

### From GitHub (recommended)

```stata
net install susoassign, from("https://raw.githubusercontent.com/emawbgit/SuSoAssign/main/") replace
help susoassign
```

### Offline or locked-down machines

Download the repository as a zip (*Code → Download ZIP*) and unzip it, then:

```stata
net install susoassign, from("C:/path/to/SuSoAssign-main") replace
```

Or copy `susoassign.ado`, `susoassign.sthlp` and `susoassign.jar` into your `PERSONAL` folder (`sysdir`).

### Check the installation

```stata
which susoassign
findfile susoassign.jar
susoassign setup                     // "not connected" means Stata and Java work
```

---

## Quick start

```stata
* 1. connect (the password is read from the environment variable SUSO_PASSWORD)
susoassign setup, server("https://demo.mysurvey.solutions") workspace("myws") user("apiuser")
susoassign questionnaires            // GUID and version of every questionnaire

* 2. one row per assignment: preloaded questions + who receives it
use households, clear                // hhid region members__0 members__1 ... interviewer
susoassign create using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3) ///
    responsible(interviewer) id(hhid) protect(members) roster(member "members.dta")

* 3. check every request without sending, then send
susoassign upload using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3) dryrun
susoassign upload using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3)

* 4. assignment ids per household
use wave2_log, clear                 // key status http assignment_id web_link message
```

A full panel example is in [`examples/example_workflow.do`](examples/example_workflow.do).

---

## Preparing your data

Name your variables as in a Survey Solutions export. That is the preload format too.

| Question type | Columns and values |
|---|---|
| Single-select | `var`: the category **code** (value labels are ignored) |
| Numeric | `var`; whole numbers for integer questions; `.` decimal separator |
| Text, barcode | `var` (string) |
| Date, date-time | `var` with a `%td` / `%tc` format, or text `YYYY-MM-DD` / `YYYY-MM-DDTHH:MM:SS` |
| GPS | `var__Latitude`, `var__Longitude` in decimal degrees |
| Multi-select | `var__<code>`: 1 = selected, 0 = not. For ordered questions use the rank 1, 2, ... Negative codes are written `var__n1` |
| Yes/No | `var__<code>`: 1 = yes (or the rank), 0 = no, missing = not answered |
| Text list | `var__0`, `var__1`, ... with one item each |

The following count as missing and are not preloaded: `.` and `.a`–`.z`, empty strings, `-999999999` and `##N/A##`.

Some things cannot be preloaded: pictures, audio, geography, linked questions and calculated variables. `create` drops variables that are not questions and gives a warning. The upload checks are stricter: there, an unknown column is an error.

### Service fields

| Option of `create` | Preload column | Meaning |
|---|---|---|
| `responsible()` | `_responsible` | Login of an interviewer or supervisor (required) |
| `quantity()` | `_quantity` | Interviews allowed; `-1` = unlimited; default 1 |
| `webmode()` | `_webmode` | 1 = web (CAWI), 0 = CAPI |
| `email()` | `_email` | Respondent email (web mode only) |
| `password()` | `_password` | Web password: 6+ upper-case letters and digits, or `?` to generate one |
| `audio()` | `_record_audio` | 1/0 audio audit; default: the questionnaire's setting |
| `aascope()` | `_aascope` | Audio audit scope: comma-separated section/roster variables |
| `comment()` | `_comment` | Comment for the interviewer |
| `targetarea()` | `_target_area` | Target area (map) |

### Rosters

Give one `.dta` per roster level in `roster(name "file.dta" ...)`. Each file has:

- the `id()` variable, linking the row to its assignment;
- one `<roster>__id` per level, outer levels first;
- the roster's questions.

| Roster triggered by | `<roster>__id` values |
|---|---|
| Numeric question | 0, 1, ..., n−1 |
| Text list | 1, 2, ...: the position of the item in the list |
| Multi-select / yes-no | The selected (yes) codes |
| Fixed items | The item codes set in Designer |

If a list's items are not preloaded in the parent data, a text column named like the list question in the roster file gives them. The API numbers list items itself. So when ids from an old export have gaps (1, 2, 4), they are renumbered in order (1, 2, 3) with a warning. Rosters that share a trigger form one level: give one file, named after any of them. For example, roster `plot` with nested fixed roster `season` is two files: `plot.dta` (`plot__id`) and `season.dta` (`plot__id season__id`).

---

## Protected answers

`protect(members n_plots crops)` locks the preloaded answers of those questions. Inside a zip, the same list is given by `protected__variables.tab` with the column `variable__name`.

- Only **numeric**, **text list** and **multi-select** questions (including yes/no) can be protected. Use their root names: `members`, not `members__0`.
- Interviewers can raise a protected number but not lower it. They can add list items or selections but not remove the preloaded ones. In yes/no mode, a preloaded yes or no cannot be changed.
- Empty answers are not protected.

---

## Uploading, resuming and undoing

- **Dry run**: `dryrun` builds and checks every request and sends nothing. `requests(file.dta)` saves every JSON body for inspection.
- **Parallel**: `parallel(4)` by default, at most 32. HQ creates each assignment in one request, so 10,000 assignments take a few minutes.
- **Outcomes** in the log (`file_log.dta`):

  | Status | Meaning |
  |---|---|
  | `created` | `assignment_id` is set |
  | `rejected` | HQ refused it; `message` has HQ's reason, with PL codes |
  | `failed` | Server or network error after retries |
  | `unknown` | No answer arrived; the assignment may exist |
  | `notsent` | Not sent because you pressed Break |
  | `skipped` | Done in an earlier run |

- **Retries**: only when the request certainly did not reach HQ (connection refused, HTTP 429/502/503/504), at most 3 times with back-off.
- **Resume**: running again with `resume` skips everything already `created`. It also skips `unknown` rows: check them in HQ, then add `retryunknown` to send them again. A second run without `resume` or `replace` is refused, so the same file cannot be uploaded twice by accident.
- **Break** stops starting new requests. The ones already sent finish and are logged.
- **Undo**: `susoassign archive using "file_log.dta"` archives every assignment that the log shows as created. `susoassign archive, ids(101 102)` archives single ones.

---

## Web (CAWI) assignments

HQ's rules, which `create` checks:

- An email or password is allowed only with `webmode`.
- An email requires quantity 1.
- When quantity is 1, passwords must be unique.
- A web assignment with quantity 1 needs an email or a password.
- A web assignment must go to an interviewer.

The web link of each created assignment is in `web_link` in the log.

---

## How it works

```
Stata data ──► create ──► tab files ──► Java: validate against questionnaire ──► assign.zip (HQ preload layout)
                                                                                     │
HQ  ◄── POST /{workspace}/api/v1/assignments (one per assignment, parallel) ◄── upload: zip ──► JSON per assignment
 │
 └──► upload log (.dta): key, status, assignment id, web link, message
```

- The questionnaire comes from `GET /api/v1/questionnaires/{id}/{version}/document`. It gives each question's GUID, type and roster level.
- Answers inside rosters are sent with the identity `<questionGUID>_<rosterVector>`. That is the only way the API accepts roster answers.
- Each answer is encoded the way HQ parses it:

  | Question type | Encoding |
  |---|---|
  | Multi-select | `["1","3"]` |
  | Yes/no | `["1 -> Yes","2 -> No"]` |
  | Text list | `["Ann","Bob"]` |
  | GPS | `lat$lon` |
  | Date | ISO text |

- `ProtectedVariables` carries the protected list.
- All files are UTF-8, so accents and non-Latin scripts pass through unchanged. All HTTP goes through Java's `HttpClient` with Basic authentication over HTTPS.

---

## Testing the package locally

```sh
# Java core: 10 test groups, including a mock HQ (retries, timeouts, resume, errors)
JAVA_HOME="<Stata>/utilities/java/<platform>/zulu-jdk11..." SFI_JAR="<Stata>/utilities/jar/sfi-api.jar" sh java/build.sh
```

```stata
* Stata layer, offline (uses tests/fixtures/test_qx.json); live too when the SUSO_* variables are set
cd C:/path/to/SuSoAssign
do susoassign_cert.do
```

GitHub Actions compiles and tests the Java core on Windows, macOS and Linux with Java 11, 17 and 21 (the Java bundled with Stata 16/17, 18 and 19).

---

## Limitations

- Only GPS latitude and longitude can be preloaded through the API. Altitude, accuracy and timestamp are dropped with a warning.
- Text-list item codes are set by HQ (1, 2, ...). Preserving gaps from an old export is not possible through the API.
- Reusable categories are checked by HQ, not locally.
- `create` does not check that responsible logins exist. HQ rejects unknown users per assignment, and the reason is in the log.
- The Stata layer was tested on Windows (Stata 17). The Java core, which does all the I/O and HTTP, is tested on macOS and Linux in CI. Reports from Mac users are welcome.

---

## Troubleshooting

| Message | Fix |
|---|---|
| `401 Unauthorized` | Wrong password, or the account is not an **API user** |
| `403 Forbidden` / `404` at setup | The API user is not in the workspace, or you used the workspace's display name instead of its name |
| `The server address must start with https://` | Use the `https://` address; plain http would send the password unencrypted |
| `susoassign.jar not found` | Reinstall with `net install ... replace`; check with `findfile susoassign.jar` |
| `Stata could not start Java` | Run `java query`; if Java was replaced by hand, restore Stata's bundled Java |
| `PL0026` (user not found) | The `_responsible` login does not exist, is locked, or has the wrong role |
| `Question(s) not found` | The questionnaire version differs from the one validated against; use the same `qid()`/`version()` |
| `Invalid data.` | HQ's generic error: run with `dryrun requests(r.dta)` and check that row's JSON |
| Cannot connect behind a proxy | Set the environment variable `JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=HOST -Dhttps.proxyPort=PORT` (ask IT for the values), then restart Stata |

---

## Package structure

```
susoassign.ado         the command (setup, questionnaires, create, upload, archive)
susoassign.sthlp       help file
susoassign.jar         Java core (Java 11 bytecode)
susoassign.pkg         net install description
stata.toc
susoassign_cert.do     certification script (offline + live)
examples/              panel preloading walk-through
java/src/              Qx (questionnaire), Preload (validation/encoding), Api (HTTP), Commands, Bridge (Stata SFI)
java/test/             TestAll (assert-based suite), MockHQ (stand-in HQ server)
tests/                 fixtures and the Stata/mock integration test
man/figures/           logo
```

---

## Prior art

- [susoapi](https://github.com/arthur-shaw/susoapi) (R): reads and manages assignments, but has no create endpoint.
- [SurveySolutionsAPIv2](https://github.com/michael-cw/SurveySolutionsAPIv2) (R): creates assignments with identifying data, but has no roster answers and no protection.
- [sursol](https://github.com/petbrueck/sursol) (Stata via R): interview management.
- [suso](https://github.com/arehman10/survey_solutions_api) (Stata + Java): broad API client that creates single assignments from raw JSON. SuSoAssign's JSON parser is adapted from it (MIT).

---

## Reporting bugs

Please open an issue on the [GitHub repository](https://github.com/emawbgit/SuSoAssign/issues) with:

- your Stata version, operating system, and `which susoassign`;
- the command you ran and the full message;
- if possible, the questionnaire's `document.json` and a few rows of `requests(file.dta)`. Remove personal data first.

---

## Citation

If you use SuSoAssign in your work, please cite:

Clemente, E. "SuSoAssign: create and upload Survey Solutions assignments from Stata." https://github.com/emawbgit/SuSoAssign, version 0.1.0.

---

## Authors

Emanuele Clemente, [github.com/emawbgit](https://github.com/emawbgit)

---

## License

MIT (see [LICENSE](LICENSE)) © 2026 Emanuele Clemente. Survey Solutions is developed by the World Bank. This package is an independent tool and is not affiliated with or endorsed by the World Bank.
