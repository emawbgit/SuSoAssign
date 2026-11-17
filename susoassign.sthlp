{smcl}
{* *! version 0.1.0  17nov2026}{...}
{vieweralsosee "java" "help java"}{...}
{viewerjumpto "Syntax" "susoassign##syntax"}{...}
{viewerjumpto "Description" "susoassign##description"}{...}
{viewerjumpto "Preparing the data" "susoassign##data"}{...}
{viewerjumpto "Options" "susoassign##options"}{...}
{viewerjumpto "Examples" "susoassign##examples"}{...}
{viewerjumpto "Stored results" "susoassign##results"}{...}
{viewerjumpto "Remarks" "susoassign##remarks"}{...}
{title:Title}

{phang}
{bf:susoassign} {hline 2} Create and upload Survey Solutions assignments, with prefilled
and protected answers, from Stata


{marker syntax}{...}
{title:Syntax}

{pstd}Connect once per session (an {it:API user} of the workspace):{p_end}
{p 8 16 2}
{cmd:susoassign setup,} {opt serv:er(url)} {opt user(name)} [{opt work:space(name)} {opt pass:word(string)} {opt time:out(#)}]

{pstd}List the questionnaires of the workspace (GUID and version):{p_end}
{p 8 16 2}
{cmd:susoassign questionnaires} [{cmd:,} {opt clear}]

{pstd}Build and validate assignments from the data in memory (one row per assignment):{p_end}
{p 8 16 2}
{cmd:susoassign create using} {it:file.zip}{cmd:,} {opt resp:onsible(varname)}
{c -(}{opt qid(guid)} {opt ver:sion(#)} | {opt qx:file(document.json)}{c )-}
[{it:create_options}]

{pstd}Send them to Headquarters:{p_end}
{p 8 16 2}
{cmd:susoassign upload using} {it:file.zip}|{it:folder}{cmd:,} {opt qid(guid)} {opt ver:sion(#)} [{it:upload_options}]

{pstd}Archive assignments (undo an upload):{p_end}
{p 8 16 2}
{cmd:susoassign archive} [{cmd:using} {it:upload_log.dta}] [{cmd:,} {opt ids(numlist)}]

{synoptset 26 tabbed}{...}
{synopthdr:create_options}
{synoptline}
{synopt:{opt id(varname)}}assignment key; required with {opt roster()}; also preloaded if it is a question{p_end}
{synopt:{opt quant:ity(varname|#)}}interviews per assignment; -1 = unlimited; default 1{p_end}
{synopt:{opt prot:ect(namelist)}}questions whose preloaded answers interviewers cannot change{p_end}
{synopt:{opt rost:er(name file ...)}}roster files: pairs of roster variable and {it:.dta} file{p_end}
{synopt:{opt web:mode(varname)}}1 = web (CAWI) assignment, 0 = CAPI{p_end}
{synopt:{opt email(varname)}}respondent email (web mode){p_end}
{synopt:{opt pass:word(varname)}}web password: 6+ upper-case letters/digits, or {cmd:?} to generate{p_end}
{synopt:{opt comm:ent(varname)}}comment shown to the interviewer{p_end}
{synopt:{opt aud:io(varname)}}1/0: audio audit on/off (default: questionnaire setting){p_end}
{synopt:{opt target:area(varname)}}target area (map name){p_end}
{synopt:{opt aascope(varname)}}audio-audit scope: comma-separated section/roster variables{p_end}
{synopt:{opt iss:ues(file.dta)}}save all validation errors and warnings{p_end}
{synopt:{opt replace}}overwrite {it:file.zip}{p_end}
{synoptline}

{synoptset 26 tabbed}{...}
{synopthdr:upload_options}
{synoptline}
{synopt:{opt par:allel(#)}}simultaneous requests, 1-32; default 4{p_end}
{synopt:{opt dry:run}}build and check every request, send nothing{p_end}
{synopt:{opt resume}}continue an upload: skip what the log shows as created{p_end}
{synopt:{opt retryunk:nown}}with {opt resume}: also re-send requests that got no answer{p_end}
{synopt:{opt log(file.dta)}}upload log; default {it:file}{cmd:_log.dta} next to {it:file.zip}{p_end}
{synopt:{opt req:uests(file.dta)}}save the JSON body of every request{p_end}
{synopt:{opt prot:ect(namelist)}}extra protected questions (adds to {it:protected__variables.tab}){p_end}
{synopt:{opt qx:file(document.json)}}validate against a local questionnaire document{p_end}
{synopt:{opt iss:ues(file.dta)}}save validation issues{p_end}
{synopt:{opt clear}}load the upload log into memory{p_end}
{synopt:{opt replace}}start a new log even if one exists{p_end}
{synoptline}


{marker description}{...}
{title:Description}

{pstd}
{cmd:susoassign} turns a Stata dataset into Survey Solutions assignments and creates them on
Headquarters (HQ) through the HQ REST API, including answers {it:preloaded} (prefilled) on the
cover page, in hidden questions and inside rosters, and answers {it:protected} (locked) so that
interviewers can only add to them.

{pstd}
{cmd:create} validates the data against the questionnaire and writes a zip in Survey Solutions' own
preload layout (one {it:.tab} file per level and {it:protected__variables.tab}). That zip can be
uploaded by {cmd:upload}, or by hand in HQ ({it:Survey Setup > Questionnaires > Upload assignments}).

{pstd}
{cmd:upload} reads such a zip (or folder), turns each assignment into a
{cmd:POST /api/v1/assignments} request and sends them in parallel. Every row ends up in an upload
log with its outcome and the new assignment id.

{pstd}
Only an {it:API user} (or an administrator) can create assignments through the API. An HQ
administrator creates API users under {it:Teams and Roles > API users} and adds them to the workspace.


{marker data}{...}
{title:Preparing the data}

{pstd}
Name variables as in a Survey Solutions export; that is also the preload format, so last round's
export can be preloaded with little work.

{p2colset 5 30 32 2}{...}
{p2col:{it:Question type}}{it:Variables and values}{p_end}
{p2line}
{p2col:single-select}{it:var}: category code (value labels are ignored){p_end}
{p2col:numeric}{it:var}; integers for integer questions{p_end}
{p2col:text, barcode}{it:var} (string){p_end}
{p2col:date}{it:var} with a {cmd:%td} or {cmd:%tc} format, or text {it:YYYY-MM-DD}{p_end}
{p2col:GPS}{it:var}{cmd:__Latitude}, {it:var}{cmd:__Longitude} (decimal degrees){p_end}
{p2col:multi-select}{it:var}{cmd:__}{it:code}: 1 = selected, 0 = not; for ordered questions the order 1, 2, ...; negative codes as {it:var}{cmd:__n1}{p_end}
{p2col:yes/no}{it:var}{cmd:__}{it:code}: 1 = yes (or the order), 0 = no, missing = not answered{p_end}
{p2col:text list}{it:var}{cmd:__0}, {it:var}{cmd:__1}, ... (one item per variable){p_end}
{p2line}

{pstd}
Missing values ({cmd:.}, {cmd:.a}-{cmd:.z}, empty strings, -999999999, ##N/A##) are not preloaded.
Pictures, audio, geography, linked questions and calculated variables cannot be preloaded.
Variables that are not questions are dropped with a warning.

{pstd}
{bf:Rosters.} Each roster level is a separate {it:.dta} file with the {opt id()} variable, one
{it:roster}{cmd:__id} variable per level (outer levels first, e.g. {cmd:plot__id season__id}) and
the roster's questions. Row ids follow Survey Solutions:

{p2colset 9 30 32 2}{...}
{p2col:numeric trigger}0, 1, ..., n-1{p_end}
{p2col:list trigger}1, 2, ... = position of the item in the list, or the item codes in an export's {it:var}{cmd:__0c}, {it:var}{cmd:__1c}, ... columns{p_end}
{p2col:multi-select trigger}the selected codes{p_end}
{p2col:fixed roster}the item codes set in Designer{p_end}

{pstd}
A list trigger can be given in the parent data ({it:members}{cmd:__0}, ...) or as a text column
named like the list question inside the roster file. Rosters sharing a trigger form one level:
give one file, named after any of them.

{pstd}
{bf:Protected answers} ({opt protect()}): numeric, text-list and multi-select (including yes/no)
questions only, by root name ({cmd:members}, not {cmd:members__0}). Interviewers can then
only raise a protected number and only add list items or selections; empty answers are not protected.

{pstd}
{bf:Web mode}: {opt email()} requires web mode and quantity 1; passwords must be unique
when quantity is 1; a web assignment with quantity 1 needs an email or a password.


{marker options}{...}
{title:Options}

{dlgtab:setup}

{phang}{opt server(url)} HQ address, e.g. {cmd:https://demo.mysurvey.solutions}. Must be {cmd:https}.

{phang}{opt workspace(name)} the workspace {it:name} (not its display name); default {cmd:primary}.

{phang}{opt user(name)}, {opt password(string)} API user credentials. Leave {opt password()} out to use the
environment variable {cmd:SUSO_PASSWORD}, which keeps the password out of do-files and logs.
The password is kept in memory only for the Stata session; it is not stored in any macro.

{phang}{opt timeout(#)} seconds to wait for each answer; default 120.

{dlgtab:create / upload}

{phang}{opt qid(guid)} and {opt version(#)} identify the questionnaire on HQ (see {cmd:susoassign questionnaires}).
{cmd:create} downloads its document to validate the data; {opt qxfile()} uses a local document instead.

{phang}{opt parallel(#)} requests in flight at once. 4 is gentle on the server; raise it for large uploads.

{phang}{opt resume} re-runs an upload safely: rows logged as {it:created} are skipped, and so are rows logged
as {it:unknown} (no answer arrived, so the assignment may exist) unless {opt retryunknown} is given.
Check HQ before using {opt retryunknown}.

{phang}Press {hi:Break} during an upload to stop starting new requests; those already sent finish and are logged.


{marker examples}{...}
{title:Examples}

{pstd}Connect (password from the environment variable SUSO_PASSWORD) and find the questionnaire:{p_end}
{phang2}{cmd:. susoassign setup, server("https://demo.mysurvey.solutions") workspace(myws) user(apiuser)}{p_end}
{phang2}{cmd:. susoassign questionnaires}{p_end}

{pstd}Households with a preloaded member list (locked) and member ages:{p_end}
{phang2}{cmd:. use households, clear}{space 8}// hhid region members__0 members__1 ... interviewer{p_end}
{phang2}{cmd:. susoassign create using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3) ///}{p_end}
{phang3}{cmd:responsible(interviewer) id(hhid) protect(members) roster(member "members.dta")}{p_end}
{phang2}{cmd:. susoassign upload using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3) dryrun}{p_end}
{phang2}{cmd:. susoassign upload using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3)}{p_end}

{pstd}Merge the new assignment ids back:{p_end}
{phang2}{cmd:. use wave2_log, clear}{p_end}
{phang2}{cmd:. rename key hhid}{p_end}
{phang2}{cmd:. merge 1:1 hhid using households}{p_end}

{pstd}Continue after a network failure, then undo everything:{p_end}
{phang2}{cmd:. susoassign upload using "wave2.zip", qid(4ce37b21bdfc440baae03353b61c4a11) version(3) resume}{p_end}
{phang2}{cmd:. susoassign archive using "wave2_log.dta"}{p_end}


{marker results}{...}
{title:Stored results}

{pstd}{cmd:susoassign create} stores in {cmd:r()}:{p_end}
{synoptset 18 tabbed}{...}
{synopt:{cmd:r(N)}}assignments{p_end}
{synopt:{cmd:r(N_roster)}}roster rows{p_end}
{synopt:{cmd:r(errors)}, {cmd:r(warnings)}}validation issues{p_end}
{synopt:{cmd:r(using)}}the zip written{p_end}
{synopt:{cmd:r(protected)}}protected questions{p_end}

{pstd}{cmd:susoassign upload} stores {cmd:r(N)}, {cmd:r(created)}, {cmd:r(rejected)} (refused by HQ, see the
message), {cmd:r(failed)} (server or network error), {cmd:r(unknown)}, {cmd:r(notsent)} and {cmd:r(log)}.
The log has one row per assignment: {cmd:key status http assignment_id web_link message}.{p_end}


{marker remarks}{...}
{title:Remarks}

{pstd}
{cmd:susoassign} needs Stata 16 or newer: it runs in Stata's bundled Java ({help java}) and needs no R,
Python or other software. It works the same on Windows, macOS and Linux.

{pstd}
Requests that get no answer before {opt timeout()} are logged as {it:unknown} and never re-sent
automatically: the server may have created the assignment, and a blind retry could duplicate it.
Requests that certainly did not reach the server (connection refused, HTTP 429/502/503/504) are retried
up to three times.

{pstd}
Survey Solutions validates every assignment again; its messages (with PL error codes) are in the log.

{pstd}
Source, bug reports and documentation: {browse "https://github.com/emawbgit/SuSoAssign"}.


{title:Author}

{pstd}Emanuele Clemente{break}
{browse "https://github.com/emawbgit/SuSoAssign/issues":github.com/emawbgit/SuSoAssign/issues}


{title:Also see}

{pstd}
Survey Solutions: {browse "https://docs.mysurvey.solutions/headquarters/preloading/"}
{p_end}
