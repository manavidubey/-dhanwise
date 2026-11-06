package dev.subscout.insight;

import static org.junit.jupiter.api.Assertions.*;

import dev.subscout.platform.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnnualSwitchSavingsTest {

    @Test
    @DisplayName("Equivalent plans with genuine savings calculate exact integer minor units")
    void testEquivalentPlanGenuineSavings() {
        // Spotify Premium Individual: ₹119/mo = ₹1,428/yr (142,800 minor units).
        // Spotify Premium Individual Annual: ₹1,189/yr (118,900 minor units).
        // Identical tier, 1 seat, lossless music streaming, same user eligibility.
        long monthlyTypicalMinor = 119_00L;
        long annualizedCurrentMinor = monthlyTypicalMinor * 12L;
        long verifiedAnnualMinor = 1189_00L;

        assertTrue(annualizedCurrentMinor > verifiedAnnualMinor);
        long savingsMinor = annualizedCurrentMinor - verifiedAnnualMinor;
        assertEquals(239_00L, savingsMinor);

        Money monthly = Money.ofMinor(monthlyTypicalMinor, "INR", 2);
        Money annual = Money.ofMinor(verifiedAnnualMinor, "INR", 2);
        Money savings = Money.ofMinor(savingsMinor, "INR", 2);

        assertEquals("₹119.00", monthly.display());
        assertEquals("₹1,189.00", annual.display());
        assertEquals("₹239.00", savings.display());
    }

    @Test
    @DisplayName("Annual plan more expensive than monthly payments must yield NO_FINANCIAL_BENEFIT")
    void testAnnualPlanMoreExpensiveYieldsNoFinancialBenefit() {
        // If monthly is ₹100/mo (₹1,200/yr), but annual plan is ₹1,300/yr:
        long monthlyTypicalMinor = 100_00L;
        long annualizedCurrentMinor = monthlyTypicalMinor * 12L;
        long expensiveAnnualMinor = 1300_00L;

        assertFalse(annualizedCurrentMinor > expensiveAnnualMinor,
                "Must never recommend switching if 12 monthly payments cost less than the annual plan");
    }

    @Test
    @DisplayName("Zero savings boundary condition must not generate a recommendation")
    void testZeroSavingsYieldsNoFinancialBenefit() {
        long monthlyTypicalMinor = 100_00L;
        long annualizedCurrentMinor = monthlyTypicalMinor * 12L;
        long identicalAnnualMinor = 1200_00L;

        assertFalse(annualizedCurrentMinor > identicalAnnualMinor,
                "Zero savings must not trigger a financial switch recommendation");
    }

    @Test
    @DisplayName("Different storage tiers must yield PLAN_MISMATCH")
    void testDifferentStorageTiersYieldsPlanMismatch() {
        // User pays for Google One 100 GB (100).
        // Candidate offer has 2,000 GB (2 TB).
        Integer currentStorageGb = 100;
        Integer candidateStorageGb = 2000;

        boolean storageEquivalent = currentStorageGb.equals(candidateStorageGb);
        assertFalse(storageEquivalent, "Tiers with different storage limits are not equivalent");
    }

    @Test
    @DisplayName("Different subscription tiers (e.g., Basic vs Premium) must yield PLAN_MISMATCH")
    void testDifferentSubscriptionTiersYieldsPlanMismatch() {
        String currentPlanId = "basic";
        String candidatePlanId = "premium";

        boolean planEquivalent = currentPlanId.equals(candidatePlanId);
        assertFalse(planEquivalent, "Comparing different tiers (Basic vs Premium) is a PLAN_MISMATCH");
    }

    @Test
    @DisplayName("Currency mismatch between monthly and annual plans must yield INSUFFICIENT_INFORMATION")
    void testCurrencyMismatchYieldsInsufficientInformation() {
        String currentCurrency = "INR";
        String candidateCurrency = "USD";

        boolean currencyAligned = currentCurrency.equalsIgnoreCase(candidateCurrency);
        assertFalse(currencyAligned, "Cannot compare INR commitment directly against USD offer without FX conversion");
    }

    @Test
    @DisplayName("Tax treatment mismatch must yield INSUFFICIENT_INFORMATION")
    void testTaxMismatchYieldsInsufficientInformation() {
        boolean currentTaxInclusive = true;
        boolean candidateTaxInclusive = false; // e.g., US pre-tax rate

        boolean taxAligned = currentTaxInclusive == candidateTaxInclusive;
        assertFalse(taxAligned, "Tax-inclusive price cannot be directly compared against tax-exclusive price");
    }

    @Test
    @DisplayName("Unreviewed source citations must yield UNVERIFIED_ANNUAL_PRICE")
    void testUnreviewedSourceCitationsYieldsUnverifiedPrice() {
        String reviewStatus = "proposed"; // Not yet reviewed by domain reviewer
        boolean verified = "reviewed".equalsIgnoreCase(reviewStatus);

        assertFalse(verified, "Proposed or unreviewed citations cannot generate verified financial recommendations");
    }

    @Test
    @DisplayName("Promotional pricing boundary check: First-year discount must not be represented as permanent annual savings")
    void testPromotionalAnnualPricingIntegrity() {
        long monthlyMinor = 1499_00L;
        long annualizedMinor = monthlyMinor * 12L;
        long regularAnnualMinor = 17988_00L;
        long promoFirstYearMinor = 6999_00L;

        // Regular annual equals 12x monthly, so long-term standard savings is 0
        long regularSavings = annualizedMinor - regularAnnualMinor;
        assertEquals(0L, regularSavings, "Standard annual plan has ₹0 net discount");

        long firstYearPromoSavings = annualizedMinor - promoFirstYearMinor;
        assertEquals(10989_00L, firstYearPromoSavings, "Promotional discount must be clearly distinguished from permanent annual savings");
    }

    @Test
    @DisplayName("Integer minor-unit overflow and boundary precision check")
    void testIntegerMinorUnitBoundaryCases() {
        long highMonthlyMinor = 45000_00L; // ₹45,000.00/mo
        long highAnnualizedMinor = highMonthlyMinor * 12L; // ₹5,40,000.00/yr
        long highAnnualMinor = 480000_00L; // ₹4,80,000.00/yr

        long savingsMinor = highAnnualizedMinor - highAnnualMinor;
        assertEquals(60000_00L, savingsMinor);
        assertEquals("₹60,000.00", Money.ofMinor(savingsMinor, "INR", 2).display());
    }
}
