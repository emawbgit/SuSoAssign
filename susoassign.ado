*! version 0.1.0  17nov2026  Emanuele Clemente
*! Create and upload Survey Solutions assignments (prefilled and protected answers) from Stata.
*! https://github.com/emawbgit/SuSoAssign  -  MIT License

program susoassign, rclass
    version 16
    gettoken sub 0 : 0, parse(" ,")
    local sub = strlower(`"`sub'"')
    if inlist(`"`sub'"', "setup", "connect")            _sa_setup `0'
    else if inlist(`"`sub'"', "questionnaires", "qx")   _sa_questionnaires `0'
    else if `"`sub'"' == "create"                       _sa_create `0'
    else if `"`sub'"' == "upload"                       _sa_upload `0'
    else if `"`sub'"' == "archive"                      _sa_archive `0'
    else {
        di as err "susoassign: unknown subcommand {bf:`sub'}"
        di as err "  use: {bf:setup}, {bf:questionnaires}, {bf:create}, {bf:upload}, {bf:archive}  (see {help susoassign})"
        exit 198
    }
    return add
end

*------------------------------------------------------------------------- Java bridge
program _sa_java
    * _sa_java method "arg1" "arg2" ...   (sets the caller's locals through c_local)
    gettoken method 0 : 0
    capture findfile susoassign.jar
    if _rc {
        di as err "susoassign.jar not found on the adopath: reinstall with"
        di as err `"  {stata net install susoassign, from("https://raw.githubusercontent.com/emawbgit/SuSoAssign/main/") replace}"'
        exit 601
    }
    local outs base created rejected failed unknown skipped notsent archived n n_assignments ///
        n_rosterrows n_errors n_warnings protected connected conn_user
    foreach o of local outs {
        local `o'
    }
    capture noisily javacall org.emawbgit.susoassign.Bridge `method', jars(susoassign.jar) args(`0')
    local rc = _rc
    foreach o of local outs {
        c_local `o' `"``o''"'
    }
    if `rc' == 5100 | `rc' == 5101 {
        di as err "Stata could not start Java. susoassign needs Stata 16 or newer with its bundled Java (see {help java})."
    }
    if `rc' exit `rc'
end

*------------------------------------------------------------------------- setup
program _sa_setup, rclass
    syntax [, SERVer(string) USER(string) WORKspace(string) PASSword(string) TIMEout(integer 120)]
    if `"`server'`user'"' == "" {
        _sa_java status
        if "`connected'" == "1" di as txt "susoassign: connected to " as res "`base'" as txt " as " as res "`conn_user'"
        else di as txt "susoassign: not connected; run {bf:susoassign setup, server() workspace() user()}"
        return local connected "`connected'"
        exit
    }
    if `"`server'"' == "" | `"`user'"' == "" {
        di as err "setup needs server() and user() (and workspace() unless it is {it:primary})"
        exit 198
    }
    if `"`workspace'"' == "" local workspace primary
    * the password never goes into a Stata macro that outlives this program
    _sa_java setup `"`server'"' `"`workspace'"' `"`user'"' `"`password'"' "`timeout'"
    global SUSOASSIGN_SERVER    `"`server'"'
    global SUSOASSIGN_WORKSPACE `"`workspace'"'
    di as txt "susoassign: connected to " as res "`base'" as txt " as " as res `"`user'"'
    return local base "`base'"
    return local workspace `"`workspace'"'
end

*------------------------------------------------------------------------- questionnaires
program _sa_questionnaires, rclass
    syntax [, CLEAR]
    tempfile f
    _sa_java questionnaires `"`f'"'
    if "`clear'" == "" preserve
    quietly import delimited using `"`f'"', delimiter(tab) varnames(1) stringcols(_all) ///
        bindquote(nobind) encoding("utf-8") clear
    if _N == 0 {
        di as txt "susoassign: no questionnaires imported in this workspace"
        return scalar N = 0
        exit
    }
    quietly destring version, replace
    sort title version
    list title variable qid version, noobs clean abbreviate(24)
    return scalar N = _N
    if "`clear'" == "" restore
