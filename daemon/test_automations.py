"""Unit tests for the automation rule engine (pure logic, no device)."""
import json
import os
import tempfile
import unittest

import automations


def notif(package="com.whatsapp", title="Boss", text="call me"):
    return {"package": package, "title": title, "text": text}


class TestMatching(unittest.TestCase):
    def test_substring_case_insensitive(self):
        self.assertTrue(automations.matches({"match": {"package": "whatsapp"}}, notif()))
        self.assertTrue(automations.matches({"match": {"text": "CALL"}}, notif()))

    def test_fields_are_anded(self):
        r = {"match": {"package": "whatsapp", "title": "boss"}}
        self.assertTrue(automations.matches(r, notif()))
        self.assertFalse(automations.matches(r, notif(title="Mom")))

    def test_empty_match_is_catch_all(self):
        self.assertTrue(automations.matches({"match": {}}, notif()))

    def test_no_match(self):
        self.assertFalse(automations.matches({"match": {"package": "telegram"}}, notif()))

    def test_regex(self):
        r = {"match": {"text": r"call|urgent", "regex": True}}
        self.assertTrue(automations.matches(r, notif(text="urgent!")))
        self.assertFalse(automations.matches(r, notif(text="hello")))

    def test_bad_regex_is_safe(self):
        r = {"match": {"text": "(", "regex": True}}
        self.assertFalse(automations.matches(r, notif()))


class TestProcess(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.notifs = []

    def _call(self, tool, args):
        self.calls.append((tool, args))

    def _notify(self, title, body):
        self.notifs.append((title, body))

    def test_fires_tool_and_notify_with_template(self):
        rules = [{"name": "R", "match": {"package": "whatsapp"},
                  "actions": [{"tool": "ring_device", "arguments": {"action": "start"}},
                              {"notify": "{title}: {text}"}]}]
        fired = automations.process(notif(), rules, self._call, self._notify)
        self.assertEqual(self.calls, [("ring_device", {"action": "start"})])
        self.assertEqual(self.notifs, [("R", "Boss: call me")])
        self.assertEqual(len(fired), 2)

    def test_no_match_no_fire(self):
        rules = [{"name": "R", "match": {"package": "telegram"}, "actions": [{"notify": "x"}]}]
        self.assertEqual(automations.process(notif(), rules, self._call, self._notify), [])

    def test_multiple_rules_fire(self):
        rules = [{"match": {}, "actions": [{"notify": "a"}]},
                 {"match": {"text": "call"}, "actions": [{"tool": "t", "arguments": {}}]}]
        fired = automations.process(notif(), rules, self._call, self._notify)
        self.assertEqual(len(fired), 2)

    def test_action_error_is_isolated(self):
        def boom(t, a):
            raise RuntimeError("nope")
        rules = [{"name": "R", "match": {}, "actions": [{"tool": "t"}, {"notify": "ok"}]}]
        automations.process(notif(), rules, boom, self._notify)
        self.assertEqual(self.notifs, [("R", "ok")])  # notify still fires after tool error


class TestLoad(unittest.TestCase):
    def test_load_rules(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "rules.json")
        with open(p, "w") as f:
            json.dump({"rules": [{"name": "x", "match": {}, "actions": []}]}, f)
        self.assertEqual(len(automations.load_rules(p)), 1)

    def test_load_missing_is_empty(self):
        self.assertEqual(automations.load_rules("/nonexistent/lxconnect/rules.json"), [])

    def test_load_malformed_is_empty(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "rules.json")
        with open(p, "w") as f:
            f.write("{ not json")
        self.assertEqual(automations.load_rules(p), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
