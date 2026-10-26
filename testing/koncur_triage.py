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


# ── koncur output parsing ───────────────────────────────────────────────────

def is_dependency_uri(uri):
    return "/m2/" in uri or "/.m2/" in uri or "/cache/m2/" in uri


def normalize_uri(uri):
    """Collapse scheme + known base-dir prefixes so Go and Java uris are comparable."""
    u = uri
    if u.startswith("file://"):
        u = u[len("file://"):]
    for root in ("/cache/m2/", "/opt/input/source/"):
        i = u.find(root)
        if i >= 0:
            return ("m2/" if "m2" in root else "source/") + u[i + len(root):]
    for root in ("/m2/", "/source/"):
        i = u.find(root)
        if i >= 0:
            return root.strip("/") + "/" + u[i + len(root):]
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
                                 is_dependency_uri(uri)))
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


def _match_incident(target, pool, tol):
    """True if an incident equal to target (uri + line within tolerance) exists in pool."""
    uri, line, is_dep = target
    allow = tol if is_dep else 0
    for (u2, l2, _) in pool:
        if u2 == uri and abs(l2 - line) <= allow:
            return True
    return False


def diff_test(test, golden, actual, cfg):
    tol = (cfg.get("line_tolerance") or {}).get("decompiled_or_dependency", 2)
    divs = []
    g_rs, a_rs = parse_rulesets(golden), parse_rulesets(actual)
    for name in sorted(set(g_rs) | set(a_rs)):
        g = g_rs.get(name, {"rules": {}, "tags": set(), "unmatched": set()})
        a = a_rs.get(name, {"rules": {}, "tags": set(), "unmatched": set()})
        for rule in sorted(set(g["rules"]) | set(a["rules"])):
            gi, ai = g["rules"].get(rule, []), a["rules"].get(rule, [])
            for inc in gi:
                if not _match_incident(inc, ai, tol):
                    divs.append(Divergence(test, name, rule, "java-fewer", inc))
            for inc in ai:
                if not _match_incident(inc, gi, tol):
                    divs.append(Divergence(test, name, rule, "java-more", inc))
        for tag in sorted(g["tags"] ^ a["tags"]):
            divs.append(Divergence(test, name, tag, "tag", tag))
        for rule in sorted(g["unmatched"] ^ a["unmatched"]):
            divs.append(Divergence(test, name, rule, "unmatched", rule))
    return divs


# ── classify + gate ─────────────────────────────────────────────────────────

def classify(div, ledger):
    for e in ledger:
        tests = entry_tests(e)
        if tests and div.test not in tests:
            continue
        if not rule_matches(e.get("rule"), div.rule):
            continue
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
    args = ap.parse_args(argv)

    ledger, cfg = load_ledger(args.ledger)
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
