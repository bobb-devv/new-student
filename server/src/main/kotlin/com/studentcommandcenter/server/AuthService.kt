package com.studentcommandcenter.server

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Account, role, password and seven-day bearer-session management. */
class AuthService {

    fun registerStudent(request: RegisterRequest): LoginResponse {
        val email = request.email.trim().lowercase()
        require(email.length <= 320 && email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            "enter a valid email address"
        }
        require(request.password.length in 8..256) { "password must be between 8 and 256 characters" }
        require(request.displayName == null || request.displayName.length <= 120) { "display name is too long" }
        request.universityId?.let { id ->
            val exists = transaction { Universities.selectAll().where { Universities.id eq id }.count() > 0L }
            require(exists) { "university not found" }
        }

        val exists = transaction { Users.selectAll().where { Users.email eq email }.count() > 0L }
        require(!exists) { "an account with that email already exists" }
        val (hash, salt) = Security.hashPassword(request.password)
        val userId = transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.passwordHash] = hash
                it[Users.passwordSalt] = salt
                it[Users.displayName] = request.displayName?.trim()?.takeIf(String::isNotBlank)
                it[Users.gender] = request.gender?.trim()?.takeIf(String::isNotBlank)
                it[Users.role] = UserRole.student.name
                it[Users.universityId] = request.universityId
                it[Users.registrationNumber] = request.registrationNumber?.trim()?.takeIf(String::isNotBlank)
                it[Users.status] = UserStatus.active.name
                it[Users.publicId] = "SCC-" + Security.randomId().uppercase()
                it[Users.createdAt] = System.currentTimeMillis()
            }[Users.id]
        }
        val user = findUser(userId) ?: error(500, "account creation failed")
        Audit.log(actorId = user.id, actorRole = "student", actorEmail = email, action = "auth.registered", entityType = "user", entityId = userId.toString())
        return LoginResponse(user = toDto(user), token = createSession(user.id))
    }

    fun login(emailInput: String, password: String): LoginResponse {
        val email = emailInput.trim().lowercase()
        val row = transaction {
            Users.selectAll().where { Users.email eq email }.limit(1).singleOrNull()?.snapshot(Users)
        } ?: error(401, "email or password is incorrect")
        if (row[Users.status] != UserStatus.active.name) error(403, "account is suspended")
        if (!Security.verifyPassword(password, row[Users.passwordSalt], row[Users.passwordHash])) {
            Audit.log(actorId = row[Users.id], actorRole = row[Users.role], actorEmail = email,
                action = "auth.login.failed", status = "error", errorMessage = "invalid credentials")
            error(401, "email or password is incorrect")
        }
        val user = row.toUserRecord()
        Audit.log(actorId = user.id, actorRole = user.role.name, actorEmail = user.email, action = "auth.login")
        return LoginResponse(user = toDto(user), token = createSession(user.id))
    }

    fun authenticate(token: String?): UserRecord? {
        if (token.isNullOrBlank() || token.length > 512) return null
        val hash = Security.sha256Hex(token)
        val now = System.currentTimeMillis()
        val userId = transaction {
            val session = Sessions.selectAll()
                .where { (Sessions.tokenHash eq hash) and (Sessions.expiresAt greater now) }
                .limit(1).singleOrNull() ?: return@transaction null
            session[Sessions.userId]
        } ?: return null
        return findUser(userId)?.takeIf { it.status == UserStatus.active }
    }

    fun currentUser(userId: Long): UserRecord? = findUser(userId)

    fun logout(token: String?) {
        if (token.isNullOrBlank()) return
        val hash = Security.sha256Hex(token)
        transaction { Sessions.deleteWhere { Sessions.tokenHash eq hash } }
    }

    fun createRegistrar(
        admin: UserRecord,
        emailInput: String,
        password: String,
        displayName: String?,
        universityId: Long
    ): UserDto {
        require(admin.role == UserRole.system_admin) { "system administrator access required" }
        val email = emailInput.trim().lowercase()
        require(email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "enter a valid email address" }
        require(password.length in 8..256) { "password must be between 8 and 256 characters" }
        val universityExists = transaction { Universities.selectAll().where { Universities.id eq universityId }.count() > 0L }
        require(universityExists) { "university not found" }
        require(transaction { Users.selectAll().where { Users.email eq email }.count() == 0L }) { "email already has an account" }
        val (hash, salt) = Security.hashPassword(password)
        val userId = transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.passwordHash] = hash
                it[Users.passwordSalt] = salt
                it[Users.displayName] = displayName?.trim()?.takeIf(String::isNotBlank)
                it[Users.gender] = null
                it[Users.role] = UserRole.registrar.name
                it[Users.universityId] = universityId
                it[Users.registrationNumber] = null
                it[Users.status] = UserStatus.active.name
                it[Users.publicId] = "SCC-" + Security.randomId().uppercase()
                it[Users.createdAt] = System.currentTimeMillis()
            }[Users.id]
        }
        val created = findUser(userId) ?: error(500, "registrar creation failed")
        Audit.log(actorId = admin.id, actorRole = admin.role.name, actorEmail = admin.email,
            action = "admin.registrar.created", entityType = "user", entityId = userId.toString(),
            detail = "universityId=$universityId")
        return toDto(created)
    }

    fun updateProfile(user: UserRecord, displayName: String?, gender: String?): UserDto {
        require(displayName == null || displayName.length <= 120) { "display name is too long" }
        require(gender == null || gender.length <= 32) { "gender value is too long" }
        transaction {
            Users.update({ Users.id eq user.id }) {
                it[Users.displayName] = displayName?.trim()?.takeIf(String::isNotBlank)
                it[Users.gender] = gender?.trim()?.takeIf(String::isNotBlank)
            }
        }
        Audit.log(actorId = user.id, actorRole = user.role.name, actorEmail = user.email, action = "profile.updated")
        return toDto(findUser(user.id) ?: user)
    }

    fun setAccountStatus(admin: UserRecord, targetId: Long, status: String): UserDto {
        require(admin.role == UserRole.system_admin) { "system administrator access required" }
        require(status in setOf("active", "suspended")) { "status must be active or suspended" }
        val target = findUser(targetId) ?: error(404, "user not found")
        require(target.role != UserRole.system_admin) { "system administrator status cannot be changed here" }
        transaction {
            Users.update({ Users.id eq targetId }) {
                it[Users.status] = status
            }
        }
        if (status == "suspended") {
            transaction { Sessions.deleteWhere { Sessions.userId eq targetId } }
        }
        Audit.log(actorId = admin.id, actorRole = admin.role.name, actorEmail = admin.email,
            action = "admin.account.status", entityType = "user", entityId = targetId.toString(), detail = status)
        return toDto(findUser(targetId) ?: target)
    }

    fun createSession(userId: Long): String {
        val token = Security.randomHex(32)
        val now = System.currentTimeMillis()
        transaction {
            Sessions.deleteWhere { Sessions.expiresAt less now }
            Sessions.insert {
                it[Sessions.tokenHash] = Security.sha256Hex(token)
                it[Sessions.userId] = userId
                it[Sessions.createdAt] = now
                it[Sessions.expiresAt] = now + Config.sessionTtlDays * 24L * 60 * 60 * 1000
            }
        }
        return token
    }

    fun findUser(id: Long): UserRecord? = transaction {
        Users.selectAll().where { Users.id eq id }.limit(1).singleOrNull()?.snapshot(Users)?.toUserRecord()
    }

    fun toDto(user: UserRecord): UserDto {
        val universityName = user.universityId?.let { id ->
            transaction { Universities.selectAll().where { Universities.id eq id }.limit(1).singleOrNull()?.get(Universities.name) }
        }
        return UserDto(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            gender = user.gender,
            role = user.role.name,
            universityId = user.universityId,
            universityName = universityName,
            registrationNumber = user.registrationNumber,
            status = user.status.name,
            publicId = user.publicId,
            createdAt = user.createdAt
        )
    }

    private fun RowSnapshot.toUserRecord(): UserRecord = UserRecord(
        id = this[Users.id],
        email = this[Users.email],
        displayName = this[Users.displayName],
        gender = this[Users.gender],
        role = UserRole.valueOf(this[Users.role]),
        universityId = this[Users.universityId],
        registrationNumber = this[Users.registrationNumber],
        status = UserStatus.valueOf(this[Users.status]),
        publicId = this[Users.publicId],
        createdAt = this[Users.createdAt]
    )
}
