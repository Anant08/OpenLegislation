package gov.nysenate.openleg.service.scraping.bill;

import com.google.common.collect.*;
import gov.nysenate.openleg.model.bill.BillVoteCode;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.spotcheck.billscrape.BillScrapeVote;
import gov.nysenate.openleg.processor.base.ParseError;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles parsing of LRS bill scraped html files.
 */
@Service
public class BillScrapeReferenceHtmlParser {

    private static final String lrsOutageText = "404 - Processing Error";
    private static final Pattern billIdPattern = Pattern.compile("^([A-z]\\d+)(?:-([A-z]))?$");

    /**
     * Parse the print number from an LRS bill scrape html file.
     * @param doc
     * @return
     */
    public String parsePrintNo(Document doc) {
        Element printNoElement = doc.select("span.nv_bot_info > strong").first();
        if (printNoElement == null) {
            throw new ParseError("could not get scraped bill print no:");
        }
        Matcher printNoMatcher = billIdPattern.matcher(printNoElement.text());
        if (printNoMatcher.matches()) {
            String basePrintNo = printNoMatcher.group(1);
            String version = printNoMatcher.group(2) == null ? "" : printNoMatcher.group(2);
            return basePrintNo + version;
        }
        else {
            throw new ParseError("could not parse scraped bill print no: " + printNoElement.text());
        }
    }

    /**
     * Parse the bill text from an LRS bill scrape html file.
     * @param doc
     * @return
     */
    public String parseText(Document doc) {
        Element contents = doc.getElementById("nv_bot_contents");
        if (contents == null) {
            throw new ParseError("Could not locate scraped bill contents");
        }
        Elements textEles = new Elements();

        // Bill text is found in all pre tags contained in <div id="nv_bot_contents"> before the first <hr class="noprint">
        for (Element element : contents.children()) {
            if ("pre".equalsIgnoreCase(element.tagName())) {
                textEles.add(element);
            } else if ("hr".equalsIgnoreCase(element.tagName()) && element.classNames().contains("noprint")) {
                break;
            }
        }

        StringBuilder textBuilder = new StringBuilder();
        textEles.forEach(ele -> processTextNode(ele, textBuilder));
        return textBuilder.toString();
    }

    /**
     * Extracts bill/memo text from an element recursively
     */
    private void processTextNode(Element ele, StringBuilder stringBuilder) {
        for (Node t : ele.childNodes()) {
            if (t instanceof Element) {
                Element e = (Element) t;
                // TEXT IN <U> TAGS IS REPRESENTED IN CAPS FOR SOBI BILL TEXT
                if ("u".equals(e.tag().getName())) {
                    stringBuilder.append(e.text().toUpperCase());
                } else {
                    processTextNode(e, stringBuilder);
                }
            } else if (t instanceof TextNode) {
                stringBuilder.append(((TextNode) t).getWholeText());
            }
        }
    }

    /**
     * Parses the sponsor memo.
     * @param doc
     * @return
     */
    public String parseMemo(Document doc) {
        Element memoElement = doc.select("pre:last-of-type").first(); // you are the first and last of your kind
        if (memoElement != null) {
            StringBuilder memoBuilder = new StringBuilder();
            processTextNode(memoElement, memoBuilder);
            // todo format text
            return memoBuilder.toString();
        }
        // TODO: add parse exception here if element is null like other methods.
        return "";
    }

    /**
     * Parses votes from a scraped bill html.
     * Will ignore Assembly votes.
     * @param doc
     * @return A collection of {@link BillScrapeVote} or an empty list if no votes were found.
     */
    public Set<BillScrapeVote> parseVotes(Document doc) {
        Set<BillScrapeVote> votes = new HashSet<>();
        Element content = doc.getElementById("nv_bot_contents");
        Elements tables = content.select("table");
        for (int i = 0; i < tables.size(); i++) {
            Element table = tables.get(i);
            if (isVoteSummaryTable(table)) {
                if (hasSingleVote(table)) {
                    votes.addAll(parseSingleVote(table, tables.get(i + 1)));
                    break;
                }
                else {
                    votes.addAll(parseMultipleVotes(table));
                    break;
                }
            }
        }
        return votes;
    }

