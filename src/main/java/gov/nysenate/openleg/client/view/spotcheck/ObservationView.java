package gov.nysenate.openleg.client.view.spotcheck;

import gov.nysenate.openleg.client.view.base.ListView;
import gov.nysenate.openleg.client.view.base.ViewObject;
import gov.nysenate.openleg.model.spotcheck.SpotCheckObservation;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

public class ObservationView<ContentKey> implements ViewObject
{
    protected LocalDateTime refDateTime;
    protected ContentKey key;
    protected LocalDateTime observedDateTime;
    protected ListView<MismatchView> mismatches;
    protected ReportIdView reportId;

    public ObservationView(SpotCheckObservation<ContentKey> observation) {
        if (observation != null) {
            this.refDateTime = observation.getReferenceId().getRefActiveDateTime();
            this.key = observation.getKey();
            this.observedDateTime = observation.getObservedDateTime();
            this.reportId = new ReportIdView(observation.getReportId());
            this.mismatches = ListView.of(
                    observation.getMismatches().values().stream()
                            .map(m -> new MismatchView(m, observation.getPriorMismatches().get(m.getMismatchType())))
                            .collect(Collectors.toList()));
        }
    }

    public LocalDateTime getRefDateTime() {
        return refDateTime;
    }

    public ContentKey getKey() {
        return key;
    }

    public LocalDateTime getObservedDateTime() {
        return observedDateTime;
    }

    public ListView<MismatchView> getMismatches() {
        return mismatches;
    }

    public ReportIdView getReportId() {
        return reportId;
    }

    @Override
    public String getViewType() {
        return "observation";
    }
}
