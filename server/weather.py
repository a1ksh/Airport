import json
import time
from functools import lru_cache
from math import isfinite
from threading import Lock
from urllib.parse import urlencode
from urllib.request import urlopen

from airport_data import CITIES

_cache = {}
_guard = Lock()


def fetch(url, params):
    with urlopen(url + "?" + urlencode(params), timeout=7) as response:
        return json.load(response)


@lru_cache(maxsize=64)
def coordinates(city):
    for name, (code, country, lat, lon) in CITIES.items():
        if city.casefold() in (name.casefold(), code.casefold()):
            return country, lat, lon
    data = fetch("https://geocoding-api.open-meteo.com/v1/search", {"name": city, "count": 1, "language": "kk"})
    results = data.get("results") if isinstance(data, dict) else None
    if not isinstance(results, list) or not results or not isinstance(results[0], dict):
        raise ValueError("Unknown city")
    place = results[0]
    return place.get("country", ""), place["latitude"], place["longitude"]


def get_forecast(city):
    key = city.casefold()
    now = time.monotonic()
    with _guard:
        entry = _cache.get(key)
    if entry and now < entry[0]:
        return entry[1]
    try:
        country, lat, lon = coordinates(city)
        forecast = fetch("https://api.open-meteo.com/v1/forecast", {
            "latitude": lat, "longitude": lon, "timezone": "auto", "forecast_days": 5,
            "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
            "daily": "weather_code,temperature_2m_min,temperature_2m_max",
        })
        current, daily = forecast["current"], forecast["daily"]
        if not isinstance(current["time"], str) or not current["time"]:
            raise ValueError("Current weather time unavailable")
        for field in ("temperature_2m", "apparent_temperature", "weather_code", "wind_speed_10m"):
            if not isfinite(current[field]):
                raise ValueError("Current weather unavailable")
        dates = daily["time"]
        if not isinstance(dates, list) or len(dates) != 5 or any(not isinstance(day, str) for day in dates):
            raise ValueError("Forecast dates unavailable")
        for field in ("weather_code", "temperature_2m_min", "temperature_2m_max"):
            values = daily[field]
            if not isinstance(values, list) or len(values) != len(dates) or any(not isfinite(value) for value in values):
                raise ValueError("Forecast unavailable")
        result = {"city": city, "country": country, "current": current, "daily": daily, "stale": False}
    except (OSError, ValueError, TypeError, KeyError):
        if entry and now < entry[0] + 172800:
            return {**entry[1], "stale": True}
        return {"city": city, "current": None}
    with _guard:
        if len(_cache) >= 96:
            _cache.pop(next(iter(_cache)))
        _cache[key] = (now + 900, result)
    return result
