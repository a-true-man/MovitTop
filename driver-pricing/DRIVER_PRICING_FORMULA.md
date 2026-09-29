# Driver / shared-ride price estimation — implementation spec

## What this is

A formula that estimates the price of a private shared-ride ("driver" / דרייבר,
common in Israel — a private car service, typically 4/6/6-spacious/7-seat
vehicles) for a given route, for use when the exact route isn't in a known
price list. Statistically fitted (linear regression) from 145 real published
price-list rows scraped from a real operator's public site (driverim.online,
covering 9 Israeli cities, September 2026). It's an approximation of an
unknown real pricing engine, not the real engine itself.

Implement this as a small, pure, dependency-free pricing module in whatever
language/framework this app uses. Two reference implementations (JavaScript
and Python) are attached — port the logic faithfully rather than
re-deriving it, the coefficients matter.

## Inputs

- `distanceKm` (number, required): **road distance** (actual driving
  distance, e.g. from a routing/maps API), NOT straight-line/haversine
  distance. The formula was fit against road distances.
- `isMajorCityPair` (boolean, optional, default `false`): true if both
  endpoints of the route are major/well-known cities with two-way commuter
  demand (e.g. Tel Aviv, Jerusalem, Bnei Brak, Bet Shemesh, Ashdod, Petah
  Tikva...). Only takes effect when `distanceKm > 40`.
- `isRemoteDestination` (boolean, optional, default `false`): true if the
  destination is a small/isolated settlement with low likelihood of a
  return passenger.
- `roundTo` (number, optional, default `10`): round the output to the
  nearest multiple of this (matches how the source prices are quoted, all
  in multiples of 10 ILS).

Leave `isMajorCityPair`/`isRemoteDestination` both `false` (the default) if
the app has no reliable signal for route/destination type — the base
distance formula alone already achieves R²=0.986 without them.

## Output

For each of 4 vehicle classes (4-seat / 6-seat-small / 6-seat-spacious /
7-seat "Sienna"-type van), both a one-way and a round-trip price, plus a
suggested max wait time in minutes. See the reference implementations for
the exact shape.

## The formula

All prices in ILS (₪). `km` = `distanceKm`.

```
1. seats4 = 36.1 + 3.606 * km                      # R²=0.986, MAE≈14₪, n=145

2. demand adjustment (optional, apply to seats4 before continuing):
   if isMajorCityPair and km > 40:  seats4 *= 0.85
   elif isRemoteDestination:        seats4 *= 1.2

3. seats6Small    = 36.35 + 1.1768 * seats4         # R²=0.998
   seats6Spacious = 52.89 + 1.306  * seats4         # R²=0.995
   seats7         = seats6Spacious + 20              # exact rule, 0 exceptions in 145 samples

4. roundTripOf(oneWayPrice) = 64.0 + 1.483 * oneWayPrice   # R²=0.991
   → apply to each of the 4 one-way prices above to get the 4 round-trip prices

5. waitTimeMinutes = distance-banded:
   km <= 20  → 30
   km <= 40  → 40
   km <= 75  → 60
   km <= 115 → 90
   km <= 160 → 105
   else      → 120

6. round every price to the nearest `roundTo` (default 10)
```

Step order matters: compute `seats4` (with its optional adjustment) first,
then derive everything else from that single number — chaining from `seats4`
is measurably more accurate than fitting each vehicle class against `km`
independently (that's why steps 3–4 look "circular" — they're not, it's a
deliberate, validated modeling choice).

## Validated example (sanity check when porting)

`estimateDriverPrice(68)` → `seats4 = 280`ish (pure distance, no adjustment)
`estimateDriverPrice(68, isMajorCityPair: true)` → `seats4 = 240` — matches
the real published Jerusalem↔Tel Aviv price (240₪) almost exactly.
`estimateDriverPrice(19, isRemoteDestination: true)` → `seats4 ≈ 130` (the
real Jerusalem↔Nokdim price is 180₪ — this is the single most extreme
outlier in the whole dataset, ~84% above what pure distance predicts, so
expect the remote-destination case to still under-shoot on rare extreme
cases; 1.2x is a conservative middle estimate, not a ceiling).

## Known limitations (be honest with the user about these if asked)

- This is a statistical fit of one competitor's published list prices, not
  a live/authoritative pricing engine — treat the output as an estimate
  band, not a quote.
- `isMajorCityPair` / `isRemoteDestination` are optional refinements with
  real but noisier support in the data (they explain a modest average
  shift, with wide variance — from 0% to +84% on the remote side). Don't
  over-trust them for exact pricing; they're best used to bias an estimate
  range, not to promise an exact number.
- No surge/holiday/night pricing, no fuel-price sensitivity, no
  per-operator variation — this reflects one operator's list prices as of
  September 2026.
- Distance must be **road distance**, not straight-line. Using straight-line
  distance will systematically under-price routes with indirect roads
  (e.g. mountainous or coastal detours).

## Files in this delivery

- `driverPricing.js` — reference implementation, Node/browser-compatible, no dependencies.
- `driver_pricing.py` — reference implementation, stdlib-only, no dependencies.
- `driverim_routes_dataset.csv` — the 145 raw data rows the formula was fit from (city, km, tier, and all 4 vehicle prices, one-way and round-trip), in case you want to re-fit, extend, or sanity-check the coefficients against more data later.

## What to do with this

Port `estimateDriverPrice` (or `estimate_driver_price`) into this app's
codebase, in whatever language/module structure fits the existing pricing
or fare-estimation code path. Keep the coefficients exactly as given above
— they're fit constants, not defaults to "clean up" or round differently.
If the app already computes road distance via a maps/routing API for other
purposes, wire that value in directly as `distanceKm`.
