#!/usr/bin/env python3
"""
Ledger-driven differential triage for koncur (MTA-8153, TEST_PLAN.md L4).

Diffs the NEW provider's analyzer output against vanilla koncur `main`'s Go-generated golden
(trusted as the Go output of record) and classifies every divergence against the parity ledger
(testing/koncur-parity-ledger.yaml):

    go-wrong / benign + accepted|excluded -> EXPLAINED  (allowed)
    java-wrong                            -> KNOWN_BUG   (fails unless --allow-known)
    needs-triage / no match               -> UNEXPLAINED (fails)

Exit code: 0 if clean, 1 if any UNEXPLAINED (or KNOWN_BUG without --allow-known).

Single-test usage:
    koncur_triage.py --ledger testing/koncur-parity-ledger.yaml \
        --test daytrader --golden golden.yaml --actual output.yaml

This module is import-safe; the diff/classify functions are unit-tested in test_koncur_triage.py.
"""
import argparse
import sys
from pathlib import Path
import yaml

EXPLAINED, KNOWN_BUG, PENDING, UNEXPLAINED = "EXPLAINED", "KNOWN_BUG", "PENDING", "UNEXPLAINED"

DEFAULT_CFG = {
    "line_tolerance": {"application_source": 0, "decompiled_or_dependency": 2},
    "ignore_empty_rulesets": True,
}


# ── ledger ──────────────────────────────────────────────────────────────────

def load_ledger(path):
    with open(path) as f:
        doc = yaml.safe_load(f) or {}
    cfg = dict(DEFAULT_CFG)
    cfg.update(doc.get("comparator", {}) or {})
    return doc.get("entries", []) or [], cfg


def entry_tests(entry):
    if entry.get("tests"):
        return set(entry["tests"])
    if entry.get("test"):
        return {entry["test"]}
    return set()  # empty == applies to any test


def rule_matches(entry_rule, rule):
    if entry_rule is None:
        return True  # entry covers the whole test
    if entry_rule.endswith("*"):
        return rule.startswith(entry_rule[:-1])
    return entry_rule == rule


# ── rule-capability scoping (java-provider vs builtin) ────────────────────────
# A rule exercises the Java provider iff its `when` clause uses a `java.*` capability
# (java.referenced / java.dependency). Rules that are purely `builtin.*` (file/xml/
# filecontent/has/json) are evaluated by the engine's builtin provider — identical no
# matter which Java provider runs — so their divergences reflect ruleset/analyzer-version
# skew, not the provider under test. Scoping the diff to java-provider rules keeps that
# noise out of the java-provider differential. See testing/BASELINE_COMPARISONS.md.

_JAVA_CAPABILITIES = ("java.referenced", "java.dependency")


def _when_uses_java(node):
    """True if a rule's `when` subtree references a java.* capability (recurses and/or/not)."""
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(k, str) and k in _JAVA_CAPABILITIES:
                return True
            if _when_uses_java(v):
                return True
        return False
    if isinstance(node, list):
        return any(_when_uses_java(x) for x in node)
    return False


def load_java_rule_ids(rulesets_dir):
    """Scan a rulesets directory and return the set of ruleIDs whose `when` uses the Java provider.

    Robust to the two koncur/analyzer file shapes (a bare list of rules, or a dict with a `rules`
    list) and to unparseable/metadata files (e.g. ruleset.yaml), which contribute no ruleIDs.
    """
    ids = set()
    root = Path(rulesets_dir)
    files = sorted(set(root.rglob("*.yaml")) | set(root.rglob("*.yml")))
    for path in files:
        try:
            with open(path) as f:
                doc = yaml.safe_load(f)
        except Exception:
            continue
        rules = doc if isinstance(doc, list) else (doc.get("rules") if isinstance(doc, dict) else None)
        if not isinstance(rules, list):
            continue
        for r in rules:
            if isinstance(r, dict) and r.get("ruleID") and _when_uses_java(r.get("when")):
                ids.add(r["ruleID"])
    return ids


# ── koncur output parsing ───────────────────────────────────────────────────

def is_nonsource_uri(uri):
    """Decompiled or dependency paths, where line numbers are not source-faithful and differ by
    decompiler (e.g. Vineflower vs fernflower). Matched by incident count per file, not by line."""
    return ("/m2/" in uri or "/.m2/" in uri or "/cache/m2/" in uri or "/java-project/" in uri)


