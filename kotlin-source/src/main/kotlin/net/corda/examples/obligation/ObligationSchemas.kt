package net.corda.examples.obligation

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

object IOUSchema

object IOUSchemaV1 : MappedSchema(
        schemaFamily = IOUSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentIOU::class.java)) {
    @Entity
    @Table(name = "iou_states")
    class PersistentIOU(
            @Column(name = "lender")
            var lenderName: String,

            @Column(name = "borrower")
            var borrowerName: String,

            @Column(name = "value")
            var value: Long,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState()
}

object IOUSchemaV2 : MappedSchema(
        schemaFamily = IOUSchema.javaClass,
        version = 2,
        mappedTypes = listOf(PersistentIOU::class.java)) {
    @Entity
    @Table(name = "iou_states2")
    class PersistentIOU(
            @Column(name = "lender")
            var lenderName: String,

            @Column(name = "borrower")
            var borrowerName: String,

            @Column(name = "value")
            var value: Long,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState()
}