    private boolean isVoteSummaryTable(Element table) {
        return !table.select("a[href^=#VOTE").isEmpty();
    }

    private boolean hasSingleVote(Element table) {
        return table.select("a[href^=#VOTE").size() == 1;
    }

    /**
     * Parses a vote from the summary table and vote table.
     * @param summaryTable The table containing vote summary data.
     * @param nextTable The next table after the vote summary table, Should always contain the vote data if
     *                  this bill only has a single vote.
     * @return
     */
    private List<BillScrapeVote> parseSingleVote(Element summaryTable, Element nextTable) {
        List<BillScrapeVote> votes = new ArrayList<>();
        Chamber chamber = parseChamber(summaryTable.select("tr").get(0));
        if (chamber.equals(Chamber.SENATE)) {
            String date = summaryTable.select("a[href^=#VOTE").text().trim();
            LocalDate voteDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("M/d/yy"));
            SortedSetMultimap<BillVoteCode, String> voteMap = parseVote(nextTable);
            votes.add(new BillScrapeVote(voteDate, voteMap));
        }
        return votes;
    }

    /**
     * Parses votes from bills with multiple votes.
     * Ignores Assembly votes.
     * @param summaryTable The vote summary table.
     * @return
     */
    private List<BillScrapeVote> parseMultipleVotes(Element summaryTable) {
        List<BillScrapeVote> votes = new ArrayList<>();
        for (Element voteSummary : summaryTable.select("tr")) {
            Chamber chamber = parseChamber(voteSummary);
            if (chamber.equals(Chamber.SENATE)) {
                String voteId = voteSummary.select("a[href^=#VOTE").attr("href").replace("#", "");
                String date = voteSummary.select("a[href^=#VOTE").text().trim();
                LocalDate voteDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("M/d/yy"));

                Element voteTable = summaryTable.parent().select("a[name=" + voteId + "] ~ table").first();
                SortedSetMultimap<BillVoteCode, String> voteMap = parseVote(voteTable);
                votes.add(new BillScrapeVote(voteDate, voteMap));
            }
        }
        return votes;
    }

    private Chamber parseChamber(Element voteSummary) {
        return Chamber.getValue(elementText(voteSummary.child(2)).split("\\s")[0]);
    }

    private SortedSetMultimap<BillVoteCode, String> parseVote(Element voteTable) {
        SortedSetMultimap<BillVoteCode, String> votes = TreeMultimap.create();
        Elements entries = voteTable.select("td");
        for (int i = 0; i < entries.size(); i = i + 2) {
            votes.put(BillVoteCode.getValue(elementText(entries.get(i))),
                    elementText(entries.get(i + 1)));
        }
        return votes;
    }

    // Trims and strips &nbsp; from element text and returns it.
    private String elementText(Element el) {
        return el.text().replace("\u00a0", "").trim();
    }

    /**
     * Determines if a bill was missing from LRS.
     * @param doc A {@link Document} containing the text from a {@link BillScrapeFile}.
     * @return {@code true} if the Bill represented by this Document was missing from LRS,
     *         {@code false} if the bill exists on LRS.
     */
    public boolean isBillMissing(Document doc) {
        Element botContents = doc.getElementById("nv_bot_contents");
        if (botContents == null) {
            return false;
        }
        Elements redFonts = botContents.select("font[color=\"red\"]");
        Element notFoundText = redFonts.first();
        return notFoundText != null && "Bill Status Information Not Found".equals(notFoundText.text());
    }

    /**
     * Detects if the document indicates an lrs outage
     * returns true if so
     */
    public boolean isLrsOutage(String content) throws IOException {
        Document doc = Jsoup.parse(content, "UTF-8");
        Elements h2Eles = doc.getElementsByTag("h2");
        if (h2Eles.isEmpty()) {
            return false;
        }
        Element firstH2 = h2Eles.first();
        return firstH2.text().startsWith(lrsOutageText);
    }
}
