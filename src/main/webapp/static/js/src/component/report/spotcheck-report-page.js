angular.module('open.spotcheck')
    .controller('SpotcheckReportCtrl',
        ['$scope', '$location', '$routeParams', '$mdDialog', 'PaginationModel', 'SpotcheckMismatchApi',
            'SpotcheckMismatchSummaryApi', 'SpotcheckMismatchIgnoreAPI', ReportCtrl]);

function ReportCtrl($scope, $location, $routeParams, $mdDialog, paginationModel, spotcheckMismatchApi,
                    mismatchSummaryApi, mismatchIgnoreApi) {

    const dateFormat = 'YYYY-MM-DD';
    const isoFormat = 'YYYY-MM-DDTHH:mm:ss';
    /** Used to look up content types corresponding to the selected tab. */
    const contentTypes = ['BILL', 'CALENDAR', 'AGENDA'];

    $scope.datasource = {
        values: [
            {
                value: 'OPENLEG',
                label: 'LBDC - OpenLegislation'
            },
            {
                value: 'NYSENATE_DOT_GOV',
                label: 'OpenLegislation - NYSenate.gov'
            }
        ],
        selected: {}
    };
    $scope.status = 'OPEN'; // Show all open issues by default.
    $scope.selectedTab = 0; // Select Bills tab by default.
    $scope.date = {};
    $scope.loading = false; // TODO remove this using promises?
    $scope.pagination = angular.extend({}, paginationModel);

    $scope.mismatchResponse = {
        mismatches: [],
        error: false,
        errorMessage: ''
    };

    $scope.summaryResponse = {
        summary: {},
        error: false,
        errorMessage: ''
    };

    $scope.onDatasourceChange = function () {
        resetPagination();
        updateMismatchSummary();
        updateMismatches();
    };

    $scope.onStatusChange = function () {
        resetPagination();
        updateMismatches();
    };

    $scope.onTabChange = function () {
        resetPagination();
        updateMismatches();
    };

    $scope.onPageChange = function (pageNum) {
        updateMismatches();
    };

    $scope.formatDate = function (date) {
        return date.format(dateFormat);
    };

    function updateMismatchSummary() {
        $scope.summaryResponse.error = false;
        mismatchSummaryApi.get($scope.datasource.selected.value, $scope.date.endOf('day').format(isoFormat))
            .then(function (mismatchSummary) {
                $scope.summaryResponse.summary = mismatchSummary;
            })
            .catch(function (response) {
                $scope.summaryResponse.error = true;
                $scope.summaryResponse.errorMessage = response.statusText;
            });
    }

    function updateMismatches() {
        $scope.loading = true;
        $scope.mismatchResponse.error = false;
        $scope.mismatchResponse.mismatches = [];
        spotcheckMismatchApi.getMismatches($scope.datasource.selected.value, selectedContentType(),
            toMismatchStatus($scope.status), $scope.date.endOf('day').format(isoFormat),
            $scope.pagination.getLimit(), $scope.pagination.getOffset())
            .then(function (result) {
                $scope.pagination.setTotalItems(result.pagination.total);
                $scope.mismatchResponse.mismatches = result.mismatches;
                $scope.loading = false;
            })
            .catch(function (response) {
                $scope.loading = false;
                $scope.mismatchResponse.error = true;
                $scope.mismatchResponse.errorMessage = response.statusText;
            });

        /**
         * Returns array of mismatch statuses corresponding to the selected status.
         */
        function toMismatchStatus(status) {
            if (status === 'OPEN') {
                return ['NEW', 'EXISTING'];
            }
            return [status];
        }
    }

    function resetPagination() {
        $scope.pagination.reset();
        $scope.pagination.setTotalItems(0);
        $scope.pagination.itemsPerPage = 10;
    }

    $scope.confirmIgnoreMismatch = function (mismatch) {
        var confirm = $mdDialog.confirm()
            .title("Ignore mismatch?")
            .ok('Yes')
            .cancel('No');

        $mdDialog.show(confirm).then(function() {
            ignoreMismatch(mismatch);
        })
    };

    function ignoreMismatch(mismatch) {
        var params = {
            dataSource: $scope.datasource.selected.value,
            contentType: selectedContentType(),
            mismatchId: mismatch.id,
            ignoreLevel: 'IGNORE_PERMANENTLY'
        };
        mismatchIgnoreApi.save(params, function (response) {
            updateMismatches();
        })
    }

    function initializeDate() {
        if ($routeParams.hasOwnProperty('date')) {
            $scope.date = moment($routeParams.date, dateFormat);
        }
        else {
            $scope.date = moment().startOf('day');
            onDateChange();
        }
    }

    function onDateChange() {
        $location.search('date', $scope.date.format(dateFormat)).replace();
    }

    function selectedContentType() {
        return contentTypes[$scope.selectedTab];
    }

    /**
     * Updates the total mismatch count of each content type's tab
     * for the selected mismatch status.
     */
    $scope.getSummaryCountForContentType = function (contentType) {
        if ($scope.summaryResponse.summary[contentType] == null) {
            return '';
        }
        return $scope.summaryResponse.summary[contentType][$scope.status] || '';
    };

    $scope.init = function () {
        resetPagination();
        initializeDate();
        $scope.datasource.selected = $scope.datasource.values[0];
        updateMismatchSummary();
        updateMismatches();
    };

    $scope.init();

}
