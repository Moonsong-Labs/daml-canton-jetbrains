package com.moonsonglabs.daml.sandbox

/** Stable activity identifiers shared by ledger parsing and Explorer filtering. */
internal object LedgerActivityKind {
    const val ACTIVE = "Active"
    const val CREATED = "Created"
    const val ARCHIVED = "Archived"
    const val ASSIGNED = "Assigned"
    const val UNASSIGNED = "Unassigned"
    const val IN_FLIGHT_ASSIGNMENT = "In-flight assignment"
    const val IN_FLIGHT_UNASSIGNMENT = "In-flight unassignment"
}