end

*------------------------------------------------------------------------- create
program _sa_create, rclass
    syntax using/, RESPonsible(varname) [QID(string) VERsion(string) QXfile(string) ///
        ID(varname) QUANTity(string) PROTect(string) ROSTer(string asis)       ///
        WEBmode(varname numeric) EMAIL(varname string) PASSword(varname string) ///
        COMMent(varname string) AUDio(varname numeric) TARGETarea(varname string) ///
        AASCOPE(varname string) ISSues(string) REPLACE]

    if _N == 0 {
        di as err "no observations: load the main (one row per assignment) dataset first"
        exit 2000
    }
    _sa_qx `"`qid'"' `"`version'"' `"`qxfile'"'
    if !regexm(lower(`"`using'"'), "\.zip$") local using `"`using'.zip"'
    if "`replace'" == "" confirm new file `"`using'"'
    _sa_abs `"`using'"'
    local using `"`abs'"'
    if `"`roster'"' != "" & "`id'" == "" {
        di as err "roster() needs id(): the variable that links roster files to the main data"
        exit 198
    }

    tempfile tf
    local dir `"`tf'_d"'
    quietly mkdir `"`dir'"'
    capture noisily {
        preserve
        * ---- main level: service columns + answers
        _sa_key `id'
        local svc responsible webmode email password comment audio targetarea aascope
        local col_responsible _responsible
        local col_webmode     _webmode
        local col_email       _email
        local col_password    _password
        local col_comment     _comment
        local col_audio       _record_audio
        local col_targetarea  _target_area
        local col_aascope     _aascope
        foreach s of local svc {
            if "``s''" == "" continue
            if "``s''" == "`col_`s''" continue
            capture confirm new variable `col_`s''
            if _rc {
                di as err "variable `col_`s'' already exists; drop it or pass it as `s'(`col_`s'')"
                exit 110
            }
            rename ``s'' `col_`s''
        }
        if `"`quantity'"' != "" {
            capture confirm number `quantity'
            if !_rc {
                capture drop _quantity
                gen long _quantity = `quantity'
            }
            else {
                confirm numeric variable `quantity'
                if "`quantity'" != "_quantity" {
                    clonevar _quantity = `quantity'
                    drop `quantity'
                }
            }
        }
        * id() stays: it is often also a preloaded question (columns that are not questions are dropped later)
        _sa_export `"`dir'/main__assignments.tab"'

        * ---- roster files: roster(name "file.dta" [name "file.dta" ...])
        local rnames
        while `"`roster'"' != "" {
            gettoken rname roster : roster
            gettoken rfile roster : roster
            if `"`rfile'"' == "" {
                di as err `"roster() takes pairs of roster name and .dta file, e.g. roster(member "members.dta" plot "plots.dta")"'
                exit 198
            }
            use `"`rfile'"', clear
            confirm variable `id'
            _sa_key `id'
            * in roster files the id is only the link to the main row
            if "`id'" != "interview__id" drop `id'
            _sa_export `"`dir'/`rname'.tab"'
            local rnames `rnames' `rname'
        }
        restore
    }
    local rc = _rc
    if `rc' {
        _sa_rmdir `"`dir'"'
        exit `rc'
    }

    tempfile issf
    local protect = subinstr(`"`protect'"', ",", " ", .)
    capture noisily _sa_java create `"`qxfile_'"' `"`qid'"' `"`version'"' `"`dir'"' `"`using'"' `"`protect'"' `"`issf'"'
    local rc = _rc
    _sa_rmdir `"`dir'"'
    if `rc' exit `rc'

    _sa_issues `"`issf'"' `"`issues'"' `n_errors' `n_warnings'
    return scalar N = `n_assignments'
    return scalar N_roster = `n_rosterrows'
    return scalar errors = `n_errors'
    return scalar warnings = `n_warnings'
    return local qid `"`qid'"'
    return local version `"`version'"'
    return local protected `"`protected'"'
    if `n_errors' > 0 {
        di as err "susoassign: `n_errors' error(s); no file written. Fix the data and run again."
        exit 459
    }
    return local using `"`using'"'
    di as txt "susoassign: " as res "`n_assignments'" as txt " assignment(s), " as res "`n_rosterrows'" ///
        as txt " roster row(s)" cond("`protected'" != "", ", protected: " + "`protected'", "")
    di as txt `"  written to {browse "`using'"}"'
    if `"`qid'"' != "" di as txt `"  next: {bf:susoassign upload using "`using'", qid(`qid') version(`version')}"'
end

*------------------------------------------------------------------------- upload
program _sa_upload, rclass
    syntax using/, QID(string) VERsion(string) [QXfile(string) PROTect(string) ///
        PARallel(integer 4) DRYrun RESUME RETRYUNKnown LOG(string) REQuests(string) ///
        ISSues(string) CLEAR REPLACE]

    _sa_qx `"`qid'"' `"`version'"' `"`qxfile'"'
    if `parallel' < 1 | `parallel' > 32 {
        di as err "parallel() must be between 1 and 32"
        exit 198
    }
    capture confirm file `"`using'"'
    if _rc {
        mata: st_local("isdir", strofreal(direxists(st_local("using"))))
        if "`isdir'" != "1" confirm file `"`using'"'
    }
    _sa_abs `"`using'"'
    local using `"`abs'"'
    if `"`log'"' == "" {
        local log = regexr(`"`using'"', "\.[zZ][iI][pP]$", "") + "_log.dta"
    }
    if !regexm(lower(`"`log'"'), "\.dta$") local log `"`log'.dta"'
    if "`dryrun'" == "" & "`resume'" == "" & "`replace'" == "" {
        capture confirm new file `"`log'"'
        if _rc {
            di as err `"upload log `log' exists: use {bf:resume} to continue that upload, or {bf:replace} to start over"'
            exit 602
        }
    }
    if "`dryrun'" == "" {
        _sa_java status
        if "`connected'" != "1" {
            di as err "not connected: run {bf:susoassign setup} first"
            exit 198
        }
    }

    * keys already done (resume)
    tempfile skipf issf reqf resf
    if "`resume'" != "" {
        capture confirm file `"`log'"'
        if !_rc {
            preserve
            quietly use `"`log'"', clear
            quietly keep if status == "created" | status == "skipped" | ("`retryunknown'" == "" & status == "unknown")
            quietly keep key
            quietly export delimited using `"`skipf'"', novarnames delimiter(tab) replace
            local nskip = _N
            restore
            di as txt "susoassign: resuming; " as res "`nskip'" as txt " assignment(s) already sent are skipped"
        }
    }
    local protect = subinstr(`"`protect'"', ",", " ", .)
    local dry = cond("`dryrun'" != "", "1", "0")
    _sa_java upload `"`qxfile_'"' `"`qid'"' `"`version'"' `"`using'"' `"`protect'"' `"`issf'"' ///
        `"`reqf'"' `"`resf'"' `"`skipf'"' "`parallel'" "`dry'"

    _sa_issues `"`issf'"' `"`issues'"' `n_errors' `n_warnings'
    return scalar N = `n_assignments'
    return scalar errors = `n_errors'
    if `n_errors' > 0 {
        di as err "susoassign: `n_errors' error(s) in `using'; nothing was sent."
        exit 459
    }
    if `"`requests'"' != "" | "`dryrun'" != "" {
        preserve
        quietly import delimited using `"`reqf'"', delimiter(tab) varnames(1) stringcols(_all) ///
            bindquote(nobind) encoding("utf-8") clear
        if `"`requests'"' != "" quietly save `"`requests'"', replace
        if "`dryrun'" != "" {
            di as txt "susoassign: dry run, " as res "`n_assignments'" as txt " request(s) built, nothing sent. First request:"
            if _N > 0 di as res json[1]
            if `"`requests'"' != "" di as txt `"  all requests saved in {browse "`requests'"}"'
        }
        restore
    }
    if "`dryrun'" != "" exit

    * results: merge with the previous log when resuming
    preserve
    quietly import delimited using `"`resf'"', delimiter(tab) varnames(1) stringcols(_all) ///
        bindquote(nobind) encoding("utf-8") clear
    quietly destring http assignment_id, replace
    capture confirm file `"`log'"'
    if "`resume'" != "" & !_rc {
        * rows skipped now keep their earlier outcome (same filter as the skip list: keys are disjoint)
        tempfile new
        quietly drop if status == "skipped"
        quietly save `new'
        quietly use `"`log'"', clear
        quietly keep if status == "created" | status == "skipped" | ("`retryunknown'" == "" & status == "unknown")
        quietly append using `new'
    }
    label variable key           "Assignment key (interview__id in the upload file)"
    label variable status        "created / rejected / failed / unknown / skipped / notsent"
    label variable http          "HTTP status"
    label variable assignment_id "Survey Solutions assignment id"
    label variable web_link      "Web interview link (web mode)"
    label variable message       "Server or connection message"
    char _dta[susoassign_qid] `"`qid'$`version'"'
    char _dta[susoassign_file] `"`using'"'
    quietly save `"`log'"', replace
    if "`clear'" == "" restore
    else restore, not

    foreach s in created rejected failed unknown notsent {
        return scalar `s' = ``s''
    }
    return local log `"`log'"'
    di as txt "susoassign: " as res "`created'" as txt " created, " as res "`rejected'" as txt " rejected, " ///
        as res "`failed'" as txt " failed, " as res "`unknown'" as txt " unknown" ///
        cond(`notsent' > 0, ", `notsent' not sent (Break)", "") cond(0`skipped' > 0, ", `skipped' skipped (resume)", "")
    di as txt `"  log: {browse "`log'"}"'
    if `rejected' + `failed' > 0 {
        di as txt "  reasons are in variable {bf:message} of the log"
    }
    if `unknown' > 0 {
        di as err "  `unknown' request(s) got no answer: they may have been created. Check HQ before re-sending"
        di as err "  (resume skips them; add {bf:retryunknown} to send them again)."
    }
