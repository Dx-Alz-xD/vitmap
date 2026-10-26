#!/usr/bin/env python3
"""Builds the World Clock's bundled map and place data in app/src/main/assets/world/.

Inputs are the npm packages world-atlas (Natural Earth 1:50m countries, public domain),
world-countries (country names and capitals, ODbL), moment-timezone (zone coordinates, MIT)
and city-timezones (cities with time zones, MIT; data from simplemaps, CC BY 4.0).

Usage: build_world_data.py <world-atlas dir> <world-countries dir> <moment-timezone dir> <city-timezones dir> <out dir>
"""
import gzip, json, struct, sys, os

atlas, wc, mtz, ctz, out = sys.argv[1:6]
topo = json.load(open(os.path.join(atlas, 'countries-50m.json')))
countries = json.load(open(os.path.join(wc, 'countries.json')))
by_num = {c['ccn3']: c for c in countries if c.get('ccn3')}
manual = {'Kosovo': 'XK', 'Somaliland': 'SO', 'N. Cyprus': 'CY', 'Indian Ocean Ter.': 'AU', 'Siachen Glacier': 'IN'}
by_cca2 = {c['cca2']: c for c in countries}

sx, sy = topo['transform']['scale']
tx, ty = topo['transform']['translate']
arcs = []
for arc in topo['arcs']:
    x = y = 0
    pts = []
    for dx, dy in arc:
        x += dx; y += dy
        pts.append((x * sx + tx, y * sy + ty))
    arcs.append(pts)

def ring(indices):
    pts = []
    for i in indices:
        a = arcs[i] if i >= 0 else list(reversed(arcs[~i]))
        pts.extend(a if not pts else a[1:])
    return pts

def zigzag(v):
    return (v << 1) ^ (v >> 31)

def varint(v, buf):
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            buf.append(b | 0x80)
        else:
            buf.append(b)
            return

shapes = {}
for g in topo['objects']['countries']['geometries']:
    name = g['properties']['name']
    code = manual.get(name) or (by_num.get(g.get('id')) or {}).get('cca2')
    if not code:
        print('skip', name, file=sys.stderr)
        continue
    polys = g['arcs'] if g['type'] == 'MultiPolygon' else [g['arcs']] if g['type'] == 'Polygon' else []
    rings = [ring(r) for p in polys for r in p]
    shapes.setdefault(code, {'name': name, 'rings': []})['rings'].extend(rings)

buf = bytearray(b'VMAP')
buf.append(1)
buf += struct.pack('<H', len(shapes))
total = 0
for code, s in sorted(shapes.items()):
    info = by_cca2.get(code)
    nm = (info['name']['common'] if info else s['name']).encode()
    lat, lng = (info['latlng'] if info and info.get('latlng') else (0, 0))
    buf += code.encode()
    buf.append(len(nm)); buf += nm
    buf += struct.pack('<hh', round(lat * 100), round(lng * 100))
    rings = [r for r in s['rings'] if len(r) >= 3]
    buf += struct.pack('<H', len(rings))
    for r in rings:
        q = [(round(x * 100), round(y * 100)) for x, y in r]
        dedup = [q[0]] + [p for i, p in enumerate(q[1:], 1) if p != q[i - 1]]
        buf += struct.pack('<H', len(dedup))
        px = py = 0
        for x, y in dedup:
            varint(zigzag(x - px), buf); varint(zigzag(y - py), buf)
            px, py = x, y
        total += len(dedup)
os.makedirs(out, exist_ok=True)
with gzip.open(os.path.join(out, 'map.bin.gz'), 'wb', 9) as f:
    f.write(bytes(buf))
print('countries with shapes', len(shapes), 'points', total, 'bytes', len(buf), file=sys.stderr)

with open(os.path.join(out, 'countries.tsv'), 'w') as f:
    for c in sorted(countries, key=lambda c: c['cca2']):
        cap = (c.get('capital') or [''])[0]
        lat, lng = c.get('latlng') or (0, 0)
        f.write('\t'.join([c['cca2'], c['name']['common'], cap, f'{lat:.3f}', f'{lng:.3f}', c.get('region', ''), c.get('subregion', '')]) + '\n')

meta = json.load(open(os.path.join(mtz, 'data', 'meta', 'latest.json')))
with open(os.path.join(out, 'zones.tsv'), 'w') as f:
    for name, z in sorted(meta['zones'].items()):
        f.write('\t'.join([name, f"{z['lat']:.3f}", f"{z['long']:.3f}", ','.join(z['countries']), z.get('comments', '').replace('\t', ' ')]) + '\n')

cities = json.load(open(os.path.join(ctz, 'data', 'cityMap.json')))
rows = []
for c in cities:
    if not c.get('timezone'):
        continue
    rows.append('\t'.join([str(c['city']), str(c.get('country', '')), str(c.get('iso2', '')), str(c.get('province', '') or ''), f"{c['lat']:.3f}", f"{c['lng']:.3f}", str(int(c.get('pop') or 0)), c['timezone']]))
with gzip.open(os.path.join(out, 'cities.tsv.gz'), 'wt', 9) as f:
    f.write('\n'.join(rows) + '\n')
print('cities', len(rows), file=sys.stderr)
