#!/usr/bin/env python3
"""
Builds Shuttle's offline place list (app/src/main/assets/places/) from GeoNames
(https://www.geonames.org, CC BY 4.0): every town of 5,000 people or more, with its region,
country, coordinates, population and time zone, so "weather in Portland, Maine" needs no
online lookup. Run by .github/workflows/places.yml, which downloads the GeoNames files:

    python3 tools/places/make_places.py cities5000.txt admin1CodesASCII.txt countryInfo.txt
"""
import os
import sys

cities_path, admin1_path, countries_path = sys.argv[1:4]
out = "app/src/main/assets/places"
os.makedirs(out, exist_ok=True)

admin1 = {}
with open(admin1_path, encoding="utf-8") as f:
    for line in f:
        code, name, _ascii, _id = line.rstrip("\n").split("\t")
        admin1[code] = name

countries = {}
with open(countries_path, encoding="utf-8") as f:
    for line in f:
        if line.startswith("#"):
            continue
        cols = line.rstrip("\n").split("\t")
        countries[cols[0]] = cols[4]

def clean(s):
    return s.replace("\t", " ").strip()

rows = []
with open(cities_path, encoding="utf-8") as f:
    for line in f:
        c = line.rstrip("\n").split("\t")
        name, ascii_name, lat, lon, cc, a1, pop, tz = c[1], c[2], c[4], c[5], c[8], c[10], c[14], c[17]
        rows.append("\t".join([
            clean(name), clean(ascii_name), a1, clean(admin1.get(f"{cc}.{a1}", "")), cc,
            f"{float(lat):.4f}", f"{float(lon):.4f}", pop or "0", tz,
        ]))

# Biggest first, so a search can stop early on a strong match.
rows.sort(key=lambda r: -int(r.split("\t")[7]))
with open(f"{out}/cities.tsv", "w", encoding="utf-8") as f:
    f.write("# name\tascii\tadmin1 code\tadmin1\tcountry\tlat\tlon\tpopulation\ttime zone. From GeoNames (CC BY 4.0).\n")
    f.write("\n".join(rows) + "\n")
with open(f"{out}/countries.tsv", "w", encoding="utf-8") as f:
    f.write("\n".join(f"{k}\t{v}" for k, v in sorted(countries.items())) + "\n")
print(f"{len(rows)} places, {len(countries)} countries")
