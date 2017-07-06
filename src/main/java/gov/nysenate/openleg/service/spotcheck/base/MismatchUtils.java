package gov.nysenate.openleg.service.spotcheck.base;

import gov.nysenate.openleg.model.spotcheck.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MismatchUtils {


    /**
     * If a mismatch has been ignored we want that to be applied to new equivalent mismatches as well.
     * The logic for determining if the ignore status should be changes is in updateIgnoreStatus.
     */
    public static List<DeNormSpotCheckMismatch> copyIgnoreStatuses(List<DeNormSpotCheckMismatch> from, List<DeNormSpotCheckMismatch> to) {
        for (DeNormSpotCheckMismatch mismatch : to) {
            if (from.contains(mismatch)) {
                mismatch.setIgnoreStatus(from.get(from.indexOf(mismatch)).getIgnoreStatus());
            }
        }
        return to;
    }

    /**
     * Updates the ignore status of the given references.
     * @param reportMismatches
     * @return List of DeNormSpotCheckMismatch's with ignore statuses updated.
     */
    public static List<DeNormSpotCheckMismatch> updateIgnoreStatus(List<DeNormSpotCheckMismatch> reportMismatches) {
        return reportMismatches.stream()
                .map(MismatchUtils::calculateIgnoreStatus)
                .collect(Collectors.toList());
    }

    private static DeNormSpotCheckMismatch calculateIgnoreStatus(DeNormSpotCheckMismatch mismatch) {
        if (mismatch.getState() == MismatchState.CLOSED) {
            mismatch.setIgnoreStatus(ignoreStatusForClosed(mismatch));
        } else {
            mismatch.setIgnoreStatus(ignoreStatusForOpen(mismatch));
        }
        return mismatch;
    }

    private static SpotCheckMismatchIgnore ignoreStatusForClosed(DeNormSpotCheckMismatch mismatch) {
        if (mismatch.getIgnoreStatus() == SpotCheckMismatchIgnore.IGNORE_PERMANENTLY) {
            return SpotCheckMismatchIgnore.IGNORE_PERMANENTLY;
        }
        return SpotCheckMismatchIgnore.NOT_IGNORED;
    }

    private static SpotCheckMismatchIgnore ignoreStatusForOpen(DeNormSpotCheckMismatch mismatch) {
        if (mismatch.getIgnoreStatus() == SpotCheckMismatchIgnore.IGNORE_ONCE) {
            return SpotCheckMismatchIgnore.NOT_IGNORED;
        }
        return mismatch.getIgnoreStatus();
    }

    /**
     * Returns a list of mismatches that have been closed by a spotcheck report.
     * Mismatches are closed if they were checked by the report (in checkedKeys and checkedTypes),
     * are not in the report mismatches and are not already resolved.
     *
     * @param reportMismatches  New mismatches generated by a report.
     * @param currentMismatches All the most recent mismatches for the datasource checked by the report.
     * @param report    The report date time to set for any resolved mismatches.
     * @return A list of mismatches resolved by this report.
     */
    public static List<DeNormSpotCheckMismatch> deriveClosedMismatches(List<DeNormSpotCheckMismatch> reportMismatches,
                                                                       List<DeNormSpotCheckMismatch> currentMismatches,
                                                                       SpotCheckReport report) {

        Set<Object> checkedKeys = report.getCheckedKeys();
        Set<SpotCheckMismatchType> checkedTypes = report.getReferenceType().checkedMismatchTypes();
        return currentMismatches.stream()
                .filter(m -> m.getState() != MismatchState.CLOSED)
                .filter(m -> checkedKeys.contains(m.getKey()))
                .filter(m -> checkedTypes.contains(m.getType()))
                .filter(m -> !reportMismatches.contains(m))
                .map(m -> closeMismatchWithReport(m, report))
                .collect(Collectors.toList());
    }

    /**
     * Closes a mismatch by the given report
     * Copy, and update the fields of the given mismatch to reflect it has been closed in this report.
     * @param mm A open mismatch that has been closed by the given report.
     * @param report
     * @return A copy of mm with fields updated to reflect it getting closed.
     */
    private static DeNormSpotCheckMismatch closeMismatchWithReport(DeNormSpotCheckMismatch mm, SpotCheckReport report) {
        DeNormSpotCheckMismatch closed = mm.copy();
        closed.setState(MismatchState.CLOSED);
        closed.setReportId(report.getId());
        closed.setReferenceId(new SpotCheckReferenceId(report.getReferenceType(), report.getReferenceDateTime()));
        closed.setReportDateTime(report.getReportDateTime());
        closed.setObservedDateTime(report.getReportDateTime());
        return closed;
    }

    /**
     * Copies the firstSeenDateTime from current mismatches to the report mismatches unless
     * the report mismatch is new or the current mismatch has been CLOSED, in which case
     * we create a new firstSeenDateTime.
     * A new firstSeenDateTime is set equal to its observedDateTime.
     */
    public static List<DeNormSpotCheckMismatch> updateFirstSeenDateTime(List<DeNormSpotCheckMismatch> reportMismatches,
                                                                        List<DeNormSpotCheckMismatch> currentMismatches) {
        for (DeNormSpotCheckMismatch rm : reportMismatches) {
            if (currentMismatches.contains(rm)) {
                DeNormSpotCheckMismatch cm = currentMismatches.get(currentMismatches.indexOf(rm));
                if (cm.getState() != MismatchState.CLOSED) {
                    copyFirstSeenDateTime(rm, cm);
                    break;
                }
            }
            resetFirstSeenDateTime(rm);
        }
        return reportMismatches;
    }

    private static void copyFirstSeenDateTime(DeNormSpotCheckMismatch rm, DeNormSpotCheckMismatch cm) {
        rm.setFirstSeenDateTime(cm.getFirstSeenDateTime());
    }

    private static void resetFirstSeenDateTime(DeNormSpotCheckMismatch rm) {
        rm.setFirstSeenDateTime(rm.getObservedDateTime());
    }
}
