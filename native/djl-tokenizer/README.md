# djl-tokenizer

Android build of the JNI library behind `ai.djl.huggingface:tokenizers`, loaded by DJL as
`libdjl_tokenizer.so`.

DJL's prebuilt `ai.djl.android:tokenizer-native` (latest 0.33.0) is linked with 4 KB ELF
alignment, which Google Play rejects for Android 15+ 16 KB-page devices. This crate vendors the
tokenizer JNI surface from [deepjavalibrary/djl](https://github.com/deepjavalibrary/djl) v0.38.0
(`extensions/tokenizers/rust/src/lib.rs`, Apache-2.0), matching the Java library version, without
the Candle engine modules. `androidApp` builds it with `cargo ndk` and 16 KB page alignment.

Upgrading: when `djl` in `gradle/libs.versions.toml` changes, re-vendor `src/lib.rs` from the same
DJL tag and match the `jni`/`tokenizers` versions in `Cargo.toml`.
