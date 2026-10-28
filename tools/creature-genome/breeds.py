"""Builds the breed catalog (shared/.../files/breeds.json) from the measured lineage.

A breed is a lineage creature that has a rig. Availability follows rarity and breeding difficulty
(docs/Node-Creature-Genome.md, Breeds); price and mutation are placeholders to be set per breed.
Breeds added by hand later go in the same file with the same fields.
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LINEAGE = os.path.join(ROOT, 'docs/swarm-terrarium/lineage.json')
RIGS = os.path.join(ROOT, 'shared/src/commonMain/composeResources/files/rigs')
OUT = os.path.join(ROOT, 'shared/src/commonMain/composeResources/files/breeds.json')

# Lineage paintings whose rig file is named differently.
RIG_ALIASES = {'016_data_miner_sheet_1_variant': 'data-miner-sheet-1-variant-1', '051_idea_catalyst': 'idea-catalyst-2'}
STORE_ONLY = {'058', '042', '036', '049', '031', '041'}
BREED_ONLY = {'035', '020', '039', '055'}
LOTTERY = {
    'rare': {'071', '015', '052', '017', '070'},
    'very-rare': {'007', '065'},
    'mythic': {'067', '028'},
}
PRICE = {'Common': 0.99, 'Uncommon': 1.99, 'Rare': 4.99, 'Very rare': 9.99, 'Mythic': 19.99}

rigs = {f[:-len('.rig.json')] for f in os.listdir(RIGS) if f.endswith('.rig.json')}
lineage = json.load(open(LINEAGE))
breeds = []
slug_of = {}
for c in lineage['creatures']:
    slug = RIG_ALIASES.get(c['id']) or re.sub(r'^\d+_', '', c['id']).replace('_', '-')
    if slug in rigs:
        slug_of[c['id']] = slug
for c in lineage['creatures']:
    slug = slug_of.get(c['id'])
    if not slug:
        continue
    number = c['id'][:3]
    lottery = next((tier for tier, ids in LOTTERY.items() if number in ids), None)
    if number in STORE_ONLY:
        source = 'store'
    elif number in BREED_ONLY or lottery == 'mythic':
        source = 'breed'
    else:
        source = 'both'
    breeds.append({
        'id': slug,
        'name': f"{c['genus']} {number}",
        'genus': c['genus'],
        'rarity': c['rarity'],
        'source': source,
        'lottery': lottery,
        'starter': c['rarity'] == 'Common' and c['breeding'] == 'Easy' and source == 'both' and lottery is None,
        # Hidden. Placeholders until set per breed.
        'price': PRICE[c['rarity']],
        'mutation': 1,
        'pairsWith': [slug_of[k['id']] for k in c['kin'] if k['id'] in slug_of and k['similarity'] >= 75],
    })
breeds.sort(key=lambda b: b['name'])
json.dump({'breeds': breeds}, open(OUT, 'w'), indent=1)
print(len(breeds), 'breeds;', sum(b['starter'] for b in breeds), 'starters;',
      {s: sum(b['source'] == s for b in breeds) for s in ('store', 'breed', 'both')},
      {t: [b['name'] for b in breeds if b['lottery'] == t] for t in LOTTERY})
