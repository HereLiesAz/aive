# Third-party notices

The Aive is licensed under the PolyForm Noncommercial License 1.0.0 (`LICENSE`). The components below
keep their own licenses; see `LICENSING` for the layout.

## WordNet

Bundled, repacked by `tools/memory_lexicon`, as `shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz`
(the notice below is also embedded in that file). Used by the programmatic memory clerks, contrast
detection and tag keywords.

~~~
WordNet Release 3.0

This software and database is being provided to you, the LICENSEE, by
Princeton University under the following license.  By obtaining, using
and/or copying this software and database, you agree that you have
read, understood, and will comply with these terms and conditions.:

Permission to use, copy, modify and distribute this software and
database and its documentation for any purpose and without fee or
royalty is hereby granted, provided that you agree to comply with
the following copyright notice and statements, including the disclaimer,
and that the same appear on ALL copies of the software, database and
documentation, including modifications that you make for internal
use or for distribution.

WordNet 3.0 Copyright 2006 by Princeton University.  All rights reserved.

THIS SOFTWARE AND DATABASE IS PROVIDED "AS IS" AND PRINCETON
UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES, EXPRESS OR
IMPLIED.  BY WAY OF EXAMPLE, BUT NOT LIMITATION, PRINCETON
UNIVERSITY MAKES NO REPRESENTATIONS OR WARRANTIES OF MERCHANT-
ABILITY OR FITNESS FOR ANY PARTICULAR PURPOSE OR THAT THE USE
OF THE LICENSED SOFTWARE, DATABASE OR DOCUMENTATION WILL NOT
INFRINGE ANY THIRD PARTY PATENTS, COPYRIGHTS, TRADEMARKS OR
OTHER RIGHTS.

The name of Princeton University or Princeton may not be used in
advertising or publicity pertaining to distribution of the software
and/or database.  Title to copyright in this software, database and
any associated documentation shall at all times remain with
Princeton University and LICENSEE agrees to preserve same.
~~~

## Model weights (downloaded at run time, not bundled)

| Model | Used for | License |
|---|---|---|
| Qwen/Qwen2.5-0.5B-Instruct and the memory / orchestration LoRA adapters trained on it | Local memory clerks and orchestration utilities | Apache License 2.0 (base model) |
| MiniLM sentence-embedding model (Association Linker) | Memory associations and summary embeddings | Apache License 2.0 — verify against the exact upstream model before release |

## Libraries

Gradle dependencies (Kotlin and kotlinx libraries, Compose Multiplatform, Ktor, SQLDelight, ONNX Runtime,
DJL Hugging Face tokenizers, Apache Commons Compress, Bouncy Castle, multiplatform-settings, cryptography-kotlin,
kmp-zip, AndroidX) are used under their own licenses, as published with each artifact.
