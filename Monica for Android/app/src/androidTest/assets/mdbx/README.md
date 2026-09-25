# Synthetic CLI interoperability fixture

`cli-origin-20260926.mdbx` was created with the real Monica CLI 0.5.0 debug binary
on 2026-09-26, using `init --tiga sky`, followed by `connect cli-fixture`.
It contains one API token titled `CLI 原生条目`, with the inert API base
`https://example.invalid/`. No network service was contacted.

The password is `Synthetic Android CLI roundtrip 20260926!`; the fake token is
`synthetic-interop-token-no-service-access`. Both are deliberately public test data.
Commands received these values as a JSON object on `--secrets-stdin`, using an
isolated `--config` and `--vault` path.

`Mdbx2CliCompatibilityInstrumentedTest` opens this file using Android's bundled
native engine, preserves the CLI payload, creates and updates an Android password,
reopens the file, and writes a portable backup for an independent CLI read.
