package net.usapo.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FloodgateMarketFormGatewayTest {
    @Test
    void formCopyIdentifiesServerXp() {
        assertTrue(FloodgateMarketFormGateway.INTRODUCTION.contains("サーバーXP"));
        assertEquals("サーバーXP残高", FloodgateMarketFormGateway.BALANCE_BUTTON_LABEL);
        assertEquals(
                "スタック全体の価格（サーバーXP）",
                FloodgateMarketFormGateway.PRICE_INPUT_LABEL);
        assertEquals("3000 サーバーXP", FloodgateMarketFormGateway.priceLabel(3_000));
    }
}
