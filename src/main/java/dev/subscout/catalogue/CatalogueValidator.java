package dev.subscout.catalogue;

import dev.subscout.platform.Currencies;
import dev.subscout.platform.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Catalogue integrity checks. These are the checks that stop a plausible-looking but wrong
 * price from reaching a recommendation: unknown currency, missing citation, duplicate
 * effective-dated offer, or an annual price that is not cheaper than twelve months.
 */
public final class CatalogueValidator {

    public record Result(List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public Result validate(CatalogueData.File file) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (file == null || file.providers() == null || file.providers().isEmpty()) {
            return new Result(List.of("Catalogue has no providers"), List.of());
        }
        if (file.version() == null || file.version().isBlank()) {
            errors.add("Catalogue version is required");
        }

        Set<String> providerIds = new HashSet<>();
        Set<String> planIds = new HashSet<>();
        Set<String> offerKeys = new HashSet<>();

        for (CatalogueData.Provider provider : file.providers()) {
            if (blank(provider.id()) || blank(provider.name())) {
                errors.add("Provider with missing id or name");
                continue;
            }
            if (!providerIds.add(provider.id())) {
                errors.add("Duplicate provider id: " + provider.id());
            }
            if (provider.homepage() != null && !provider.homepage().startsWith("https://")) {
                errors.add("Provider " + provider.id() + " homepage must be https");
            }
            if (provider.plans() == null || provider.plans().isEmpty()) {
                warnings.add("Provider " + provider.id() + " has no plans");
                continue;
            }

            for (CatalogueData.Plan plan : provider.plans()) {
                String planKey = provider.id() + "/" + plan.id();
                if (blank(plan.id()) || blank(plan.name())) {
                    errors.add("Plan with missing id or name under " + provider.id());
                    continue;
                }
                if (!planIds.add(planKey)) {
                    errors.add("Duplicate plan id: " + planKey);
                }
                if (blank(plan.capability())) {
                    errors.add("Plan " + planKey + " has no capability");
                }
                if (plan.offers() == null || plan.offers().isEmpty()) {
                    errors.add("Plan " + planKey + " has no offers");
                    continue;
                }

                Money monthly = null;
                for (CatalogueData.Offer offer : plan.offers()) {
                    String where = planKey + " " + offer.region() + " " + offer.currency() + " " + offer.billingPeriod();
                    if (offer.region() == null || offer.region().length() != 2) {
                        errors.add("Offer " + where + " has an invalid region");
                    }
                    if (!Currencies.supports(offer.currency())) {
                        errors.add("Offer " + where + " uses an unsupported currency");
                        continue;
                    }
                    if (!Set.of("monthly", "annual", "quarterly").contains(offer.billingPeriod())) {
                        errors.add("Offer " + where + " has an invalid billing period");
                    }
                    Money price;
                    try {
                        price = Money.parse(offer.price(), offer.currency());
                    } catch (RuntimeException invalid) {
                        errors.add("Offer " + where + " has an unreadable price: " + offer.price());
                        continue;
                    }
                    if (price.isNegative()) {
                        errors.add("Offer " + where + " has a negative price");
                    }
                    if (blank(offer.validFrom())) {
                        errors.add("Offer " + where + " has no validFrom");
                    } else {
                        try {
                            LocalDate.parse(offer.validFrom());
                        } catch (RuntimeException invalid) {
                            errors.add("Offer " + where + " has an invalid validFrom: " + offer.validFrom());
                        }
                    }
                    String key = where + " " + offer.validFrom();
                    if (!offerKeys.add(key)) {
                        errors.add("Duplicate offer: " + key);
                    }
                    if (offer.source() == null) {
                        errors.add("Offer " + where + " has no source citation");
                    } else {
                        if (offer.source().url() == null || !offer.source().url().startsWith("https://")) {
                            errors.add("Offer " + where + " citation must be an https URL");
                        }
                        if (blank(offer.source().retrievedAt()) || blank(offer.source().reviewer())) {
                            errors.add("Offer " + where + " citation needs retrievedAt and reviewer");
                        }
                        if (!Set.of("reviewed", "proposed").contains(offer.source().reviewStatus())) {
                            errors.add("Offer " + where + " reviewStatus must be reviewed or proposed");
                        }
                    }

                    if ("monthly".equals(offer.billingPeriod())) {
                        monthly = price;
                    }
                    if ("annual".equals(offer.billingPeriod()) && monthly != null
                            && offer.currency().equals(monthly.currency())
                            && price.minor() >= monthly.minor() * 12L) {
                        warnings.add("Offer " + where + " is not cheaper than twelve monthly payments");
                    }
                }
            }
        }

        return new Result(errors, warnings);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank() || value.toLowerCase(Locale.ROOT).equals("null");
    }
}
