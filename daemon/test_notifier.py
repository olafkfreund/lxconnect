import unittest

import notifier

FULL_CAPS = {"body", "body-markup", "body-hyperlinks", "body-images",
             "actions", "icon-static", "inline-reply"}

CHAT = {
    "key": "0|com.whatsapp|1|null|10123",
    "packageName": "com.whatsapp",
    "appLabel": "WhatsApp",
    "title": "Alex",
    "text": "see https://example.com",
    "titleMarkup": "Alex",
    "textMarkup": 'see <a href="https://example.com">https://example.com</a>',
    "urls": ["https://example.com"],
    "hasLargeIcon": True,
    "hasPicture": False,
    "actions": [
        {"index": 0, "title": "Reply", "isReply": True},
        {"index": 1, "title": "Mark as read", "isReply": False},
    ],
}


def make(call_tool=None, fetch_image=None):
    return notifier.Notifier(call_tool or (lambda *a: None), fetch_image, bus=object())


class TestBuild(unittest.TestCase):
    def test_uses_markup_when_supported(self):
        summary, body, _, _ = make().build(CHAT, FULL_CAPS)
        self.assertEqual(summary, "Alex")
        self.assertIn('<a href="https://example.com">', body)

    def test_falls_back_to_plain_text_without_markup(self):
        _, body, _, _ = make().build(CHAT, {"body", "actions"})
        self.assertEqual(body, "see https://example.com")
        self.assertNotIn("<a href", body)

    def test_default_action_is_offered(self):
        _, _, actions, _ = make().build(CHAT, FULL_CAPS)
        self.assertEqual(actions[0], "default")

    def test_inline_reply_replaces_the_reply_button(self):
        _, _, actions, hints = make().build(CHAT, FULL_CAPS)
        self.assertNotIn("reply", actions)
        self.assertIn("x-kde-reply-placeholder-text", hints)

    def test_inline_reply_action_id_is_present(self):
        # The hint alone renders no text field: without the action id there is
        # neither an inline field nor the fallback button, so reply is
        # unreachable on exactly the servers that support it.
        _, _, actions, _ = make().build(CHAT, FULL_CAPS)
        self.assertIn("inline-reply", actions)

    def test_some_way_to_reply_exists_for_every_capability_mix(self):
        for caps in ({"actions", "inline-reply"}, {"actions"}, FULL_CAPS,
                     FULL_CAPS - {"inline-reply"}):
            _, _, actions, _ = make().build(CHAT, caps)
            self.assertTrue({"inline-reply", "reply"} & set(actions),
                            f"no way to reply with caps={sorted(caps)}")

    def test_reply_button_when_server_lacks_inline_reply(self):
        caps = FULL_CAPS - {"inline-reply"}
        _, _, actions, hints = make().build(CHAT, caps)
        self.assertIn("reply", actions)
        self.assertNotIn("x-kde-reply-placeholder-text", hints)

    def test_non_reply_actions_are_exposed_by_index(self):
        _, _, actions, _ = make().build(CHAT, FULL_CAPS)
        self.assertIn("action-1", actions)
        self.assertEqual(actions[actions.index("action-1") + 1], "Mark as read")
        # The reply action must not also appear as a plain button.
        self.assertNotIn("action-0", actions)

    def test_no_actions_when_server_lacks_the_capability(self):
        _, _, actions, _ = make().build(CHAT, {"body"})
        self.assertEqual(actions, [])

    def test_link_only_in_intent_is_appended(self):
        notif = dict(CHAT, text="tap here", textMarkup="tap here")
        _, body, _, _ = make().build(notif, FULL_CAPS)
        self.assertIn('<a href="https://example.com">', body)

    def test_appended_urls_are_escaped(self):
        # The URL comes from the phone; an unescaped quote would break out of
        # href="..." and corrupt the markup the desktop server parses.
        evil = 'https://e.test/?a=1&b=2"><script'
        notif = dict(CHAT, text="tap", textMarkup="tap", urls=[evil])
        _, body, _, _ = make().build(notif, FULL_CAPS)
        self.assertNotIn('"><script', body)
        self.assertIn("&amp;", body)
        self.assertIn("&quot;", body)

    def test_call_category_is_urgent(self):
        _, _, _, hints = make().build(dict(CHAT, category="call"), FULL_CAPS)
        self.assertEqual(hints["urgency"], 2)

    def test_title_falls_back_to_app_label(self):
        summary, _, _, _ = make().build(
            {"packageName": "com.foo", "appLabel": "Foo"}, FULL_CAPS)
        self.assertEqual(summary, "Foo")


class TestSignalRouting(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.n = make(call_tool=lambda name, args: self.calls.append((name, args)))
        self.n._id_to_key[7] = CHAT["key"]
        self.n._key_to_id[CHAT["key"]] = 7

    def test_inline_reply_is_sent_to_the_phone(self):
        self.n.handle_signal("NotificationReplied", [7, "on my way"])
        self.assertEqual(self.calls, [("reply_to_notification", {
            "notificationKey": CHAT["key"], "replyText": "on my way"})])

    def test_body_click_opens_the_app_on_the_phone(self):
        self.n.handle_signal("ActionInvoked", [7, "default"])
        self.assertEqual(self.calls, [("activate_notification",
                                       {"notificationKey": CHAT["key"]})])

    def test_action_button_maps_back_to_its_index(self):
        self.n.handle_signal("ActionInvoked", [7, "action-1"])
        self.assertEqual(self.calls, [("invoke_notification_action", {
            "notificationKey": CHAT["key"], "actionIndex": 1})])

    def test_unknown_notification_id_is_ignored(self):
        self.n.handle_signal("ActionInvoked", [999, "default"])
        self.assertEqual(self.calls, [])

    def test_empty_reply_is_not_sent(self):
        self.n.handle_signal("NotificationReplied", [7, ""])
        self.assertEqual(self.calls, [])

    def test_tracked_ids_stay_bounded(self):
        # A server that never emits NotificationClosed would otherwise leak one
        # entry per notification for the daemon's whole uptime.
        for i in range(notifier.MAX_TRACKED + 50):
            self.n._key_to_id[f"k{i}"] = i
            self.n._id_to_key[i] = f"k{i}"
            self.n._forget_oldest()
        self.assertLessEqual(len(self.n._key_to_id), notifier.MAX_TRACKED)
        self.assertLessEqual(len(self.n._id_to_key), notifier.MAX_TRACKED + 1)

    def test_close_forgets_the_mapping_both_ways(self):
        self.n.handle_signal("NotificationClosed", [7, 2])
        self.assertNotIn(7, self.n._id_to_key)
        self.assertNotIn(CHAT["key"], self.n._key_to_id)


if __name__ == "__main__":
    unittest.main()
