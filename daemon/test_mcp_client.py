"""Unit tests for MCPClient — no device or TLS needed.

A FakeServer replaces the client's outbound _post with a responder that turns each
POST into an inbound SSE message via _on_message, exercising the real handshake,
id-correlation, error, timeout and notification logic.
"""
import base64
import json
import threading
import unittest

import mcp_client


class FakeServer:
    def __init__(self, client, responder):
        self.client = client
        self.responder = responder  # fn(payload) -> reply dict or None
        client._post = self._post
        client._url = "http://mock/"
        client._endpoint = "?sessionId=test"

    def _post(self, payload):
        reply = self.responder(payload)
        if reply is not None:
            threading.Thread(
                target=self.client._on_message,
                args=(json.dumps(reply),), daemon=True,
            ).start()


def result_msg(rid, text=None, image_b64=None, is_error=False):
    content = []
    if text is not None:
        content.append({"type": "text", "text": text})
    if image_b64 is not None:
        content.append({"type": "image", "data": image_b64, "mimeType": "image/jpeg"})
    return {"jsonrpc": "2.0", "id": rid, "result": {"content": content, "isError": is_error}}


class TestMCPClient(unittest.TestCase):
    def _ready_client(self, responder):
        c = mcp_client.MCPClient()
        FakeServer(c, responder)
        c._ready.set()  # bypass the SSE handshake for call-level tests
        return c

    def test_call_returns_text(self):
        c = self._ready_client(lambda p: result_msg(p["id"], text="Battery: 50%"))
        r = c.call("get_device_info", {})
        self.assertEqual(r["text"], "Battery: 50%")
        self.assertFalse(r["isError"])

    def test_call_returns_image(self):
        b = base64.b64encode(b"\xff\xd8\xffjpeg").decode()
        c = self._ready_client(lambda p: result_msg(p["id"], image_b64=b))
        r = c.call("take_picture", {})
        self.assertEqual(r["images"], [b])
        self.assertEqual(r["text"], "")

    def test_error_result_flag(self):
        c = self._ready_client(lambda p: result_msg(p["id"], text="nope", is_error=True))
        r = c.call("reply_to_notification", {"notificationKey": "x", "replyText": "y"})
        self.assertTrue(r["isError"])

    def test_jsonrpc_error_raises(self):
        c = self._ready_client(
            lambda p: {"jsonrpc": "2.0", "id": p["id"], "error": {"message": "bad"}})
        with self.assertRaises(RuntimeError):
            c.call("send_sms", {})

    def test_timeout_raises(self):
        c = self._ready_client(lambda p: None)  # server never replies
        with self.assertRaises(TimeoutError):
            c.call("status", {}, timeout=1)

    def test_id_correlation(self):
        # overlapping concurrent calls each get their own matching result
        c = self._ready_client(lambda p: result_msg(p["id"], text=f"id{p['id']}"))
        results = {}

        def worker(n):
            results[n] = c.call("get_device_info", {"n": n})["text"]

        ts = [threading.Thread(target=worker, args=(i,)) for i in range(5)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        self.assertEqual(len(set(results.values())), 5)

    def test_handshake_sets_ready(self):
        c = mcp_client.MCPClient()
        FakeServer(c, lambda p: result_msg(-1, text="ok")
                   if p.get("method") == "initialize" else None)
        c._on_line("endpoint", "?sessionId=test")  # spawns the handshake thread
        self.assertTrue(c._ready.wait(2))

    def test_notification_callback(self):
        got = []
        c = mcp_client.MCPClient(on_notification=got.append)
        c._on_message('{"method":"notifications/phone_notification","params":{"title":"Hi"}}')
        self.assertEqual(got[0]["title"], "Hi")

    def test_not_ready_raises(self):
        c = mcp_client.MCPClient()
        with self.assertRaises(RuntimeError):
            c.call("status", {}, timeout=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
