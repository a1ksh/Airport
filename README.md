# Airport

A native Android airport companion built with **Java, XML layouts and SQLite**. The app connects passenger journeys, flight operations and administration through three separate roles.

The app interface is in Kazakh. Navigation labels below describe the corresponding actions in English.

## Features

| Role | Features |
| --- | --- |
| Passenger | Search and save flights, select a seat, book a trip, open a QR boarding pass, manage a preparation checklist, browse trip history, receive flight notices, contact support, submit star ratings and collect travel passport stamps. |
| Operator | Add and edit flights, update gates, times and statuses, view passenger manifests, check passengers in, confirm completed trips, reply to support requests and inspect their own activity log. |
| Administrator | Search and filter accounts, block or reactivate eligible accounts, create operators, read support requests, review ratings and statistics, and inspect staff activity logs. |

Additional features include current weather and a five-day forecast, airport directions in Google Maps, an external official flight board, a travel guide, two bundled videos and an animated globe.

## Getting started

1. Clone this repository, or select **Code → Download ZIP** on GitHub and extract it.
2. Open the project root in **Android Studio**: the folder containing `settings.gradle` and this README.
3. Install **Android SDK Platform 37** and let Gradle sync finish. The project configures **JDK 25 for the Gradle daemon**; application Java source and target compatibility are **Java 17**.
4. Select an emulator such as **Medium Phone**, or connect an Android device running **Android 8.0 / API 26 or later**.
5. Select the **app** run configuration and press **Run**.

Internet access is needed for the initial Gradle dependency downloads and live weather. The Android app stores its application data locally on the device.

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

Enter an email address and password manually on the login screen. All three demo accounts use the password **`Airport123`**.

| Role | Name | Email |
| --- | --- | --- |
| Passenger | Karlygash | `user@airport.test` |
| Operator | Beibars | `operator@airport.test` |
| Administrator | Erasyl | `admin@airport.test` |

New users can register a passenger account. The profile screen supports changing a display name and, for personal accounts, changing a password. Demo account passwords are protected.

Passwords are stored as salted hashes in SQLite. Login and registration perform password calculations on a background thread. The form displays a loading state, preserves an ongoing operation across screen rotation and restores its controls after an error.

## Demo walkthrough

Use the **same emulator or Android installation** when switching between roles so that every account works with the same local SQLite database.

1. Sign in as the passenger. Open **Messages → New request** and send a support question.
2. Open **Profile → Sign out**, then sign in as the operator. Open the request, write a reply and update its status if needed. The messages button displays an unread count.
3. Sign back in as the passenger. Read the reply in the same conversation; its unread indicator clears after reading.
4. Open **Flight search**, choose a date and inspect a flight. Save it for later, or choose an available seat and book it. Open the QR boarding pass and mark items in the preparation checklist. Past and cancelled flights appear in separate trip views.
5. As the operator, open a flight's passenger list or enter a booking code to check a passenger in. Changing a gate, time or flight status creates notices for booked passengers with flight alerts enabled.
6. As the administrator, open account management to search, filter, block or reactivate eligible accounts, or add an operator. Administrator accounts are protected from blocking.
7. As the passenger, open **Reviews → Leave a review**, select 1–5 stars and enter 1–2000 characters. Each submission creates a separate review. The administrator can read all reviews and inspect the average rating and rating distribution.
8. Open weather, Maps directions, the official flight board or the travel guide. In the media section, play, pause and stop either bundled video. The videos are stored inside the APK; the globe animation is drawn with Canvas.

## My Travel Passport

Open **Profile → My Travel Passport** to view confirmed trip totals, city stamps and visited-country counts. Tap a city stamp to see its trip dates, flight numbers, booking codes and seats. Repeated visits increase the trip count for that city. Cities with an unknown country do not add an invented country to the total.

To award a stamp, the operator opens **Flights → Flight → Passengers**, selects a checked-in passenger and confirms **Complete trip**. The flight must be a departing flight, its departure time must have passed, and it must not be cancelled. Confirming the same trip again does not create another stamp.

The `travel_stamps` table stores a snapshot of the city, flight, departure time, seat and booking code. Later flight edits preserve that confirmed travel history. `passportCities()` groups stamps with `GROUP BY city` and `COUNT(*)`. Each passenger can access only their own passport. New accounts start with an explanatory empty state.

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

SQLite operations are implemented in `data/AirportDb.java`. Each Activity has its own XML layout. Banner images are stored in `drawable-nodpi/`; `airport_sunrise.png` is generated artwork.

## Database

The Android database is **`airport_course.db`**, schema version **6**. Inspect it in Android Studio through **App Inspection → Database Inspector** while the app is running.

| Area | Tables |
| --- | --- |
| Accounts and flights | `users`, `flights` |
| Trips and saved flights | `bookings`, `saved_flights`, `travel_stamps` |
| Support conversations | `tickets`, `messages`, `reads` |
| Feedback and notifications | `reviews`, `notices` |
| Activity and demo scheduling | `ops_log`, `demo_days` |

The database contains 12 application tables. Opening the flights screen populates a six-day demo schedule. Schema upgrades preserve existing accounts, bookings and reviews.

The companion Safari web version uses a separate `airport.sqlite3` database on the computer. The Android and web databases are independent and do not synchronize automatically.

## Recorded validation

Validation was performed in separate QA installations. Recorded results from **21 November 2026** include:

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
- [Android SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)
- [Android Intents](https://developer.android.com/guide/components/intents-common)
