package com.flight.search.dto;

import java.util.List;

/** Wrapper for the flight-search response ({@code { "flights": [ ... ] }}). */
public record FlightSearchResponse(List<FlightSearchResult> flights) {
}
