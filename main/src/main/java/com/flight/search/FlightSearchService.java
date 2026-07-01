package com.flight.search;

import com.flight.search.dto.FlightSearchResponse;

import java.time.LocalDate;

/** Public read API of the {@code search} package (API 1). */
public interface FlightSearchService {

    /**
     * Find flights on the given route departing on {@code date}, each with an
     * available-seats snapshot.
     */
    FlightSearchResponse search(String source, String destination, LocalDate date);
}
