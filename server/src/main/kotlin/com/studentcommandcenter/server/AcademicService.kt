package com.studentcommandcenter.server

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Small student-owned academic year/semester/unit tree. */
class AcademicService {
    fun years(userId: Long): List<AcademicYearDto> {
        val yearRows = transaction {
            AcademicYears.selectAll().where { AcademicYears.userId eq userId }
                .orderBy(AcademicYears.name to org.jetbrains.exposed.sql.SortOrder.DESC).toList()
        }
        return yearRows.map { year ->
            val yearId = year[AcademicYears.id]
            val semesters = transaction {
                Semesters.selectAll().where {
                    (Semesters.academicYearId eq yearId) and (Semesters.userId eq userId)
                }.orderBy(Semesters.name to org.jetbrains.exposed.sql.SortOrder.ASC).map { semester ->
                    SemesterDto(
                        id = semester[Semesters.id], academicYearId = semester[Semesters.academicYearId],
                        name = semester[Semesters.name], startDate = semester[Semesters.startDate], endDate = semester[Semesters.endDate],
                        isCurrent = semester[Semesters.isCurrent], archived = semester[Semesters.archived]
                    )
                }
            }
            AcademicYearDto(
                id = yearId, name = year[AcademicYears.name], startDate = year[AcademicYears.startDate],
                endDate = year[AcademicYears.endDate], archived = year[AcademicYears.archived], semesters = semesters
            )
        }
    }

    fun createYear(userId: Long, name: String, startDate: String?, endDate: String?): AcademicYearDto {
        require(name.isNotBlank() && name.length <= 120) { "academic year name is required" }
        val id = transaction {
            AcademicYears.insert {
                it[AcademicYears.userId] = userId
                it[AcademicYears.name] = name.trim()
                it[AcademicYears.startDate] = startDate
                it[AcademicYears.endDate] = endDate
                it[AcademicYears.archived] = false
            }[AcademicYears.id]
        }
        Audit.log(actorId = userId, actorRole = "student", action = "academic.year.created", entityType = "academicYear", entityId = id.toString())
        return years(userId).first { it.id == id }
    }

    fun createSemester(userId: Long, yearId: Long, name: String, startDate: String?, endDate: String?): SemesterDto {
        require(name.isNotBlank() && name.length <= 120) { "semester name is required" }
        val id = transaction {
            require(AcademicYears.selectAll().where { (AcademicYears.id eq yearId) and (AcademicYears.userId eq userId) }.count() > 0L) {
                "academic year not found"
            }
            Semesters.insert {
                it[Semesters.userId] = userId
                it[Semesters.academicYearId] = yearId
                it[Semesters.name] = name.trim()
                it[Semesters.startDate] = startDate
                it[Semesters.endDate] = endDate
                it[Semesters.isCurrent] = false
                it[Semesters.archived] = false
            }[Semesters.id]
        }
        Audit.log(actorId = userId, actorRole = "student", action = "academic.semester.created", entityType = "semester", entityId = id.toString())
        return semester(userId, id)
    }

    fun semester(userId: Long, semesterId: Long): SemesterDto = transaction {
        Semesters.selectAll().where { (Semesters.id eq semesterId) and (Semesters.userId eq userId) }
            .limit(1).singleOrNull()?.let { row ->
                SemesterDto(row[Semesters.id], row[Semesters.academicYearId], row[Semesters.name], row[Semesters.startDate],
                    row[Semesters.endDate], row[Semesters.isCurrent], row[Semesters.archived])
            }
    } ?: error(404, "semester not found")

    fun setCurrent(userId: Long, semesterId: Long): SemesterDto {
        semester(userId, semesterId)
        transaction {
            Semesters.update({ Semesters.userId eq userId }) { it[Semesters.isCurrent] = false }
            Semesters.update({ (Semesters.userId eq userId) and (Semesters.id eq semesterId) }) { it[Semesters.isCurrent] = true }
        }
        return semester(userId, semesterId)
    }

    fun units(userId: Long, semesterId: Long?): List<UnitDto> = transaction {
        val query = if (semesterId == null) Units.selectAll().where { Units.userId eq userId }
        else Units.selectAll().where { (Units.userId eq userId) and (Units.semesterId eq semesterId) }
        query.orderBy(Units.name to org.jetbrains.exposed.sql.SortOrder.ASC).map { row ->
            UnitDto(row[Units.id], row[Units.semesterId], row[Units.name], row[Units.code], row[Units.lecturer],
                row[Units.credits], row[Units.description], row[Units.remarks])
        }
    }

    fun createUnit(userId: Long, semesterId: Long, name: String, code: String?, lecturer: String?, credits: String?, description: String?): UnitDto {
        require(name.isNotBlank() && name.length <= 200) { "unit name is required" }
        semester(userId, semesterId)
        val id = transaction {
            Units.insert {
                it[Units.userId] = userId
                it[Units.semesterId] = semesterId
                it[Units.name] = name.trim()
                it[Units.code] = code?.take(40)
                it[Units.lecturer] = lecturer?.take(200)
                it[Units.credits] = credits?.take(20)
                it[Units.description] = description?.take(5000)
                it[Units.remarks] = null
            }[Units.id]
        }
        return units(userId, semesterId).first { it.id == id }
    }
}