# Anchors that make a URI base-dir-independent: the path *after* the anchor is stable regardless of
# where the project was checked out / mounted (kantra CLI vs Tackle Hub differ only in the prefix).
_SRC_ANCHORS = ("/src/main/java/", "/src/test/java/", "/src/main/scala/", "/src/main/kotlin/",
                "/src/main/resources/", "/src/main/webapp/", "/src/main/", "/src/test/", "/src/")
_DEP_ANCHORS = ("/cache/m2/", "/.m2/repository/", "/m2/")


def normalize_uri(uri):
    """Collapse scheme + base dir so URIs are comparable across run paths (CLI vs Hub).

    The Hub path prefixes the repo subdir (…/book-server/src/main/java/X) while the CLI/golden path does
    not (…/src/main/java/X); both must reduce to the same key. We take the path after a stable anchor
    (src root / m2 / java-project), falling back to the file name.
    """
    u = uri[len("file://"):] if uri.startswith("file://") else uri
    for a in _SRC_ANCHORS:
        i = u.rfind(a)
        if i >= 0:
            return u[i + len(a):]
    for a in _DEP_ANCHORS:
        i = u.rfind(a)
        if i >= 0:
            return "m2/" + u[i + len(a):]
    i = u.rfind("/java-project/")
    if i >= 0:
        return u[i + len("/java-project/"):]
    return u.rsplit("/", 1)[-1]  # fall back to the file name


def parse_rulesets(doc):
    """{ruleset_name: {"rules": {rule: [(uri_norm, line, is_dep)]}, "tags": set, "unmatched": set}}"""
    out = {}
    for rs in (doc or []):
        name = rs.get("name", "")
        rules = {}
        for section in ("violations", "insights"):
            for rule, body in (rs.get(section) or {}).items():
                incs = []
                for inc in (body.get("incidents") or []):
                    uri = inc.get("uri", "")
                    incs.append((normalize_uri(uri), int(inc.get("lineNumber", 0) or 0),
                                 is_nonsource_uri(uri)))
                rules.setdefault(rule, []).extend(incs)
        out[name] = {
            "rules": rules,
            "tags": set(rs.get("tags") or []),
            "unmatched": set(rs.get("unmatched") or []),
        }
    return out


# ── diff ────────────────────────────────────────────────────────────────────

class Divergence:
    def __init__(self, test, ruleset, rule, kind, detail):
        self.test, self.ruleset, self.rule = test, ruleset, rule
        self.kind, self.detail = kind, detail  # kind: java-fewer | java-more | tag | unmatched

    def __repr__(self):
        return f"{self.kind} {self.test}/{self.ruleset}/{self.rule}: {self.detail}"


def _diff_incidents(gi, ai):
    """Returns (removed, added) incidents for one (ruleset, rule).

    Source paths are matched exactly on (uri, line). Non-source paths (decompiled / dependency) are
    matched by incident COUNT per file, ignoring line numbers — decompiler line numbers are not
    comparable across providers, so a pure line shift must not count as a divergence.
    """
    from collections import Counter
    removed, added = [], []

    src_g = Counter((u, l) for (u, l, ns) in gi if not ns)
    src_a = Counter((u, l) for (u, l, ns) in ai if not ns)
    for key in (src_g - src_a).elements():
        removed.append((key[0], key[1], False))
    for key in (src_a - src_g).elements():
        added.append((key[0], key[1], False))

    dep_g = Counter(u for (u, l, ns) in gi if ns)
    dep_a = Counter(u for (u, l, ns) in ai if ns)
    for uri in sorted(set(dep_g) | set(dep_a)):
        delta = dep_g[uri] - dep_a[uri]
        for _ in range(delta):
            removed.append((uri, None, True))
        for _ in range(-delta):
            added.append((uri, None, True))
    return removed, added


