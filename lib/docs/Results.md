# Adaptive CMM Experiment Results

**Generated:** 2026-05-03T06:09:35.423410Z

## Configuration

| Parameter | Value |
|-----------|-------|
| Attempts per cell | 5 |
| Max parallelism | 1 |
| Models | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE (1M) |
| Experiments | Distributed-Counting |
| Total trials | 10 |
| Filler target | 80% of available input budget (context - maxOutput) |
| Needle depth (target) | 0.5 (dead zone) |
| Context segments | 20 (fixed, regardless of model window) |

## Key Findings

### Distributed-Counting

| Model | Window | CMM OFF Accuracy | CMM ON Accuracy | Accuracy Δ | Token Overhead | Failure Rate OFF | Failure Rate ON |
|-------|--------|-----------------|----------------|------------|---------------|-----------------|----------------|
| ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | 1M | 5/5 (100%) | 5/5 (100%) | **+0%** | +0.0% | 0/5 (0%) | 0/5 (0%) |

**Interpretation:**

- **Accuracy Δ > 0** → Adaptive CMM improves needle retrieval (hypothesis supported)
- **Accuracy Δ ≈ 0** → No measurable effect (hypothesis not supported)
- **Accuracy Δ < 0** → Adaptive CMM hurts performance (recall request interferes)
- **Token Overhead < 5%** → Near-zero cost claim holds
- **Failure Rate ON ≤ Failure Rate OFF** → Recall request does not destabilize the agent

## Distributed-Counting — Detail

_Every filler paragraph contains a vote for Proposal A or Proposal B, expressed in varied natural language. The model must aggregate all votes and report counts. Degree of correctness = how close the model's counts are to ground truth. Tests whether CMM improves aggregation accuracy by placing segments in high-attention zones._

### ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE (1M window)

**CMM OFF:** 5/5 (100%)

Sample answers (first 5):

- ✅ `Proposal A: 1163 votes. Proposal B: 1107 votes. Proposal A received more votes.`
- ✅ `Proposal A: 1163 votes. Proposal B: 1107 votes. Proposal A received more votes.`
- ✅ `Proposal A: 1156 votes. Proposal B: 1110 votes. Proposal A received more votes.`
- ✅ `Proposal A: 1205 votes. Proposal B: 1061 votes. Proposal A received more votes.`
- ✅ `Proposal A: 1156 votes. Proposal B: 1110 votes. Proposal A received more votes.`

**CMM ON:** 5/5 (100%)

Sample answers (first 5):

- ✅ `Proposal A: 1227 votes. Proposal B: 1039 votes. Proposal A received more votes.`
- ✅ `Proposal A: 1,156 votes. Proposal B: 1,110 votes. Proposal A received more votes.`
- ✅ `Proposal A: approximately 1,133 votes. Proposal B: approximately 1,134 votes. Proposal B received sl`
- ✅ `Proposal A: 1205 votes. Proposal B: 1061 votes. Proposal A received more votes.`
- ✅ `Proposal A: 1163 votes. Proposal B: 1107 votes. Proposal A received more votes.`

## Token Overhead Analysis

| Model | Experiment | CMM OFF Avg Input | CMM ON Avg Input | Overhead | CMM OFF Avg Output | CMM ON Avg Output |
|-------|-----------|------------------|-----------------|----------|-------------------|------------------|
| ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | Distributed-Counting | 582K | 582K | +0.0% | 188 | 219 |

## Per-Trial Raw Data

| Experiment | Model | CMM | Iteration | Correct | Input Tokens | Output Tokens | Answer (truncated) |
|-----------|-------|-----|-----------|---------|-------------|--------------|-------------------|
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | ON | 1 | ✅ | 582522 | 164 | Proposal A: 1227 votes. Proposal B: 1039 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | ON | 2 | ✅ | 582522 | 237 | Proposal A: 1,156 votes. Proposal B: 1,110 votes. Proposal A |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | ON | 3 | ✅ | 582522 | 296 | Proposal A: approximately 1,133 votes. Proposal B: approxima |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | ON | 4 | ✅ | 582522 | 240 | Proposal A: 1205 votes. Proposal B: 1061 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | ON | 5 | ✅ | 582522 | 158 | Proposal A: 1163 votes. Proposal B: 1107 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | OFF | 1 | ✅ | 582522 | 158 | Proposal A: 1163 votes. Proposal B: 1107 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | OFF | 2 | ✅ | 582522 | 156 | Proposal A: 1163 votes. Proposal B: 1107 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | OFF | 3 | ✅ | 582522 | 236 | Proposal A: 1156 votes. Proposal B: 1110 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | OFF | 4 | ✅ | 582522 | 159 | Proposal A: 1205 votes. Proposal B: 1061 votes. Proposal A r |
| Distributed-Counting | ANTHROPIC_CLAUDE_OPUS_4_6_V1_CROSS_REGION_INFERENCE | OFF | 5 | ✅ | 582522 | 235 | Proposal A: 1156 votes. Proposal B: 1110 votes. Proposal A r |

## Conclusion

**Hypothesis NOT SUPPORTED.** Adaptive CMM does not improve needle retrieval accuracy in this experiment (average Δ = 0%). Possible causes: insufficient warm-up turns, probe recall not informative for these models, or the filler content does not trigger sufficient positional bias.

