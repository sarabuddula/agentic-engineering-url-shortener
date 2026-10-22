package fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** FR-001 fixture test that passes (T128). */
class PassingTest {

    @Test
    void adds() {
        assertEquals(2, 1 + 1);
    }
}
