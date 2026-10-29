# Authnkey source attribution

Reference: https://github.com/mimi89999/Authnkey

Reviewed revision: e8bc2193017313eb7322e8d58943926d9490e278 (MIT; Michel Le Bihan).

The Cbor, Cose, CTAP, FidoCommands, FidoTransport, TransportType and AuthnkeyError Kotlin files are adapted from that revision. Monica adds bounded CBOR parsing, trailing-data and duplicate-key rejection, and stricter authenticator checks. USB/NFC adapters, PIN protocols 1/2, request validation, Compose UI and provider integration are implemented separately. The MIT notice is retained in Authnkey-MIT.txt and shipped in assets/hardware_authnkey_license.txt.

The unchanged Public Suffix List was downloaded from https://publicsuffix.org/list/public_suffix_list.dat (version 2026-10-28_13-15-55_UTC, commit 9dfbe81931367b3f0af21035c36ae7eeb830dd88). It retains its MPL-2.0 header; the license is retained in Public-Suffix-List-MPL-2.0.txt and assets/hardware_public_suffix_license.txt.
