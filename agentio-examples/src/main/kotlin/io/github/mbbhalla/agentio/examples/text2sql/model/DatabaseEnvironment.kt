package io.github.mbbhalla.agentio.examples.text2sql.model

interface DatabaseEnvironment {
    fun listTables(): Set<TableName>
    fun getTableInfo(tableName: TableName): TableInfo
    fun explain(sql: String): ExplainResult
    fun executeQuery(sql: String): Dataset
}