end

*------------------------------------------------------------------------- archive
program _sa_archive, rclass
    syntax [using/] [, IDs(numlist integer >0)]
    if `"`using'"' != "" {
        preserve
        quietly use `"`using'"', clear
        quietly keep if status == "created" & !missing(assignment_id)
        quietly levelsof assignment_id, local(fromlog)
        restore
        local ids `ids' `fromlog'
    }
    if "`ids'" == "" {
        di as err "nothing to archive: give ids() or an upload log"
        exit 198
    }
    _sa_java status
    if "`connected'" != "1" {
        di as err "not connected: run {bf:susoassign setup} first"
        exit 198
    }
    tempfile resf
    local ids : list uniq ids
    _sa_java archive "`ids'" `"`resf'"'
    di as txt "susoassign: " as res "`archived'" as txt " of " as res `: word count `ids'' as txt " assignment(s) archived"
    if `archived' < `: word count `ids'' {
        preserve
        quietly import delimited using `"`resf'"', delimiter(tab) varnames(1) stringcols(_all) bindquote(nobind) encoding("utf-8") clear
        list key message if status != "archived", noobs
        restore
    }
    return scalar archived = `archived'
end

*------------------------------------------------------------------------- helpers
program _sa_qx
    * validate qid/version/qxfile and hand the absolute qxfile back as qxfile_
    args qid version qxfile
    if `"`qxfile'"' != "" {
        confirm file `"`qxfile'"'
        mata: st_local("abs", pathresolve(pwd(), st_local("qxfile")))
        c_local qxfile_ `"`abs'"'
    }
    else c_local qxfile_ ""
    if `"`qid'"' == "" & `"`qxfile'"' == "" {
        di as err "give qid() and version() (see {bf:susoassign questionnaires}), or qxfile()"
        exit 198
    }
    if `"`qid'"' != "" {
        local g = subinstr(subinstr(subinstr(lower(`"`qid'"'), "-", "", .), "{", "", .), "}", "", .)
        if !ustrregexm("`g'", "^[0-9a-f]{32}$") {
            di as err `"qid() must be the questionnaire GUID, e.g. 4ce37b21-bdfc-440b-aae0-3353b61c4a11; got `qid'"'
            exit 198
        }
        capture confirm integer number `version'
        if _rc | `"`version'"' == "" {
            di as err "version() must be the questionnaire version number on HQ"
            exit 198
        }
    }
