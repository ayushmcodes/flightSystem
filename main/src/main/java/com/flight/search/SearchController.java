package com.flight.search;

import com.flight.search.dto.FlightSearchResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class SearchController {

    private final FlightSearchService flightSearchService;

    public SearchController(FlightSearchService flightSearchService) {
        this.flightSearchService = flightSearchService;
    }

    // GET /flights/search?source=BLR&destination=DEL&date=2026-07-15
    @GetMapping("/flights/search")
    public FlightSearchResponse search(@RequestParam String source,
                                       @RequestParam String destination,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return flightSearchService.search(source, destination, date);
    }
}
