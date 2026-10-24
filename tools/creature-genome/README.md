# Creature genome tools

Measures the node creature paintings and derives their genes, kinship, families, rarity and
breeding difficulty (`docs/Node-Creature-Genome.md`).

~~~
pip install numpy scipy pillow
cd docs/swarm-terrarium/characters
python3 ../../../tools/creature-genome/extract.py /tmp/genes.json
python3 ../../../tools/creature-genome/lineage.py /tmp/genes.json /tmp/lineage.json
~~~

`docs/swarm-terrarium/lineage.json` is that output with the genus names added by hand (family
numbers from clustering are not stable across re-runs, so names are assigned after review).
