# Memory language resources

Static data the programmatic memory clerks read (see `docs/architecture/MEMORY_DETERMINISTIC_SEMANTICS.md`).

| Output (`shared/src/commonMain/composeResources/files/`) | Script | Source | License |
|---|---|---|---|
| `aive-wordnet-v1.txt.gz` | `build_wordnet_lexicon.py` | Princeton WordNet 3.1 (nltk_data `wordnet31`) | WordNet License; the full notice is embedded in the file |
| `aive-pos-tagger-v1.txt.gz` | `train_pos_tagger.py` | Universal Dependencies English-EWT | CC BY-SA 4.0; the weights are an adaptation and carry the same license (notice embedded) |

UD English-GUM is not used: its UD release is CC BY-NC-SA (non-commercial).

Rebuild, in order (the tagger reads the WordNet file for its features):

~~~
python3 tools/memory_lexicon/build_wordnet_lexicon.py
python3 tools/memory_lexicon/train_pos_tagger.py
~~~

Both downloads cache under `build/`. Output is deterministic (fixed seed, gzip mtime 0). Retraining rewrites
`pos_tagger_golden.tsv`; `MemoryLanguageResourcesTest` fails if the Kotlin tagger disagrees with it.
