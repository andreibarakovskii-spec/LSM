import os
import tempfile
import unittest

from relay import MessageStore


class RelayStoreTests(unittest.TestCase):
    def setUp(self):
        fd, self.path = tempfile.mkstemp(prefix="lsm-relay-", suffix=".db")
        os.close(fd)
        self.store = MessageStore(self.path)

    def tearDown(self):
        try:
            os.remove(self.path)
        except FileNotFoundError:
            pass

    def test_offline_recipient_receives_later_then_ack_removes(self):
        self.assertEqual(self.store.count(), 0)
        self.store.enqueue("m1", "bob", "ciphertext-only", ttl_seconds=3600, now=1000)
        self.assertEqual(self.store.count(), 1)

        pending = self.store.pending("bob", now=1200)
        self.assertEqual(len(pending), 1)
        self.assertEqual(pending[0]["id"], "m1")
        self.assertEqual(pending[0]["ciphertext"], "ciphertext-only")

        self.assertTrue(self.store.ack("bob", "m1"))
        self.assertEqual(self.store.pending("bob", now=1201), [])
        self.assertEqual(self.store.count(), 0)

    def test_duplicate_message_id_is_idempotent(self):
        self.store.enqueue("m1", "bob", "ciphertext-v1", now=1000)
        self.store.enqueue("m1", "bob", "ciphertext-v1", now=1001)
        self.assertEqual(self.store.count(), 1)

    def test_wrong_recipient_cannot_ack(self):
        self.store.enqueue("m1", "bob", "ciphertext", now=1000)
        self.assertFalse(self.store.ack("alice", "m1"))
        self.assertEqual(self.store.count(), 1)

    def test_expired_messages_are_deleted(self):
        self.store.enqueue("m1", "bob", "ciphertext", ttl_seconds=60, now=1000)
        self.assertEqual(self.store.pending("bob", now=1059)[0]["id"], "m1")
        self.assertEqual(self.store.pending("bob", now=1060), [])
        self.assertEqual(self.store.count(), 0)


if __name__ == "__main__":
    unittest.main()
