# koncur Differential Harness (MTA-8153)

Turns a golden-vs-actual koncur diff into a **ledger-driven, pass/fail gate**, so the new provider can
be compared against vanilla koncur `main` without maintaining a forked golden branch. See
`../TEST_PLAN.md` (L4) for the rationale.

## Model

Single run — we trust vanilla `main`'s goldens as the **Go output of record** (no live Go run):

```
run NEW provider vs vanilla koncur main  →  per-test output.yaml
    diff vs main's expected-output.yaml  →  divergences (normalized)
    classify each divergence vs the ledger (testing/koncur-parity-ledger.yaml):
        go-wrong|benign + accepted|excluded -> EXPLAINED   (allowed)
        java-wrong                          -> KNOWN_BUG    (fails unless --allow-known)
        needs-triage | no match             -> PENDING / UNEXPLAINED (fails)
```

Divergences are **triaged, not auto-failed** — the Go baseline is the Go *output of record*, not ground
truth (Go is sometimes wrong; those are `go-wrong` ledger entries).

## Components

- **`koncur_triage.py`** — the classifier/gate. Reads two koncur output YAMLs (golden + actual) and the
  ledger; emits a triage report and an exit code. Normalization (from the ledger `comparator` block):
  order-insensitive; `message`/`links`/`codeSnip` ignored; `file://` + base-dir prefixes normalized
  (incl. `/cache/m2` vs `/m2`). Source paths match exactly on `(uri, line)`; **non-source paths**
  (decompiled `java-project/` + dependency `m2`) match by **incident count per file**, ignoring line —
  decompiler line numbers differ across providers (Vineflower vs fernflower), so a pure line shift must
  not register as a divergence.
- **`test_koncur_triage.py`** — unit tests (15) covering diff, normalization, tolerance, classification,
  and gate behavior. Run: `cd testing && python3 -m unittest -v test_koncur_triage`.
- **`koncur-parity-ledger.yaml`** — the durable triage record (seeded from `FAILING_TESTS.md`).

## Usage (single test)

```bash
python3 testing/koncur_triage.py \
    --ledger testing/koncur-parity-ledger.yaml \
    --test daytrader \
    --golden /path/to/koncur/tests/daytrader/expected-output.yaml \
    --actual /path/to/run/output.yaml
# exit 0 = clean; 1 = UNEXPLAINED/PENDING (or KNOWN_BUG without --allow-known)
```

## CI rollout plan

1. **Populate (advisory):** add a workflow that clones koncur `main` (not the forked SHA), runs the new
   provider per test, and runs `koncur_triage.py` per test **advisory** (`continue-on-error`). Its job is
   to surface the real divergence set so the ledger can be filled in (resolve every `PENDING`/`UNEXPLAINED`
   into an accepted `go-wrong`/`benign` or a tracked `java-wrong`).
2. **Gate:** once the ledger explains the steady-state divergences, flip the job to blocking and retire the
   pinned forked-branch `koncur-e2e` job (MTA-8152).

## Follow-ups

- Batch/directory mode (triage all tests in one invocation) + an aggregate summary.
- Emit machine-readable output (JSON) for a parity dashboard.
- Pin `ruleset_ref` / kantra digest in the ledger `baseline` so runs are reproducible (the Quarkus-3.40
  rule drift seen in CI run 39956522958 was a pinned-golden vs `:latest`-rules mismatch).
