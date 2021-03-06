package gov.nysenate.openleg.processor.law;

import gov.nysenate.openleg.BaseTests;
import gov.nysenate.openleg.annotation.SillyTest;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.model.law.LawFile;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Category(SillyTest.class)
@Component
public class ManagedLawProcessServiceTest extends BaseTests
{
    private static final Logger logger = LoggerFactory.getLogger(ManagedLawProcessServiceTest.class);

    @Autowired
    private ManagedLawProcessService lawProcessService;

    @Test
    public void testCollate() throws Exception {
        lawProcessService.collateLawFiles();
    }

    @Test
    public void testProcessLawFiles() throws Exception {
        List<LawFile> lawFiles = lawProcessService.getPendingLawFiles(LimitOffset.ALL);
        lawProcessService.processLawFiles(lawFiles);
    }
}