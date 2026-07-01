package com.flight.inventory;

import com.flight.inventory.dto.HoldResult;
import com.flight.inventory.entity.Seat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies that InventoryServiceImpl correctly maps SeatRepository rowcount responses
 * to HoldResult outcomes. The rowcount IS the no-oversell contract — these tests prove
 * the service interprets it correctly for all three cases.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock
    private SeatRepository seatRepository;

    private InventoryServiceImpl inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryServiceImpl(seatRepository);
    }

    // --- holdSeat ---

    @Test
    void holdSeat_won_returnsHeld() {
        Seat seat = seat("SEAT-SF1-3A", "3A");
        when(seatRepository.holdSeat(eq("SF1"), eq("3A"), eq("BK-1"), any())).thenReturn(1);
        when(seatRepository.findByScheduledFlightIdAndSeatNo("SF1", "3A")).thenReturn(Optional.of(seat));

        HoldResult result = inventoryService.holdSeat("SF1", "3A", "BK-1", Duration.ofMinutes(10));

        assertThat(result.outcome()).isEqualTo(HoldResult.Outcome.HELD);
        assertThat(result.seatId()).isEqualTo("SEAT-SF1-3A");
        assertThat(result.seatNo()).isEqualTo("3A");
    }

    @Test
    void holdSeat_seatExistsButLost_returnsConflict() {
        when(seatRepository.holdSeat(eq("SF1"), eq("3A"), eq("BK-1"), any())).thenReturn(0);
        when(seatRepository.findByScheduledFlightIdAndSeatNo("SF1", "3A")).thenReturn(Optional.of(seat("SEAT-SF1-3A", "3A")));

        HoldResult result = inventoryService.holdSeat("SF1", "3A", "BK-1", Duration.ofMinutes(10));

        assertThat(result.outcome()).isEqualTo(HoldResult.Outcome.CONFLICT);
        assertThat(result.seatId()).isNull();
    }

    @Test
    void holdSeat_seatNotFound_returnsNotFound() {
        when(seatRepository.holdSeat(eq("SF1"), eq("3A"), eq("BK-1"), any())).thenReturn(0);
        when(seatRepository.findByScheduledFlightIdAndSeatNo("SF1", "3A")).thenReturn(Optional.empty());

        HoldResult result = inventoryService.holdSeat("SF1", "3A", "BK-1", Duration.ofMinutes(10));

        assertThat(result.outcome()).isEqualTo(HoldResult.Outcome.NOT_FOUND);
    }

    // --- confirmSeat ---

    @Test
    void confirmSeat_rowcount1_returnsTrue() {
        when(seatRepository.commitSeat("SEAT-1", "BK-1")).thenReturn(1);
        assertThat(inventoryService.confirmSeat("SEAT-1", "BK-1")).isTrue();
    }

    @Test
    void confirmSeat_rowcount0_returnsFalse() {
        when(seatRepository.commitSeat("SEAT-1", "BK-1")).thenReturn(0);
        assertThat(inventoryService.confirmSeat("SEAT-1", "BK-1")).isFalse();
    }

    // --- releaseSeat ---

    @Test
    void releaseSeat_rowcount1_returnsTrue() {
        when(seatRepository.releaseSeat("BK-1")).thenReturn(1);
        assertThat(inventoryService.releaseSeat("BK-1")).isTrue();
    }

    // --- helpers ---

    private static Seat seat(String seatId, String seatNo) {
        Seat s = new Seat();
        s.setSeatId(seatId);
        s.setSeatNo(seatNo);
        return s;
    }
}
