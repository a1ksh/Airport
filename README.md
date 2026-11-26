# Airport

Airport connects passenger journeys, flight operations and administration through three roles. The Android launcher is a **Java WebView client** for the same HTTPS site used in Safari and desktop browsers. The FastAPI server stores accounts, bookings, messages and reviews in SQLite.

The app interface is in Kazakh. Navigation labels below describe the corresponding actions in English.

## Features

| Role | Features |
| --- | --- |
| Passenger | Compare direct and one-stop tickets, choose a fare and seats, and pay in tenge by demo wallet, card or QR. Each leg has a QR pass. Review the first purchase, request a refund, and earn passport stamps and discounts on completed flights. |
| Operator | Manage their airline's flights, prices, gates, flight durations and check-in windows. See its manifests and support requests, check in passengers and confirm arrivals. |
| Administrator | Oversee all airlines, flights, manifests, payments and refunds. Add an airline with its first operator, assign additional operators, publish ticket types, manage accounts and review feedback. |

The app links directly to a live aircraft map over Kazakhstan and includes directions between cities and Astana's two terminals, weather forecasts, a travel guide and two videos. Passengers can save a ticket for each leg as an offline PDF.

**About** (Біз туралы) lists the project team and the address of Astana International Airport. **Мекенжайды көру** opens the real address in Google Maps. **Жол бағыты** opens the project's fictional interactive map. Guests can use the map at `/#directions` and from the Android launcher's About screen before connecting to a server.

## Getting started

1. Clone this repository, or select **Code → Download ZIP** on GitHub and extract it.
2. Open the project root in **Android Studio**: the folder containing `settings.gradle` and this README.
3. Install **Android SDK Platform 37** and let Gradle sync finish. The project configures **JDK 25 for the Gradle daemon**; application Java source and target compatibility are **Java 17**.
4. Select an emulator such as **Medium Phone**, or connect an Android device running **Android 8.0 / API 26 or later**.
5. Start the FastAPI server and run the **app** configuration. Tap **Сервер** and enter its HTTPS address. For a debug build in Android Studio's emulator, use `http://10.0.2.2:8765` to reach the server on your computer. [Server setup guide](server/README.md)

To use the same data on another device, open the same HTTPS address in Safari or a desktop browser and sign in. If the address changes, enter the new address in **Сервер** and tap **Қосылу**. The app checks the server’s `/health` endpoint and remembers the validated address on that device. An updated installation preserves the old device database. Accounts that exist only in that database require a separate migration to become available on the server.

### Build an APK

On Windows, run this command from the project root:

