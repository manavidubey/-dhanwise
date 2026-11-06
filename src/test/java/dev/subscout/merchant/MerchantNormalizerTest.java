package dev.subscout.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MerchantNormalizerTest {

    private final MerchantNormalizer normalizer = new MerchantNormalizer();

    @Test
    void stripsGatewayPrefixAndReferenceNumbers() {
        MerchantNormalizer.Normalized normalized = normalizer.normalize("RAZORPAY*NETFLIX COM 1234 IN");
        assertEquals("NETFLIX", normalized.normalized());
        assertEquals("Netflix", normalized.displayHint());
    }

    @Test
    void stripsUpiNarrationNoise() {
        MerchantNormalizer.Normalized normalized = normalizer.normalize("UPI/DR/412345678901/SPOTIFY INDIA");
        assertEquals("SPOTIFY", normalized.normalized());
    }

    @Test
    void collapsesDuplicatedBrandTokens() {
        MerchantNormalizer.Normalized normalized = normalizer.normalize("GOOGLE *Google One 9876");
        assertEquals("GOOGLE ONE", normalized.normalized());
        assertEquals("Google One", normalized.displayHint());
    }

    @Test
    void keepsMeaningfulProductWords() {
        assertEquals("AMAZON PRIME VIDEO", normalizer.normalize("AMAZON PRIME VIDEO IN").normalized());
    }

    @Test
    void handlesEmptyDescriptor() {
        assertEquals("UNKNOWN MERCHANT", normalizer.normalize(null).normalized());
        assertEquals("UNKNOWN MERCHANT", normalizer.normalize("   ").normalized());
    }
}
