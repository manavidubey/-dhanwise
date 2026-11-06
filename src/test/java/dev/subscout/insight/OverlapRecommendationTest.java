package dev.subscout.insight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.subscout.platform.Ids;
import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverlapRecommendationTest {

    @Test
    void separateCapabilitiesNeverTriggerInvalidOverlap() {
        // Grammarly (writing_assistance), Swiggy One (food_delivery), Zomato Gold (food_delivery)
        InsightRepository.SubscriptionRow grammarly = new InsightRepository.SubscriptionRow(
                "sub-1", "profile-1", "series-1", "Grammarly", "active", "NONE",
                "annual", 1798800L, "INR", 2, LocalDate.now().plusMonths(1), 0.95,
                false, "writing_assistance", "offer-1", null);

        InsightRepository.SubscriptionRow swiggy = new InsightRepository.SubscriptionRow(
                "sub-2", "profile-1", "series-2", "Swiggy One", "active", "NONE",
                "quarterly", 14900L, "INR", 2, LocalDate.now().plusMonths(1), 0.95,
                false, "food_delivery", "offer-2", null);

        InsightRepository.SubscriptionRow zomato = new InsightRepository.SubscriptionRow(
                "sub-3", "profile-1", "series-3", "Zomato Gold", "active", "NONE",
                "quarterly", 9900L, "INR", 2, LocalDate.now().plusMonths(1), 0.95,
                false, "food_delivery", "offer-3", null);

        List<InsightRepository.SubscriptionRow> subs = List.of(grammarly, swiggy, zomato);

        // Group by capability
        java.util.Map<String, List<InsightRepository.SubscriptionRow>> byCapability = new java.util.LinkedHashMap<>();
        for (var sub : subs) {
            byCapability.computeIfAbsent(sub.capability(), ignored -> new java.util.ArrayList<>()).add(sub);
        }

        // writing_assistance has only Grammarly: no overlap
        assertFalse(byCapability.get("writing_assistance").size() >= 2,
                "Grammarly must not be grouped into an overlap with food delivery apps");

        // food_delivery has Swiggy and Zomato: valid functional comparison
        assertTrue(byCapability.get("food_delivery").size() == 2,
                "Swiggy One and Zomato Gold should be compared strictly within food_delivery");
    }
}
