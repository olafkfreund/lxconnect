"""Trigger-based automations for lxconnect.

Watches incoming phone notifications (fed from the daemon's SSE loop) and runs
user-defined rules: match on package/title/text (substring or regex), then fire
actions — call any phone tool, or raise a desktop notification.

Rules live in ~/.config/lxconnect/rules.json:

    {
      "rules": [
        {
          "name": "Boss on WhatsApp",
          "match": {"package": "whatsapp", "text": "boss"},
          "actions": [
            {"tool": "ring_device", "arguments": {"action": "start"}},
            {"notify": "{title}: {text}"}
          ]
        }
      ]
    }

`match` fields are ANDed; an absent field matches anything, so `"match": {}` is a
catch-all. Set `"regex": true` in a rule's match to treat patterns as regexes.
`notify` templates may use {title} {text} {package}.
"""
import json
import os
import re

RULES_FILE = os.path.expanduser("~/.config/lxconnect/rules.json")


def load_rules(path=RULES_FILE):
    if not os.path.exists(path):
        return []
    try:
        with open(path) as f:
            return json.load(f).get("rules", [])
    except Exception as e:
        print(f"Failed to load rules ({e}); ignoring.")
        return []


def _field_matches(pattern, value, use_regex):
    if pattern is None:
        return True
    value = value or ""
    if use_regex:
        try:
            return re.search(pattern, value, re.IGNORECASE) is not None
        except re.error:
            return False
    return pattern.lower() in value.lower()


def matches(rule, notif):
    m = rule.get("match", {})
    use_regex = bool(m.get("regex", False))
    return all(
        _field_matches(m.get(field), notif.get(field), use_regex)
        for field in ("package", "title", "text")
    )


def _expand(template, notif):
    try:
        return template.format(
            title=notif.get("title", ""),
            text=notif.get("text", ""),
            package=notif.get("package", ""),
        )
    except Exception:
        return template


def process(notif, rules, call_tool, notify):
    """Run every matching rule's actions. `call_tool(name, args)` dispatches a tool;
    `notify(title, body)` raises a desktop notification. Returns a list of
    (rule_name, action_label) that fired — handy for logging and tests."""
    fired = []
    for rule in rules:
        if not matches(rule, notif):
            continue
        name = rule.get("name", "rule")
        for action in rule.get("actions", []):
            try:
                if "tool" in action:
                    call_tool(action["tool"], action.get("arguments", {}))
                    fired.append((name, f"tool:{action['tool']}"))
                elif "notify" in action:
                    notify(name, _expand(action["notify"], notif))
                    fired.append((name, "notify"))
            except Exception as e:
                print(f"Automation '{name}' action failed: {e}")
    return fired
