package com.studentcommandcenter.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Pure Kotlin, server-rendered system-admin, university registrar and employer pages. */
fun Application.configureAdminWeb(
    auth: AuthService,
    credentials: CredentialService,
    verification: VerificationService,
    chain: BlockchainAnchor
) {
    routing {
        get("/") { call.respondRedirect("/admin") }
        get("/admin/login") {
            val csrf = call.request.cookies["SCC_CSRF"] ?: Security.randomHex(16)
            call.setCookie("SCC_CSRF", csrf, httpOnly = false, maxAge = 3600)
            call.html(loginPage(csrf, null))
        }
        post("/admin/login") {
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) {
                call.respondText(loginPage("", "Session check failed. Refresh and try again."), ContentType.Text.Html, status = io.ktor.http.HttpStatusCode.Forbidden)
                return@post
            }
            try {
                val result = auth.login(form["email"].orEmpty(), form["password"].orEmpty())
                call.setCookie("SCC_SESSION", result.token, httpOnly = true, maxAge = (Config.sessionTtlDays * 86400).toInt())
                call.setCookie("SCC_CSRF", Security.randomHex(16), httpOnly = false, maxAge = (Config.sessionTtlDays * 86400).toInt())
                call.respondRedirect("/admin")
            } catch (e: Exception) {
                val csrf = call.request.cookies["SCC_CSRF"] ?: Security.randomHex(16)
                call.setCookie("SCC_CSRF", csrf, httpOnly = false, maxAge = 3600)
                call.html(loginPage(csrf, e.message ?: "Sign-in failed"))
            }
        }
        post("/admin/logout") {
            val form = call.receiveParameters()
            if (call.checkCsrf(form["csrf"])) auth.logout(call.request.cookies["SCC_SESSION"])
            call.clearCookie("SCC_SESSION")
            call.clearCookie("SCC_CSRF")
            call.respondRedirect("/admin/login")
        }

        get("/admin") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@get }
            val csrf = call.request.cookies["SCC_CSRF"].orEmpty()
            call.html(if (user.role == UserRole.system_admin) systemAdminHome(user, adminOverview(chain), csrf) else registrarHome(user, verification, csrf))
        }

        get("/admin/universities") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@get }
            if (user.role != UserRole.system_admin && user.role != UserRole.registrar) {
                call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@get
            }
            val list = if (user.role == UserRole.registrar) listOfNotNull(user.universityId?.let(::webUniversity)) else webUniversities()
            call.html(universityListPage(user, list, call.request.cookies["SCC_CSRF"].orEmpty()))
        }
        post("/admin/universities") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            if (user.role != UserRole.system_admin) { call.respondText("System administrator access required", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val name = form["name"].orEmpty().trim()
            require(name.isNotBlank()) { "university name is required" }
            val id = transaction {
                Universities.insert {
                    it[Universities.name] = name.take(200)
                    it[Universities.tagline] = form["tagline"]?.take(300)
                    it[Universities.details] = form["details"]?.take(5000)
                    it[Universities.logoUrl] = form["logoUrl"]?.take(500)
                    it[Universities.createdAt] = System.currentTimeMillis()
                }[Universities.id]
            }
            Audit.log(actorId = user.id, actorRole = user.role.name, actorEmail = user.email, action = "admin.university.created", entityType = "university", entityId = id.toString())
            call.respondRedirect("/admin/universities/$id")
        }

        get("/admin/universities/{id}") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@get }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid university id")
            if (user.role == UserRole.registrar && user.universityId != id) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@get }
            if (user.role !in setOf(UserRole.system_admin, UserRole.registrar)) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@get }
            val workspace = credentials.universityWorkspace(id)
            call.html(universityDashboardPage(user, workspace, csrf = call.request.cookies["SCC_CSRF"].orEmpty()))
        }
        post("/admin/universities/{id}/schools") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid university id")
            if (user.role != UserRole.system_admin && !(user.role == UserRole.registrar && user.universityId == id)) {
                call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post
            }
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val name = form["name"].orEmpty().trim()
            val prefix = form["prefix"].orEmpty().trim().uppercase()
            require(name.isNotBlank() && prefix.matches(Regex("^[A-Z0-9]{1,12}$"))) { "enter a school name and a 1-12 character prefix" }
            transaction {
                require(Universities.selectAll().where { Universities.id eq id }.count() > 0L) { "university not found" }
                require(Schools.selectAll().where { (Schools.universityId eq id) and (Schools.prefix eq prefix) }.count() == 0L) { "prefix already exists" }
                Schools.insert { it[Schools.universityId] = id; it[Schools.name] = name.take(200); it[Schools.prefix] = prefix; it[Schools.createdAt] = System.currentTimeMillis() }
            }
            call.respondRedirect("/admin/universities/$id")
        }
        post("/admin/universities/{id}/registrars") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            if (user.role != UserRole.system_admin) { call.respondText("System administrator access required", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid university id")
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            auth.createRegistrar(user, form["email"].orEmpty(), form["password"].orEmpty(), form["displayName"], id)
            call.respondRedirect("/admin/universities/$id")
        }
        post("/admin/universities/{id}/import") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid university id")
            if (user.role != UserRole.system_admin && !(user.role == UserRole.registrar && user.universityId == id)) {
                call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post
            }
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val commit = form["mode"] == "commit"
            val result = credentials.importStudents(user.id, id, form["csvText"].orEmpty(), form["sourceName"], commit)
            call.html(messagePage("CSV ${if (commit) "import committed" else "preview"}",
                "Accepted: ${result.accepted} &nbsp; Skipped: ${result.skipped} &nbsp; Data rows: ${result.totalRows} &nbsp; Commit: ${result.committed}",
                "/admin/universities/$id"))
        }
        post("/admin/universities/{id}/issue") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid university id")
            if (user.role != UserRole.system_admin && !(user.role == UserRole.registrar && user.universityId == id)) {
                call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post
            }
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val studentId = form["studentRecordId"]?.toLongOrNull() ?: error(400, "select a student")
            val newId = credentials.issueCredential(user.id, id, studentId, form["title"].orEmpty(),
                form["credentialType"] ?: "AcademicCredential", form["awardDate"], form["classification"], emptyMap())
            call.respondRedirect("/admin/universities/$id?issued=${credentials.credentialDto(newId).credentialId}")
        }
        post("/admin/credentials/{id}/revoke") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            val credentialId = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid credential id")
            val row = credentials.credentialRowById(credentialId)
            if (user.role != UserRole.system_admin && !(user.role == UserRole.registrar && user.universityId == row[Credentials.universityId])) {
                call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post
            }
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            credentials.revokeCredential(user.id, credentialId, form["reason"].orEmpty())
            call.respondRedirect("/admin/universities/${row[Credentials.universityId]}")
        }
        post("/admin/credentials/{id}/reissue") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            val credentialId = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid credential id")
            val row = credentials.credentialRowById(credentialId)
            if (user.role != UserRole.system_admin && !(user.role == UserRole.registrar && user.universityId == row[Credentials.universityId])) {
                call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post
            }
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            credentials.reissueCredential(user.id, credentialId, null, null, null, null, null)
            call.respondRedirect("/admin/universities/${row[Credentials.universityId]}")
        }

        get("/admin/accounts") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@get }
            if (user.role != UserRole.system_admin) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@get }
            val accounts = transaction {
                Users.selectAll().orderBy(Users.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC).limit(300).map { row ->
                    "<tr><td>${esc(row[Users.email])}</td><td>${esc(row[Users.displayName] ?: "")}</td><td>${esc(row[Users.role])}</td><td>${esc(row[Users.status])}</td><td><form method='post' action='/admin/accounts/${row[Users.id]}/status'><input type='hidden' name='csrf' value='${esc(call.request.cookies["SCC_CSRF"].orEmpty())}'><button name='status' value='${if (row[Users.status] == "active") "suspended" else "active"}'>${if (row[Users.status] == "active") "Suspend" else "Activate"}</button></form></td></tr>"
                }
            }
            call.html(page("Accounts", "<h1>Student & staff accounts</h1><table><tr><th>Email</th><th>Name</th><th>Role</th><th>Status</th><th>Action</th></tr>${accounts.joinToString("")}</table>${adminNav(user)}"))
        }
        post("/admin/accounts/{id}/status") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            if (user.role != UserRole.system_admin) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid account id")
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            auth.setAccountStatus(user, id, form["status"].orEmpty())
            call.respondRedirect("/admin/accounts")
        }
        get("/admin/audit") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@get }
            if (user.role != UserRole.system_admin) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@get }
            val events = transaction {
                AuditLogs.selectAll().orderBy(AuditLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC).limit(200).map {
                    "<tr><td>${it[AuditLogs.createdAt]}</td><td>${esc(it[AuditLogs.actorEmail] ?: "")}</td><td>${esc(it[AuditLogs.action])}</td><td>${esc(it[AuditLogs.entityType] ?: "")}</td><td>${esc(it[AuditLogs.status])}</td></tr>"
                }
            }
            call.html(page("Audit", "<h1>Audit log</h1><table><tr><th>Time (epoch ms)</th><th>Actor</th><th>Action</th><th>Entity</th><th>Status</th></tr>${events.joinToString("")}</table>${adminNav(user)}"))
        }

        // Open employer portal; no account required. A random tracking code lets the employer check the result.
        get("/verify") { call.html(employerPage(webUniversities(), null)) }
        post("/verify/request") {
            val form = call.receiveParameters()
            val request = verification.submitRequest(form["employerName"].orEmpty(), form["employerOrganisation"].orEmpty(),
                form["employerEmail"].orEmpty(), form["credentialKey"].orEmpty(), form["universityId"]?.toLongOrNull() ?: 0L)
            call.respondRedirect("/verify/result/${request.trackingCode}")
        }
        get("/verify/result/{code}") {
            val code = call.parameters["code"].orEmpty()
            val result = verification.resultForTrackingCode(code)
            call.html(employerResultPage(result, code))
        }

        // Registrar inbox; pending release requests are filtered to their university by service call.
        get("/admin/registrar") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@get }
            if (user.role != UserRole.registrar && user.role != UserRole.system_admin) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@get }
            call.html(registrarHome(user, verification, call.request.cookies["SCC_CSRF"].orEmpty()))
        }
        post("/admin/verification-requests/{id}/decision") {
            val user = call.webUser(auth)
            if (user == null) { call.respondRedirect("/admin/login"); return@post }
            if (user.role != UserRole.registrar && user.role != UserRole.system_admin) { call.respondText("Not authorized", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: error(400, "invalid request id")
            val form = call.receiveParameters()
            if (!call.checkCsrf(form["csrf"])) { call.respondText("Session check failed", status = io.ktor.http.HttpStatusCode.Forbidden); return@post }
            val fields = form.getAll("fields") ?: emptyList()
            verification.decide(user, id, form["decision"] == "approve", fields, form["note"])
            call.respondRedirect("/admin/registrar")
        }
    }
}

