package gov.nysenate.openleg.model.spotcheck;

import gov.nysenate.openleg.dao.base.OrderBy;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.spotcheck.MismatchOrderBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Contains query parameters used to query for spotcheck mismatches.
 * Provides reasonable defaults for non required parameters.
 */
public class MismatchQuery<ContentKey> {

    private LocalDate reportDate;
    private SpotCheckDataSource dataSource;
    private Set<SpotCheckContentType> contentTypes;
    private MismatchStatus status;
    private Set<SpotCheckMismatchType> mismatchTypes;
    private Set<SpotCheckMismatchIgnore> ignoredStatuses;
    private Set<ContentKey> keys;

    private OrderBy orderBy;

    public MismatchQuery(LocalDate reportDate, SpotCheckDataSource dataSource,
                         MismatchStatus status, Set<SpotCheckContentType> contentTypes) {
        this.reportDate = reportDate;
        this.dataSource = dataSource;
        this.status = status;
        this.contentTypes = contentTypes;
        // Default values
        this.mismatchTypes = EnumSet.allOf(SpotCheckMismatchType.class);
        this.ignoredStatuses = EnumSet.of(SpotCheckMismatchIgnore.NOT_IGNORED);
        this.orderBy = new OrderBy(MismatchOrderBy.REFERENCE_DATE.getColumnName(), SortOrder.DESC);
    }

    /* --- Functional Getters --- */

    public MismatchState getState() {
        return status.getState();
    }

    public LocalDateTime getObservedStartDateTime() {
        return status.getObservedStartDateTime(reportDate);
    }

    public LocalDateTime getFirstSeenStartDateTime() {
        return status.getFirstSeenStartDateTime(reportDate);
    }

    public LocalDateTime getFirstSeenEndDateTime() {
        return status.getFirstSeenEndDateTime(reportDate);
    }

    public LocalDateTime getObservedEndDateTime() {
        return status.getObservedEndDateTime(reportDate);
    }

    public boolean isFilteringKeys() {
        return keys != null && !keys.isEmpty();
    }

    /* --- Builder Methods --- */

    public MismatchQuery<ContentKey> withIgnoredStatuses(Set<SpotCheckMismatchIgnore> ignoredStatuses) {
        this.ignoredStatuses = ignoredStatuses;
        return this;
    }

    public MismatchQuery<ContentKey> withOrderBy(OrderBy orderBy) {
        this.orderBy = orderBy;
        return this;
    }

    public MismatchQuery<ContentKey> withMismatchTypes(EnumSet<SpotCheckMismatchType> mismatchTypes){
        this.mismatchTypes = mismatchTypes;
        return this;
    }

    public MismatchQuery<ContentKey> withKeys(Set<ContentKey> keys) {
        this.keys = keys;
        return this;
    }

    /* --- Getters --- */

    public LocalDate getReportDate() {
        return reportDate;
    }

    public SpotCheckDataSource getDataSource() {
        return dataSource;
    }

    public Set<SpotCheckContentType> getContentTypes() {
        return contentTypes;
    }

    public MismatchStatus getStatus() {
        return status;
    }

    public Set<SpotCheckMismatchType> getMismatchTypes() {
        return mismatchTypes;
    }

    public Set<SpotCheckMismatchIgnore> getIgnoredStatuses() {
        return ignoredStatuses;
    }

    public OrderBy getOrderBy() {
        return orderBy;
    }

    public Set<ContentKey> getKeys() {
        return keys;
    }
}
