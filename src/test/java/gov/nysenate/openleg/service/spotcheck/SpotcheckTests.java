package gov.nysenate.openleg.service.spotcheck;

import gov.nysenate.openleg.BaseTests;
import gov.nysenate.openleg.WebAppBaseTests;
import gov.nysenate.openleg.client.response.base.BaseResponse;
import gov.nysenate.openleg.controller.api.admin.SpotCheckCtrl;
import gov.nysenate.openleg.model.spotcheck.SpotCheckRefType;
import gov.nysenate.openleg.service.spotcheck.agenda.AgendaSpotcheckProcessService;
import gov.nysenate.openleg.service.spotcheck.base.SpotcheckRunService;
import org.junit.Test;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class SpotcheckTests extends WebAppBaseTests {
    private static final Logger logger = LogManager.getLogger();

    @Autowired
    SpotcheckRunService spotcheckRunService;

    @Autowired SpotCheckCtrl spotCheckCtrl;

    @Test
    public void runWeeklyReports() {
        spotcheckRunService.runWeeklyReports();
    }

    @Test
    public void runReports()
    {
        spotcheckRunService.runReports(SpotCheckRefType.SENATE_SITE_CALENDAR);
    }

    @Test
    public void openObsGetTest() {
        LocalTime start = LocalTime.now();
        logger.info("start {}", start);
        BaseResponse response =
                spotCheckCtrl.getOpenMismatches("scraped-bill", null, "CONTENT_KEY", null, null, false, false, false, false, true,
                        new ServletWebRequest(new MockHttpServletRequest()));
        LocalTime end = LocalTime.now();
        logger.info("done {}", end);
        logger.info("took {}", Duration.between(start, end));
    }
}
