package kz.edu.airportcourse.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** One local database. Each query uses a Cursor and bound arguments. */
public final class AirportDb extends SQLiteOpenHelper {
    public static final String PASSENGER = "passenger", OPERATOR = "operator", ADMIN = "admin";
    public static final String DEMO_PASSWORD = "Airport123";
    public static final String[] FLIGHT_STATES = {"Уақтылы", "Кешігуде", "Отырғызу", "Тоқтатылды"};
    public static final String[] TICKET_STATES = {"Жаңа", "Қаралып жатыр", "Шешілді"};
    public static final int ALL_TRIP_TASKS = 15;
    public static final ZoneOffset ASTANA = ZoneOffset.ofHours(5);
    private static final String CABIN_COLUMNS = "ABCDEF";
    private static final int CABIN_ROWS = 5;
    private static final String[] DEMO_CITIES = {"Алматы", "Шымкент", "Ақтау", "Ыстанбұл", "Дубай", "Ташкент"};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public record Account(long id, String name, String email, String role, boolean active) {
        public String roleName() {
            if (role.equals(ADMIN)) return "Әкімші";
            if (role.equals(OPERATOR)) return "Оператор";
            return "Жолаушы";
        }
        public boolean demo() {
            return email.equals("user@airport.test") || email.equals("operator@airport.test") || email.equals("admin@airport.test");
        }
    }
    public record Flight(long id, String number, String city, String gate, long time, int direction, int status) {}
    public record Ticket(long id, long owner, String name, String subject, int state, long updated, int unread) {}
    public record Message(String sender, String role, String body, long time, boolean mine) {}
    public record Booking(long id, String code, String flight, String city, String gate, long time,
                          boolean checked, String seat, int status, int tasks, int direction) {
        public boolean upcoming(long now) { return time > now && status != 3; }
        public String route() { return direction == 0 ? "Астана → " + city : city + " → Астана"; }
    }
    public record Notice(long id, long flight, String title, String body, long created, boolean seen) {}
    public record ManifestEntry(String name, String code, String seat, boolean checked, boolean completed) {}
    public record TravelStamp(String code, String flightNumber, String city, long departure, String seat, long completed) {}
    public record PassportCity(String city, int trips, long lastDeparture) {}
    public record QueueEntry(String name, String code, String seat, String flight, String city, long time) {}
    public record OpsEntry(String actor, String action, String detail, long time) {}
    public record Review(long id, long owner, String name, int rating, String body, long updated) {}
    public record RatingCount(int rating, int count) {}

