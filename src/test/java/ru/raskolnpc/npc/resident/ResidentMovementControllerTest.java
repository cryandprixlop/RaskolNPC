package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResidentMovementControllerTest {
    @Test
    void acceptsBoundedPositiveMovementStep() {
        assertDoesNotThrow(() -> new ResidentMovementController(0.8));
    }

    @Test
    void rejectsNonPositiveOrExcessiveMovementStep() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentMovementController(0));
        assertThrows(IllegalArgumentException.class, () -> new ResidentMovementController(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new ResidentMovementController(1.6));
    }
}
