package com.seatsure.demo;

import com.seatsure.config.CacheConfig;
import com.seatsure.dto.AdminDtos.RowSpec;
import com.seatsure.entity.*;
import com.seatsure.repository.*;
import com.seatsure.service.AdminCatalogService;
import com.seatsure.service.BookingReferences;
import com.seatsure.service.Roles;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/**
 * Seeds (and optionally resets) a realistic demo: cinemas in four cities, fictional movies, four
 * days of shows around today, and seats already partly sold, so the seat maps, "filling fast"
 * badges and the admin dashboard look alive. Enabled with app.demo.enabled=true.
 *
 * <p>Catalog rows go through JPA. The bulk (shows, ~40k seat inventory rows, thousands of
 * bookings) is written with JDBC batches and ids reserved from the sequences up front.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    // ---------------------------------------------------------------- the dataset

    private record MovieSeed(String title, String language, Certificate certificate, int minutes, String genres,
                             int releasedDaysAgo, String synopsis) {
    }

    private static final List<MovieSeed> MOVIES = List.of(
            new MovieSeed("Monsoon Protocol", "Hindi", Certificate.UA, 152, "Action,Thriller", 9,
                    "When a cyberattack shuts down Mumbai's flood barriers on the wettest night of the year, a "
                            + "suspended disaster-response officer has six hours to find the insider who sold the codes."),
            new MovieSeed("Orbit of Ashes", "English", Certificate.UA, 141, "Sci-Fi,Adventure", 16,
                    "The last crew of a failing orbital refinery must decide whether to save their station "
                            + "or the planet below it."),
            new MovieSeed("The Tea Estate Murders", "Hindi", Certificate.UA, 128, "Mystery,Drama", 23,
                    "A retired detective visiting her sister in the Nilgiris is drawn into the disappearance "
                            + "of a planter, and a family that has kept the same secret for forty years."),
            new MovieSeed("Kalinga Rising", "Odia", Certificate.UA, 165, "Historical,Action", 4,
                    "A sweeping retelling of a young general who united rival harbour towns against an "
                            + "invading fleet."),
            new MovieSeed("Paper Boats", "Tamil", Certificate.U, 118, "Family,Drama", 30,
                    "Two brothers who haven't spoken in a decade drive their grandmother back to the fishing "
                            + "village where she was born."),
            new MovieSeed("Night Shift at Malabar", "Malayalam", Certificate.A, 112, "Horror,Thriller", 11,
                    "A trainee doctor on her first night shift at a coastal hospital realises the patients on "
                            + "the third floor were discharged in 1987."),
            new MovieSeed("Gully Champions", "Hindi", Certificate.U, 135, "Sports,Comedy", 2,
                    "A gang of teenagers from a Dharavi lane enter a corporate cricket tournament with a "
                            + "borrowed bat and a coach who has never played."),
            new MovieSeed("Silicon Hearts", "English", Certificate.UA, 109, "Romance,Comedy", 18,
                    "Two rival app founders in Bengaluru fake a relationship to win over the same investor."),
            new MovieSeed("The Last Ferry to Ross Island", "Bengali", Certificate.UA, 131, "Drama", -12,
                    "An archivist travelling to the Andamans to close a family case finds a ferry log that "
                            + "rewrites her grandfather's story."),
            new MovieSeed("Code Red: Bengaluru", "Kannada", Certificate.UA, 147, "Action,Crime", -20,
                    "A traffic constable stumbles on a heist that uses the city's gridlock as its getaway plan."));

    private record CinemaSeed(String city, String name, String address, List<String> lineup) {
    }

    private static final List<CinemaSeed> CINEMAS = List.of(
            new CinemaSeed("Mumbai", "Starlight Cinemas: Andheri West", "4th floor, Link Square, Link Road, Andheri West",
                    List.of("Monsoon Protocol", "Gully Champions", "Orbit of Ashes", "The Tea Estate Murders", "Silicon Hearts")),
            new CinemaSeed("Mumbai", "Aurora Multiplex: Lower Parel", "Mill Compound, Senapati Bapat Marg, Lower Parel",
                    List.of("Gully Champions", "Monsoon Protocol", "Silicon Hearts", "Orbit of Ashes", "Paper Boats")),
            new CinemaSeed("Bengaluru", "Starlight Cinemas: Koramangala", "80 Feet Road, 4th Block, Koramangala",
                    List.of("Silicon Hearts", "Orbit of Ashes", "Night Shift at Malabar", "Monsoon Protocol", "Paper Boats")),
            new CinemaSeed("Bengaluru", "Lumen Screens: Whitefield", "Phoenix Tech Park, ITPL Main Road, Whitefield",
                    List.of("Orbit of Ashes", "Night Shift at Malabar", "Gully Champions", "Silicon Hearts", "The Tea Estate Murders")),
            new CinemaSeed("Delhi NCR", "Aurora Multiplex: Saket", "District Centre, Press Enclave Marg, Saket",
                    List.of("Monsoon Protocol", "The Tea Estate Murders", "Orbit of Ashes", "Gully Champions", "Silicon Hearts")),
            new CinemaSeed("Delhi NCR", "Lumen Screens: Gurugram", "Cyber Park, DLF Phase 2, Gurugram",
                    List.of("Orbit of Ashes", "Gully Champions", "Monsoon Protocol", "Silicon Hearts", "Night Shift at Malabar")),
            new CinemaSeed("Bhubaneswar", "Starlight Cinemas: Patia", "KIIT Road, Patia",
                    List.of("Kalinga Rising", "Monsoon Protocol", "Gully Champions", "Orbit of Ashes", "Paper Boats")),
            new CinemaSeed("Bhubaneswar", "Aurora Multiplex: Janpath", "Unit 3, Janpath",
                    List.of("Kalinga Rising", "The Tea Estate Murders", "Silicon Hearts", "Gully Champions", "Monsoon Protocol")));

    /** Large screen: 8 Classic rows, 3 Prime rows and a Recliner row at the back. */
    private static final List<RowSpec> LARGE_SCREEN = rows(
            "ABCDEFGH", SeatCategory.CLASSIC, 18, List.of(4, 14),
            "JKL", SeatCategory.PRIME, 18, List.of(4, 14),
            "M", SeatCategory.RECLINER, 8, List.of(4));
    private static final List<RowSpec> SMALL_SCREEN = rows(
            "ABCDEF", SeatCategory.CLASSIC, 14, List.of(7),
            "GH", SeatCategory.PRIME, 14, List.of(7),
            "J", SeatCategory.RECLINER, 6, List.of(3));

    private static final List<LocalTime> SLOTS = List.of(
            LocalTime.of(10, 15), LocalTime.of(13, 45), LocalTime.of(17, 15), LocalTime.of(20, 45));

    private static final String[] CUSTOMER_NAMES = {
            "Aarav Sharma", "Ananya Iyer", "Vivaan Reddy", "Diya Nair", "Ishaan Gupta", "Kavya Das", "Arjun Mohanty",
            "Saanvi Mehta", "Rohan Rao", "Meera Kulkarni", "Kabir Banerjee", "Priya Joshi", "Aditya Pillai",
            "Nisha Chopra", "Rahul Mishra", "Sneha Bose", "Vikram Singh", "Pooja Patel", "Siddharth Menon", "Riya Sethi"};

    // ---------------------------------------------------------------- wiring

    @Value("${app.demo.enabled:false}")
    private boolean enabled;

    @Value("${app.demo.admin-email:demo.admin@seatsure.dev}")
    private String demoAdminEmail;

    @Value("${app.demo.customer-email:demo.customer@seatsure.dev}")
    private String demoCustomerEmail;

    @Value("${app.booking.convenience-fee-paise:2500}")
    private int feePerSeat;

    private final CityRepository cities;
    private final CinemaRepository cinemas;
    private final ScreenRepository screens;
    private final SeatRepository seats;
    private final MovieRepository movies;
    private final UserRepository users;
    private final LoginSecurityRepository loginSecurity;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    /** One unusable random hash shared by all demo accounts (hashing per account is slow on small hosts). */
    private String unusablePasswordHash;

    public boolean isEnabled() {
        return enabled;
    }

    public String demoAdminEmail() {
        return demoAdminEmail;
    }

    public String demoCustomerEmail() {
        return demoCustomerEmail;
    }

    /** Seeds the dataset if demo mode is on and there are no cinemas yet. */
    @CacheEvict(allEntries = true, cacheNames = {CacheConfig.NOW_SHOWING, CacheConfig.ADMIN_DASHBOARD})
    @Transactional
    public void seedIfEmpty() {
        if (!enabled) {
            return;
        }
        ensureAccount("Demo Admin", demoAdminEmail, Roles.ROLE_ADMIN);
        if (cinemas.count() > 0) {
            log.info("Demo mode: data already present, skipping seed");
            return;
        }
        seed();
    }

    /** Wipes the catalog, bookings and customer accounts (admins are kept), then reseeds around today. */
    @CacheEvict(allEntries = true, cacheNames = {CacheConfig.NOW_SHOWING, CacheConfig.ADMIN_DASHBOARD})
    @Transactional
    public void reset() {
        if (!enabled) {
            return;
        }
        log.info("Demo mode: resetting data");
        entityManager.flush();
        entityManager.clear();
        // Cascades to screens, seats, shows, prices, show seats, holds, payments, waitlists and bookings
        jdbc.execute("TRUNCATE movies, cinemas, payment_webhook_events, mock_gateway_events CASCADE");
        jdbc.update("DELETE FROM users WHERE role = ?", Roles.ROLE_CUSTOMER.name());
        ensureAccount("Demo Admin", demoAdminEmail, Roles.ROLE_ADMIN);
        seed();
    }

    // ---------------------------------------------------------------- seeding

    private void seed() {
        Random random = new Random(2026); // deterministic: the same demo every day
        LocalDate today = LocalDate.now(clock.withZone(zone));

        Map<String, Movie> movieByTitle = new LinkedHashMap<>();
        for (MovieSeed m : MOVIES) {
            Movie movie = new Movie();
            movie.setTitle(m.title());
            movie.setSynopsis(m.synopsis());
            movie.setLanguage(m.language());
            movie.setCertificate(m.certificate());
            movie.setDurationMinutes(m.minutes());
            movie.setGenres(m.genres());
            movie.setReleaseDate(today.minusDays(m.releasedDaysAgo()));
            movieByTitle.put(m.title(), movies.save(movie));
        }

        List<Long> customerIds = new ArrayList<>();
        customerIds.add(ensureAccount("Demo Customer", demoCustomerEmail, Roles.ROLE_CUSTOMER).getId());
        for (String name : CUSTOMER_NAMES) {
            String email = name.toLowerCase(Locale.ROOT).replace(' ', '.') + DemoPrivacy.SEEDED_DOMAIN;
            customerIds.add(ensureAccount(name, email, Roles.ROLE_CUSTOMER).getId());
        }

        List<ScreenPlan> plans = new ArrayList<>();
        for (CinemaSeed c : CINEMAS) {
            City city = cities.findByNameIgnoreCase(c.city())
                    .orElseThrow(() -> new IllegalStateException("Reference city missing: " + c.city()));
            Cinema cinema = cinemas.save(new Cinema(city, c.name(), c.address()));
            plans.add(screen(cinema, "Audi 1", LARGE_SCREEN, c, true));
            plans.add(screen(cinema, "Audi 2", SMALL_SCREEN, c, false));
        }
        entityManager.flush(); // JDBC below reads what JPA just wrote

        Stats stats = new Stats();
        for (int dayOffset = -1; dayOffset <= 2; dayOffset++) {
            LocalDate day = today.plusDays(dayOffset);
            for (int p = 0; p < plans.size(); p++) {
                ScreenPlan plan = plans.get(p);
                for (int slot = 0; slot < SLOTS.size(); slot++) {
                    String title = plan.lineup().get(Math.floorMod(slot + p + dayOffset * 2, plan.lineup().size()));
                    Movie movie = movieByTitle.get(title);
                    // The demo customer has one past and one upcoming booking at the first cinema
                    boolean forDemoCustomer = p == 0 && ((dayOffset == -1 && slot == 2) || (dayOffset == 1 && slot == 3));
                    seedShow(plan, movie, day, slot, customerIds, forDemoCustomer, random, stats);
                }
            }
        }
        stats.flush(jdbc);
        log.info("Demo mode: seeded {} cinemas, {} movies, {} shows, {} bookings ({} tickets)",
                CINEMAS.size(), MOVIES.size(), stats.shows, stats.bookingCount, stats.tickets);
    }

    private record SeatInfo(long id, String row, int number, SeatCategory category) {
    }

    private record ScreenPlan(long screenId, boolean large, List<String> lineup, String city,
                              List<List<SeatInfo>> rows) {
    }

    private ScreenPlan screen(Cinema cinema, String name, List<RowSpec> layout, CinemaSeed seed, boolean large) {
        Screen screen = screens.save(new Screen(cinema, name));
        List<Seat> created = seats.saveAll(AdminCatalogService.layoutSeats(screen, layout));
        Map<String, List<SeatInfo>> byRow = new LinkedHashMap<>();
        for (Seat s : created) {
            byRow.computeIfAbsent(s.getRowLabel(), r -> new ArrayList<>())
                    .add(new SeatInfo(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.getCategory()));
        }
        return new ScreenPlan(screen.getId(), large, seed.lineup(), seed.city(), List.copyOf(byRow.values()));
    }

    private void seedShow(ScreenPlan plan, Movie movie, LocalDate day, int slot, List<Long> customerIds,
                          boolean forDemoCustomer, Random random, Stats stats) {
        Instant now = clock.instant();
        Instant startsAt = day.atTime(SLOTS.get(slot)).atZone(zone).toInstant();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(movie.getDurationMinutes())).plus(Show.TURNAROUND);
        String format = plan.large() && movie.getTitle().equals("Orbit of Ashes") ? "3D" : "2D";
        long showId = stats.nextShowId(jdbc);
        stats.shows++;
        stats.showRows.add(new Object[]{showId, movie.getId(), plan.screenId(), ts(startsAt), ts(endsAt), format,
                "SCHEDULED", ts(now)});

        Map<SeatCategory, Integer> prices = prices(plan.city(), day, slot);
        prices.forEach((category, paise) -> stats.priceRows.add(new Object[]{showId, category.name(), paise}));

        // How full: evenings and weekends sell better; a few shows sell out
        boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
        double target = 0.15 + slot * 0.12 + (weekend ? 0.15 : 0) + random.nextDouble() * 0.25;
        if (random.nextDouble() < 0.05) {
            target = 1.0;
        }
        Map<Long, Long> bookedBy = new HashMap<>(); // seat id -> booking id
        if (forDemoCustomer) {
            // Two seats in the middle of the first Prime row
            List<SeatInfo> prime = plan.rows().stream()
                    .filter(r -> r.get(0).category() == SeatCategory.PRIME).findFirst().orElseThrow();
            int mid = prime.size() / 2;
            book(prime.subList(mid - 1, mid + 1), customerIds.get(0), showId, startsAt, prices, bookedBy, random, stats);
        }
        for (List<SeatInfo> row : plan.rows()) {
            int i = 0;
            while (i < row.size()) {
                if (bookedBy.containsKey(row.get(i).id()) || random.nextDouble() >= target) {
                    i++;
                    continue;
                }
                int group = 1;
                int max = 1 + random.nextInt(4);
                while (group < max && i + group < row.size() && !bookedBy.containsKey(row.get(i + group).id())) {
                    group++;
                }
                long customer = customerIds.get(1 + random.nextInt(customerIds.size() - 1));
                book(row.subList(i, i + group), customer, showId, startsAt, prices, bookedBy, random, stats);
                i += group;
            }
        }
        for (List<SeatInfo> row : plan.rows()) {
            for (SeatInfo s : row) {
                Long booking = bookedBy.get(s.id());
                stats.showSeatRows.add(new Object[]{showId, s.id(), booking == null ? "AVAILABLE" : "BOOKED", booking});
            }
        }
        if (stats.showSeatRows.size() > 5000) {
            stats.flush(jdbc);
        }
    }

    private void book(List<SeatInfo> groupSeats, long customer, long showId, Instant startsAt,
                      Map<SeatCategory, Integer> prices, Map<Long, Long> bookedBy, Random random, Stats stats) {
        long bookingId = stats.nextBookingId(jdbc);
        int tickets = 0;
        for (SeatInfo s : groupSeats) {
            int price = prices.get(s.category());
            tickets += price;
            bookedBy.put(s.id(), bookingId);
            stats.bookingSeatRows.add(new Object[]{bookingId, s.id(), s.row() + s.number(), s.category().name(), price});
        }
        // Booked some time in the five days before the show (and never in the future)
        Instant now = clock.instant();
        Instant latest = startsAt.minus(Duration.ofHours(1));
        if (latest.isAfter(now)) {
            latest = now;
        }
        Instant bookedAt = latest.minusSeconds(random.nextInt(5 * 24 * 3600));
        stats.bookingRows.add(new Object[]{bookingId, stats.uniqueReference(random), customer, showId, "CONFIRMED",
                groupSeats.size(), tickets, feePerSeat * groupSeats.size(), ts(bookedAt)});
        stats.bookingCount++;
        stats.tickets += groupSeats.size();
    }

    /** Ticket prices in paise: bigger cities cost more, evenings and weekends a little more. */
    private static Map<SeatCategory, Integer> prices(String city, LocalDate day, int slot) {
        boolean metro = !city.equals("Bhubaneswar");
        double factor = (slot >= 2 ? 1.2 : 1.0)
                * (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY ? 1.1 : 1.0);
        Map<SeatCategory, Integer> prices = new EnumMap<>(SeatCategory.class);
        prices.put(SeatCategory.CLASSIC, roundToTenRupees((metro ? 220 : 150) * factor));
        prices.put(SeatCategory.PRIME, roundToTenRupees((metro ? 320 : 220) * factor));
        prices.put(SeatCategory.RECLINER, roundToTenRupees((metro ? 650 : 450) * factor));
        return prices;
    }

    private static int roundToTenRupees(double rupees) {
        return (int) (Math.round(rupees / 10.0) * 10) * 100;
    }

    /** Bound as timestamptz with an explicit offset, independent of the JVM and session time zones. */
    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private User ensureAccount(String name, String email, Roles role) {
        Optional<User> existing = users.findByEmail(User.normalizeEmail(email));
        if (existing.isPresent()) {
            return existing.get();
        }
        if (unusablePasswordHash == null) {
            unusablePasswordHash = passwordEncoder.encode(UUID.randomUUID() + "-" + new SecureRandom().nextLong());
        }
        User user = users.save(new User(name, email, unusablePasswordHash, role));
        loginSecurity.save(new LoginSecurity(user));
        return user;
    }

    private static List<RowSpec> rows(Object... spec) {
        List<RowSpec> result = new ArrayList<>();
        for (int i = 0; i < spec.length; i += 4) {
            String labels = (String) spec[i];
            SeatCategory category = (SeatCategory) spec[i + 1];
            int count = (Integer) spec[i + 2];
            @SuppressWarnings("unchecked") List<Integer> aisles = (List<Integer>) spec[i + 3];
            for (char label : labels.toCharArray()) {
                result.add(new RowSpec(String.valueOf(label), category, count, aisles));
            }
        }
        return result;
    }

    /** Rows collected for batch insertion, and ids reserved from the identity sequences in blocks. */
    private static final class Stats {
        int shows;
        int bookingCount;
        int tickets;
        final List<Object[]> showRows = new ArrayList<>();
        final List<Object[]> priceRows = new ArrayList<>();
        final List<Object[]> bookingRows = new ArrayList<>();
        final List<Object[]> bookingSeatRows = new ArrayList<>();
        final List<Object[]> showSeatRows = new ArrayList<>();
        private final Deque<Long> showIds = new ArrayDeque<>();
        private final Deque<Long> bookingIds = new ArrayDeque<>();
        private final Set<String> references = new HashSet<>();

        /** The tables were just emptied, so uniqueness within this seed is uniqueness overall. */
        String uniqueReference(Random random) {
            String ref;
            do {
                ref = BookingReferences.next(random);
            } while (!references.add(ref));
            return ref;
        }

        long nextShowId(JdbcTemplate jdbc) {
            return next(jdbc, showIds, "shows");
        }

        long nextBookingId(JdbcTemplate jdbc) {
            return next(jdbc, bookingIds, "bookings");
        }

        private static long next(JdbcTemplate jdbc, Deque<Long> pool, String table) {
            if (pool.isEmpty()) {
                pool.addAll(jdbc.queryForList("SELECT nextval(pg_get_serial_sequence('" + table
                        + "', 'id')) FROM generate_series(1, 500)", Long.class));
            }
            return pool.pop();
        }

        /** Parents before children: shows, prices, bookings, booking seats, then the seat inventory. */
        void flush(JdbcTemplate jdbc) {
            jdbc.batchUpdate("INSERT INTO shows (id, movie_id, screen_id, starts_at, ends_at, format, status, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", showRows);
            jdbc.batchUpdate("INSERT INTO show_prices (show_id, category, price_paise) VALUES (?, ?, ?)", priceRows);
            jdbc.batchUpdate("INSERT INTO bookings (id, reference, user_id, show_id, status, seat_count, tickets_paise, "
                    + "convenience_fee_paise, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", bookingRows);
            jdbc.batchUpdate("INSERT INTO booking_seats (booking_id, seat_id, seat_label, category, price_paise) "
                    + "VALUES (?, ?, ?, ?, ?)", bookingSeatRows);
            jdbc.batchUpdate("INSERT INTO show_seats (show_id, seat_id, status, booking_id) VALUES (?, ?, ?, ?)",
                    showSeatRows);
            showRows.clear();
            priceRows.clear();
            bookingRows.clear();
            bookingSeatRows.clear();
            showSeatRows.clear();
        }
    }
}
