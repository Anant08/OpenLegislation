angular.module('open.spotcheck').factory('SpotcheckMismatchSummaryApi',
    ['$resource', mismatchSummaryApi]);

function mismatchSummaryApi($resource) {

    var mismatchSummaryApi = $resource(adminApiPath + "/spotcheck/:datasource/open-mismatches/summary", {datasource: '@datasource'});

    function MismatchSummary(openCount, newCount, existingCount, resolvedCount,
                             billCount, calendarCount, agendaCount) {
        this.openCount = openCount;
        this.newCount = newCount;
        this.existingCount = existingCount;
        this.resolvedCount = resolvedCount;
        this.billCount = billCount;
        this.calendarCount = calendarCount;
        this.agendaCount = agendaCount;
    }

    // TODO: Add API filter for mismatchStatus so content type counts can be updated for the selected mismatch statuses.
    /**
     *
     * @param datasource
     * @param date An ISO date time string. Returns summary data for mismatches observed before this date time.
     */
    function get(datasource, date) {
        return mismatchSummaryApi.get({datasource: datasource, observedBefore: date}).$promise
            .then(createSummary)
    }

    // TODO: ResolvedCount is not tested.
    function createSummary(response) {
        console.log(response);
        var openCount = 0;
        var newCount = 0;
        var existingCount = 0;
        var resolvedCount = 0;
        var billCount = 0;
        var calendarCount = 0;
        var agendaCount = 0;
        angular.forEach(response.result.summaryMap, function (refType) {
            openCount += refType.openMismatches;
            newCount += refType.mismatchStatuses.NEW || 0;
            existingCount += refType.mismatchStatuses.EXISTING || 0;
            resolvedCount += refType.mismatchStatuses.RESOLVED || 0;
            billCount += countsForContentType('BILL', refType);
            calendarCount += countsForContentType('CALENDAR', refType);
            agendaCount += countsForContentType('AGENDA', refType);
        });
        return new MismatchSummary(openCount, newCount, existingCount, resolvedCount,
            billCount, calendarCount, agendaCount);
    }

    function countsForContentType(contentType, referenceNode) {
        if (referenceContentTypeMap[referenceNode.refType] === contentType) {
            return referenceNode.openMismatches;
        }
        return 0;
    }

    return {
        get: get
    }
}