private suspend fun ApplicationCall.webUser(auth: AuthService): UserRecord? = auth.authenticate(request.cookies["SCC_SESSION"])

private suspend fun ApplicationCall.setCookie(name: String, value: String, httpOnly: Boolean, maxAge: Int) {
    val secure = request.headers["X-Forwarded-Proto"]?.equals("https", true) == true
    val safeValue = value.replace(";", "").replace("\r", "").replace("\n", "")
    val cookie = buildString {
        append("$name=$safeValue; Path=/; SameSite=Lax; Max-Age=$maxAge")
        if (httpOnly) append("; HttpOnly")
        if (secure) append("; Secure")
    }
    response.headers.append(HttpHeaders.SetCookie, cookie)
}

private suspend fun ApplicationCall.clearCookie(name: String) {
    response.headers.append(HttpHeaders.SetCookie, "$name=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax")
}

private suspend fun ApplicationCall.checkCsrf(formToken: String?): Boolean {
    val cookie = request.cookies["SCC_CSRF"] ?: return false
    return formToken != null && cookie.length == formToken.length &&
        java.security.MessageDigest.isEqual(cookie.toByteArray(), formToken.toByteArray())
}

private suspend fun ApplicationCall.html(content: String) = respondText(content, ContentType.Text.Html)

