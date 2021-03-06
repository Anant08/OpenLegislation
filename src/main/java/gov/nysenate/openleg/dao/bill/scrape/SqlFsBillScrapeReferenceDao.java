package gov.nysenate.openleg.dao.bill.scrape;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import gov.nysenate.openleg.config.Environment;
import gov.nysenate.openleg.dao.base.*;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.base.Version;
import gov.nysenate.openleg.model.bill.BaseBillId;
import gov.nysenate.openleg.model.spotcheck.billscrape.BillScrapeQueueEntry;
import gov.nysenate.openleg.model.spotcheck.billscrape.BillScrapeReference;
import gov.nysenate.openleg.service.scraping.bill.BillScrapeFile;
import gov.nysenate.openleg.util.DateUtils;
import gov.nysenate.openleg.util.FileIOUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static gov.nysenate.openleg.dao.bill.scrape.SqlBillScrapeReferenceQuery.*;

/**
 * Created by kyle on 3/19/15.
 */
@Repository
public class SqlFsBillScrapeReferenceDao extends SqlBaseDao implements BillScrapeReferenceDao {

    private static final Logger logger = LoggerFactory.getLogger(SqlFsBillScrapeReferenceDao.class);
    private static final String FILE_TEMPLATE = "${sessionYear}-${printNo}-${scrapedTime}.html";

    @Autowired
    private Environment environment;

    private File scrapedBillIncomingDir;
    private File scrapedBillArchiveDir;

    @PostConstruct
    public void init() {
        scrapedBillIncomingDir = new File(environment.getScrapedStagingDir(), "bill");
        scrapedBillArchiveDir = new File(new File(environment.getArchiveDir(), "scraped"), "bill");
        try {
            FileUtils.forceMkdir(scrapedBillIncomingDir);
            FileUtils.forceMkdir(scrapedBillArchiveDir);
        } catch (IOException ex) {
            logger.error("could not create bill scraped dirs " + scrapedBillIncomingDir.getPath() + " and " + scrapedBillArchiveDir.getPath());
        }
    }

    @Override
    public void saveScrapedBillContent(String content, BaseBillId scrapedBill) throws IOException {
        // Save file
        File scrapeFile = createScrapeFile(scrapedBillIncomingDir, scrapedBill);
        FileIOUtils.writeStringToFile(scrapeFile, content, StandardCharsets.UTF_8);

        // Save to db
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fileName", scrapeFile.getName())
                .addValue("filePath", FilenameUtils.getFullPath(scrapeFile.getPath()));
        String sql = SqlBillScrapeReferenceQuery.INSERT_BILL_SCRAPE_FILE.getSql(schema());
        jdbcNamed.update(sql, params);
    }

    private File createScrapeFile(File stagingDir, BaseBillId baseBillId) {
        String file = StrSubstitutor.replace(FILE_TEMPLATE, ImmutableMap.<String, String>builder()
                .put("sessionYear", Integer.toString(baseBillId.getSession().getYear()))
                .put("printNo", baseBillId.getPrintNo())
                .put("scrapedTime", LocalDateTime.now().format(DateUtils.BASIC_ISO_DATE_TIME))
                .build());
        return new File(stagingDir, file);
    }

    @Override
    public List<BillScrapeFile> getIncomingScrapedBills() {
        String sql = SqlBillScrapeReferenceQuery.SELECT_INCOMING_BILL_SCRAPE_FILES.getSql(schema());
        List<BillScrapeFile> scrapeFiles = jdbcNamed.query(sql, billScrapeFileMapper);
        return scrapeFiles;
    }

    @Override
    public BillScrapeFile archiveScrapedBill(BillScrapeFile scrapedBill) throws IOException {
        // archive file
        File archivedScrapedBill = new File(scrapedBillArchiveDir, scrapedBill.getFileName());
        FileUtils.deleteQuietly(archivedScrapedBill);
        FileUtils.moveFile(scrapedBill.getFile(), archivedScrapedBill);

        // update scraped bill
        scrapedBill.setFilePath(scrapedBillArchiveDir.getPath());
        scrapedBill.setArchived(true);

        // archive in db
        updateScrapedBill(scrapedBill);

        return scrapedBill;
    }

