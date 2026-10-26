#!/usr/bin/env bash
set -u
B=https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1
echo "=== CURRENCIES"
curl -sS --max-time 30 $B/currencies.json | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin), ensure_ascii=False, separators=(",",":")))'
echo "=== RATES"
curl -sS --max-time 30 $B/currencies/usd.json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d, separators=(",",":")))'
echo "=== HISTORICAL"
curl -sS -o /dev/null -w "%{http_code}\n" --max-time 30 https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@2025-01-01/v1/currencies/usd.min.json
curl -sS -o /dev/null -w "%{http_code}\n" --max-time 30 https://2025-01-01.currency-api.pages.dev/v1/currencies/usd.min.json
curl -sS --max-time 30 https://2025-01-01.currency-api.pages.dev/v1/currencies/usd.min.json | head -c 200; echo
echo "=== ERAPI"
curl -sS --max-time 30 https://open.er-api.com/v6/latest/USD | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["rates"]), " ".join(sorted(d["rates"])))'
