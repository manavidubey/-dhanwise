package dev.subscout.catalogue;

import dev.subscout.platform.Currencies;
import dev.subscout.platform.Money;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Seeds the catalogue from versioned JSON. Invalid data aborts startup: a finance product
 * should refuse to run on a catalogue it cannot defend.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CatalogueSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogueSeeder.class);

    private final ObjectMapper mapper;
    private final CatalogueRepository repository;
    private final CatalogueValidator validator = new CatalogueValidator();

    public CatalogueSeeder(ObjectMapper mapper, CatalogueRepository repository) {
        this.mapper = mapper;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        CatalogueData.File file;
        try (InputStream input = getClass().getResourceAsStream("/catalogue/catalogue.json")) {
            if (input == null) {
                log.warn("No bundled catalogue found");
                return;
            }
            file = mapper.readValue(input, CatalogueData.File.class);
        }

        CatalogueValidator.Result result = validator.validate(file);
        for (String warning : result.warnings()) {
            log.warn("Catalogue: {}", warning);
        }
        if (!result.valid()) {
            result.errors().forEach(error -> log.error("Catalogue: {}", error));
            throw new IllegalStateException("Catalogue validation failed with " + result.errors().size() + " error(s)");
        }

        int offers = 0;
        for (CatalogueData.Provider provider : file.providers()) {
            repository.ensureProvider(provider.id(), provider.name(), provider.category(), provider.homepage());
            for (CatalogueData.Plan plan : provider.plans()) {
                String planId = provider.id() + "/" + plan.id();
                String tradeoffsJson = plan.lossTradeoffs() == null ? "[]" : dev.subscout.platform.Json.arr(plan.lossTradeoffs());
                repository.ensurePlan(planId, provider.id(), plan.name(), plan.capability(),
                        plan.storageGb(), plan.seats(), plan.maxResolution(), plan.simultaneousScreens(),
                        plan.hasAds(), plan.familySharing(), tradeoffsJson);

                if (plan.bundledPerks() != null) {
                    for (CatalogueData.BundledPerk perk : plan.bundledPerks()) {
                        String benefitId = planId + "/benefit/" + perk.capability();
                        repository.ensureBundledBenefit(benefitId, "plan", planId, perk.capability(),
                                perk.targetProviderId(), perk.targetPlanId(), perk.description(),
                                perk.activationRequired(), perk.status() != null ? perk.status() : "AVAILABLE_TO_CLAIM",
                                perk.termsUrl());
                    }
                }

                for (CatalogueData.Offer offer : plan.offers()) {
                    Money price = Money.parse(offer.price(), offer.currency());
                    String citationId = "cite-" + Integer.toHexString(
                            (offer.source().url() + "|" + offer.source().retrievedAt()).hashCode());
                    repository.ensureCitation(citationId, offer.source().url(), offer.source().retrievedAt(),
                            offer.source().reviewStatus(), offer.source().reviewer());
                    repository.ensureOffer(
                            repository.deterministicOfferId(planId, offer),
                            planId,
                            offer.region(),
                            offer.currency(),
                            Currencies.exponentOf(offer.currency()),
                            offer.billingPeriod(),
                            price.minor(),
                            offer.taxInclusive(),
                            offer.validFrom(),
                            offer.validTo() == null || offer.validTo().isBlank() ? null : offer.validTo(),
                            citationId);
                    offers++;
                }
            }
        }
        log.info("Catalogue ready: {} providers, {} offers (version {})",
                file.providers().size(), offers, file.version());
        if (repository.hasProposedOffers()) {
            log.warn("Catalogue contains offers with reviewStatus=proposed; the UI labels these as unverified");
        }
    }

    static List<String> regions(CatalogueData.File file) {
        return file.providers().stream()
                .flatMap(provider -> provider.plans().stream())
                .flatMap(plan -> plan.offers().stream())
                .map(CatalogueData.Offer::region)
                .distinct()
                .toList();
    }

    static LocalDate parseDate(String value) {
        return LocalDate.parse(value);
    }
}
