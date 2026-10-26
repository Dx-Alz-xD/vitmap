#!/usr/bin/env python3
"""Builds the ForEx currency catalog and offline rate snapshot in app/src/main/assets/forex/.

Inputs: an extracted @fawazahmed0/currency-api npm package (v1/currencies.json and
v1/currencies/eur.json) and the world-countries package (currency names, symbols, countries).

Usage: build_forex_data.py <currency-api package dir> <world-countries package dir> <out dir>
"""
import json, os, sys

api, wc, out = sys.argv[1:4]
names = json.load(open(os.path.join(api, 'v1', 'currencies.json')))
eur = json.load(open(os.path.join(api, 'v1', 'currencies', 'eur.json')))
countries = json.load(open(os.path.join(wc, 'countries.json')))

symbols, used_by, wc_names = {}, {}, {}
for c in countries:
    for code, info in (c.get('currencies') or {}).items():
        code = code.lower()
        used_by.setdefault(code, []).append(c['cca2'])
        if info.get('symbol') and code not in symbols:
            symbols[code] = info['symbol']
        if info.get('name') and code not in wc_names:
            wc_names[code] = info['name']

metal = {'xau', 'xag', 'xpt', 'xpd'}
legacy = set('ats bef dem esp fim frf iep itl luf nlg pte grd sit cyp mtl skk eek lvl ltl hrk bgn val sml mcf adp '
             'azm byr ghc mro mzm rol sdd std tmm trl veb vef zmk zwd zwl sll mgf srg cuc ang'.split())
local = set('ggp jep imp tvd fok kid ckd spl cnh xdr mxv clf'.split())
legacy_names = {
    'sml': 'San Marino Lira', 'mcf': 'Monégasque Franc', 'adp': 'Andorran Peseta', 'ang': 'Netherlands Antillean Guilder',
    'trl': 'Turkish Lira (old)', 'veb': 'Venezuelan Bolívar (1879)', 'vef': 'Venezuelan Bolívar Fuerte', 'zwd': 'Zimbabwean Dollar (old)',
    'zwl': 'Zimbabwean Dollar (2019)', 'sll': 'Sierra Leonean Leone (old)', 'mro': 'Mauritanian Ouguiya (old)', 'std': 'São Tomé Dobra (old)',
    'byr': 'Belarusian Ruble (old)', 'ghc': 'Ghanaian Cedi (old)', 'rol': 'Romanian Leu (old)', 'mzm': 'Mozambican Metical (old)',
    'azm': 'Azerbaijani Manat (old)', 'tmm': 'Turkmenistani Manat (old)', 'sdd': 'Sudanese Dinar', 'zmk': 'Zambian Kwacha (old)',
    'mgf': 'Malagasy Franc', 'srg': 'Surinamese Guilder', 'cuc': 'Cuban Convertible Peso', 'bgn': 'Bulgarian Lev',
}
fixes = {
    'xcg': 'Caribbean Guilder', 'ved': 'Venezuelan Digital Bolívar', 'zwg': 'Zimbabwe Gold', 'mxv': 'Mexican Investment Unit (UDI)',
    'bob': 'Bolivian Boliviano', 'awg': 'Aruban Florin', 'bbd': 'Barbadian Dollar', 'eurc': 'Euro Coin', 'kas': 'Kaspa',
    'lunc': 'Terra Classic', 'ordi': 'ORDI', 'pepe': 'Pepe', 'usdd': 'USDD', 'xbt': 'Bitcoin (XBT)', 'spl': 'Seborga Luigino',
    'cnh': 'Chinese Yuan (offshore)', 'xau': 'Gold (troy ounce)', 'xag': 'Silver (troy ounce)', 'xpt': 'Platinum (troy ounce)',
    'xpd': 'Palladium (troy ounce)', 'azn': 'Azerbaijani Manat', 'tvd': 'Tuvaluan Dollar', 'imp': 'Manx Pound',
}
extra = {
    'fok': ('Faroese Króna', 'kr', ['FO']), 'kid': ('Kiribati Dollar', '$', ['KI']), 'ckd': ('Cook Islands Dollar', '$', ['CK']),
    'clf': ('Chilean Unidad de Fomento', 'UF', ['CL']), 'sml': (legacy_names['sml'], '₤', ['SM']), 'mcf': (legacy_names['mcf'], '₣', ['MC']),
    'adp': (legacy_names['adp'], '₧', ['AD']), 'xaug': ('Gold (gram)', 'g', []), 'xagg': ('Silver (gram)', 'g', []),
}
fiat = set(used_by) | {'xcg', 'ved', 'zwg', 'svc', 'xdr'}
legacy_where = dict(ats='AT', bef='BE', dem='DE', esp='ES', fim='FI', frf='FR', iep='IE', itl='IT', luf='LU', nlg='NL', pte='PT',
                    grd='GR', sit='SI', cyp='CY', mtl='MT', skk='SK', eek='EE', lvl='LV', ltl='LT', hrk='HR', bgn='BG', val='VA',
                    sml='SM', mcf='MC', adp='AD', azm='AZ', byr='BY', ghc='GH', mro='MR', mzm='MZ', rol='RO', sdd='SD', std='ST',
                    tmm='TM', trl='TR', veb='VE', vef='VE', zmk='ZM', zwd='ZW', zwl='ZW', sll='SL', mgf='MG', srg='SR', cuc='CU',
                    ang='CW', spl='IT', xcg='CW', ved='VE', zwg='ZW', svc='SV', cnh='CN', mxv='MX', clf='CL')

rows = []
codes = sorted(set(names) | set(extra))
for code in codes:
    if code == 'xbt':
        continue  # duplicate of BTC
    if code in metal or code in ('xaug', 'xagg'):
        kind = 'METAL'
    elif code in legacy:
        kind = 'LEGACY'
    elif code in local:
        kind = 'LOCAL'
    elif code in fiat:
        kind = 'FIAT'
    else:
        kind = 'CRYPTO'
    name = fixes.get(code) or legacy_names.get(code) or (extra.get(code) or (None,))[0] or names.get(code) or wc_names.get(code) or code.upper()
    if not name:
        name = code.upper()
    symbol = (extra.get(code) or (None, None))[1] or symbols.get(code, '')
    where = [legacy_where[code]] if code in legacy_where else (used_by.get(code) or (extra.get(code) or (None, None, []))[2])
    if code in ('ggp',): where = ['GG']
    if code in ('jep',): where = ['JE']
    if code in ('imp',): where = ['IM']
    if code == 'tvd': where = ['TV']
    rows.append('\t'.join([code, name, kind, symbol, ','.join(sorted(set(where)))]))

os.makedirs(out, exist_ok=True)
with open(os.path.join(out, 'catalog.tsv'), 'w') as f:
    f.write('\n'.join(rows) + '\n')
with open(os.path.join(out, 'snapshot.json'), 'w') as f:
    json.dump({'date': eur['date'], 'base': 'eur', 'rates': eur['eur']}, f, separators=(',', ':'))
kinds = {}
for r in rows:
    kinds[r.split('\t')[2]] = kinds.get(r.split('\t')[2], 0) + 1
print(len(rows), kinds, file=sys.stderr)