end

program _sa_abs
    * absolute path: Java does not follow Stata's -cd-
    args path
    mata: st_local("p", pathresolve(pwd(), st_local("path")))
    c_local abs `"`p'"'
end

program _sa_key
    * create interview__id (string) from the id variable, or from row numbers
    args id
    if "`id'" == "" {
        capture drop interview__id
        gen str20 interview__id = string(_n, "%12.0f")
        exit
    }
    capture confirm string variable `id'
    if _rc gen strL __sa_key = string(`id', "%21.0g")
    else gen strL __sa_key = strtrim(`id')
    if "`id'" == "interview__id" drop interview__id
    capture drop interview__id
    rename __sa_key interview__id
    quietly count if interview__id == "" | interview__id == "."
    if r(N) {
        di as err "id(): `r(N)' observation(s) have a missing key"
        exit 459
    }
    order interview__id
end

program _sa_export
    * dates to ISO text, then a tab file with codes (not value labels)
    args file
    foreach v of varlist _all {
        capture confirm numeric variable `v'
        if _rc continue
        local f : format `v'
        if regexm("`f'", "^%-?td") {
            quietly gen str10 __sa_d = cond(missing(`v'), "", string(`v', "%tdCCYY-NN-DD"))
        }
        else if regexm("`f'", "^%-?t[cC]") {
            quietly gen str19 __sa_d = cond(missing(`v'), "", string(`v', "%tcCCYY-NN-DD!THH:MM:SS"))
        }
        else continue
        order __sa_d, after(`v')
        drop `v'
        rename __sa_d `v'
    }
    quietly export delimited using `"`file'"', delimiter(tab) nolabel replace
end

program _sa_rmdir
    args dir
    local files : dir `"`dir'"' files "*"
    foreach f of local files {
        capture erase `"`dir'/`f'"'
    }
    capture rmdir `"`dir'"'
end

program _sa_issues
    * show validation issues; optionally save them
    args file save nerr nwarn
    if `nerr' + `nwarn' == 0 {
        if `"`save'"' != "" di as txt "  no issues"
        exit
    }
    preserve
    quietly import delimited using `"`file'"', delimiter(tab) varnames(1) stringcols(_all) ///
        bindquote(nobind) encoding("utf-8") clear
    sort severity file key, stable
    if `"`save'"' != "" quietly save `"`save'"', replace
    local shown = min(_N, 30)
    di as txt _n "susoassign: " as err "`nerr' error(s)" as txt ", `nwarn' warning(s)" _c
    if _N > `shown' di as txt " (first `shown' shown" cond(`"`save'"' != "", "; all in `save')", "; add issues(file) to save all)")
    else di
    forvalues i = 1/`shown' {
        local where = file[`i'] + cond(column[`i'] != "", "/" + column[`i'], "") + cond(key[`i'] != "", " [" + key[`i'] + "]", "")
        if severity[`i'] == "error" di as err "  `where': " message[`i']
        else di as txt "  `where': " message[`i']
    }
    restore
end
