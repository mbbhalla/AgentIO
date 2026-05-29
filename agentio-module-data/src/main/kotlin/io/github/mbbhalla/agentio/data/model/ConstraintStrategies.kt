package io.github.mbbhalla.agentio.data.model

enum class ViolationBehavior {
    THROW,
    IGNORE,
}

data class ConstraintStrategies(
    val onPrimaryKeyViolation: ViolationBehavior = ViolationBehavior.THROW,
    val onUniqueViolation: ViolationBehavior = ViolationBehavior.THROW,
    val onNotNullViolation: ViolationBehavior = ViolationBehavior.THROW,
    val onForeignKeyViolation: ViolationBehavior = ViolationBehavior.THROW,
)
