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
  while a regression ("finds fewer") still fails the gate. The `unmatched` rule list is **not** diffed (it
  is the inverse of the incident signal — redundant noise). **Scope** (ledger `comparator`): `diff_tags:
  false` — tags are engine/ruleset-version-derived (discovery addon + tagging rules), not the java
  provider's incident output, so provider parity compares **incidents only**; `exclude_tests` drops
  non-Java tests (python/go/.NET) which validate other providers. With this scope both the CLI and Hub
  paths are clean (0 unexplained). _(Tag diffing / direction-scoping remain available if `diff_tags` is
  re-enabled — e.g. `direction: [java-more, tag-added]`.)_
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
- **`.github/workflows/koncur-hub-e2e.yaml`** — in-cluster (Tackle Hub) differential, **gating** (nightly +
  manual). The first run's ~4069 "divergences" were base-dir noise (Hub repo-subdir prefix) + tag-taxonomy
  from the Hub analyzer addon's differing ruleset version + non-Java tests. With base-dir-independent URIs,
  incidents-only (`diff_tags: false`), and `exclude_tests`, the hub path is clean with **0 Java-provider
  incident regressions**. (If the Hub addon's ruleset version is later pinned to the golden's, tags could be
  re-enabled for extra coverage.)

## Follow-ups

- Give `koncur-hub-e2e` the differential treatment (then delete the forked `koncur-new-java-provider` branch).
- Batch/directory mode (triage all tests in one invocation) + an aggregate summary.
- Emit machine-readable output (JSON) for a parity dashboard.
- Pin `ruleset_ref` / kantra digest in the ledger `baseline` so runs are reproducible (the Quarkus-3.40
  rule drift seen in CI run 39956522958 was a pinned-golden vs `:latest`-rules mismatch).
