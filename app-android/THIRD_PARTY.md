# Third-party components in the Android app

Everything below ships inside the APK. The licence texts are in
`app/src/main/assets/licenses/`, and the app shows them under Settings > This app >
Licences.

## Fonts

All under the SIL Open Font License 1.1.

| Face | Use | Copyright | Licence text |
|---|---|---|---|
| Schibsted Grotesk | UI | Copyright 2023 The Schibsted-Grotesk Project Authors | `OFL-SchibstedGrotesk.txt` |
| Iosevka Term | terminal (subset to the ranges a terminal needs) | Copyright 2015-2025, Renzhi Li (Belleve Invis) | `OFL-Iosevka.txt` |
| Porthole Serif | the Claude theme's prose and display lines | © 2014 - 2021 Adobe Systems Incorporated, with Reserved Font Name 'Source' | `OFL-SourceSerif4.txt` |
| Google Sans Flex | the Gemini theme's prose and display lines (Regular and SemiBold instanced at opsz 16, Latin subset) | Copyright 2015 The Google Sans Flex Authors | `OFL-GoogleSansFlex.txt` |

Porthole Serif is a Modified Version of Source Serif 4 by Adobe: Regular and SemiBold
instanced at opsz 16 and subset to Latin. Source Serif declares the Reserved Font Name
"Source", so the modified files carry the family name Porthole Serif in their name
tables. The resource files keep their `source_serif4_*.ttf` names.

## Libraries

| Library | Licence | Licence text |
|---|---|---|
| AndroidX and Jetpack Compose, including Glance, Browser, Core SplashScreen and the Material icons | Apache 2.0 | `Apache-2.0.txt` |
| Kotlin standard library and kotlinx.coroutines | Apache 2.0 | `Apache-2.0.txt` |
| OkHttp and Okio (Square) | Apache 2.0 | `Apache-2.0.txt` |
| sshj and asn-one (Hierynomus) | Apache 2.0 | `Apache-2.0.txt` |
| Guava ListenableFuture, javax.inject, JSpecify, JetBrains annotations, Firebase components and encoders, Google Data Transport (transitive) | Apache 2.0 | `Apache-2.0.txt` |
| Bouncy Castle: bcprov, bcpkix, bcutil | Bouncy Castle Licence (MIT) | `BouncyCastle.txt` |
| SLF4J API, used by sshj | MIT | `slf4j-MIT.txt` |
| EdDSA-Java (`net.i2p.crypto:eddsa`) | CC0 1.0 | none required |
| Google Play services code scanner, with Play services base, basement and tasks, and ML Kit | Android Software Development Kit License; ML Kit Terms of Service | https://developer.android.com/studio/terms, https://developers.google.com/ml-kit/terms |

OkHttp includes a compiled copy of the Public Suffix List
(https://publicsuffix.org/list/), which is under the Mozilla Public License 2.0
(https://mozilla.org/MPL/2.0/).

## Brand marks

The Claude Code, GitHub, Tailscale and tmux icons
(`app/src/main/res/drawable/ic_brand_*.xml`) use vector data from Simple Icons (CC0 1.0).
The marks belong to their owners and are used only to identify the tools they name.

## Trademarks

Claude and Claude Code are trademarks of Anthropic. Gemini and Google Sans are
trademarks of Google. Porthole is an independent project and is not affiliated with,
sponsored by or endorsed by Anthropic or Google.