```powershell
./gradlew.bat :app:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

The generated APK is saved to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Build configuration

| Setting | Value |
| --- | --- |
| Application ID | `kz.edu.airportcourse` |
| Java source / target | 17 |
| Gradle daemon toolchain | 25 |
| Gradle wrapper | 9.5.0 |
| Compile / target SDK | 37 |
| Minimum SDK | 26 |

## Demo accounts

Enter an email address and password on the login screen. The public passenger demonstration uses **`user@airport.test`** with **`Airport123`**. Request staff credentials from the shared server owner.

| Role | Name | Email |
| --- | --- | --- |
| Passenger | Қарлығаш | `user@airport.test` |
| Operator | Бейбарс | `operator@airport.test` |
| FlyArystan operator | FlyArystan операторы | `flyarystan@airport.test` |
| Administrator | Ерасыл | `admin@airport.test` |

New users can register a passenger account. The profile screen supports changing a display name and, for personal accounts, changing a password. The public demonstration passenger password is protected.

The server stores salted password hashes. Each browser or Android installation receives its own session cookie after signing in.

## Demo walkthrough

Use the same server address on all devices. For a cross-device demonstration, open the passenger account on Android and the operator account in a separate browser.

1. Sign in as the passenger. Open **Messages → New request** and send a support question.
2. In the operator’s browser, open the request, write a reply and update its status if needed. The messages button displays an unread count.
3. On Android, read the reply in the same conversation. Its unread indicator clears after reading.
4. Open **Билет іздеу**, choose **Астана → Ыстанбұл** for tomorrow, and compare the direct trip with a connection in Алматы. Sort by price, departure or travel time. Schedules and check-in times use Astana time (UTC+5).
5. Choose **Эконом**, **Эконом Плюс** or **Бизнес-класс** and a seat on each leg. Confirm a wallet payment, enter the code shown for the fictional Qanat Bank card, or approve the order's demo QR. The receipt records the original method and transaction number. The first paid ticket invites the passenger to rate checkout. **Билетті қайтару** previews the refund to the same method before releasing the seats. Returned tickets stay in the history.
6. As the airline operator, edit a flight's schedule and check-in window. Passengers with flight alerts enabled receive the new times. Check a passenger in during that window and confirm the completed flight after its arrival.
7. Open **Пікірлер** after a confirmed flight to rate it once. For a quick demonstration the initial passenger already has two confirmed trips. Three completed flights earn 5% off future tickets and ten earn 10%. **Профиль** shows the discount and wallet balance.
8. As administrator, open **Компаниялар, тарифтер және төлемдер** to add an airline with its first operator, add a fare with baggage and refund terms, or view payments and refunds across airlines.
9. Open **Жол бағыты**, tap two stops or use the selectors, and choose a bus, car or walking route. Try the intercity bus station to T1, or walk from T1 to T2. The highlighted route, distance and time use a fictional network. **Қазір әуеде** opens the ADSB.lol live map over Kazakhstan in the browser.
10. In **Сапарлар**, open a ticket and choose **Билетті PDF ретінде сақтау**. The file contains that leg's route, departure, arrival, seat and QR code and opens offline. On Android choose a folder in the system file picker. Download a fresh copy if the airline changes the schedule or gate. The operator checks the booking's current status at check-in.

Each passenger starts with 1,000,000 demo tenge. Prices and fare rules are classroom examples. Payment and refund entries stay in the project's SQLite database. The ticket flow accepts no card details. If the airline cancels a flight or a schedule change breaks the connection, unused affected tickets qualify for a full refund of their paid amount.

## My Travel Passport

Open **Profile → My Travel Passport** to view confirmed trip totals, city stamps and visited-country counts. Tap a city stamp to see its trip dates, flight numbers, booking codes and seats. Repeated visits increase the trip count for that city. Cities with an unknown country do not add an invented country to the total.

To award a stamp, the operator opens **Flights → Flight → Passengers**, selects a checked-in passenger and confirms **Complete trip** after scheduled arrival. Each completed flight earns one stamp for its destination.

The `travel_stamps` table stores a snapshot of the city, flight, departure time, seat and booking code. Later flight edits preserve that confirmed travel history. The passport endpoint groups stamps by city. Each passenger can access only their own passport. New accounts start with an explanatory empty state.

## Project structure

```text
app/src/main/
├── java/kz/edu/airportcourse/
│   ├── data/          # SQLite operations and weather data
│   └── ui/            # Activities, adapters, dialogs and animated views
├── res/
│   ├── layout/        # Activity, dialog and list-row XML layouts
│   ├── drawable/      # Icons, backgrounds and shape resources
│   ├── drawable-nodpi/# Banner artwork
│   ├── raw/           # Two bundled MP4 videos
│   ├── anim/          # View animations
│   ├── menu/          # Menu resources
│   └── values/        # Text and style resources
└── AndroidManifest.xml
```

The current launcher is `ui/SharedAirportActivity.java`. Its connection controls are in `res/layout/activity_shared_airport.xml` and its default server address is in `res/values/shared_server.xml`. The repository also preserves the earlier native activities, XML layouts, artwork and device-side SQLite implementation in `data/AirportDb.java`.

The `server/` directory contains the shared FastAPI application, SQLite schema and browser interface. Its JavaScript and styles also power the Android WebView. Each server installation creates its own database.

## Database

The shared FastAPI service owns one SQLite database for every client connected to that server. Each signed-in user can access the records allowed by their role. Session records support separate logins on multiple devices.

| Area | Tables |
| --- | --- |
| Airlines and ticket sales | `airlines`, `fares`, `orders` |
| Accounts and flights | `users`, `flights` |
| Trips and saved flights | `bookings`, `saved_flights`, `travel_stamps` |
| Support conversations | `tickets`, `messages`, `reads` |
| Feedback and notifications | `reviews`, `notices` |
| Activity and demo scheduling | `ops_log`, `market_days`, earlier `demo_days` |
| Sessions | `sessions` |

Earlier Android installations used **`airport_course.db`**, schema version **6**, for device-local data. The shared launcher reads and writes records through the server, including when installed as an update on those devices.

## Recorded validation

Validation of the earlier native Android implementation was performed in separate QA installations. Recorded results from **21 November 2026** include:

| Check | Recorded result |
| --- | --- |
| Travel passport and SQLite checks | 39 passed |
| Database and role checks | 88 passed |
| Screen scenarios | 47 passed |
| Login, registration and rotation checks | 28 passed |
| Android lint | 0 errors, 0 warnings |

Passport checks covered the v5 → v6 migration, existing data, access rules, rejection of incomplete trips, duplicate confirmation and historical snapshots. Emulator checks covered profile navigation, city details, rotation and stamps appearing after an operator's confirmation.

Both videos were checked for playback, pause, resume and stop behavior. Fourteen screens were compared using matching database records; visible dimensions, text and control states matched, and eight header regions also matched pixel by pixel. The main application was rebuilt under `kz.edu.airportcourse`.

## References

- [Open-Meteo weather API](https://open-meteo.com/en/docs)
- [ADSB.lol live aircraft map](https://adsb.lol/?lat=48.5&lon=67.5&zoom=4)
- [Astana Airport terminal and check-in information](https://www.nn-airport.kz/pages/check-in_procedures)
- [Google Maps directions URLs](https://developers.google.com/maps/documentation/urls/get-started)
- [Android SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)
- [Android Intents](https://developer.android.com/guide/components/intents-common)
