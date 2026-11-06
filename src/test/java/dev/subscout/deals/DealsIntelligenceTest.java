package dev.subscout.deals;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DealsIntelligenceTest {

    @Test
    void testCapDiscountCalculationDoesNotInflate() {
        // 20% discount on ₹2,000 reference spend (200,000 minor units) = ₹400 (40,000 minor units).
        // BUT max discount is capped at ₹200 (20,000 minor units).
        // Calculation must return exactly 20,000 minor units (₹200.00), NEVER inflated to ₹400.
        long savings = DealsIntelligenceService.calculateEstimatedSavings(
                null,       // discountAmountMinor
                20.0,       // discountPct (20%)
                200_00L,    // maxDiscountMinor (₹200 cap)
                500_00L,    // minSpendMinor (₹500)
                2000_00L,   // pastSpendMinor (₹2,000 past spend)
                1           // 1 transaction
        );

        assertEquals(200_00L, savings, "20% on ₹2,000 capped at ₹200 must strictly yield ₹200 savings (20000 minor units)");
    }

    @Test
    void testPercentageDiscountWithoutCapCalculatesCorrectly() {
        // 10% discount on ₹1,000 spend with no cap = ₹100 (10,000 minor units)
        long savings = DealsIntelligenceService.calculateEstimatedSavings(
                null,       // discountAmountMinor
                10.0,       // discountPct (10%)
                null,       // no max discount cap
                200_00L,    // minSpendMinor
                1000_00L,   // pastSpendMinor (₹1,000 spend)
                1           // 1 transaction
        );

        assertEquals(100_00L, savings, "10% on ₹1,000 without cap should yield ₹100 (10000 minor units)");
    }

    @Test
    void testFlatDiscountTakesPrecedence() {
        // Flat discount of ₹150 (15,000 minor units)
        long savings = DealsIntelligenceService.calculateEstimatedSavings(
                150_00L,    // discountAmountMinor
                null,
                null,
                199_00L,
                3000_00L,
                5
        );

        assertEquals(150_00L, savings, "Flat discount must be returned directly without arbitrary modifications");
    }

    @Test
    void testRelevanceScoringSignals() {
        // Base relevance = 0.50
        double baseScore = DealsIntelligenceService.calculateRelevanceScore("FOOD_DINING", "Swiggy", 0, true, false, false);
        assertEquals(0.50, baseScore, 0.001);

        // Frequent merchant spend boost (+0.35)
        double spendScore = DealsIntelligenceService.calculateRelevanceScore("FOOD_DINING", "Swiggy", 4, true, false, false);
        assertEquals(0.85, spendScore, 0.001);

        // Membership unlocked perk boost (+0.30)
        double membershipScore = DealsIntelligenceService.calculateRelevanceScore("FOOD_DINING", "Swiggy", 0, true, true, false);
        assertEquals(0.80, membershipScore, 0.001);

        // Expiring soon urgency boost (+0.15)
        double expiryScore = DealsIntelligenceService.calculateRelevanceScore("SHOPPING", "Amazon", 0, true, false, true);
        assertEquals(0.65, expiryScore, 0.001);
    }
}
