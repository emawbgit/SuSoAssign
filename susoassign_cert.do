* susoassign_cert.do - certification script for susoassign
*
* Run from the repository root:   do susoassign_cert.do
* Part 1 (always): offline checks against tests/fixtures/test_qx.json.
* Part 2 (only when the environment variables below are set): live checks on a
* Survey Solutions server. Test assignments are archived at the end.
*   SUSO_SERVER  SUSO_WORKSPACE  SUSO_USER  SUSO_PASSWORD  SUSO_INTERVIEWER
*   SUSO_QID  SUSO_QVERSION    (any questionnaire imported in that workspace)

version 16
clear all
set more off
local root = c(pwd)
capture confirm file "`root'/susoassign.ado"
if _rc {
    di as err "run this do-file from the SuSoAssign folder"
    exit 601
}
adopath ++ "`root'"
discard
local qx "`root'/tests/fixtures/test_qx.json"
tempfile t
local work "`t'_cert"
mkdir "`work'"
cd "`work'"

*---------------------------------------------------------------- data
* rosters
clear
input str5 hhid byte member__id int age byte sex
"H001" 1 41 1
"H001" 2 39 2
"H002" 1 70 2
end
save member, replace

clear
input str5 hhid byte plot__id double plot_area
"H001" 0 1.25
"H001" 1 0.5
end
save plot, replace

* main: one row per assignment
clear
input str5 hhid str10 resp byte region int hhsize str12 d double(lat lon) byte(assets__1 assets__2 assets__3) str20 members__0 str20 members__1 byte n_plots double weight
"H001" "int1" 1 4 "2024-05-01" -1.2921 36.8219 1 0 1 "Ann"   "Bob"  2 1.5
"H002" "int1" 2 1 "2024-05-02" 0.3476 32.5825 0 1 0 "Zoë"   ""     .  2
"H003" "sup1" 1 3 ""           .       .      . . . ""      ""     .  1
end
gen visit_date = date(d, "YMD")
format visit_date %td
drop d
rename (lat lon) (loc__Latitude loc__Longitude)
label define region 1 "North" 2 "South"
label values region region
gen double income = .5 in 1

*---------------------------------------------------------------- 1. create (offline)
susoassign create using "assign.zip", qxfile("`qx'") responsible(resp) id(hhid) ///
    quantity(1) protect(members assets) roster(member "member.dta" plot "plot.dta")
assert r(N) == 3
assert r(N_roster) == 5
assert r(errors) == 0
assert r(warnings) == 1                    // weight is not a question: dropped
confirm file "assign.zip"

*---------------------------------------------------------------- 2. upload, dryrun (offline)
susoassign upload using "assign.zip", qid(11111111-2222-3333-4444-555555555555) version(3) ///
    qxfile("`qx'") dryrun requests(req.dta)
assert r(N) == 3
preserve
use req, clear
assert _N == 3
assert strpos(json[1], `""Responsible":"int1","Quantity":1,"QuestionnaireId":"11111111222233334444555555555555$3""') == 2
assert strpos(json[1], `"{"Variable":"region","Answer":"1"}"') > 0             // codes, not value labels
assert strpos(json[1], `"{"Variable":"visit_date","Answer":"2024-05-01"}"') > 0  // %td -> ISO
assert strpos(json[1], `"{"Variable":"income","Answer":"0.5"}"') > 0
assert strpos(json[1], `"{"Variable":"assets","Answer":"[\"1\",\"3\"]"}"') > 0
assert strpos(json[1], `"{"Variable":"loc","Answer":"-1.2921$36.8219"}"') > 0
assert strpos(json[1], `"{"Identity":"e0000000000000000000000000000001_1","Variable":"age","Answer":"39"}"') > 0
assert strpos(json[1], `"{"Identity":"f0000000000000000000000000000001_1","Variable":"plot_area","Answer":"0.5"}"') > 0
assert strpos(json[1], `""ProtectedVariables":["members","assets"]"') > 0
assert strpos(json[2], `"[\"Zoë\"]"') > 0                                     // UTF-8 kept
assert strpos(json[2], "Identity") > 0
assert strpos(json[3], `""Responsible":"sup1""') > 0
assert strpos(json[3], `"{"Variable":"hhid","Answer":"H003"}"') > 0
assert strpos(json[3], "Identity") == 0 & strpos(json[3], "loc") == 0     // no rosters, empty GPS skipped
restore

*---------------------------------------------------------------- 3. errors are caught before anything is sent
preserve
replace region = 9 in 2
capture noisily susoassign create using "bad.zip", qxfile("`qx'") responsible(resp) id(hhid) replace
assert _rc == 459
capture confirm file "bad.zip"
assert _rc
restore

capture noisily susoassign create using "bad.zip", qxfile("`qx'") responsible(resp) protect(region) replace
assert _rc == 459

preserve
replace resp = "" in 1
capture noisily susoassign create using "bad.zip", qxfile("`qx'") responsible(resp) replace issues(iss.dta)
assert _rc == 459
use iss, clear
assert strpos(message[1], "_responsible") > 0
restore

* roster rows that do not belong to an assignment
preserve
use member, clear
replace hhid = "H999" in 3
save member_bad, replace
restore
capture noisily susoassign create using "bad.zip", qxfile("`qx'") responsible(resp) id(hhid) roster(member "member_bad.dta") replace
assert _rc == 459

*---------------------------------------------------------------- 4. web mode
preserve
keep in 1/2
gen byte web = 1
gen str20 em = "a@example.org" in 1
gen str10 pw = "?"
susoassign create using "web.zip", qxfile("`qx'") responsible(resp) webmode(web) email(em) password(pw) replace
susoassign upload using "web.zip", qid(11111111222233334444555555555555) version(3) qxfile("`qx'") dryrun requests(wreq.dta)
use wreq, clear
assert strpos(json[1], `""Email":"a@example.org","Password":"?","WebMode":true"') > 0
restore

di as res _n "susoassign offline certification: PASSED"

*---------------------------------------------------------------- 5. live (optional)
local server : env SUSO_SERVER
local qid    : env SUSO_QID
if "`server'" == "" | "`qid'" == "" {
    di as txt "live part skipped (set SUSO_SERVER, SUSO_WORKSPACE, SUSO_USER, SUSO_PASSWORD, SUSO_INTERVIEWER, SUSO_QID, SUSO_QVERSION)"
    cd "`root'"
    exit
}
local ws   : env SUSO_WORKSPACE
local user : env SUSO_USER
local int  : env SUSO_INTERVIEWER
local ver  : env SUSO_QVERSION
susoassign setup, server("`server'") workspace("`ws'") user("`user'")
susoassign questionnaires
clear
set obs 3
gen resp = "`int'"
gen key = "C" + string(_n)
susoassign create using "live.zip", qid(`qid') version(`ver') responsible(resp) id(key) quantity(1) replace
susoassign upload using "live.zip", qid(`qid') version(`ver') replace
assert r(created) == 3
* resume: nothing left to send
susoassign upload using "live.zip", qid(`qid') version(`ver') resume
assert r(created) == 0
use live_log, clear
assert _N == 3 & status == "created"
susoassign archive using "live_log.dta"
assert r(archived) == 3
di as res _n "susoassign live certification: PASSED"
cd "`root'"
