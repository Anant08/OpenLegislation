package gov.nysenate.openleg.controller.api;

import com.google.common.collect.Range;
import gov.nysenate.openleg.client.response.base.BaseResponse;
import gov.nysenate.openleg.client.response.base.ListViewResponse;
import gov.nysenate.openleg.client.response.base.ViewObjectResponse;
import gov.nysenate.openleg.client.response.error.ErrorCode;
import gov.nysenate.openleg.client.response.error.ViewObjectErrorResponse;
import gov.nysenate.openleg.client.view.committee.CommitteeIdView;
import gov.nysenate.openleg.client.view.committee.CommitteeIdRangeView;
import gov.nysenate.openleg.client.view.committee.CommitteeVersionIdView;
import gov.nysenate.openleg.client.view.committee.CommitteeView;
import gov.nysenate.openleg.controller.api.base.BaseCtrl;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.CommitteeId;
import gov.nysenate.openleg.model.entity.CommitteeNotFoundEx;
import gov.nysenate.openleg.model.entity.CommitteeVersionId;
import gov.nysenate.openleg.service.entity.CommitteeService;
import gov.nysenate.openleg.util.DateUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import static gov.nysenate.openleg.controller.api.base.BaseCtrl.BASE_API_PATH;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME;

@RestController
@RequestMapping(value = BASE_API_PATH + "/committees", method = RequestMethod.GET)
public class CommitteeGetCtrl extends BaseCtrl
{
    private static final Logger logger = LoggerFactory.getLogger(CommitteeGetCtrl.class);

    @Autowired
    CommitteeService committeeService;

    /** --- Request Handlers --- */

    /**
     * Returns the current committee version with the given name for the specified chamber
     * @param chamberName
     * @param committeeName
     * @return
     */
    @RequestMapping(value = "/{chamberName:(?i)senate|assembly}/{committeeName}")
    public BaseResponse getCurrentCommittee(@PathVariable String chamberName,
                                            @PathVariable String committeeName)
            throws CommitteeNotFoundEx {
        return new ViewObjectResponse<>( new CommitteeView(
                committeeService.getCommittee(new CommitteeId(Chamber.getValue(chamberName), committeeName))));
    }

    /**
     * Returns the committee version corresponding to the given committee version id
     *      consisting of chamber, committee name, session year and reference date
     * @param chamberName
     * @param committeeName
     * @param sessionYear
     * @param referenceDate
     * @return
     * @throws CommitteeNotFoundEx
     */
    @RequestMapping(value = "/{chamberName:(?i)senate|assembly}/{committeeName}/{sessionYear:[\\d]{4}}/{referenceDate}")
    public BaseResponse getCommitteeAtTime(@PathVariable String chamberName,
                                           @PathVariable String committeeName,
                                           @PathVariable int sessionYear,
                                           @PathVariable @DateTimeFormat(iso = DATE_TIME) LocalDateTime referenceDate)
        throws CommitteeNotFoundEx {
        return new ViewObjectResponse<>( new CommitteeView(
                committeeService.getCommittee(new CommitteeVersionId(Chamber.getValue(chamberName), committeeName,
                                                        SessionYear.of(sessionYear), referenceDate))));
    }

    /**
     * Returns the current committee version for each committee in the given chamber
     * @param chamberName
     * @param full
     * @param webRequest
     * @return
     */
    @RequestMapping(value = "/{chamberName:(?i)senate|assembly}")
    public BaseResponse getCommitteesForChamber(@PathVariable String chamberName,
                                                @RequestParam(defaultValue = "false") boolean full,
                                                WebRequest webRequest) {
        LimitOffset limitOffset = getLimitOffset(webRequest, LimitOffset.FIFTY);
        Chamber chamber = Chamber.getValue(chamberName);
        return ListViewResponse.of(
                committeeService.getCommitteeList(chamber, limitOffset).stream()
                        .map(committee -> full ? new CommitteeView(committee)
                                               : new CommitteeVersionIdView(committee.getVersionId()) )
                        .collect(Collectors.toList()),
                committeeService.getCommitteeListCount(chamber), limitOffset );
    }

    /**
     * Returns all of the previous committee versions for the given committee id ordered by session year and creation date
     * @param chamberName
     * @param committeeName
     * @param full
     * @param webRequest
     * @return
     * @throws CommitteeNotFoundEx
     */
    @RequestMapping(value = "/{chamberName:(?i)senate|assembly}/{committeeName}/history")
    public BaseResponse getCommitteeHistory(@PathVariable String chamberName,
                                            @PathVariable String committeeName,
                                            @RequestParam(defaultValue = "false") boolean full,
                                            WebRequest webRequest) throws CommitteeNotFoundEx {
        LimitOffset limitOffset = getLimitOffset(webRequest, LimitOffset.FIFTY);
        SortOrder sortOrder = getSortOrder(webRequest, SortOrder.DESC);
        Range<LocalDateTime> dateTimeRange = DateUtils.toDateTimeRange(getDateRange(webRequest, DateUtils.ALL_DATES));
        CommitteeId committeeId = new CommitteeId(Chamber.getValue(chamberName), committeeName);
        return ListViewResponse.of(
                committeeService.getCommitteeHistory(committeeId, dateTimeRange, limitOffset, sortOrder).stream()
                        .map(committee -> full ? new CommitteeView(committee)
                                               : new CommitteeVersionIdView(committee.getVersionId()) )
                        .collect(Collectors.toList()),
                committeeService.getCommitteeHistoryCount(committeeId, dateTimeRange), limitOffset);
    }

    /** --- Exception Handlers --- */

    @ExceptionHandler(CommitteeNotFoundEx.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ViewObjectErrorResponse handleCommitteeNotFoundEx(CommitteeNotFoundEx ex) {
        logger.debug(ExceptionUtils.getStackTrace(ex));
        if (ex.getDateTimeRange() != null && !ex.getDateTimeRange().equals(DateUtils.ALL_DATE_TIMES)) {
            return new ViewObjectErrorResponse(ErrorCode.COMMITTEE_VERSION_NOT_FOUND,
                    new CommitteeIdRangeView(ex.getCommitteeId(), ex.getDateTimeRange()));
        } else if (ex.getCommitteeId() instanceof CommitteeVersionId) {
            return new ViewObjectErrorResponse(ErrorCode.COMMITTEE_VERSION_NOT_FOUND,
                    new CommitteeVersionIdView((CommitteeVersionId) ex.getCommitteeId()));
        } else {
            return new ViewObjectErrorResponse(ErrorCode.COMMITTEE_NOT_FOUND, new CommitteeIdView(ex.getCommitteeId()));
        }
    }
}