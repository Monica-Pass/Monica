# Portable encrypted ZIP backup: V1 and optional V2

This contract covers the outer encryption of Android ZIP backups used by manual export/import and cloud backup. It does not change the ZIP contents, local master-password/MDK wrapping, KDBX, MDBX, Bitwarden JSON, or unversioned encrypted configuration strings.

## Default and compatibility

- New installations, existing settings without a format choice, and new manual-export pages use **V1**. V2 requires an explicit selection.
- Cloud backups persist the choice in their backup preferences; WebDAV and ordinary-edition OneDrive share these preferences and archive creation. Manual export has an independent per-page choice.
- Readers accept V1 and V2 automatically. No password/KDF retry across versions. Unsupported `MONICA_ENC_V*` containers must be rejected, never treated as plaintext ZIP.
- V2 may be unreadable by older Android builds, extensions and desktop clients. Retain earlier backups. Changing the choice affects newly created archives only; existing archives are not rewritten.

## Frozen V1

`ASCII("MONICA_ENC_V1") || salt[32] || nonce[12] || ciphertext || tag[16]`

PBKDF2-HMAC-SHA256, exactly 100000 iterations, 32-byte key; AES-256-GCM, 12-byte nonce, 16-byte tag, empty AAD. Header length 57; minimum envelope length 73. The old `salt:iv:ciphertext` Base64 configuration-string format also remains 100000 iterations. It does not acquire this file header.

## V2 header

All integers are unsigned, big-endian. The header is exactly 68 bytes. The present V2 implementation accepts **only** the following profile; fields are identifiers, not invitations to accept arbitrary costs or allocate attacker-selected sizes.

| Offset | Bytes | Value |
| --- | --- | --- |
| 0 | 13 | ASCII `MONICA_ENC_V2` |
| 13 | 1 | KDF ID `01`: PBKDF2-HMAC-SHA256 |
| 14 | 1 | Cipher ID `01`: AES-256-GCM |
| 15 | 1 | Flags `00` |
| 16 | 4 | Iterations `00 09 27 C0` (600000) |
| 20 | 2 | Salt length `00 20` (32) |
| 22 | 2 | Nonce length `00 0C` (12) |
| 24 | 32 | Random salt |
| 56 | 12 | Random nonce |
| 68 | variable | Ciphertext followed by the 16-byte GCM tag |

Derive `key = PBKDF2-HMAC-SHA256(passwordUtf8, salt, 600000, 32)`.
Encrypt with AES-256-GCM and **all 68 header bytes, including salt and nonce, as AAD**. The GCM tag is a 16-byte suffix. Empty plaintext is valid, with a minimum envelope size of 84 bytes. Fresh independent cryptographic randomness is required for every production salt and nonce.

Passwords are well-formed Unicode scalar sequences encoded as UTF-8. Do not trim, normalize, case-fold, or append NUL. Leading/trailing spaces and decomposed combining characters are significant. Reject unpaired UTF-16 surrogates before converting V2 passwords. The included vectors explicitly distinguish precomposed `é` from `e` + U+0301.

## Reader order and failure handling

1. Read the magic and require a known exact version. Check the version-specific minimum size.
2. For V2, validate every algorithm, flag, cost and length field against the exact profile above **before** running PBKDF2 or allocating from a declared length. Reject other costs, including zero, huge costs and otherwise plausible alternatives.
3. Derive the key and initialize AES-GCM with the declared fixed-size nonce. Authenticate all 68 header bytes as AAD.
4. Verify the entire ciphertext and tag before publishing plaintext or touching the restore destination. Reject tampering, truncation, wrong password and unknown versions. A header, parameter or authentication rejection must not modify the source or any existing restore destination, nor retry using V1 or plaintext interpretation. After successful authentication, callers must use owned temporary output and handle I/O failures; this API does not promise atomic replacement of an arbitrary existing destination.

The Android implementation retains the existing whole-file crypto approach; this format change is not a streaming or large-archive memory optimization. Header sizes/costs are bounded; callers remain responsible for archive size limits and ZIP extraction policy.

## Public conformance vectors

[portable-backup-encryption-vectors.json](portable-backup-encryption-vectors.json) contains 10 entirely synthetic vectors, generated independently using Python `hashlib.pbkdf2_hmac` and `cryptography` AESGCM. All 10 key and envelope values were independently reproduced with OpenJDK 17 `PBEKeySpec` and AES-GCM. This is host interoperability evidence; Android-provider and integrated backup-flow tests are separate.

Each entry includes the exact password, password UTF-8 hex, salt, nonce, cost, derived key, plaintext, header and complete envelope in hex. Test-only fixed salts/nonces MUST NOT be reused for production encryption. The fixtures cover ASCII, Chinese, emoji, combining characters, whitespace, empty plaintext and binary plaintext, in both formats.

## Client boundaries

The browser extension currently uses this Android V1 archive for both manual ZIP and WebDAV. Its local Argon2id vault envelope, `MONICA_EXTENSION_BACKUP` JSON, and ordinary `{version, items}` JSON are separate formats. V2 support belongs in its Android backup codec and detection paths, while keeping V1 export as default.

The independent Avalonia client currently uses its own PBKDF2-300000 JSON backup envelope. That is not Android V1 ZIP and must remain readable. Android ZIP import requires explicit V1/V2 outer decoding plus Android ZIP record mapping; adding a KDF constant alone does not create interoperability. Neither client should reinterpret native MDBX as this ZIP format.

## Cross-client implementation tracking

- [Browser extension V2 compatibility request #32](https://github.com/Monica-Pass/Monica-for-Extension/issues/32).
- [Avalonia Android ZIP compatibility request #88](https://github.com/Monica-Pass/Monica-by-Avalonia/issues/88).

These issues request implementation and verification. Their existence does not mean either client already supports V2 or complete Android ZIP record mapping. Keep V1 as the compatibility default until the receiving client has confirmed support.
