# agentio-experiments

Research experiments evaluating the effectiveness of AgentIO's Context Memory Managers, particularly the Adaptive CMM's ability to improve information retrieval accuracy in large context windows.

## Package Structure

```
io.github.mbbhalla.agentio.experiments/
└── adaptive/
    ├── ExperimentConfig.kt              Suite and cell configuration
    ├── ExperimentType.kt                Experiment type enum
    ├── FillerCorpus.kt                  Procedural filler text generator
    ├── NeedleContextBuilder.kt          Context assembly (filler + needles)
    ├── ResultsWriter.kt                 Markdown results output
    ├── Runner.kt                        Entry point and experiment loop
    └── function/
        └── NeedleRetrievalAgenticFunction.kt   Agent + factory for trials
```

## Experiments

### Needle-in-a-Haystack

A single "needle" (secret code) is embedded at depth 0.5 (the dead zone) within a large body of filler text. The model must retrieve the exact code. Tests whether the Adaptive CMM detects low attention at the needle's position and moves it to a hot zone.

### Multi-Needle

Five needles at depths 0.1, 0.3, 0.5, 0.7, 0.9. The target needle at 0.5 has the highest importance score. Tests whether the constrained assignment correctly prioritizes the important needle over distractors.

### Distributed Counting

Every filler paragraph contains a vote for Proposal A or Proposal B, expressed in varied natural language. The model must aggregate all votes and report counts. Tests whether the CMM improves aggregation accuracy by placing segments in high-attention zones.

## Methodology

Each experiment runs a matrix of:
- **Experiment Type** × **Model** × **CMM Toggle** (ON/OFF)

For each cell, the `AgenticFunctionEvaluator` runs N iterations (default: 100) and measures:
- **Accuracy**: correct retrievals / total iterations
- **Accuracy Δ**: CMM ON accuracy minus CMM OFF accuracy (the headline number)
- **Token Overhead**: extra input tokens from probe embedding
- **Failure Rate**: whether the recall request destabilizes the agent

### Key Design Decisions

- **Factory mode**: Each iteration gets a fresh `NeedleRetrievalAgenticFunction` with its own `AdaptiveContextMemoryManager` (no heatmap leakage between trials)
- **80% fill**: Context is filled to 80% of the available input budget to trigger the lost-in-the-middle effect
- **20 segments**: Filler is chunked into exactly 20 segments regardless of model window size
- **Deterministic filler**: `FillerCorpus` uses seeded RNG for reproducible generation

## Running

```bash
./gradlew :agentio-experiments:RunAdaptiveExperiment
```

Results are written to `docs/Results.md`.

### Configuration

Edit `Runner.SUITE_CONFIG` to control:
- Which experiment types to run
- Which models to evaluate
- Number of iterations per cell
- Parallelism level
- AWS region

```kotlin
private val SUITE_CONFIG = ExperimentSuiteConfig(
    experimentTypes = listOf(ExperimentType.DISTRIBUTED_COUNTING),
    models = listOf(LLM.ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE),
    numIterations = 100,
    maxParallelism = 1,
    bedrockRegion = "us-west-2",
)
```

## Dependencies

- `agentio-core`
- `agentio-cmm-impl` (for `AdaptiveContextMemoryManager`)
- AWS SDK for Kotlin (Bedrock Runtime)
- Vavr
- Kotlinx Serialization

## Build

```bash
./gradlew :agentio-experiments:build
```
