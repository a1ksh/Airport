# Airport shared server

The FastAPI service serves the Kazakh-language web interface and the account, booking and messaging API. Safari, desktop browsers and the Android app share the same SQLite database when they connect to this service. Each device signs in separately.

## Run on Windows

Open PowerShell in this `server` folder. Use Python 3.10 or newer.

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe setup_accounts.py
.\.venv\Scripts\python.exe -m uvicorn server:app --host 127.0.0.1 --port 8765 --no-proxy-headers
```

Open [Airport on this computer](http://localhost:8765/) or go directly to [About Airport](http://localhost:8765/#about). Stop the server with Ctrl+C. On later runs, start it with the last command. On macOS and Linux, use `python3` to create the virtual environment and `.venv/bin/python` for the remaining commands.

## Accounts and storage

The public demo passenger signs in with `user@airport.test` and the shared demo password `Airport123`. Use a separately registered passenger account for personal bookings. The shared passenger password is fixed.

`setup_accounts.py` creates the administrator and an operator for each demo airline. Each receives a unique randomly generated password, displayed once in the local terminal. Save these passwords privately. Staff can change theirs in Profile. Running setup again preserves existing accounts, passwords and data.

| Role | Name | Sign-in email |
| --- | --- | --- |
| Passenger | Қарлығаш | `user@airport.test` |
| Operator | Бейбарс | `operator@airport.test` |
| FlyArystan operator | FlyArystan операторы | `flyarystan@airport.test` |
| Administrator | Ерасыл | `admin@airport.test` |

Other passengers can register from the sign-in page. The administrator can add operators from the account management screen. The initial database includes sample flights. On a fresh installation, the demo passenger's travel passport also shows two completed international trips. Other passengers start with an empty passport and earn a stamp when an operator confirms a completed trip.

The default database is `data/airport.sqlite3`. To use a different path, set `AIRPORT_WEB_DB` to the same absolute database path for both the account setup and server commands. A fresh checkout creates its own database.

## Connect a phone

**Android Studio emulator.** With the server running on port 8765, enter `http://10.0.2.2:8765` in **Сервер** in the debug APK. On Android 17 and newer, allow local network access when prompted for this connection. A physical Android device uses the HTTPS setup below.

**Safari on the same Wi-Fi.** Start Uvicorn with `--host 0.0.0.0` instead of `--host 127.0.0.1`. Open `http://<computer-LAN-IP>:8765/` on your phone. The computer's firewall must allow this connection on your private network.

**Android or Safari over HTTPS.** For a temporary demo, run the loopback server from the first example. In another terminal with Cloudflare's `cloudflared` installed, start a Quick Tunnel.

```powershell
cloudflared tunnel --url http://127.0.0.1:8765
```

In a third PowerShell window in the `server` folder, paste the HTTPS URL printed by `cloudflared` when prompted. The server reads this hostname on each request.

```powershell
$airportUrl = [uri](Read-Host 'Tunnel HTTPS URL')
New-Item -ItemType Directory -Force -Path runtime | Out-Null
Set-Content -Encoding ascii -Path runtime/tunnel-host.txt -Value $airportUrl.Host
```

Open the tunnel URL in Safari, or enter its base HTTPS address in the Android app's **Сервер** setting. Open `/#about` for the airport map, directions and project team. Keep the computer, server and tunnel running during the demo. If the tunnel generates another URL, update the hostname file and use the new URL on each device.

## Source files

| Path | Purpose |
| --- | --- |
| `server.py` | FastAPI routes, sessions, role checks and same-origin access |
| `airport_data.py`, `schema.sql`, `migrations/` | Database setup, transactional upgrades, schedules and role-scoped queries |
| `sales.py` | Direct/connecting itineraries, fare quotes, atomic demo payments and refunds |
| `live_flights.py` | Cached observations for earlier clients using the live API |
| `ticket_pdf.py` | Single-flight PDF with an embedded font and scannable booking code |
| `weather.py` | City lookup and weather forecasts from Open-Meteo |
| `static/` | Web interface, styles, photographs and videos |
| `setup_accounts.py` | Local provisioning of demo staff accounts |

## Updating an existing installation

Pull the new revision and install `requirements.txt` in the server's virtual environment. Stop the old server, then run `setup_accounts.py` and restart Uvicorn using the existing `AIRPORT_WEB_DB` path. Startup migrates the schema in one transaction. Existing bookings keep their seats, codes and QR passes. Earlier bookings carry no payment charge. Existing operators, flights and support requests are assigned to Air Astana. Account setup provisions the FlyArystan operator on an upgraded server.

The demo schedule covers today and the next five days. A transfer requires 60–360 minutes between arrival and the next departure. A pending payment expires in five minutes. On confirmation the API rechecks prices and seats, records the wallet, simulated card or QR payment and issues all tickets in one transaction. Repeating confirmation or refund preserves the original transaction. Card and QR refunds leave the demo wallet balance intact. Existing paid orders retain their wallet ledger and receive a receipt number.

Passengers can rate their first purchase from the ticket or the reviews screen. Each operator-confirmed flight remains separately eligible for a flight review. The route map uses the same fictional city and terminal network on the website and in the offline Android About screen. The dashboard opens Flightradar24 centered on Kazakhstan.

The ticket PDF endpoint checks the passenger's session, ownership and cancellation status before generating the file. Noto Sans is embedded for Kazakh text. Its SIL Open Font License is included in `fonts/OFL.txt`.

## Run the ticketing regression tests

From the server folder, use the same Python environment as the server.

```powershell
.\.venv\Scripts\python.exe -m pip install httpx
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```
