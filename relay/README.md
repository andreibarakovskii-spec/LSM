# LSM store-and-forward relay test

This relay is intentionally minimal. It stores only opaque `ciphertext` envelopes addressed to a recipient ID. It does not receive or persist plaintext message content or decryption keys.

Tested flow:

1. Sender creates a message and keeps it in the encrypted local outbox.
2. Relay accepts an opaque envelope and stores it while the recipient is offline.
3. Recipient later polls pending messages and receives the same ciphertext.
4. Recipient sends an ACK for the message ID.
5. Relay deletes the envelope.

Properties in this prototype:

- idempotent message IDs (`INSERT OR IGNORE`)
- automatic expiry, maximum TTL seven days
- ACK is scoped to recipient + message ID
- request bodies/ciphertext are not logged
- body size is limited to 64 KiB
- no plaintext fallback on the Android local outbox

Not production security yet:

- recipient authentication is not implemented
- ACK authentication is not implemented
- end-to-end ratchet/key exchange is not implemented
- TLS termination/deployment is not included

The relay test is a transport/storage proof only. Production E2E will be added as a separate cryptographic layer before real-user testing.