private fun loginPage(csrf: String, error: String?) = page("Sign in", """
    <main class="narrow"><div class="brand">SCC <span>ANCHORED</span></div><h1>Institution sign in</h1>
    <p class="muted">System administrators and university registrars sign in here.</p>
    ${error?.let { "<div class='alert'>${esc(it)}</div>" } ?: ""}
    <form method="post" action="/admin/login"><input type="hidden" name="csrf" value="${esc(csrf)}">
      <label>Email<input required type="email" name="email" autocomplete="username"></label>
      <label>Password<input required type="password" name="password" autocomplete="current-password"></label>
      <button class="primary">Sign in</button></form><p><a href="/verify">Employer certificate verification ↗</a></p></main>
""".trimIndent())

private fun page(title: String, body: String): String = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)} · SCC Anchored</title><style>
:root{color-scheme:light;--ink:#17253b;--muted:#64748b;--line:#e2e8f0;--bg:#f4f7fb;--navy:#10233f;--teal:#087e8b;--gold:#e5a837}*{box-sizing:border-box}body{margin:0;background:var(--bg);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink)}header{background:var(--navy);color:white;padding:16px max(22px,calc((100% - 1100px)/2));display:flex;align-items:center;justify-content:space-between}header a{color:#c6e8ed;text-decoration:none;margin-left:15px}.brand{font-weight:800;letter-spacing:.08em}.brand span{color:#83d6d3;font-size:.8em}main{max-width:1100px;margin:26px auto;padding:0 20px}.narrow{max-width:440px;margin:8vh auto;background:white;padding:32px;border-radius:20px;box-shadow:0 10px 40px #10233f12}.narrow~header{display:none}h1{font-size:1.8rem;line-height:1.2;margin:.3em 0 .6em}h2{font-size:1.2rem;margin:0 0 12px}h3{font-size:1rem}.muted{color:var(--muted)}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:14px}.card{background:white;border:1px solid var(--line);border-radius:14px;padding:18px;margin:14px 0}.metric{font-size:1.8rem;font-weight:750}.pill{display:inline-block;padding:3px 9px;border-radius:999px;background:#e6f5f5;color:#086875;font-size:.78rem;font-weight:700}.alert{padding:12px;background:#fff1f0;color:#9f2a24;border-radius:10px;margin:12px 0}label{display:block;margin:12px 0;color:var(--muted);font-weight:650}input,select,textarea{width:100%;padding:11px 12px;border:1px solid #cbd5e1;border-radius:9px;margin-top:5px;font:inherit;color:var(--ink);background:white}textarea{min-height:125px;resize:vertical}button,.button{border:0;border-radius:9px;background:#e7edf4;color:#1c2d43;padding:10px 15px;font:inherit;font-weight:700;cursor:pointer;text-decoration:none;display:inline-block}.primary{background:var(--teal);color:white}.danger{background:#fde8e7;color:#a32b26}table{width:100%;border-collapse:collapse;background:white}th,td{text-align:left;padding:10px;border-bottom:1px solid var(--line);vertical-align:top}th{font-size:.78rem;text-transform:uppercase;letter-spacing:.05em;color:var(--muted)}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.row>*{flex:1}.small{font-size:.82rem}.container{border-left:4px solid var(--teal)}.footer{color:var(--muted);font-size:.8rem;margin-top:30px}a{color:#0b7080}code{overflow-wrap:anywhere}.nav{display:flex;gap:12px;flex-wrap:wrap;margin:14px 0}.inline{display:inline-block;margin:0 4px}@media(max-width:650px){main{padding:0 12px;margin:16px auto}.card{padding:14px}table{font-size:.84rem}th,td{padding:7px}}
</style></head><body><header><a class="brand" href="/admin">SCC <span>ANCHORED</span></a><nav><a href="/admin">Dashboard</a><a href="/admin/universities">Universities</a><a href="/admin/registrar">Registrar</a><a href="/verify">Employer portal</a></nav></header>${body}<footer class="footer" style="text-align:center;padding:20px">Student Command Center · Pilot interface · Use HTTPS and institution-approved data only.</footer></body></html>"""

private fun esc(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun csrfInput(csrf: String) = "<input type='hidden' name='csrf' value='${esc(csrf)}'>"
private fun encQuery(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8)

private fun adminNav(user: UserRecord) = "<div class='nav'><a class='button' href='/admin'>Dashboard</a><a class='button' href='/admin/universities'>Universities</a><a class='button' href='/admin/accounts'>Accounts</a><a class='button' href='/admin/audit'>Audit log</a><a class='button' href='/admin/registrar'>Registrar requests</a></div>"
private fun logoutForm(csrf: String) = "<form method='post' action='/admin/logout' class='inline'>${csrfInput(csrf)}<button>Sign out</button></form>"

private fun systemAdminHome(user: UserRecord, overview: AdminOverviewDto, csrf: String): String {
    val metrics = listOf("Universities" to overview.universities, "Schools" to overview.schools, "Students" to overview.students,
        "Registrars" to overview.registrars, "Credentials" to overview.credentials, "Pending employer requests" to overview.pendingVerificationRequests,
        "Containers" to overview.containers, "Files" to overview.files)
    return page("System admin dashboard", """
      <main><div class="row"><div><h1>System administrator dashboard</h1><p class="muted">Signed in as ${esc(user.email)}. Manage institutions, school registrars and platform access.</p></div>${logoutForm(csrf)}</div>
      <div class="grid">${metrics.joinToString("") { (label, value) -> "<div class='card'><div class='muted'>${esc(label)}</div><div class='metric'>$value</div></div>" }}</div>
      <div class="card"><h2>Platform services</h2><p>IPFS: <span class='pill'>${if (overview.ipfsConfigured) "configured" else "not configured"}</span> &nbsp; Polygon: <span class='pill'>${if (overview.chainConfigured) esc(overview.chainNetwork) else "local-only"}</span></p>
      <p>Credential issuance signs a W3C-shaped VC-JWT with the university key; the record and container data are encrypted off-chain. No student plaintext is put on-chain.</p></div>
      ${adminNav(user)}<div class="card"><h2>Set up a university</h2><p>Create the institution, define schools/prefixes, then add the registrar's account.</p><a class="button primary" href="/admin/universities">Open institution directory</a></div></main>
    """.trimIndent())
}

private fun registrarHome(user: UserRecord, verification: VerificationService, csrf: String): String {
    val universityId = user.universityId
    if (universityId == null && user.role == UserRole.registrar) return page("Registrar dashboard", "<main><h1>No university assigned</h1><p>Ask a system administrator to assign your registrar account.</p></main>")
    val requests = if (user.role == UserRole.registrar) verification.requestsForRegistrar(universityId!!) else emptyList()
    val requestRows = requests.joinToString("") { r ->
        val options = """<form method="post" action="/admin/verification-requests/${r.id}/decision">${csrfInput(csrf)}<p><label><input style="width:auto" type="checkbox" name="fields" value="identity" checked> Student identity</label><label><input style="width:auto" type="checkbox" name="fields" value="programme" checked> Programme</label><label><input style="width:auto" type="checkbox" name="fields" value="credential" checked> Award details</label><label><input style="width:auto" type="checkbox" name="fields" value="chain" checked> Chain proof</label></p><label>Decision note<input name="note" maxlength="1000"></label><button class="primary" name="decision" value="approve">Approve release</button> <button class="danger" name="decision" value="reject">Reject</button></form>"""
        "<tr><td>${r.id}</td><td>${esc(r.employerName)}<br><span class='muted'>${esc(r.employerOrganisation)} · ${esc(r.employerEmail)}</span></td><td>${esc(r.credentialKey)}</td><td>${esc(r.matchedStudentName ?: "")}</td><td>${esc(r.status)}</td><td>${if (r.status == "pending") options else esc(r.decisionNote ?: "—")}</td></tr>"
    }
    val body = """
      <main><div class="row"><div><h1>University registrar dashboard</h1><p class="muted">${esc(user.email)}${if (user.role == UserRole.registrar) " · university #${user.universityId}" else " · system admin"}</p></div>${logoutForm(csrf)}</div>
      <div class="card"><h2>Credential workspace</h2><p>Import the university roster, issue credentials, revoke or reissue records, and review external requests. Each decision is written to the university audit trail.</p>${if (universityId != null) "<a class='button primary' href='/admin/universities/$universityId'>Open university workspace</a>" else "<a class='button' href='/admin/universities'>Select university</a>"}</div>
      <div class="card"><h2>Employer release requests</h2><p class="muted">The employer must submit a certificate number or registration number and the matching university. Only matching keys reach this queue. Approving releases the selected fields to the employer's request link for 30 days.</p><div style="overflow:auto"><table><tr><th>#</th><th>Employer</th><th>Certificate / key</th><th>Matched student</th><th>Status</th><th>Action</th></tr>${requestRows.ifEmpty { "<tr><td colspan='6'>No requests yet.</td></tr>" }}</table></div></div>
      ${adminNav(user)}</main>
    """.trimIndent()
    return page("Registrar dashboard", body)
}

private fun universityListPage(user: UserRecord, list: List<UniversityDto>, csrf: String): String {
    val rows = list.joinToString("") { "<tr><td><a href='/admin/universities/${it.id}'>${esc(it.name)}</a></td><td>${it.schoolCount}</td><td><a class='button' href='/admin/universities/${it.id}'>Open dashboard</a></td></tr>" }
    val createForm = if (user.role == UserRole.system_admin) """
      <div class="card"><h2>Add a university</h2><form method="post" action="/admin/universities">${csrfInput(csrf)}<label>Name<input name="name" required maxlength="200"></label><label>Tagline<input name="tagline" maxlength="300"></label><label>Details<textarea name="details"></textarea></label><button class="primary">Create university</button></form></div>
    """.trimIndent() else ""
    return page("Universities", "<main><h1>University directory</h1><p class='muted'>The system administrator creates institutions; each university registrar receives institution-scoped access.</p><div class='card'><table><tr><th>University</th><th>Schools</th><th></th></tr>${rows.ifEmpty { "<tr><td colspan='3'>No universities have been configured.</td></tr>" }}</table></div>$createForm${adminNav(user)}</main>")
}

private fun universityDashboardPage(user: UserRecord, workspace: UniversityCredentialWorkspace, csrf: String): String {
    val uni = workspace.university
    val schools = webSchools(uni.id)
    val studentOptions = workspace.importedStudents.joinToString("") { "<option value='${it.id}'>${esc(it.name)} · ${esc(it.registrationNumber ?: "no registration number")}</option>" }
    val students = workspace.importedStudents.joinToString("") { "<tr><td>${esc(it.name)}</td><td>${esc(it.registrationNumber ?: "")}</td><td>${esc(it.email)}</td><td>${esc(it.programme ?: "")}</td></tr>" }
    val creds = workspace.credentials.joinToString("") { c ->
        val actions = if (c.status == "active") """
          <form class="inline" method="post" action="/admin/credentials/${c.id}/revoke">${csrfInput(csrf)}<input name="reason" placeholder="Reason for revocation" required maxlength="500"><button class="danger">Revoke</button></form>
          <form class="inline" method="post" action="/admin/credentials/${c.id}/reissue">${csrfInput(csrf)}<button>Reissue / update</button></form>
        """.trimIndent() else "—"
        "<tr><td><code>${esc(c.credentialId)}</code><br><small>${esc(c.digest.take(16))}…</small></td><td>${esc(c.studentName ?: "")}</td><td>${esc(c.title)}</td><td>${esc(c.status)}</td><td>${esc(c.anchorStatus)}${c.anchorTxHash?.let { "<br><small>${esc(it.take(14))}…</small>" } ?: ""}</td><td>$actions</td></tr>"
    }
    val schoolRows = schools.joinToString("") { "<tr><td>${esc(it.name)}</td><td>${esc(it.prefix)}</td></tr>" }
    val registrarUsers = if (user.role == UserRole.system_admin) """
      <div class="card"><h2>Create registrar account</h2><form method="post" action="/admin/universities/${uni.id}/registrars">${csrfInput(csrf)}<div class="row"><label>Name<input name="displayName" maxlength="120"></label><label>Email<input name="email" required type="email"></label></div><label>Temporary password (8+ characters)<input type="password" name="password" required minlength="8"></label><button class="primary">Create registrar</button></form></div>
    """.trimIndent() else ""
    return page("${uni.name} dashboard", """
      <main><div class="row"><div><a href="/admin/universities">← Universities</a><h1>${esc(uni.name)}</h1><p class="muted">${esc(uni.tagline ?: "Registrar workspace")} · institution ID ${uni.id}</p></div>${logoutForm(csrf)}</div>
      <div class="grid"><div class="card"><div class="muted">Imported students</div><div class="metric">${workspace.importedStudents.size}</div></div><div class="card"><div class="muted">Credentials</div><div class="metric">${workspace.credentials.size}</div></div><div class="card"><div class="muted">Signing keys</div><div class="metric">${workspace.keys.size}</div></div></div>
      <div class="card"><h2>Schools</h2><table><tr><th>School</th><th>Registration prefix</th></tr>${schoolRows.ifEmpty { "<tr><td colspan='2'>No schools yet.</td></tr>" }}</table><form method="post" action="/admin/universities/${uni.id}/schools">${csrfInput(csrf)}<div class="row"><label>School name<input name="name" required></label><label>Registration prefix<input name="prefix" required maxlength="12"></label></div><button>Add school</button></form></div>
      $registrarUsers
      <div class="card"><h2>Import student roster CSV</h2><p>Required: <code>full_name,email</code>. Optional: <code>external_id,registration_number,programme,school_name</code>. Import is additive; duplicates are skipped. Limit: 2 MB / 2,000 rows.</p><form method="post" action="/admin/universities/${uni.id}/import">${csrfInput(csrf)}<label>Source file name<input name="sourceName" placeholder="registrar-export-2026.csv"></label><label>CSV text<textarea name="csvText" required placeholder="full_name,email,registration_number,programme\n..." style="min-height:180px"></textarea></label><button name="mode" value="preview">Preview (no write)</button> <button class="primary" name="mode" value="commit">Commit additive import</button></form></div>
      <div class="card"><h2>Issue academic credential</h2><p>A W3C VC-shaped credential is Ed25519-signed, encrypted in the database and pinned to IPFS as ciphertext. Wallet matching uses imported student email.</p><form method="post" action="/admin/universities/${uni.id}/issue">${csrfInput(csrf)}<label>Imported student<select name="studentRecordId" required><option value="">Select student</option>$studentOptions</select></label><div class="row"><label>Credential title<input name="title" required placeholder="Bachelor of Science"></label><label>Type<input name="credentialType" value="AcademicCredential"></label></div><div class="row"><label>Award date<input name="awardDate" type="date"></label><label>Classification<input name="classification" placeholder="First Class Honours"></label></div><button class="primary">Issue & anchor</button></form></div>
      <div class="card"><h2>Student roster</h2><div style="overflow:auto"><table><tr><th>Name</th><th>Registration</th><th>Email</th><th>Programme</th></tr>${students.ifEmpty { "<tr><td colspan='4'>Import a CSV roster to begin.</td></tr>" }}</table></div></div>
      <div class="card"><h2>Issued credentials</h2><div style="overflow:auto"><table><tr><th>Certificate / digest</th><th>Student</th><th>Award</th><th>Status</th><th>Anchor</th><th>Actions</th></tr>${creds.ifEmpty { "<tr><td colspan='6'>No credentials issued yet.</td></tr>" }}</table></div></div>
      <div class="card"><h2>Issuer keys</h2><p>Internal signing private keys are encrypted at rest with SCC_CREDENTIAL_MASTER_KEY. External public-key registrations do not enable external signing.</p>${workspace.keys.joinToString("<hr>") { "<p><b>${if (it.isInternal) "Internal" else "External"}</b> · <code>${esc(it.keyId)}</code></p>" }}</div>
      ${adminNav(user)}</main>
    """.trimIndent())
}

private fun employerPage(universities: List<UniversityDto>, error: String?) = page("Employer verification", """
  <main class="narrow"><div class="brand">SCC <span>ANCHORED</span></div><h1>Verify an academic certificate</h1><p class="muted">Enter your contact details, the certificate number or registration key, and the issuing university. The system checks the university/key pair before forwarding a release request to that institution's registrar.</p>${error?.let { "<div class='alert'>${esc(it)}</div>" } ?: ""}
  <form method="post" action="/verify/request"><label>Your name<input name="employerName" required maxlength="200"></label><label>Organisation<input name="employerOrganisation" required maxlength="300"></label><label>Work email<input name="employerEmail" type="email" required></label><label>Certificate number, SHA-256 key or registration number<input name="credentialKey" required maxlength="300" placeholder="SCC-VC-… or registration number"></label><label>University<select name="universityId" required><option value="">Choose issuing university</option>${universities.joinToString("") { "<option value='${it.id}'>${esc(it.name)}</option>" }}</select></label><button class="primary">Request record release</button></form>
  <div class="card small"><b>What happens next?</b><ol><li>The system checks that the supplied key belongs to the selected university.</li><li>The university registrar reviews your request and chooses which record fields to release.</li><li>After approval, use the private request link to view the released record. The request link expires after 30 days.</li></ol>Submitting a request does not guarantee approval. No student record is shown before the registrar approves.</div><p><a href="/admin/login">Institution sign in ↗</a></p></main>
""".trimIndent())

private fun employerResultPage(result: VerificationResultDto?, code: String): String {
    val content = if (result == null) {
        "<div class='alert'>This request link is invalid or has expired.</div>"
    } else {
        val statusMessage = when (result.status) {
            "pending" -> "<div class='card'>The university registrar has not made a decision yet. Refresh this page later.</div>"
            "rejected" -> "<div class='card'>The registrar declined this release request.</div>"
            "approved" -> result.released?.let { record ->
                """
                <div class="card"><h2>Released academic record</h2>
                <p><b>${esc(record.studentName ?: "Student identity withheld")}</b></p>
                <p>${esc(record.title)} · ${esc(record.credentialType)}</p>
                <p>Issuer: ${esc(record.issuerName)}</p>
                <p>Registration: ${esc(record.registrationNumber ?: "Not released")}</p>
                <p>Programme: ${esc(record.programme ?: "Not released")}</p>
                <p>Award date: ${esc(record.awardDate ?: "Not specified")} · Classification: ${esc(record.classification ?: "Not released")}</p>
                <p>Status: <span class="pill">${esc(record.status)}</span></p>
                <p>Credential number: <code>${esc(record.credentialId)}</code></p>
                <p>Digest: <code>${esc(record.digest)}</code></p>
                <p>Chain proof: ${record.onChain?.let { esc(it.network) + " · " + esc(it.chainStatus) } ?: "Not available"}</p>
                </div>
                """.trimIndent()
            } ?: "<div class='card'>Approved, but no record fields were released.</div>"
            else -> ""
        }
        "<p>Request <code>${esc(code.take(12))}…</code></p><p>Status: <span class='pill'>${esc(result.status)}</span></p>" +
            "<p>Employer: ${esc(result.employerName)} · ${esc(result.employerOrganisation)}</p>" + statusMessage
    }
    return page("Verification request", "<main class='narrow'><a href='/verify'>← New verification</a><h1>Request status</h1>$content</main>")
}

private fun messagePage(title: String, message: String, back: String) = page(title, "<main><h1>${esc(title)}</h1><div class='card'>$message</div><a class='button primary' href='${esc(back)}'>Return to dashboard</a></main>")

private fun webUniversities(): List<UniversityDto> = transaction {
    Universities.selectAll().orderBy(Universities.name to org.jetbrains.exposed.sql.SortOrder.ASC).map { row ->
        val id = row[Universities.id]
        UniversityDto(id, row[Universities.name], row[Universities.tagline], row[Universities.details], row[Universities.logoUrl],
            Schools.selectAll().where { Schools.universityId eq id }.count().toInt(), row[Universities.createdAt])
    }
}
private fun webUniversity(id: Long): UniversityDto? = webUniversities().firstOrNull { it.id == id }
private fun webSchools(id: Long) = transaction {
    Schools.selectAll().where { Schools.universityId eq id }.orderBy(Schools.name to org.jetbrains.exposed.sql.SortOrder.ASC)
        .map { SchoolDto(it[Schools.id], it[Schools.universityId], it[Schools.name], it[Schools.prefix]) }
}
