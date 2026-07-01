package com.flight.search;

import com.flight.inventory.CatalogQueryService;
import com.flight.inventory.dto.FlightAvailability;
import com.flight.search.dto.FlightSearchResponse;
import com.flight.search.dto.FlightSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies that FlightSearchServiceImpl correctly delegates to CatalogQueryService and
 * maps every field from FlightAvailability (internal DTO) to FlightSearchResult (public DTO).
 * A silent field rename or reorder would produce nulls without these assertions.
 */
@ExtendWith(MockitoExtension.class)
class FlightSearchServiceImplTest {

    @Mock
    private CatalogQueryService catalogQueryService;

    private FlightSearchServiceImpl searchService;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    @BeforeEach
    void setUp() {
        searchService = new FlightSearchServiceImpl(catalogQueryService);
    }

    @Test
    void search_returnsMatchingFlights() {
        OffsetDateTime dep1 = OffsetDateTime.parse("2026-07-15T08:00:00Z");
        OffsetDateTime arr1 = OffsetDateTime.parse("2026-07-15T10:50:00Z");
        OffsetDateTime dep2 = OffsetDateTime.parse("2026-07-15T18:00:00Z");
        OffsetDateTime arr2 = OffsetDateTime.parse("2026-07-15T20:45:00Z");

        List<FlightAvailability> availability = List.of(
                new FlightAvailability("SF1", "6E-203", "BLR", "DEL", dep1, arr1, 42, new BigDecimal("5000.00")),
                new FlightAvailability("SF2", "6E-411", "BLR", "DEL", dep2, arr2, 18, new BigDecimal("5500.00"))
        );
        when(catalogQueryService.searchFlights("BLR", "DEL", DATE)).thenReturn(availability);

        FlightSearchResponse response = searchService.search("BLR", "DEL", DATE);

        assertThat(response.flights()).hasSize(2);

        FlightSearchResult first = response.flights().get(0);
        assertThat(first.scheduledFlightId()).isEqualTo("SF1");
        assertThat(first.flightNumber()).isEqualTo("6E-203");
        assertThat(first.source()).isEqualTo("BLR");
        assertThat(first.destination()).isEqualTo("DEL");
        assertThat(first.departureTime()).isEqualTo(dep1);
        assertThat(first.arrivalTime()).isEqualTo(arr1);
        assertThat(first.availableSeats()).isEqualTo(42);
        assertThat(first.baseFare()).isEqualByComparingTo("5000.00");

        assertThat(response.flights().get(1).scheduledFlightId()).isEqualTo("SF2");
        assertThat(response.flights().get(1).availableSeats()).isEqualTo(18);
    }

    @Test
    void search_noFlights_returnsEmptyList() {
        when(catalogQueryService.searchFlights("BLR", "DEL", DATE)).thenReturn(List.of());

        FlightSearchResponse response = searchService.search("BLR", "DEL", DATE);

        assertThat(response.flights()).isEmpty();
    }
}
