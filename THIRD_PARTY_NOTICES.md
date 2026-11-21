# Third-party notices

## java-airplay (MIT)
Files under `app/src/main/java/com/github/serezhka/airplay/protocol/` and the FairPlay tables in
`app/src/main/resources/fairplay/` are taken from https://github.com/serezhka/java-airplay
(commit 61f6be78ff4f22133987a38d9fdb95da47428be6) and adapted for Android (no Lombok/SLF4J,
no Java 9+ APIs, dependency-free plist parsing, persisted pairing identity).
Copyright (c) 2022 Sergei Fedorov. Licensed under the MIT License, see `LICENSE-java-airplay.txt`.
The receiver feature set, mDNS TXT records, and `/info` fields also follow java-airplay.

The FairPlay key-unwrapping routines (`SapKeySchedule`, `SapHash`, `FairPlayDigest`,
`FairPlayScrambler`, tables) are java-airplay's Java port of the community reverse-engineered
"playfair" code also used by RPiPlay/UxPlay. Check the upstream licensing of that code before
redistributing this app commercially.

## Apple ALAC reference decoder (Apache License 2.0)
`app/src/main/java/com/groundscreen/tv/media/AlacDecoder.java` is a Java port of Apple's ALAC
decoder (ALACDecoder.cpp, ag_dec.c, dp_dec.c, matrix_dec.c). Copyright (c) 2011 Apple Inc.
https://github.com/macosforge/alac. Licensed under the Apache License, Version 2.0:
http://www.apache.org/licenses/LICENSE-2.0

## Runtime libraries (from Maven Central)
- net.i2p.crypto:eddsa 0.3.0 (Ed25519), CC0 1.0 Universal

X25519 (pair-verify key exchange) is implemented in `com.groundscreen.tv.crypto.X25519` (RFC 7748).
The JVM test harness uses org.whispersystems:curve25519-java (GPLv3) only as an independent
reference at test time. It is not bundled in the app.
