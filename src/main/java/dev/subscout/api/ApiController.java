package dev.subscout.api;

import dev.subscout.catalogue.CatalogueRepository;
import dev.subscout.insight.InsightRepository;
import dev.subscout.insight.InsightService;
import dev.subscout.ledger.CsvStatementParser;
import dev.subscout.ledger.ImportService;
import dev.subscout.ledger.LedgerRepository;
import dev.subscout.platform.Money;
import dev.subscout.platform.ProfileService;
import dev.subscout.recurring.Cadence;
import dev.subscout.recurring.DetectionService;
import dev.subscout.recurring.RecurrenceDetector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import dev.subscout.ledger.ReconciliationService;
import dev.subscout.recurring.CashflowForecastService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ProfileService profiles;
    private final ImportService imports;
    private final DetectionService detection;
    private final InsightService insights;
    private final InsightRepository insightRepository;
    private final LedgerRepository ledger;
    private final CatalogueRepository catalogue;
    private final ReconciliationService reconciliation;
    private final CashflowForecastService forecasting;

    public ApiController(ProfileService profiles, ImportService imports, DetectionService detection,
                         InsightService insights, InsightRepository insightRepository,
                         LedgerRepository ledger, CatalogueRepository catalogue,
                         ReconciliationService reconciliation, CashflowForecastService forecasting) {
        this.profiles = profiles;
        this.imports = imports;
        this.detection = detection;
        this.insights = insights;
        this.insightRepository = insightRepository;
        this.ledger = ledger;
        this.catalogue = catalogue;
        this.reconciliation = reconciliation;
        this.forecasting = forecasting;
    }

    @GetMapping("/overview")
    public Dtos.OverviewView overview() {
        String profileId = profiles.ensureDefaultProfile();
        String currency = profiles.currency(profileId);

        List<Dtos.SubscriptionView> subscriptions = insightRepository.subscriptions(profileId).stream()
                .map(row -> new Dtos.SubscriptionView(
                        row.id(),
                        row.seriesId(),
                        row.displayName(),
                        row.state(),
                        row.cancellationStage(),
                        row.cadence(),
                        Dtos.MoneyView.of(Money.ofMinor(row.typicalMinor(), row.currency(), row.exponent())),
                        Dtos.MoneyView.of(annualized(row)),
                        row.nextExpected() == null ? null : row.nextExpected().toString(),
                        round(row.confidence()),
                        row.variableAmount(),
                        row.capability()))
                .toList();

        List<Dtos.SeriesView> candidates = insightRepository.series(profileId, "candidate").stream()
                .map(row -> {
                    String priceChangeStr = null;
                    if (row.previousMinor() != null && row.currentMinor() != null && row.changePct() != null) {
                        Money prev = Money.ofMinor(row.previousMinor(), row.currency(), row.exponent());
                        Money curr = Money.ofMinor(row.currentMinor(), row.currency(), row.exponent());
                        priceChangeStr = String.format("%s -> %s (+%.1f%%)", prev.display(), curr.display(), row.changePct());
                    }
                    return new Dtos.SeriesView(
                            row.id(),
                            row.merchantName(),
                            row.cadence(),
                            Dtos.MoneyView.of(Money.ofMinor(row.typicalMinor(), row.currency(), row.exponent())),
                            row.nextExpected() == null ? null : row.nextExpected().toString(),
                            round(row.confidence()),
                            row.variableAmount(),
                            priceChangeStr);
                })
                .toList();

        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(30);
        List<Dtos.RenewalView> renewals = insightRepository.subscriptions(profileId).stream()
                .filter(row -> row.nextExpected() != null)
                .filter(row -> !row.state().equals("cancelled"))
                .map(row -> new Dtos.RenewalView(
                        row.id(),
                        row.displayName(),
                        row.nextExpected().toString(),
                        Dtos.MoneyView.of(Money.ofMinor(row.typicalMinor(), row.currency(), row.exponent())),
                        java.time.temporal.ChronoUnit.DAYS.between(today, row.nextExpected())))
                .sorted(java.util.Comparator.comparingLong(Dtos.RenewalView::daysRemaining))
                .toList();

        List<Dtos.RecommendationView> recommendations = insightRepository.recommendations(profileId).stream()
                .map(row -> new Dtos.RecommendationView(
                        row.id(), row.kind(), row.title(), row.detail(), row.savingsClass(),
                        row.amountMinor() == null ? null
                                : Dtos.MoneyView.of(Money.ofMinor(row.amountMinor(), row.currency(), row.exponent())),
                        round(row.confidence()), row.evidence(), row.assumptions()))
                .toList();

        var totals = insights.savings(profileId, currency);
        Dtos.SavingsView savings = new Dtos.SavingsView(
                Dtos.MoneyView.of(totals.potential()),
                Dtos.MoneyView.of(totals.estimated()),
                Dtos.MoneyView.of(totals.confirmed()),
                Dtos.MoneyView.of(totals.realized()));

        return new Dtos.OverviewView(
                profiles.displayName(profileId),
                currency,
                catalogue.hasProposedOffers(),
                new Dtos.CountsView(ledger.countTransactions(profileId),
                        insightRepository.countSubscriptions(profileId),
                        insightRepository.countCandidates(profileId)),
                subscriptions,
                candidates,
                renewals,
                recommendations,
                savings);
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.ImportResultView upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        String profileId = profiles.ensureDefaultProfile();
        String accountId = profiles.defaultAccountId(profileId);
        ImportService.Result result = imports.importCsv(profileId, accountId, file.getOriginalFilename(), file.getBytes());
        DetectionService.Result detectionResult = detection.run(profileId);
        insights.refresh(profileId, detectionResult.series());
        reconciliation.reconcileProfile(profileId);
        return new Dtos.ImportResultView(result.importId(), result.totalRows(), result.imported(),
                result.duplicates(), result.duplicateFile(), result.errors());
    }

    @PostMapping("/demo")
    public Dtos.OverviewView demo() {
        String profileId = profiles.ensureDefaultProfile();
        ledger.deleteAll(profileId);
        String accountId = profiles.defaultAccountId(profileId);
        imports.importCsv(profileId, accountId, "sample-statement.csv", profiles.sampleStatement());
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        reconciliation.reconcileProfile(profileId);
        return overview();
    }

    @PostMapping("/reset")
    public Dtos.OverviewView reset() {
        String profileId = profiles.ensureDefaultProfile();
        ledger.deleteAll(profileId);
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        return overview();
    }

    @PostMapping("/profile")
    public Dtos.OverviewView updateProfile(@RequestBody Dtos.UpdateProfileRequest request) {
        String profileId = profiles.ensureDefaultProfile();
        profiles.updateProfile(profileId, request.displayName(), request.currency());
        return overview();
    }

    @PostMapping("/series/{id}/triage")
    public Dtos.OverviewView triage(@PathVariable String id, @RequestParam String status) {
        detection.triage(id, status);
        String profileId = profiles.ensureDefaultProfile();
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        return overview();
    }

    @PostMapping("/subscriptions")
    public Dtos.OverviewView createSubscription(@RequestBody Dtos.CreateSubscriptionRequest request) {
        String profileId = profiles.ensureDefaultProfile();
        String cur = request.currency() != null && !request.currency().isBlank() ? request.currency() : profiles.currency(profileId);
        Money money = Money.parse(request.amount(), cur);
        LocalDate nextExpected = request.nextExpected() != null && !request.nextExpected().isBlank()
                ? LocalDate.parse(request.nextExpected()) : LocalDate.now().plusMonths(1);
        insights.createManualSubscription(profileId, request.name(), request.cadence(),
                money.minor(), cur, money.exponent(), nextExpected, request.capability());
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        return overview();
    }

    @DeleteMapping("/subscriptions/{id}")
    public Dtos.OverviewView deleteSubscription(@PathVariable String id) {
        insights.deleteSubscription(id);
        String profileId = profiles.ensureDefaultProfile();
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        return overview();
    }

    @PostMapping("/subscriptions/{id}/lifecycle")
    public Dtos.OverviewView updateLifecycle(@PathVariable String id, @RequestParam String stage) {
        if ("CANCELLATION_REQUESTED".equalsIgnoreCase(stage)) {
            insights.requestCancellation(id);
        } else if ("USER_CONFIRMED_CANCELLED".equalsIgnoreCase(stage) || "confirmed".equalsIgnoreCase(stage)) {
            insights.confirmCancellation(id);
        } else if ("REALIZED_SAVINGS".equalsIgnoreCase(stage) || "realized".equalsIgnoreCase(stage)) {
            insights.verifyChargeStopped(id);
        } else {
            insights.cancel(id);
        }
        String profileId = profiles.ensureDefaultProfile();
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        return overview();
    }

    @PostMapping("/recommendations/{id}/triage")
    public Dtos.OverviewView triageRecommendation(@PathVariable String id, @RequestBody Dtos.TriageRecommendationRequest request) {
        insights.triageRecommendation(id, request.status(), request.actionTaken(), request.snoozeDays());
        return overview();
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public Dtos.OverviewView cancel(@PathVariable String id) {
        insights.cancel(id);
        String profileId = profiles.ensureDefaultProfile();
        DetectionService.Result result = detection.run(profileId);
        insights.refresh(profileId, result.series());
        return overview();
    }

    @GetMapping("/catalogue")
    public Dtos.CatalogueView catalogue() {
        Map<String, List<CatalogueRepository.OfferRow>> offersByPlan = catalogue.offersByPlan();
        List<Dtos.ProviderView> providers = new ArrayList<>();
        Map<String, List<Dtos.PlanView>> plansByProvider = new LinkedHashMap<>();

        for (CatalogueRepository.ProviderRow provider : catalogue.providers()) {
            providers.add(new Dtos.ProviderView(provider.id(), provider.name(), provider.category(),
                    provider.homepage(), plansByProvider.computeIfAbsent(provider.id(), ignored -> new ArrayList<>())));
        }

        for (Map.Entry<String, List<CatalogueRepository.OfferRow>> entry : offersByPlan.entrySet()) {
            List<CatalogueRepository.OfferRow> rows = entry.getValue();
            CatalogueRepository.OfferRow first = rows.get(0);
            String providerId = first.planId().split("/")[0];
            List<Dtos.PlanView> plans = plansByProvider.get(providerId);
            if (plans == null) {
                continue;
            }
            List<Dtos.OfferView> offers = rows.stream()
                    .map(row -> new Dtos.OfferView(row.region(),
                            Dtos.MoneyView.of(Money.ofMinor(row.priceMinor(), row.currency(), row.exponent())),
                            row.billingPeriod(), row.taxInclusive(), row.sourceUrl(), row.retrievedAt(),
                            row.reviewStatus()))
                    .toList();
            plans.add(new Dtos.PlanView(entry.getKey(), first.planName(), first.capability(), offers));
        }

        return new Dtos.CatalogueView(
                "Bundled seed catalogue. Prices marked proposed are unverified and must be reviewed before use.",
                providers);
    }

    @GetMapping("/perks")
    public List<Dtos.PerkView> perks() {
        String profileId = profiles.ensureDefaultProfile();
        List<InsightRepository.SubscriptionRow> userSubs = insightRepository.subscriptions(profileId);
        List<CatalogueRepository.BundledBenefitRow> benefits = catalogue.bundledBenefits();
        List<Dtos.PerkView> list = new ArrayList<>();

        for (CatalogueRepository.BundledBenefitRow b : benefits) {
            String planId = b.sourceId();
            String sourceName = planId;
            // Find provider / plan display name
            String providerId = planId.contains("/") ? planId.split("/")[0] : planId;
            for (CatalogueRepository.ProviderRow p : catalogue.providers()) {
                if (p.id().equalsIgnoreCase(providerId)) {
                    sourceName = p.name();
                    break;
                }
            }

            // Check if user has an active subscription to this source or provider
            boolean heldByUser = userSubs.stream()
                    .filter(s -> "active".equalsIgnoreCase(s.state()))
                    .anyMatch(s -> s.displayName().toLowerCase().contains(providerId.toLowerCase())
                            || (s.capability() != null && s.capability().equalsIgnoreCase(b.capability())));

            list.add(new Dtos.PerkView(
                    b.id(),
                    b.sourceId(),
                    sourceName,
                    b.capability(),
                    b.targetProviderId(),
                    b.targetPlanId(),
                    b.description(),
                    b.activationRequired(),
                    b.status(),
                    b.termsUrl(),
                    heldByUser));
        }
        return list;
    }

    @GetMapping("/reconciliation")
    public List<Dtos.ReconciledItemView> getReconciliation() {
        String profileId = profiles.ensureDefaultProfile();
        return reconciliation.getReconciliations(profileId).stream()
                .map(item -> new Dtos.ReconciledItemView(
                        item.id(),
                        item.txnId(),
                        item.subscriptionId(),
                        item.subscriptionName(),
                        item.postedOn().toString(),
                        item.descriptorRaw(),
                        item.matchType().name(),
                        Dtos.MoneyView.of(item.expectedAmount()),
                        Dtos.MoneyView.of(item.actualAmount()),
                        Dtos.MoneyView.of(item.variance()),
                        item.notes()))
                .toList();
    }

    @PostMapping("/reconciliation/run")
    public List<Dtos.ReconciledItemView> runReconciliation() {
        String profileId = profiles.ensureDefaultProfile();
        return reconciliation.reconcileProfile(profileId).stream()
                .map(item -> new Dtos.ReconciledItemView(
                        item.id(),
                        item.txnId(),
                        item.subscriptionId(),
                        item.subscriptionName(),
                        item.postedOn().toString(),
                        item.descriptorRaw(),
                        item.matchType().name(),
                        Dtos.MoneyView.of(item.expectedAmount()),
                        Dtos.MoneyView.of(item.actualAmount()),
                        Dtos.MoneyView.of(item.variance()),
                        item.notes()))
                .toList();
    }

    @GetMapping("/forecasting")
    public Dtos.CashflowForecastView getCashflowForecast() {
        String profileId = profiles.ensureDefaultProfile();
        String currency = profiles.currency(profileId);
        CashflowForecastService.ForecastSummary summary = forecasting.forecast(profileId, currency);

        List<Dtos.ForecastEventView> events = summary.upcomingEvents().stream()
                .map(e -> new Dtos.ForecastEventView(
                        e.subscriptionId(),
                        e.name(),
                        e.scheduledOn().toString(),
                        e.billingFrequency(),
                        Dtos.MoneyView.of(e.amount()),
                        e.eventType(),
                        e.inclusionReason(),
                        e.daysFromNow()))
                .toList();

        return new Dtos.CashflowForecastView(
                Dtos.MoneyView.of(summary.horizon30Days()),
                Dtos.MoneyView.of(summary.horizon60Days()),
                Dtos.MoneyView.of(summary.horizon90Days()),
                summary.eventCount30Days(),
                summary.eventCount60Days(),
                summary.eventCount90Days(),
                events);
    }

    @GetMapping(value = "/export/transactions.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportTransactions() {
        String profileId = profiles.ensureDefaultProfile();
        StringBuilder csv = new StringBuilder("date,description,direction,amount,currency\n");
        for (LedgerRepository.ExportRow row : ledger.exportRows(profileId)) {
            csv.append(CsvStatementParser.sanitizeCell(row.postedOn().toString())).append(',')
                    .append(CsvStatementParser.sanitizeCell(row.description())).append(',')
                    .append(row.direction()).append(',')
                    .append(Money.ofMinor(row.amountMinor(), row.currency(), row.exponent()).major().toPlainString())
                    .append(',').append(row.currency()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=subscout-transactions.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Money annualized(InsightRepository.SubscriptionRow row) {
        if (row.typicalMinor() <= 0 || "variable".equals(row.cadence())) {
            return Money.ofMinor(0, row.currency(), row.exponent());
        }
        return Money.ofMinor(row.typicalMinor() * Cadence.fromWire(row.cadence()).occurrencesPerYear(),
                row.currency(), row.exponent());
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    static String describe(RecurrenceDetector.Series series) {
        return series.merchantName() + " " + series.cadence().wire();
    }
}