def diff_test(test, golden, actual, cfg):
    divs = []
    # Optional: scope to java-provider rules only (ignore builtin-only rules). None = no scoping.
    java_rule_ids = cfg.get("java_rule_ids")
    g_rs, a_rs = parse_rulesets(golden), parse_rulesets(actual)
    for name in sorted(set(g_rs) | set(a_rs)):
        g = g_rs.get(name, {"rules": {}, "tags": set(), "unmatched": set()})
        a = a_rs.get(name, {"rules": {}, "tags": set(), "unmatched": set()})
        for rule in sorted(set(g["rules"]) | set(a["rules"])):
            if java_rule_ids is not None and rule not in java_rule_ids:
                continue  # builtin-only rule: out of scope for the java-provider differential
            removed, added = _diff_incidents(g["rules"].get(rule, []), a["rules"].get(rule, []))
            for inc in removed:
                divs.append(Divergence(test, name, rule, "java-fewer", inc))
            for inc in added:
                divs.append(Divergence(test, name, rule, "java-more", inc))
        if cfg.get("diff_tags", True):
            for tag in sorted(g["tags"] - a["tags"]):
                divs.append(Divergence(test, name, tag, "tag-dropped", tag))  # detects FEWER techs
            for tag in sorted(a["tags"] - g["tags"]):
                divs.append(Divergence(test, name, tag, "tag-added", tag))    # detects MORE techs
        # NOTE: `unmatched` rule lists are intentionally NOT diffed — a rule is in `unmatched` iff it
        # produced no incidents, so diffing it just double-counts the incident signal (java-more/fewer)
        # as noise. Incidents (violations + insights) are the source of truth.
    return divs


# ── classify + gate ─────────────────────────────────────────────────────────

def classify(div, ledger):
    for e in ledger:
        tests = entry_tests(e)
        if tests and div.test not in tests:
            continue
        if not rule_matches(e.get("rule"), div.rule):
            continue
        dirs = e.get("direction")
        if dirs is not None:
            if isinstance(dirs, str):
                dirs = [dirs]
            if div.kind not in dirs:
                continue  # entry only covers some directions (e.g. accept java-more/tag-added,
                          # but still flag java-fewer/tag-dropped regressions)
        cls, status = e.get("classification"), e.get("status")
        if cls in ("go-wrong", "benign") and status in ("accepted", "excluded"):
            return EXPLAINED
        if cls == "java-wrong":
            return KNOWN_BUG
        if status == "needs-triage":
            return PENDING
    return UNEXPLAINED


def gate(divergences, ledger, allow_known=False):
    buckets = {EXPLAINED: [], KNOWN_BUG: [], PENDING: [], UNEXPLAINED: []}
    for d in divergences:
        buckets[classify(d, ledger)].append(d)
    failing = bool(buckets[UNEXPLAINED]) or bool(buckets[PENDING])
    if buckets[KNOWN_BUG] and not allow_known:
        failing = True
    return (1 if failing else 0), buckets


def format_report(buckets):
    lines = ["=== koncur differential triage ==="]
    for label in (UNEXPLAINED, PENDING, KNOWN_BUG, EXPLAINED):
        items = buckets[label]
        lines.append(f"\n[{label}] {len(items)}")
        for d in items[:50]:
            lines.append(f"  - {d}")
        if len(items) > 50:
            lines.append(f"  ... and {len(items) - 50} more")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Ledger-driven koncur differential triage")
    ap.add_argument("--ledger", required=True)
    ap.add_argument("--test", required=True)
    ap.add_argument("--golden", required=True)
    ap.add_argument("--actual", required=True)
    ap.add_argument("--allow-known", action="store_true",
                    help="treat ledger-tracked java-wrong as expected (non-failing)")
    ap.add_argument("--rulesets",
                    help="path to the rulesets dir; scope the diff to java-provider rules "
                         "(java.referenced / java.dependency), ignoring builtin-only rules so "
                         "ruleset/analyzer-version skew doesn't masquerade as a provider divergence")
    args = ap.parse_args(argv)

    ledger, cfg = load_ledger(args.ledger)
    if args.test in (cfg.get("exclude_tests") or []):
        print(f"SKIPPED: {args.test} is excluded (not a Java-provider test)")
        return 0
    if args.rulesets:
        java_ids = load_java_rule_ids(args.rulesets)
        if not java_ids:
            # Empty set would skip EVERY rule -> a false "clean". Refuse to scope; compare all.
            print(f"WARNING: no java-provider rules found under {args.rulesets}; "
                  f"NOT scoping (comparing all rules) to avoid masking divergences.\n", file=sys.stderr)
        else:
            cfg["java_rule_ids"] = java_ids
            print(f"Scoped to {len(java_ids)} java-provider rules (from {args.rulesets})\n")
    with open(args.golden) as f:
        golden = yaml.safe_load(f)
    with open(args.actual) as f:
        actual = yaml.safe_load(f)

    divs = diff_test(args.test, golden, actual, cfg)
    code, buckets = gate(divs, ledger, allow_known=args.allow_known)
    print(format_report(buckets))
    return code


if __name__ == "__main__":
    sys.exit(main())
