package com.seatsure.service;

import com.seatsure.dto.AdminDtos.CinemaRequest;
import com.seatsure.dto.AdminDtos.CinemaView;
import com.seatsure.dto.AdminDtos.RowSpec;
import com.seatsure.dto.AdminDtos.ScreenRequest;
import com.seatsure.dto.AdminDtos.ScreenView;
import com.seatsure.entity.Cinema;
import com.seatsure.entity.City;
import com.seatsure.entity.Screen;
import com.seatsure.entity.Seat;
import com.seatsure.entity.SeatCategory;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.exception.ResourceNotFoundException;
import com.seatsure.repository.CinemaRepository;
import com.seatsure.repository.CityRepository;
import com.seatsure.repository.ScreenRepository;
import com.seatsure.repository.SeatRepository;
import com.seatsure.repository.SeatRepository.SeatCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Admin: cinemas, their screens and each screen's seat layout. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueAdminService {

    private final CityRepository cities;
    private final CinemaRepository cinemas;
    private final ScreenRepository screens;
    private final SeatRepository seats;

    /** Every cinema with its screens: three queries in all, however many cinemas and screens there are. */
    @Transactional(readOnly = true)
    public List<CinemaView> cinemas() {
        List<Cinema> all = cinemas.findAllWithCity();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Screen>> screensByCinema = screens.findByCinemaIdIn(all.stream().map(Cinema::getId).toList())
                .stream().collect(Collectors.groupingBy(s -> s.getCinema().getId()));
        List<Long> screenIds = screensByCinema.values().stream().flatMap(List::stream).map(Screen::getId).toList();
        Map<Long, List<SeatCount>> seatsByScreen = screenIds.isEmpty() ? Map.of()
                : seats.countByScreenAndCategory(screenIds).stream().collect(Collectors.groupingBy(SeatCount::getScreenId));
        return all.stream().map(c -> view(c, screensByCinema.getOrDefault(c.getId(), List.of()), seatsByScreen)).toList();
    }

    @Transactional
    public CinemaView createCinema(CinemaRequest request) {
        City city = cities.findById(request.cityId())
                .orElseThrow(() -> ResourceNotFoundException.of("City", request.cityId()));
        if (cinemas.existsByCityIdAndNameIgnoreCase(city.getId(), request.name().trim())) {
            throw new ConflictException("A cinema with this name already exists in " + city.getName());
        }
        Cinema cinema = cinemas.save(new Cinema(city, request.name().trim(), request.address().trim()));
        return view(cinema, List.of(), Map.of());
    }

    /** Creates a screen and its seats from a row-by-row layout. */
    @Transactional
    public ScreenView createScreen(Long cinemaId, ScreenRequest request) {
        Cinema cinema = cinemas.findById(cinemaId)
                .orElseThrow(() -> ResourceNotFoundException.of("Cinema", cinemaId));
        if (screens.existsByCinemaIdAndNameIgnoreCase(cinemaId, request.name().trim())) {
            throw new ConflictException("This cinema already has a screen called " + request.name().trim());
        }
        Set<String> labels = new HashSet<>();
        for (RowSpec row : request.rows()) {
            if (!labels.add(row.label())) {
                throw new BadRequestException("Row " + row.label() + " appears twice");
            }
        }
        Screen screen = screens.save(new Screen(cinema, request.name().trim()));
        List<Seat> layout = layoutSeats(screen, request.rows());
        seats.saveAll(layout);
        log.info("Screen {} created at cinema {} with {} seats", screen.getName(), cinemaId, layout.size());
        return new ScreenView(screen.getId(), screen.getName(), layout.size(),
                layout.stream().map(Seat::getCategory).distinct().sorted().toList());
    }

    /** Seat n of a row sits in grid column n + (number of aisles before it). */
    public static List<Seat> layoutSeats(Screen screen, List<RowSpec> rows) {
        List<Seat> result = new ArrayList<>();
        for (RowSpec row : rows) {
            Set<Integer> aisles = new HashSet<>(row.aisleAfter() == null ? List.of() : row.aisleAfter());
            for (Integer a : aisles) {
                if (a >= row.seats()) {
                    throw new BadRequestException("Row " + row.label() + ": an aisle after seat " + a
                            + " is outside the row (" + row.seats() + " seats)");
                }
            }
            int col = 0;
            for (int n = 1; n <= row.seats(); n++) {
                col++;
                result.add(new Seat(screen, row.label(), n, col, row.category()));
                if (aisles.contains(n)) {
                    col++; // leave an empty column
                }
            }
        }
        return result;
    }

    private static CinemaView view(Cinema cinema, List<Screen> cinemaScreens, Map<Long, List<SeatCount>> seatsByScreen) {
        List<ScreenView> screenViews = cinemaScreens.stream().map(sc -> {
            List<SeatCount> counts = seatsByScreen.getOrDefault(sc.getId(), List.of());
            int seatCount = (int) counts.stream().mapToLong(SeatCount::getSeats).sum();
            List<SeatCategory> categories = counts.stream().map(SeatCount::getCategory).distinct().sorted().toList();
            return new ScreenView(sc.getId(), sc.getName(), seatCount, categories);
        }).toList();
        return new CinemaView(cinema.getId(), cinema.getName(), cinema.getAddress(), cinema.getCity().getId(),
                cinema.getCity().getName(), screenViews);
    }
}
