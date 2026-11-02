package io.github.mbbhalla.agentio.examples.compass

import io.github.mbbhalla.agentio.data.env.DatabaseEnvironment
import io.github.mbbhalla.agentio.data.env.DuckDbDatabaseEnvironment
import io.github.mbbhalla.agentio.data.model.ConstraintStrategies
import io.github.mbbhalla.agentio.data.model.ViolationBehavior
import java.nio.file.Path

/**
 * Sample supply-chain dataset packaged as parquet files. Constraint violations in the source
 * data are tolerated (logged, not thrown) since this is sample data with intentional dirty rows.
 */
object SupplyChainDatabase {
    const val DATASET_1_NAME = "supplychain_dataset_1"
    const val DATASET_2_NAME = "supplychain_dataset_2"

    val environment1: DatabaseEnvironment by lazy {
        val resourceDir =
            SupplyChainDatabase::class.java
                .getResource("/$DATASET_1_NAME")
                ?.toURI()
                ?.let { Path.of(it) }
                ?: throw IllegalStateException("Dataset parquet resources not found on classpath at /$DATASET_1_NAME")

        DuckDbDatabaseEnvironment.fromParquet(
            directory = resourceDir,
            constraintStrategies =
                ConstraintStrategies(
                    onPrimaryKeyViolation = ViolationBehavior.IGNORE,
                    onUniqueViolation = ViolationBehavior.IGNORE,
                    onNotNullViolation = ViolationBehavior.IGNORE,
                    onForeignKeyViolation = ViolationBehavior.IGNORE,
                ),
        )
    }

    val environment2: DatabaseEnvironment by lazy {
        val resourceDir =
            SupplyChainDatabase::class.java
                .getResource("/$DATASET_2_NAME")
                ?.toURI()
                ?.let { Path.of(it) }
                ?: throw IllegalStateException("Dataset parquet resources not found on classpath at /$DATASET_2_NAME")

        DuckDbDatabaseEnvironment.fromParquet(
            directory = resourceDir,
            constraintStrategies =
                ConstraintStrategies(
                    onPrimaryKeyViolation = ViolationBehavior.IGNORE,
                    onUniqueViolation = ViolationBehavior.IGNORE,
                    onNotNullViolation = ViolationBehavior.IGNORE,
                    onForeignKeyViolation = ViolationBehavior.IGNORE,
                ),
        )
    }
}