    public AirportDb(Context context) { super(context.getApplicationContext(), "airport_course.db", null, 6); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT NOT NULL UNIQUE,"
            + " role TEXT NOT NULL CHECK(role IN ('passenger','operator','admin')), salt TEXT NOT NULL,"
            + " password TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE TABLE flights(id INTEGER PRIMARY KEY, number TEXT NOT NULL UNIQUE, city TEXT NOT NULL,"
            + " gate TEXT NOT NULL, time INTEGER NOT NULL, direction INTEGER NOT NULL, status INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE tickets(id INTEGER PRIMARY KEY, owner INTEGER NOT NULL REFERENCES users(id),"
            + " subject TEXT NOT NULL, state INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, ticket INTEGER NOT NULL REFERENCES tickets(id),"
            + " sender INTEGER NOT NULL REFERENCES users(id), body TEXT NOT NULL, sent INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE reads(user INTEGER NOT NULL REFERENCES users(id), ticket INTEGER NOT NULL"
            + " REFERENCES tickets(id), last_message INTEGER NOT NULL, PRIMARY KEY(user,ticket))");
        db.execSQL("CREATE TABLE bookings(id INTEGER PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id),"
            + " flight INTEGER NOT NULL REFERENCES flights(id), code TEXT NOT NULL UNIQUE,"
            + " checked INTEGER NOT NULL DEFAULT 0, seat TEXT, UNIQUE(user,flight))");
        db.execSQL("CREATE INDEX message_ticket ON messages(ticket,id)");
        createTravelTables(db);
        addProfileSchema(db);
        createOpsLog(db);
        createReviews(db);
        createPassport(db);
        createAccount(db, "Қарлығаш", "user@airport.test", DEMO_PASSWORD, PASSENGER);
        createAccount(db, "Бейбарс", "operator@airport.test", DEMO_PASSWORD, OPERATOR);
        createAccount(db, "Ерасыл", "admin@airport.test", DEMO_PASSWORD, ADMIN);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE bookings ADD COLUMN seat TEXT");
            createTravelTables(db);
        }
        if (oldVersion < 3) addProfileSchema(db);
        if (oldVersion < 4) createOpsLog(db);
        if (oldVersion < 5) {
            createReviews(db);
            db.update("users", values("name", "Қарлығаш"), "email=? AND name=?", new String[]{"user@airport.test", "Аружан"});
            db.update("users", values("name", "Бейбарс"), "email=? AND name=?", new String[]{"operator@airport.test", "Данияр"});
            db.update("users", values("name", "Ерасыл"), "email=? AND name=?", new String[]{"admin@airport.test", "Айдана"});
        }
        if (oldVersion < 6) createPassport(db);
    }

    private static void createPassport(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE travel_stamps(id INTEGER PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id),"
            + " code TEXT NOT NULL UNIQUE, flight_number TEXT NOT NULL, city TEXT NOT NULL,"
            + " departure INTEGER NOT NULL, seat TEXT, completed INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX travel_stamps_user ON travel_stamps(user,departure)");
    }

    private static void createReviews(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reviews(id INTEGER PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id),"
            + " rating INTEGER NOT NULL CHECK(rating BETWEEN 1 AND 5), body TEXT NOT NULL"
            + " CHECK(length(trim(body)) BETWEEN 1 AND 2000), updated INTEGER NOT NULL)");
    }

    public Review review(long passenger) {
        require(passenger, PASSENGER);
        try (Cursor row = getReadableDatabase().rawQuery("SELECT r.id,r.user,u.name,r.rating,r.body,r.updated"
            + " FROM reviews r JOIN users u ON u.id=r.user WHERE r.user=? ORDER BY r.id DESC LIMIT 1", args(passenger))) {
            return row.moveToFirst() ? readReview(row) : null;
        }
    }

    public void saveReview(long passenger, int rating, String body) {
        require(passenger, PASSENGER);
        String message = body == null ? "" : body.trim();
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("1–5 аралығындағы бағаны таңдаңыз.");
        if (message.isEmpty() || message.length() > 2000) {
            throw new IllegalArgumentException("Пікірді 1–2000 таңба аралығында жазыңыз.");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.insertOrThrow("reviews", null, values("user", passenger, "rating", rating,
            "body", message, "updated", System.currentTimeMillis()));
    }

    public List<Review> reviews(long admin) {
        require(admin, ADMIN);
        List<Review> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT r.id,r.user,u.name,r.rating,r.body,r.updated"
            + " FROM reviews r JOIN users u ON u.id=r.user ORDER BY r.updated DESC,r.id DESC", null)) {
            while (row.moveToNext()) result.add(readReview(row));
        }
        return result;
    }

    public List<RatingCount> ratingDistribution(long admin) {
        require(admin, ADMIN);
        List<RatingCount> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT rating,COUNT(*) FROM reviews GROUP BY rating ORDER BY rating DESC", null)) {
            while (row.moveToNext()) result.add(new RatingCount(row.getInt(0), row.getInt(1)));
        }
        return result;
    }

    private static Review readReview(Cursor row) {
        return new Review(row.getLong(0), row.getLong(1), row.getString(2), row.getInt(3), row.getString(4), row.getLong(5));
    }

    private static void createTravelTables(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX booking_seat ON bookings(flight,seat)");
        db.execSQL("CREATE TABLE saved_flights(user INTEGER NOT NULL REFERENCES users(id),"
            + " flight INTEGER NOT NULL REFERENCES flights(id), PRIMARY KEY(user,flight))");
        db.execSQL("CREATE TABLE demo_days(day TEXT PRIMARY KEY NOT NULL)");
    }

    private static void addProfileSchema(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE users ADD COLUMN flight_alerts INTEGER NOT NULL DEFAULT 1");
        db.execSQL("ALTER TABLE bookings ADD COLUMN trip_tasks INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE notices(id INTEGER PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id),"
            + " flight INTEGER NOT NULL REFERENCES flights(id), title TEXT NOT NULL, body TEXT NOT NULL,"
            + " created INTEGER NOT NULL, seen INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX notices_user ON notices(user,id)");
    }

    private static void createOpsLog(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ops_log(id INTEGER PRIMARY KEY, actor INTEGER NOT NULL REFERENCES users(id),"
            + " action TEXT NOT NULL, detail TEXT NOT NULL, created INTEGER NOT NULL)");
    }

    private static void log(SQLiteDatabase db, long actor, String action, String detail) {
        db.insertOrThrow("ops_log", null, values("actor", actor, "action", action, "detail", detail,
            "created", System.currentTimeMillis()));
    }

    public List<OpsEntry> operations(long user) {
        Account actor = require(user, null);
        boolean admin = actor.role.equals(ADMIN);
        if (!admin && !actor.role.equals(OPERATOR)) throw new IllegalStateException("Бұл әрекетке рұқсат жоқ.");
        List<OpsEntry> result = new ArrayList<>();
        String where = admin ? "" : " WHERE l.actor=?";
        try (Cursor row = getReadableDatabase().rawQuery("SELECT u.name,l.action,l.detail,l.created FROM ops_log l"
            + " JOIN users u ON u.id=l.actor" + where + " ORDER BY l.id DESC LIMIT 60", admin ? null : args(user))) {
            while (row.moveToNext()) result.add(new OpsEntry(row.getString(0), row.getString(1), row.getString(2), row.getLong(3)));
        }
        return result;
    }

    public List<QueueEntry> checkInQueue(long operator) {
        require(operator, OPERATOR);
        long end = LocalDate.now(ASTANA).plusDays(1).atStartOfDay().toInstant(ASTANA).toEpochMilli();
        String[] bounds = {Long.toString(System.currentTimeMillis()), Long.toString(end)};
        List<QueueEntry> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT u.name,b.code,b.seat,f.number,f.city,f.time FROM bookings b"
            + " JOIN users u ON u.id=b.user JOIN flights f ON f.id=b.flight"
            + " WHERE b.checked=0 AND f.direction=0 AND f.status!=3 AND f.time>? AND f.time<? ORDER BY f.time,u.name", bounds)) {
            while (row.moveToNext()) result.add(new QueueEntry(row.getString(0), row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), row.getLong(5)));
        }
        return result;
    }

    public void ensureDemoSchedule() {
        SQLiteDatabase db = getWritableDatabase();
        LocalDate today = LocalDate.now(ASTANA);
        db.beginTransaction();
        try {
            long nextNumber = DatabaseUtils.longForQuery(db, "SELECT COALESCE(MAX(id),0)+201 FROM flights", null);
            for (int offset = 0; offset < 6; offset++) {
                LocalDate day = today.plusDays(offset);
                if (db.insertWithOnConflict("demo_days", null, values("day", day.toString()), SQLiteDatabase.CONFLICT_IGNORE) == -1) continue;
                for (int slot = 0; slot < 5; slot++) {
                    int city = (day.getDayOfYear() + slot) % DEMO_CITIES.length;
                    boolean arrival = slot == 4;
                    ContentValues flight = values("number", "AP " + nextNumber++, "city", DEMO_CITIES[city],
                        "gate", (arrival ? "A" : "B") + (slot + 2),
                        "time", day.atTime(arrival ? 15 : 9 + slot * 3, 40 - slot * 5).toInstant(ASTANA).toEpochMilli(),
                        "direction", arrival ? 1 : 0, "status", 0);
                    while (db.insertWithOnConflict("flights", null, flight, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                        flight.put("number", "AP " + nextNumber++);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public Set<Long> savedFlights(long user) {
        require(user, PASSENGER);
        Set<Long> result = new HashSet<>();
        try (Cursor row = getReadableDatabase().query("saved_flights", new String[]{"flight"},
            "user=?", args(user), null, null, null)) {
            while (row.moveToNext()) result.add(row.getLong(0));
        }
        return result;
    }

    public void setSaved(long user, long flight, boolean saved) {
        require(user, PASSENGER);
        SQLiteDatabase db = getWritableDatabase();
        if (saved) db.insertWithOnConflict("saved_flights", null, values("user", user, "flight", flight), SQLiteDatabase.CONFLICT_IGNORE);
        else db.delete("saved_flights", "user=? AND flight=?", new String[]{Long.toString(user), Long.toString(flight)});
    }

    private static ContentValues values(Object... entries) {
        ContentValues result = new ContentValues();
        for (int i = 0; i < entries.length; i += 2) {
            String key = (String) entries[i];
            Object value = entries[i + 1];
            if (value instanceof Number) result.put(key, ((Number) value).longValue());
            else result.put(key, (String) value);
        }
        return result;
    }

    private static String[] args(long first) { return new String[]{Long.toString(first)}; }

    private static String hash(String password, String salt) {
        PBEKeySpec key = new PBEKeySpec(password.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), 120_000, 256);
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable", e);
        } finally {
            key.clearPassword();
        }
    }

    private static String newSalt() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, Base64.NO_WRAP);
    }

    private static long createAccount(SQLiteDatabase db, String name, String email, String password, String role) {
        String salt = newSalt();
        return db.insertWithOnConflict("users", null, values("name", name, "email", email,
            "role", role, "salt", salt, "password", hash(password, salt)), SQLiteDatabase.CONFLICT_IGNORE);
    }

    public long register(String name, String email, String password) {
        return createAccount(getWritableDatabase(), name, email.toLowerCase(Locale.ROOT), password, PASSENGER);
    }

    public Account login(String email, String password) {
        try (Cursor row = getReadableDatabase().query("users",
            new String[]{"id","name","email","role","active","password","salt"},
            "email=?", new String[]{email.toLowerCase(Locale.ROOT)}, null, null, null)) {
            if (!row.moveToFirst() || row.getInt(4) == 0) return null;
            byte[] expected = Base64.decode(row.getString(5), Base64.NO_WRAP);
            byte[] entered = Base64.decode(hash(password, row.getString(6)), Base64.NO_WRAP);
            return MessageDigest.isEqual(expected, entered) ? account(row) : null;
        }
    }

    public Account account(long id) {
        try (Cursor row = getReadableDatabase().query("users", new String[]{"id","name","email","role","active"},
            "id=? AND active=1", args(id), null, null, null)) {
            return row.moveToFirst() ? account(row) : null;
        }
    }

    private static Account account(Cursor row) {
        return new Account(row.getLong(0), row.getString(1), row.getString(2), row.getString(3), row.getInt(4) != 0);
    }

    private Account require(long id, String role) {
        Account actor = account(id);
        if (actor == null || (role != null && !actor.role.equals(role))) {
            throw new IllegalStateException("Бұл әрекетке рұқсат жоқ.");
        }
        return actor;
    }

    public List<Account> accounts(long admin) {
        require(admin, ADMIN);
        List<Account> list = new ArrayList<>();
        try (Cursor row = getReadableDatabase().query("users", new String[]{"id","name","email","role","active"},
            null, null, null, null, "id")) {
            while (row.moveToNext()) list.add(account(row));
        }
        return list;
    }

    public void setActive(long admin, long target, boolean active) {
        require(admin, ADMIN);
        SQLiteDatabase db = getWritableDatabase();
        int state = active ? 1 : 0;
        db.beginTransaction();
        try {
            if (db.update("users", values("active", state), "id=? AND role!='admin' AND active!=?",
                new String[]{Long.toString(target), Integer.toString(state)}) == 1) {
                log(db, admin, active ? "Аккаунт қайта ашылды" : "Аккаунт бұғатталды", "Аккаунт #" + target);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public boolean addOperator(long admin, String name, String email, String password) {
        require(admin, ADMIN);
        String display = name.trim();
        String address = email.trim().toLowerCase(Locale.ROOT);
        if (display.isEmpty() || display.length() > 60 || address.length() > 90
            || !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()
            || password.length() < 8 || password.length() > 80) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = createAccount(db, display, address, password, OPERATOR);
            if (id < 0) return false;
            log(db, admin, "Оператор қосылды", display + " · #" + id);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public boolean rename(long user, String name) {
        require(user, null);
        String cleaned = name.trim();
        if (cleaned.isEmpty() || cleaned.length() > 60) return false;
        return getWritableDatabase().update("users", values("name", cleaned), "id=? AND active=1", args(user)) == 1;
    }

    public boolean changePassword(long user, String current, String replacement) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Account actor = require(user, null);
            if (actor.demo() || replacement.length() < 8 || replacement.length() > 80 || login(actor.email, current) == null) {
                return false;
            }
            String salt = newSalt();
            boolean changed = db.update("users", values("password", hash(replacement, salt), "salt", salt),
                "id=? AND active=1", args(user)) == 1;
            db.setTransactionSuccessful();
            return changed;
        } finally { db.endTransaction(); }
    }

    public boolean flightAlerts(long user) {
        require(user, PASSENGER);
        return DatabaseUtils.longForQuery(getReadableDatabase(), "SELECT flight_alerts FROM users WHERE id=?", args(user)) != 0;
    }

    public void setFlightAlerts(long user, boolean enabled) {
        require(user, PASSENGER);
        getWritableDatabase().update("users", values("flight_alerts", enabled ? 1 : 0), "id=? AND active=1", args(user));
    }

    public int unreadNotices(long user) {
        require(user, null);
        return (int) DatabaseUtils.longForQuery(getReadableDatabase(),
            "SELECT COUNT(*) FROM notices WHERE user=? AND seen=0", args(user));
    }

    public List<Notice> notices(long user) {
        require(user, null);
        List<Notice> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().query("notices", new String[]{"id","flight","title","body","created","seen"},
                "user=?", args(user), null, null, "id DESC")) {
            while (row.moveToNext()) result.add(new Notice(row.getLong(0), row.getLong(1), row.getString(2),
                row.getString(3), row.getLong(4), row.getInt(5) != 0));
        }
        return result;
    }

    public void readNotices(long user, Long id) {
        require(user, null);
        String where = id == null ? "user=? AND seen=0" : "user=? AND id=? AND seen=0";
        String[] selection = id == null ? args(user) : new String[]{Long.toString(user), id.toString()};
        getWritableDatabase().update("notices", values("seen", 1), where, selection);
    }

    public void deleteNotice(long user, long id) {
        require(user, null);
        getWritableDatabase().delete("notices", "user=? AND id=?", new String[]{Long.toString(user), Long.toString(id)});
    }

    public List<Flight> flights(int direction, String search) {
        List<Flight> list = new ArrayList<>();
        try (Cursor row = getReadableDatabase().query("flights",
            new String[]{"id","number","city","gate","time","direction","status"},
            "direction=?", args(direction), null, null, "time")) {
            String needle = search.toLowerCase(Locale.ROOT).replace(" ", "");
            while (row.moveToNext()) {
                Flight f = new Flight(row.getLong(0), row.getString(1), row.getString(2), row.getString(3),
                    row.getLong(4), row.getInt(5), row.getInt(6));
                if (f.number.toLowerCase(Locale.ROOT).replace(" ", "").contains(needle)
                    || f.city.toLowerCase(Locale.ROOT).replace(" ", "").contains(needle)
                    || airportCode(f.city).toLowerCase(Locale.ROOT).equals(needle)) list.add(f);
            }
        }
        return list;
    }

    public boolean saveFlight(long operator, Flight flight) {
        require(operator, OPERATOR);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues changes = values("number", flight.number, "city", flight.city, "gate", flight.gate,
            "time", flight.time, "direction", flight.direction, "status", flight.status);
        db.beginTransaction();
        try {
            if (flight.id == 0) {
                if (db.insertWithOnConflict("flights", null, changes, SQLiteDatabase.CONFLICT_IGNORE) == -1) return false;
                log(db, operator, "Рейс қосылды", flight.number + " · " + flight.city);
                db.setTransactionSuccessful();
                return true;
            }
            List<String> updates = new ArrayList<>();
            try (Cursor old = db.query("flights", new String[]{"gate","time","status","city","number","direction"},
                    "id=?", args(flight.id), null, null, null)) {
                if (!old.moveToFirst()) return false;
                if (!old.getString(0).equals(flight.gate)) updates.add("Қақпа " + old.getString(0) + " → " + flight.gate);
                if (old.getLong(1) != flight.time) updates.add("Уақыт " + time(old.getLong(1)) + " → " + time(flight.time));
                if (old.getInt(2) != flight.status) updates.add("Мәртебе · " + FLIGHT_STATES[flight.status]);
                if (!old.getString(3).equals(flight.city) || old.getInt(5) != flight.direction) {
                    updates.add("Бағыт · " + (flight.direction == 0 ? "Астана → " + flight.city : flight.city + " → Астана"));
                }
                if (!old.getString(4).equals(flight.number)) updates.add("Рейс " + old.getString(4) + " → " + flight.number);
            }
            if (db.updateWithOnConflict("flights", changes, "id=?", args(flight.id), SQLiteDatabase.CONFLICT_IGNORE) != 1) {
                return false;
            }
            if (!updates.isEmpty()) {
                log(db, operator, flight.number + " · Рейс жаңарды", String.join("\n", updates));
                db.execSQL("INSERT INTO notices(user,flight,title,body,created)"
                    + " SELECT b.user,b.flight,?,?,? FROM bookings b JOIN users u ON u.id=b.user"
                    + " WHERE b.flight=? AND u.active=1 AND u.flight_alerts=1",
                    new Object[]{flight.number + " · Рейс жаңарды", String.join("\n", updates), System.currentTimeMillis(), flight.id});
            }
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public long createTicket(long user, String subject, String message) {
        require(user, PASSENGER);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            long id = db.insertOrThrow("tickets", null, values("owner", user, "subject", subject, "updated", now));
            db.insertOrThrow("messages", null, values("ticket", id, "sender", user, "body", message, "sent", now));
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    public List<Ticket> tickets(long user) {
        Account who = require(user, null);
        List<Ticket> list = new ArrayList<>();
        String query = "SELECT t.id,t.owner,u.name,t.subject,t.state,t.updated,"
            + " (SELECT COUNT(*) FROM messages m WHERE m.ticket=t.id AND m.sender!=?"
            + " AND m.id>COALESCE((SELECT last_message FROM reads WHERE user=? AND ticket=t.id),0))"
            + " FROM tickets t JOIN users u ON u.id=t.owner";
        String uid = Long.toString(user);
        boolean passenger = who.role.equals(PASSENGER);
        try (Cursor row = getReadableDatabase().rawQuery(query + (passenger ? " WHERE t.owner=?" : "")
            + " ORDER BY t.updated DESC,t.id DESC", passenger ? new String[]{uid,uid,uid} : new String[]{uid,uid})) {
            while (row.moveToNext()) {
                list.add(new Ticket(row.getLong(0), row.getLong(1), row.getString(2), row.getString(3),
                    row.getInt(4), row.getLong(5), row.getInt(6)));
            }
        }
        return list;
    }

    public Ticket ticket(long user, long id) {
        for (Ticket ticket : tickets(user)) if (ticket.id == id) return ticket;
        throw new IllegalStateException("Өтінім табылмады.");
    }

    public int unread(long user) {
        int result = 0;
        for (Ticket ticket : tickets(user)) result += ticket.unread;
        return result;
    }

    public List<Message> openMessages(long user, long id) {
        ticket(user, id);
        SQLiteDatabase db = getWritableDatabase();
        List<Message> list = new ArrayList<>();
        db.beginTransaction();
        try {
            long last = 0;
            try (Cursor row = db.rawQuery("SELECT m.id,u.name,u.role,m.body,m.sent,m.sender"
                + " FROM messages m JOIN users u ON u.id=m.sender WHERE m.ticket=? ORDER BY m.id", args(id))) {
                while (row.moveToNext()) {
                    last = row.getLong(0);
                    list.add(new Message(row.getString(1), row.getString(2), row.getString(3), row.getLong(4),
                        row.getLong(5) == user));
                }
            }
            db.insertWithOnConflict("reads", null, values("user", user, "ticket", id, "last_message", last),
                SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
            return list;
        } finally { db.endTransaction(); }
    }

    public void send(long user, long id, String message) {
        if (require(user, null).role.equals(ADMIN)) throw new IllegalStateException("Жауапты оператор жібереді.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Ticket ticket = ticket(user, id);
            if (ticket.state == 2) throw new IllegalStateException("Өтінім шешілген.");
            long now = System.currentTimeMillis();
            db.insertOrThrow("messages", null, values("ticket", id, "sender", user, "body", message, "sent", now));
            db.update("tickets", values("updated", now), "id=?", args(id));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void setTicketState(long operator, long id, int state) {
        require(operator, OPERATOR);
        getWritableDatabase().update("tickets", values("state", state, "updated", System.currentTimeMillis()), "id=?", args(id));
    }

    public static List<String> seats() {
        List<String> result = new ArrayList<>();
        for (int row = 1; row <= CABIN_ROWS; row++) {
            for (int column = 0; column < CABIN_COLUMNS.length(); column++) {
                result.add(row + String.valueOf(CABIN_COLUMNS.charAt(column)));
            }
        }
        return result;
    }

    public Set<String> occupiedSeats(long user, long flight) {
        require(user, PASSENGER);
        Set<String> result = new HashSet<>();
        try (Cursor row = getReadableDatabase().query("bookings", new String[]{"seat"},
            "flight=? AND seat IS NOT NULL", args(flight), null, null, null)) {
            while (row.moveToNext()) result.add(row.getString(0));
        }
        return result;
    }

    public Booking booking(long user, long flight) {
        for (Booking item : bookings(user)) if (item.id == flight) return item;
        return null;
    }

    public Booking book(long user, long id, String seat) {
        require(user, PASSENGER);
        if (!seats().contains(seat)) throw new IllegalStateException("Бос орынды таңдаңыз.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Booking existing = booking(user, id);
            if (existing != null) {
                db.setTransactionSuccessful();
                return existing;
            }
            long now = System.currentTimeMillis();
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM flights"
                    + " WHERE id=? AND direction=0 AND status!=3 AND time>?", new String[]{Long.toString(id),Long.toString(now)}) != 1) {
                throw new IllegalStateException("Бұл рейске бронь жасау қолжетімсіз.");
            }
            String code = "NQZ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            long inserted = db.insertWithOnConflict("bookings", null,
                values("user", user, "flight", id, "code", code, "seat", seat), SQLiteDatabase.CONFLICT_IGNORE);
            if (inserted == -1) throw new IllegalStateException("Бұл орын бос емес. Басқа орын таңдаңыз.");
            Booking result = booking(user, id);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    public List<Booking> bookings(long user) {
        require(user, PASSENGER);
        List<Booking> list = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT f.id,b.code,f.number,f.city,f.gate,f.time,b.checked,b.seat,f.status,b.trip_tasks,f.direction"
            + " FROM bookings b JOIN flights f ON f.id=b.flight WHERE b.user=? ORDER BY f.time", args(user))) {
            while (row.moveToNext()) list.add(new Booking(row.getLong(0), row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), row.getLong(5), row.getInt(6) == 1,
                row.getString(7), row.getInt(8), row.getInt(9), row.getInt(10)));
        }
        return list;
    }

    public boolean cancelBooking(long user, long flightId) {
        require(user, PASSENGER);
        return getWritableDatabase().delete("bookings", "user=? AND flight=? AND checked=0",
            new String[]{Long.toString(user),Long.toString(flightId)}) == 1;
    }

    public boolean saveTripTasks(long user, long flight, int tasks) {
        require(user, PASSENGER);
        return getWritableDatabase().update("bookings", values("trip_tasks", tasks & ALL_TRIP_TASKS),
            "user=? AND flight=?", new String[]{Long.toString(user), Long.toString(flight)}) == 1;
    }

    public boolean checkIn(long operator, String code) {
        require(operator, OPERATOR);
        SQLiteDatabase db = getWritableDatabase();
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        db.beginTransaction();
        try {
            if (db.update("bookings", values("checked", 1),
                "code=? AND checked=0 AND flight IN (SELECT id FROM flights WHERE direction=0 AND status!=3 AND time>?)",
                new String[]{normalized, Long.toString(System.currentTimeMillis())}) != 1) return false;
            String flight = DatabaseUtils.stringForQuery(db, "SELECT f.number FROM flights f JOIN bookings b ON b.flight=f.id"
                + " WHERE b.code=?", new String[]{normalized});
            log(db, operator, "Жолаушы тіркелді", flight);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public List<ManifestEntry> manifest(long operator, long flight) {
        require(operator, OPERATOR);
        List<ManifestEntry> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT u.name,b.code,b.seat,b.checked,EXISTS(SELECT 1 FROM travel_stamps s WHERE s.code=b.code)"
            + " FROM bookings b JOIN users u ON u.id=b.user WHERE b.flight=? ORDER BY b.seat,b.id", args(flight))) {
            while (row.moveToNext()) {
                result.add(new ManifestEntry(row.getString(0), row.getString(1), row.getString(2), row.getInt(3) == 1, row.getInt(4) == 1));
            }
        }
        return result;
    }

    /** Operator confirms actual completion; a passed departure alone never awards a stamp. */
    public boolean completeTrip(long operator, String code) {
        require(operator, OPERATOR);
        if (code == null) return false;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM travel_stamps WHERE code=?", new String[]{normalized}) > 0) {
                db.setTransactionSuccessful();
                return true;
            }
            try (Cursor row = db.rawQuery("SELECT b.user,b.code,f.number,f.city,f.time,b.seat FROM bookings b"
                + " JOIN flights f ON f.id=b.flight WHERE b.code=? AND b.checked=1 AND f.direction=0"
                + " AND f.status!=3 AND f.time<=?", new String[]{normalized, Long.toString(System.currentTimeMillis())})) {
                if (!row.moveToFirst()) return false;
                db.insertWithOnConflict("travel_stamps", null, values("user", row.getLong(0), "code", row.getString(1),
                    "flight_number", row.getString(2), "city", row.getString(3), "departure", row.getLong(4),
                    "seat", row.getString(5), "completed", System.currentTimeMillis()), SQLiteDatabase.CONFLICT_IGNORE);
                log(db, operator, "Сапар аяқталды", row.getString(2) + " · " + normalized);
            }
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public List<TravelStamp> travelStamps(long passenger) {
        require(passenger, PASSENGER);
        List<TravelStamp> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT code,flight_number,city,departure,seat,completed"
            + " FROM travel_stamps WHERE user=? ORDER BY departure DESC,id DESC", args(passenger))) {
            while (row.moveToNext()) result.add(new TravelStamp(row.getString(0), row.getString(1), row.getString(2),
                row.getLong(3), row.getString(4), row.getLong(5)));
        }
        return result;
    }

    public List<PassportCity> passportCities(long passenger) {
        require(passenger, PASSENGER);
        List<PassportCity> result = new ArrayList<>();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT city,COUNT(*),MAX(departure) FROM travel_stamps"
            + " WHERE user=? GROUP BY city ORDER BY MAX(departure) DESC,city", args(passenger))) {
            while (row.moveToNext()) result.add(new PassportCity(row.getString(0), row.getInt(1), row.getLong(2)));
        }
        return result;
    }

    public static String cityCountry(String city) {
        return switch (airportCode(city.trim())) {
            case "ALA", "CIT", "SCO", "GUW" -> "Қазақстан";
            case "IST" -> "Түркия";
            case "DXB" -> "БАӘ";
            case "TAS" -> "Өзбекстан";
            default -> "";
        };
    }

    public static String time(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ASTANA).format(DATE);
    }

    public static String airportCode(String city) {
        return switch (city.toLowerCase(Locale.ROOT)) {
            case "алматы" -> "ALA";
            case "шымкент" -> "CIT";
            case "ақтау" -> "SCO";
            case "атырау" -> "GUW";
            case "ыстанбұл" -> "IST";
            case "дубай" -> "DXB";
            case "ташкент" -> "TAS";
            default -> "—";
        };
    }
}