    @Override
    public void updateScrapedBill(BillScrapeFile scrapeFile) {
        String sql = SqlBillScrapeReferenceQuery.UPDATE_BILL_SCRAPE_FILE.getSql(schema());
        jdbcNamed.update(sql, billScrapeParams(scrapeFile));
    }

    @Override
    public List<BillScrapeFile> pendingScrapeBills() {
        String sql = SqlBillScrapeReferenceQuery.SELECT_PENDING_BILL_SCRAPE_FILES.getSql(schema());
        return jdbcNamed.query(sql, billScrapeFileMapper);
    }

    @Override
    public BaseBillId getScrapeQueueHead() throws EmptyResultDataAccessException {
        PaginatedList<BillScrapeQueueEntry> scrapeQueue = getScrapeQueue(LimitOffset.ONE, SortOrder.DESC);
        if (scrapeQueue.getResults().isEmpty()) {
            throw new EmptyResultDataAccessException("no bills in scrape queue", 1);
        }
        return scrapeQueue.getResults().get(0).getBaseBillId();
    }

    @Override
    public PaginatedList<BillScrapeQueueEntry> getScrapeQueue(LimitOffset limitOffset, SortOrder order) {
        PaginatedRowHandler<BillScrapeQueueEntry> rowHandler =
                new PaginatedRowHandler<>(limitOffset, "total", scrapeQueueEntryRowMapper);
        jdbcNamed.query(SELECT_SCRAPE_QUEUE.getSql(schema(),
                new OrderBy("priority", order, "added_time", SortOrder.getOpposite(order)), limitOffset), rowHandler);
        return rowHandler.getList();
    }

    @Override
    public void addBillToScrapeQueue(BaseBillId id, int priority) {
        MapSqlParameterSource params = getQueueParams(id, priority);
        try {
            jdbcNamed.update(INSERT_SCRAPE_QUEUE.getSql(schema()), params);
        }catch(DuplicateKeyException ex){
            jdbcNamed.update(UPDATE_SCRAPE_QUEUE.getSql(schema()), params);
        }
    }

    @Override
    public void deleteBillFromScrapeQueue(BaseBillId id){
        MapSqlParameterSource params = getQueueParams(id);
        jdbcNamed.update(DELETE_SCRAPE_QUEUE.getSql(schema()), params);
    }

    /**----------   Map Parameters   -------*/

    public MapSqlParameterSource getQueueParams(BaseBillId id) {
        return getQueueParams(id, 0);
    }

    public MapSqlParameterSource billScrapeParams(BillScrapeFile file) {
        return new MapSqlParameterSource()
                .addValue("fileName", file.getFileName())
                .addValue("filePath", file.getFilePath())
                .addValue("isArchived", file.isArchived())
                .addValue("isPendingProcessing", file.isPendingProcessing());
    }

    public MapSqlParameterSource getQueueParams(BaseBillId id, int priority) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("printNo", id.getPrintNo());
        params.addValue("sessionYear", id.getSession().getYear());
        params.addValue("priority", priority);
        return params;
    }
    /**----------   Bill Row Mapper   -------*/

    private static final RowMapper<BillScrapeFile> billScrapeFileMapper = (rs, rowNum) ->
            new BillScrapeFile(rs.getString("file_name"),
                    rs.getString("file_path"),
                    getLocalDateTimeFromRs(rs, "staged_date_time"),
                    rs.getBoolean("is_archived"),
                    rs.getBoolean("is_pending_processing"));

    private static final RowMapper<BillScrapeQueueEntry> scrapeQueueEntryRowMapper = (rs, rowNum) ->
            new BillScrapeQueueEntry(
                    new BaseBillId(rs.getString("print_no"), rs.getInt("session_year")),
                    rs.getInt("priority"), getLocalDateTimeFromRs(rs, "added_time")
            );

}
