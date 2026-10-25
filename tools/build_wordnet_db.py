#!/usr/bin/env python3
"""Builds app/src/main/assets/wordnet.db.gz from the WordNet 3.1 dict files.

Usage: build_wordnet_db.py <path-to-wordnet-dict-dir> <output.db.gz>
The dict dir is e.g. the `dict/` folder of the `wordnet-db` npm package.
"""
import gzip, os, re, sqlite3, sys, shutil

POS_FILES = {'n': 'noun', 'v': 'verb', 'a': 'adj', 'r': 'adv'}
POS_CODE = {'n': 1, 'v': 2, 'a': 3, 's': 3, 'r': 4}

def sid(pos, offset):
    return POS_CODE[pos] * 100_000_000 + int(offset)

def main(src, out):
    synsets = {}
    for p, name in POS_FILES.items():
        with open(os.path.join(src, 'data.' + name), encoding='utf-8', errors='replace') as f:
            for line in f:
                if line.startswith('  '):
                    continue
                head, _, gloss = line.partition(' | ')
                parts = head.split()
                offset, _lex, ss_type, w_cnt = parts[0], parts[1], parts[2], int(parts[3], 16)
                words = []
                i = 4
                for _ in range(w_cnt):
                    words.append(parts[i].replace('_', ' '))
                    i += 2
                p_cnt = int(parts[i]); i += 1
                ptrs = []
                for _ in range(p_cnt):
                    sym, t_off, t_pos, st = parts[i], parts[i + 1], parts[i + 2], parts[i + 3]
                    ptrs.append((sym, t_off, t_pos, st))
                    i += 4
                gloss = gloss.strip()
                examples = re.findall(r'"([^"]+)"', gloss)
                definition = re.sub(r'"[^"]*"', '', gloss)
                definition = re.sub(r'\s*;\s*(;\s*)*', '; ', definition).strip(' ;')
                synsets[sid(ss_type, offset)] = dict(pos=ss_type, words=words, ptrs=ptrs, definition=definition, examples=examples)

    def word_of(target_id, idx):
        s = synsets.get(target_id)
        if not s:
            return None
        if idx == 0:
            return s['words'][0]
        return s['words'][idx - 1] if idx - 1 < len(s['words']) else None

    if os.path.exists(out + '.tmp'):
        os.remove(out + '.tmp')
    db = sqlite3.connect(out + '.tmp')
    db.execute('PRAGMA page_size = 4096')
    db.execute('CREATE TABLE synsets (id INTEGER PRIMARY KEY, pos TEXT NOT NULL, definition TEXT NOT NULL, examples TEXT NOT NULL, words TEXT NOT NULL, antonyms TEXT NOT NULL, hypernyms TEXT NOT NULL, similar TEXT NOT NULL)')
    db.execute('CREATE TABLE words (lemma TEXT PRIMARY KEY, synsets TEXT NOT NULL) WITHOUT ROWID')
    rows = []
    for k, s in synsets.items():
        ants, hyps, sims = [], [], []
        for sym, t_off, t_pos, st in s['ptrs']:
            target = sid(t_pos, t_off)
            if sym == '!':
                w = word_of(target, int(st[2:], 16))
                if w and w not in ants:
                    ants.append(w)
            elif sym in ('@', '@i'):
                t = synsets.get(target)
                if t and t['words'][0] not in hyps:
                    hyps.append(t['words'][0])
            elif sym in ('&', '^'):
                t = synsets.get(target)
                if t and t['words'][0] not in sims:
                    sims.append(t['words'][0])
        words = [re.sub(r'\([a-z]+\)$', '', w) for w in s['words']]
        rows.append((k, s['pos'], s['definition'], '|'.join(s['examples']), '|'.join(words), '|'.join(ants), '|'.join(hyps[:4]), '|'.join(sims[:8])))
    db.executemany('INSERT INTO synsets VALUES (?,?,?,?,?,?,?,?)', rows)

    lemmas = {}
    for p, name in POS_FILES.items():
        with open(os.path.join(src, 'index.' + name), encoding='utf-8', errors='replace') as f:
            for line in f:
                if line.startswith('  '):
                    continue
                parts = line.split()
                lemma = parts[0].replace('_', ' ')
                syn_cnt = int(parts[2])
                offsets = parts[-syn_cnt:]
                lst = lemmas.setdefault(lemma, [])
                for o in offsets:
                    # adjective satellites live in data.adj with type 's'; the POS code is shared
                    lst.append(str(sid(p, o)))
    db.executemany('INSERT INTO words VALUES (?,?)', [(k, ','.join(v)) for k, v in lemmas.items()])
    db.commit()
    db.execute('VACUUM')
    db.close()
    with open(out + '.tmp', 'rb') as fi, gzip.open(out, 'wb', compresslevel=9) as fo:
        shutil.copyfileobj(fi, fo)
    size = os.path.getsize(out + '.tmp')
    os.remove(out + '.tmp')
    print(f'{len(synsets)} synsets, {len(lemmas)} lemmas, db {size/1e6:.1f} MB, gz {os.path.getsize(out)/1e6:.1f} MB')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
