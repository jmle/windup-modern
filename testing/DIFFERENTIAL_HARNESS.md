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
  order-insensitive; `message`/`links`/`codeSnip` ignored; URIs are **base-dir-independent** — reduced to
  the path after a stable anchor (`src/main/java/` etc., the `m2` tail, or `java-project/`), with the file
  name as a fallback — so the Hub path (`…/book-server/src/main/java/X`) and the CLI/golden path
  (`…/src/main/java/X`) compare equal. Source paths match exactly on `(uri, line)`; **non-source paths**
  (decompiled `java-project/` + dependency `m2`) match by **incident count per file**, ignoring line —
  decompiler line numbers differ across providers (Vineflower vs fernflower), so a pure line shift must
  not register as a divergence. Classification is **direction-aware**: a ledger entry may accept only
  certain directions (e.g. `direction: [java-more, tag-added]`) so "new provider finds more" is allowed
  while a regression ("finds fewer" / `tag-dropped`) still fails the gate. Technology tags are diffed as
  `tag-added` / `tag-dropped`; the `unmatched` rule list is **not** diffed (it is the inverse of the
  incident signal — redundant noise).
- **`test_koncur_triage.py`** — unit tests (19) covering diff, normalization, count-based non-source
  matching, direction-aware classification, and the gate. Run:
  `cd testing && python3 -m unittest -v test_koncur_triage`.
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

## CI (current)

- **`.github/workflows/koncur-differential.yaml`** — clones koncur `main`, regenerates per-test output
  with the new provider (`koncur generate`), and runs `koncur_triage.py` per test. **Gating** (fails on
  any UNEXPLAINED/PENDING; a tracked `java-wrong` can be tolerated with `--allow-known`). Runs **nightly
  + on demand** (heavy, ~90 min). First real run: all 17 apps clean (daytrader 13 + seam 288 explained,
  0 regressions).
- **`.github/workflows/unit-tests.yaml`** — fast **per-PR** gate (`mvn test` + the triage harness tests).
- The stale forked-baseline `koncur-e2e` workflow has been **retired** (MTA-8152).
- **`.github/workflows/koncur-hub-e2e.yaml`** — in-cluster (Tackle Hub) differential, **advisory** for now
  (nightly + manual). First run: base-dir normalization cut noise from ~4069 to ~349, and **0 Java-provider
  incident regressions**. The residual is **not the provider**: ~345 are `technology-usage`/`discovery-rules`
  **tag-taxonomy** differences because the Hub analyzer addon pulls a **different ruleset version** than the
  kantra-generated golden (e.g. tag "Java Source" → "Java"), and 4 are a `python-sample` ruleset-name quirk
  (`konveyor-analysis` ↔ `files`). **Blocker before hub can gate:** align the Hub addon's ruleset version
  with the golden's (pin `ruleset_ref`), or scope the hub differential to Java-provider incidents.

## Follow-ups

- Give `koncur-hub-e2e` the differential treatment (then delete the forked `koncur-new-java-provider` branch).
- Batch/directory mode (triage all tests in one invocation) + an aggregate summary.
- Emit machine-readable output (JSON) for a parity dashboard.
- Pin `ruleset_ref` / kantra digest in the ledger `baseline` so runs are reproducible (the Quarkus-3.40
  rule drift seen in CI run 39956522958 was a pinned-golden vs `:latest`-rules mismatch).
