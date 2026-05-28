package io.github.mbbhalla.agentio.examples.text2sql.data

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import java.nio.file.Path

object EmployeeDatabase {
    val environment: DatabaseEnvironment by lazy {
        val resourceDir =
            EmployeeDatabase::class.java
                .getResource("/text2sql/employee-db")
                ?.toURI()
                ?.let { Path.of(it) }
                ?: throw IllegalStateException("Employee DB parquet resources not found")

        DuckDbDatabaseEnvironment.fromParquet(resourceDir)
    }
}
