#!/usr/bin/env python3
"""Unit tests for koncur_triage. Run: python3 -I -m unittest -v (from testing/)."""
import unittest
import koncur_triage as kt

CFG = dict(kt.DEFAULT_CFG)


def ruleset(name, violations=None, tags=None, unmatched=None):
    rs = {"name": name}
    if violations:
        rs["violations"] = violations
    if tags:
        rs["tags"] = tags
    if unmatched:
        rs["unmatched"] = unmatched
    return rs


def incident(uri, line):
    return {"uri": uri, "lineNumber": line, "message": "ignored text"}


class DiffTests(unittest.TestCase):
    def test_identical_outputs_no_divergence(self):
        doc = [ruleset("rs", {"r1": {"incidents": [incident("file:///source/A.java", 10)]}})]
        self.assertEqual(kt.diff_test("t", doc, doc, CFG), [])

    def test_java_fewer_and_more(self):
        golden = [ruleset("rs", {"r1": {"incidents": [incident("file:///source/A.java", 10)]}})]
        actual = [ruleset("rs", {"r1": {"incidents": [incident("file:///source/B.java", 20)]}})]
        kinds = sorted(d.kind for d in kt.diff_test("t", golden, actual, CFG))
        self.assertEqual(kinds, ["java-fewer", "java-more"])

    def test_message_drift_is_not_a_divergence(self):
        # same uri+line, different message -> ignored
        golden = [ruleset("rs", {"r1": {"incidents": [
            {"uri": "file:///source/A.java", "lineNumber": 5, "message": "old"}]}})]
        actual = [ruleset("rs", {"r1": {"incidents": [
            {"uri": "file:///source/A.java", "lineNumber": 5, "message": "NEW TEXT"}]}})]
        self.assertEqual(kt.diff_test("t", golden, actual, CFG), [])

    def test_dependency_line_shift_ignored(self):
        # dependency uri, different line, same count per file -> no divergence (count-based)
        golden = [ruleset("rs", {"r1": {"incidents": [incident("file:///cache/m2/x/Foo.java", 40)]}})]
        actual = [ruleset("rs", {"r1": {"incidents": [incident("file:///m2/x/Foo.java", 41)]}})]
        self.assertEqual(kt.diff_test("t", golden, actual, CFG), [])

    def test_decompiled_binary_line_shift_ignored(self):
        # java-project/ decompiled output: decompiler line numbers differ; same count -> no divergence
        golden = [ruleset("rs", {"r1": {"incidents": [
            incident("file:///source/java-project/src/main/java/A.java", 7),
            incident("file:///source/java-project/src/main/java/A.java", 8)]}})]
        actual = [ruleset("rs", {"r1": {"incidents": [
            incident("file:///source/java-project/src/main/java/A.java", 12),
            incident("file:///source/java-project/src/main/java/A.java", 13)]}})]
        self.assertEqual(kt.diff_test("t", golden, actual, CFG), [])

    def test_nonsource_count_mismatch_is_divergence(self):
        # decompiled: golden has 2 incidents in the file, actual 1 -> one java-fewer
        golden = [ruleset("rs", {"r1": {"incidents": [
            incident("file:///source/java-project/src/main/java/A.java", 7),
            incident("file:///source/java-project/src/main/java/A.java", 8)]}})]
        actual = [ruleset("rs", {"r1": {"incidents": [
            incident("file:///source/java-project/src/main/java/A.java", 7)]}})]
        kinds = [d.kind for d in kt.diff_test("t", golden, actual, CFG)]
        self.assertEqual(kinds, ["java-fewer"])

    def test_application_source_no_tolerance(self):
        golden = [ruleset("rs", {"r1": {"incidents": [incident("file:///source/A.java", 10)]}})]
        actual = [ruleset("rs", {"r1": {"incidents": [incident("file:///source/A.java", 11)]}})]
        self.assertEqual(len(kt.diff_test("t", golden, actual, CFG)), 2)  # fewer + more

    def test_unmatched_rule_divergence(self):
        golden = [ruleset("rs", unmatched=["only-in-golden"])]
        actual = [ruleset("rs", unmatched=["new-rule-00010"])]
        kinds = {(d.kind, d.rule) for d in kt.diff_test("t", golden, actual, CFG)}
        self.assertIn(("unmatched", "new-rule-00010"), kinds)


class ClassifyGateTests(unittest.TestCase):
    def _div(self, test="daytrader", ruleset="ee-to-quarkus", rule="r1", kind="java-fewer"):
        return kt.Divergence(test, ruleset, rule, kind, ("u", 1, False))

    def test_go_wrong_accepted_is_explained(self):
        ledger = [{"test": "daytrader", "rule": "ee-to-quarkus-00020",
                   "classification": "go-wrong", "status": "accepted"}]
        d = self._div(rule="ee-to-quarkus-00020")
        self.assertEqual(kt.classify(d, ledger), kt.EXPLAINED)

    def test_benign_wildcard_rule_match(self):
        ledger = [{"tests": ["daytrader"], "rule": "hibernate-orm7-to-quarkus-*",
                   "classification": "benign", "status": "accepted"}]
        d = self._div(rule="hibernate-orm7-to-quarkus-00050")
        self.assertEqual(kt.classify(d, ledger), kt.EXPLAINED)

    def test_java_wrong_is_known_bug(self):
        ledger = [{"test": "tomcat-legacy", "classification": "java-wrong", "status": "open"}]
        d = self._div(test="tomcat-legacy", rule="anything")
        self.assertEqual(kt.classify(d, ledger), kt.KNOWN_BUG)

    def test_needs_triage_is_pending(self):
        ledger = [{"tests": ["daytrader"], "rule": "r1",
                   "classification": "benign", "status": "needs-triage"}]
        self.assertEqual(kt.classify(self._div(), ledger), kt.PENDING)

    def test_no_entry_is_unexplained(self):
        self.assertEqual(kt.classify(self._div(), []), kt.UNEXPLAINED)

    def test_wrong_test_does_not_match(self):
        ledger = [{"test": "seam-booking", "classification": "go-wrong", "status": "accepted"}]
        self.assertEqual(kt.classify(self._div(test="daytrader"), ledger), kt.UNEXPLAINED)

    def test_gate_passes_when_all_explained(self):
        ledger = [{"test": "daytrader", "classification": "go-wrong", "status": "accepted"}]
        code, buckets = kt.gate([self._div()], ledger)
        self.assertEqual(code, 0)
        self.assertEqual(len(buckets[kt.EXPLAINED]), 1)

    def test_gate_fails_on_unexplained(self):
        code, _ = kt.gate([self._div()], [])
        self.assertEqual(code, 1)

    def test_gate_known_bug_fails_by_default_but_allowed_with_flag(self):
        ledger = [{"test": "daytrader", "classification": "java-wrong", "status": "open"}]
        self.assertEqual(kt.gate([self._div()], ledger)[0], 1)
        self.assertEqual(kt.gate([self._div()], ledger, allow_known=True)[0], 0)


if __name__ == "__main__":
    unittest.main()
