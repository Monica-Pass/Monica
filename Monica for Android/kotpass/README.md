# Kotpass 0.10.0 with a scoped KDF hook

Source: https://github.com/keemobile/kotpass/tree/4b6455872fb882b74787c9ba18dd7024a9dde69d/kotpass/src/main

The 104 upstream source files and their license notices are retained. The only
upstream source change is in cryptography/KeyTransform.kt: optional, thread
scoped Argon2 and AES-KDF providers can supply the same 32-byte transform, with the original
implementation used when the provider returns null. Composite-key copies are
cleared after either path. KdfTransform.kt contains the new hook.

Monica installs the hook only around codec calls. It does not cache transformed
keys, change KDF parameters, replace the file format, or bypass HMAC verification.
Unrecognized parameters, secret/associated data and unavailable JNI use the
original Kotpass implementation. Codec calls outside the scope remain unchanged.

This local source module replaces the Maven artifact so ordinary and F-Droid
builds use the same explicit source patch without reflection or bytecode rewriting.
LICENSE.txt and the embedded Bouncy Castle notices remain part of the source.

The module uses JDK 17 to compile Java 11 bytecode, matching the Android app.
Tests cover scope restoration, worker isolation, fallback and borrowed-input cleanup.
