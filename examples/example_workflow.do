* example_workflow.do - preload wave 2 of a household panel from the wave 1 export
*
* Replace the names in CAPITALS with yours. The questionnaire is assumed to have:
*   cover:   hhid (text, identifying), region (single-select), gps (GPS)
*   members: a text list (protected) that triggers the roster "member" with age, sex
* Variable names follow the Survey Solutions export, which is also the preload format.

version 16
clear all

*-------------------------------------------------------------- 1. connect
* Keep the password out of the do-file: set it once in the environment, e.g.
*   Windows:  setx SUSO_PASSWORD "..."      macOS/Linux: export SUSO_PASSWORD=...
susoassign setup, server("https://YOUR.mysurvey.solutions") workspace("YOUR_WORKSPACE") user("YOUR_API_USER")
susoassign questionnaires                    // copy the GUID and version from here
local qid "PASTE-QUESTIONNAIRE-GUID"
local ver 1

*-------------------------------------------------------------- 2. main level: one row per assignment
use "wave1/HOUSEHOLD.dta", clear             // main file of the wave 1 Stata export
keep interview__id hhid region gps__Latitude gps__Longitude members__*
merge 1:1 hhid using "plan.dta", keepusing(interviewer) assert(match) nogenerate   // who visits whom
tempfile main
save `main'

*-------------------------------------------------------------- 3. roster: members of each household
use "wave1/member.dta", clear                // interview__id member__id age sex ...
keep interview__id member__id age sex
replace age = age + 1                        // one year later
save "member_w2.dta", replace

*-------------------------------------------------------------- 4. create: validate and build the zip
use `main', clear
susoassign create using "wave2.zip", qid(`qid') version(`ver') responsible(interviewer) ///
    id(interview__id) quantity(1) protect(members) roster(member "member_w2.dta") replace
* errors are listed (and saved with issues(file.dta)); nothing is written until they are fixed.
* wave2.zip can also be uploaded by hand in HQ: Survey Setup > Questionnaires > Upload assignments.

*-------------------------------------------------------------- 5. upload
susoassign upload using "wave2.zip", qid(`qid') version(`ver') dryrun requests("wave2_requests.dta")
susoassign upload using "wave2.zip", qid(`qid') version(`ver') parallel(4)
* after a failure or Break, run the same command with -resume-.

*-------------------------------------------------------------- 6. assignment ids back into the data
use "wave2_log.dta", clear
tab status
list key message if status != "created", noobs
rename key interview__id
merge 1:1 interview__id using `main', nogenerate

*-------------------------------------------------------------- undo (if needed)
* susoassign archive using "wave2_log.dta"
