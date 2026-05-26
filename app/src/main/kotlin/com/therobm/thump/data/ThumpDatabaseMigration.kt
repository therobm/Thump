package com.therobm.thump.data

/**
 * One step in ThumpData's persistent-schema history. The list of migrations is the schema:
 * each step has a target version and the explicit SQL that takes the database from the
 * previous version to that one.
 *
 * Same posture as Flatline's `Database/Migrations.cs` — a sequence of hand-written, explicit
 * SQL deltas, applied in order, with the applied versions recorded in `schema_versions` so a
 * re-run can pick up partial work.
 */
data class ThumpDatabaseMigration(
    val targetVersion: Int,
    val statements: List<String>,
)
