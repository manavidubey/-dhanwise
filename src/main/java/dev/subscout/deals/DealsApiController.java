package dev.subscout.deals;

import dev.subscout.platform.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deals")
public class DealsApiController {

    public record DealActionRequest(
            String status,
            String action,
            Long realizedSavingsMinor,
            String note
    ) {
        public String effectiveStatus() {
            if (status != null && !status.isBlank()) return status;
            if ("save".equalsIgnoreCase(action)) return "saved";
            if ("unsave".equalsIgnoreCase(action)) return "available";
            if ("redeem".equalsIgnoreCase(action)) return "redeemed";
            if ("dismiss".equalsIgnoreCase(action)) return "dismissed";
            return action != null ? action : "available";
        }
    }

    private final DealsIntelligenceService dealsService;
    private final ProfileService profiles;

    public DealsApiController(DealsIntelligenceService dealsService, ProfileService profiles) {
        this.dealsService = dealsService;
        this.profiles = profiles;
    }

    @GetMapping
    public DealsIntelligenceService.DealsSummaryView getDeals() {
        String profileId = profiles.ensureDefaultProfile();
        String currency = profiles.currency(profileId);
        return dealsService.getDealsSummary(profileId, currency);
    }

    @PostMapping("/{id}/action")
    public DealsIntelligenceService.DealsSummaryView performAction(
            @PathVariable String id,
            @RequestBody DealActionRequest request) {
        String profileId = profiles.ensureDefaultProfile();
        dealsService.updateStatus(profileId, id, request.effectiveStatus(), request.realizedSavingsMinor(), request.note());
        String currency = profiles.currency(profileId);
        return dealsService.getDealsSummary(profileId, currency);
    }
}
