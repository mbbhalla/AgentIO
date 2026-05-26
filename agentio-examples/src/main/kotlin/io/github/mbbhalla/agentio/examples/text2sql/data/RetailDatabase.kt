package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment

object RetailDatabase {

    val environment: DatabaseEnvironment by lazy {
        DuckDbDatabaseEnvironment.fromStatements(
            ddl = RetailSchema.DDL_STATEMENTS,
            dml = RetailSeedData.INSERTS,
            tableMetadata = RetailSchema.TABLES,
        )
    }
}
