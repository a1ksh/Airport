"""Read airborne public airline observations from the adsb.lol v2 API."""
import json
import re
import time
from threading import Lock
from urllib.request import Request, urlopen

from fastapi import HTTPException

IATA_PREFIX = {"KC": "KZR", "FS": "AYN", "DV": "VSV", "TK": "THY", "FZ": "FDB",
               "EK": "UAE", "LH": "DLH", "BA": "BAW", "HY": "UZB", "QR": "QTR"}
AIRPORTS = {"NQZ": "51.02/71.47/200", "ALA": "43.35/77.04/100", "IST": "41.28/28.75/50",
            "LHR": "51.47/-0.46/25"}
_cache = {}
_lock = Lock()


def live_board(mode, query, airport="NQZ"):
    query = query.strip().upper().replace(" ", "")
    if not query:
        path = "point/" + AIRPORTS[airport]
    elif mode == "registration" and re.fullmatch(r"[A-Z0-9][A-Z0-9-]{2,11}", query):
        path = "reg/" + query
    elif mode == "flight" and re.fullmatch(r"[A-Z0-9]{2,3}[0-9][A-Z0-9]{0,4}", query):
        if query[:2] in IATA_PREFIX and query[2].isdigit():
            query = IATA_PREFIX[query[:2]] + query[2:]
        path = "callsign/" + query
    else:
        raise HTTPException(400, "Борттың тіркеу нөмірін немесе рейстің шақыру белгісін тексеріңіз.")
    with _lock:
        cached = _cache.get(path)
        if cached and time.monotonic() - cached[0] < 60:
            return cached[1]
    try:
        request = Request("https://api.adsb.lol/v2/" + path, headers={"User-Agent": "AirportCourse/1.2"})
        with urlopen(request, timeout=7) as response:
            feed = json.load(response)
        aircraft, observed = feed["ac"], int(feed["now"])
        if not isinstance(aircraft, list):
            raise ValueError("Missing observations")
        if observed < 10**12:
            observed *= 1000
        rows = []
        for item in aircraft:
            callsign = (item.get("flight") or "").strip()
            if (not re.fullmatch(r"[A-Z]{3}[0-9][A-Z0-9]{0,4}", callsign) or item.get("dbFlags", 0) & 1
                    or not isinstance(item.get("alt_baro"), (float, int)) or item["alt_baro"] <= 0
                    or item.get("seen", 999) > 120):
                continue
            rows.append({"callsign": callsign, "registration": item.get("r"), "type": item.get("t")})
        result = {"source": "adsb.lol", "observed": observed, "aircraft": sorted(rows, key=lambda r: r["callsign"])}
        if abs(time.time()*1000 - observed) > 5*60_000:
            raise ValueError("Observation expired")
    except (OSError, ValueError, KeyError, TypeError):
        raise HTTPException(503, "Ұшу деректері қазір қолжетімсіз. Сәл кейінірек қайталап көріңіз.")
    with _lock:
        if len(_cache) >= 128:
            _cache.pop(next(iter(_cache)))
        _cache[path] = (time.monotonic(), result)
    return result
