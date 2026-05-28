package io.github.mbbhalla.agentio.data.env

import io.github.mbbhalla.agentio.data.model.DatabaseEnvironmentSnapshot
import io.github.mbbhalla.agentio.data.model.Dataset
import io.github.mbbhalla.agentio.data.model.ExplainResult
import io.github.mbbhalla.agentio.data.model.TableInfo
import io.github.mbbhalla.agentio.data.model.TableName

abstract class DatabaseEnvironment {
    abstract val snapshot: DatabaseEnvironmentSnapshot?

    abstract fun listTables(): Set<TableName>

    abstract fun getTableInfo(tableName: TableName): TableInfo

    abstract fun explain(sql: String): ExplainResult

    abstract fun statementType(sql: String): StatementType

    abstract fun executeQuery(sql: SelectSqlStatement): Dataset

    companion object {
        @Volatile
        var current: DatabaseEnvironment? = null
            private set

        fun activate(env: DatabaseEnvironment) {
            current = env
        }
    }
}
