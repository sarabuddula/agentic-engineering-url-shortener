package fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** FR-002 fixture test that fails on purpose (T128). */
class FailingTest {

    @Test
    void subtractsWrongly() {
        assertEquals(1, 3 - 1, "deliberate failure");
    }
}
