* Stata <-> Java <-> HTTP integration test against the mock HQ (development only).
* Start the mock first (from java/):
*   java -cp "build/classes;build/test" org.emawbgit.susoassign.MockHQ 18765 ../tests/fixtures/test_qx.json
* then, from the repository root:  do tests/stata_mock.do
version 16
clear all
set more off
local root = c(pwd)
adopath ++ "`root'"
discard
tempfile t
local work "`t'_mock"
mkdir "`work'"
cd "`work'"

* plain http is refused for anything but localhost; wrong password is reported
capture noisily susoassign setup, server("http://example.org") workspace(ws) user(u) password(p)
assert _rc == 198
capture noisily susoassign setup, server("http://127.0.0.1:18765") workspace(ws) user(u) password(wrong)
assert _rc == 677
capture noisily susoassign setup, server("http://127.0.0.1:18765") workspace(nows) user(u) password(p)
assert _rc == 677
susoassign setup, server("http://127.0.0.1:18765") workspace(ws) user(u) password(p) timeout(5)
susoassign setup
assert r(connected) == "1"

susoassign questionnaires
assert r(N) == 1

clear
input str6 key str8 resp str5 hhid
"K0" "int1"  "A"
"K1" "bad"   "B"
"K2" "plain" "C"
"K3" "flaky" "D"
"K4" "down"  "E"
"K5" "slow"  "F"
"K6" "int1"  "G"
end
* questionnaire document downloaded from the (mock) server
susoassign create using "a.zip", qid(11112222-3333-4444-5555-555555555555) version(3) responsible(resp) id(key)
susoassign upload using "a.zip", qid(11112222-3333-4444-5555-555555555555) version(3) parallel(3)
assert r(created) == 3 & r(rejected) == 2 & r(failed) == 1 & r(unknown) == 1
use a_log, clear
assert _N == 7
assert status == "created" if inlist(key, "K0", "K3", "K6")
assert assignment_id == 7 if key == "K3"
assert strpos(message, "PL0026: User not found") if key == "K1"
assert status == "unknown" if key == "K5"

* a second run without resume/replace is refused (protects against duplicates)
capture noisily susoassign upload using "a.zip", qid(11112222333344445555555555555555) version(3)
assert _rc == 602

* resume: created + unknown are skipped, the 3 others are sent again
susoassign upload using "a.zip", qid(11112222333344445555555555555555) version(3) resume
assert r(created) == 0 & r(rejected) == 2 & r(failed) == 1
use a_log, clear
assert _N == 7
assert status == "created" if inlist(key, "K0", "K3", "K6")
assert status == "unknown" if key == "K5"

* retryunknown sends K5 again (4 requests: K1 K2 K4 K5); the mock is always slow, so it stays unknown
susoassign upload using "a.zip", qid(11112222333344445555555555555555) version(3) resume retryunknown
use a_log, clear
assert status == "unknown" if key == "K5"
count if status == "created"
assert r(N) == 3

susoassign archive using "a_log.dta"
assert r(archived) == 3
susoassign archive, ids(5 6)
assert r(archived) == 2

di as res _n "Stata/mock integration: PASSED"
cd "`root'"
