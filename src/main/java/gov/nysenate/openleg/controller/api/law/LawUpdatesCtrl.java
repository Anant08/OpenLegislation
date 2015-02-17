package gov.nysenate.openleg.controller.api.law;

import com.google.common.collect.Range;
import gov.nysenate.openleg.client.response.base.BaseResponse;
import gov.nysenate.openleg.client.response.base.DateRangeListViewResponse;
import gov.nysenate.openleg.client.view.law.LawDocIdView;
import gov.nysenate.openleg.client.view.law.LawVersionIdView;
import gov.nysenate.openleg.client.view.updates.UpdateDigestView;
import gov.nysenate.openleg.client.view.updates.UpdateTokenView;
import gov.nysenate.openleg.controller.api.base.BaseCtrl;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.PaginatedList;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.law.data.LawUpdatesDao;
import gov.nysenate.openleg.model.law.LawDocId;
import gov.nysenate.openleg.model.law.LawVersionId;
import gov.nysenate.openleg.model.updates.UpdateDigest;
import gov.nysenate.openleg.model.updates.UpdateToken;
import gov.nysenate.openleg.model.updates.UpdateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;

import static gov.nysenate.openleg.controller.api.base.BaseCtrl.BASE_API_PATH;
import static java.util.stream.Collectors.toList;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequestMapping(value = BASE_API_PATH + "/laws", method = RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
public class LawUpdatesCtrl extends BaseCtrl
{
    private static final Logger logger = LoggerFactory.getLogger(LawUpdatesCtrl.class);

    @Autowired private LawUpdatesDao lawUpdatesDao;

    /**
     * Law Updates API (for all laws)
     * ------------------------------
     *
     * Usages:
     * (GET) /api/3/laws/updates/{from date-time}
     * (GET) /api/3/laws/updates/{from date-time}/{to date-time}
     *
     * Request Params: detail (boolean) - Show update digests within each token.
     *                 type (string) - Update type (processed, published) Default: published
     *                 limit, offset (int) - Paginate
     *                 order (string) - Order by update date
     *
     * Expected Response: List of UpdateTokenView<LawVersionId> or UpdateDigestTokenView<LawDocId> if detail = true
     */
    @RequestMapping(value = "/updates/{from}")
    public BaseResponse getAllUpdates(@PathVariable String from, WebRequest request) {
        return getAllUpdates(parseISODateTime(from, "from"), LocalDateTime.now(), request);
    }

    @RequestMapping(value = "/updates/{from}/{to:.*\\.?.*}")
    public BaseResponse getAllUpdates(@PathVariable String from, @PathVariable String to, WebRequest request) {
        return getAllUpdates(parseISODateTime(from, "from"), parseISODateTime(to, "to"), request);
    }

    /**
     * Law Updates API (for a specific law)
     * ------------------------------------
     *
     * Usages:
     * (GET) /api/3/laws/{lawId}updates/
     * (GET) /api/3/laws/{lawId}/updates/{from date-time}
     * (GET) /api/3/laws/{lawId}/updates/{from date-time}/{to date-time}
     *
     * Where lawId is the three letter id for the law (e.g. ABC or EDN).
     * @see #getAllUpdates for request params and output.
     */
    @RequestMapping(value = "/{lawId:[\\w]{3}}/updates/")
    public BaseResponse getUpdatesForLaw(@PathVariable String lawId) {
        return null;
    }

    @RequestMapping(value = "/{lawId:[\\w]{3}}/updates/{from}")
    public BaseResponse getUpdatesForLaw(@PathVariable String lawId, @PathVariable String from) {
        return null;
    }

    @RequestMapping(value = "/{lawId:[\\w]{3}}/updates/{from}/{to:.*\\.?.*}")
    public BaseResponse getUpdatesForLaw(@PathVariable String lawId, @PathVariable String from, @PathVariable String to) {
        return null;
    }

    /**
     * Law Updates API (for a specific document)
     * -----------------------------------------
     *
     * Usages:
     * (GET) /api/3/laws/{lawId}/{locationId}/updates/
     * (GET) /api/3/laws/{lawId}/{locationId}/updates/{from date-time}
     * (GET) /api/3/laws/{lawId}/{locationId}/updates/{from date-time}/{to date-time}
     *
     * Where locationId is the 'locationId' in {@link gov.nysenate.openleg.model.law.LawDocId}.
     * @see #getAllUpdates for request params and output.
     */
    @RequestMapping(value = "/{lawId}/{locationId}/updates/")
    public BaseResponse getUpdatesForLawDoc(@PathVariable String lawId, @PathVariable String locationId) {
        return null;
    }

    @RequestMapping(value = "/{lawId}/{locationId}/updates/{from}")
    public BaseResponse getUpdatesForLawDoc(@PathVariable String lawId, @PathVariable String locationId,
                                            @PathVariable String from) {
        return null;
    }

    @RequestMapping(value = "/{lawId}/{locationId}/updates/{from}/{to:.*\\.?.*}")
    public BaseResponse getUpdatesForLawDoc(@PathVariable String lawId, @PathVariable String locationId,
                                            @PathVariable String from, @PathVariable String to) {
        return null;
    }

    /** --- Internal --- */

    private BaseResponse getAllUpdates(LocalDateTime from, LocalDateTime to, WebRequest request) {
        LimitOffset limOff = getLimitOffset(request, 50);
        Range<LocalDateTime> updateRange = getClosedOpenRange(from, to, "from", "to");
        boolean detail = getBooleanParam(request, "detail", false);
        SortOrder sortOrder = getSortOrder(request, SortOrder.ASC);
        UpdateType updateType = getUpdateTypeFromParam(request);

        if (!detail) {
            PaginatedList<UpdateToken<LawVersionId>> updateTokens =
                lawUpdatesDao.getUpdates(updateRange, updateType, sortOrder, limOff);
            return DateRangeListViewResponse.of(updateTokens.getResults().stream()
                    .map(token -> new UpdateTokenView(token, new LawVersionIdView(token.getId())))
                    .collect(toList()), updateRange, updateTokens.getTotal(), limOff);
        }
        else {
            PaginatedList<UpdateDigest<LawDocId>> updateDigests =
                lawUpdatesDao.getDetailedUpdates(updateRange, updateType, sortOrder, limOff);
            return DateRangeListViewResponse.of(updateDigests.getResults().stream()
                    .map(digest -> new UpdateDigestView(digest, new LawDocIdView(digest.getId())))
                    .collect(toList()), updateRange, updateDigests.getTotal(), limOff);
        }
    }
}