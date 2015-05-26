package gov.nysenate.openleg.processor.law;

import gov.nysenate.openleg.model.law.LawChapterCode;
import gov.nysenate.openleg.model.law.LawDocInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LawTitleParser
{
    private static final Logger logger = LoggerFactory.getLogger(LawTitleParser.class);

    protected static String sectionTitlePattern = "(?i)((?:Section|§)\\s*%s).?\\s(.+?)\\.(.*)";
    protected static Pattern articleTitlePattern = Pattern.compile("((ARTICLE|TITLE).+?\\\\n)(.+?)\\\\n(Section)?");

    /** --- Methods --- */

    public static String extractTitle(LawDocInfo lawDocInfo, String bodyText) {
        String title = "";
        if (lawDocInfo != null) {
            switch (lawDocInfo.getDocType()) {
                case CHAPTER:
                    title = extractTitleFromChapter(lawDocInfo);
                    break;
                case ARTICLE:
                case TITLE:
                    title = extractTitleFromArticle(lawDocInfo, bodyText);
                    break;
                case SUBTITLE:
                case PART:
                case SUB_PART:
                    break;
                case SECTION:
                    title = extractTitleFromSection(lawDocInfo, bodyText);
                    break;
                case INDEX:
                    break;
                case CONTENTS:
                    break;
                default: break;
            }
        }
        return title;
    }

    /**
     * Extract the chapter title using the mapping of law id to LawChapterType if possible.
     */
    protected static String extractTitleFromChapter(LawDocInfo docInfo) {
        try {
            LawChapterCode chapterType = LawChapterCode.valueOf(docInfo.getLawId());
            return chapterType.getName();
        }
        catch (IllegalArgumentException ex) {
            return docInfo.getLawId() + " Law";
        }
    }

    /**
     * Parses the title for an article by assuming that most article titles are presented in all caps.
     */
    protected static String extractTitleFromArticle(LawDocInfo lawDocInfo, String bodyText) {
        Matcher articleTitleMatcher = articleTitlePattern.matcher(bodyText);
        if (articleTitleMatcher.find()) {
            String title = articleTitleMatcher.group(3).replaceAll("\\\\n", "").replaceAll("\\s{2,}", " ");
            return capitalizeTitle(title.trim());
        }
        return "";
    }

    /**
     * Extract the title from the section document using a common pattern if applicable or just getting the
     * first line or so.
     */
    protected static String extractTitleFromSection(LawDocInfo docInfo, String text) {
        String title = "";
        if (text != null && !text.isEmpty()) {
            int asteriskLoc = docInfo.getLocationId().indexOf("*");
            String locationId = (asteriskLoc != -1)
                                ? docInfo.getLocationId().substring(0, asteriskLoc) : docInfo.getLocationId();
            Pattern titlePattern = Pattern.compile(String.format(sectionTitlePattern, locationId.toLowerCase()));
            int sectionIdx = text.indexOf("§");
            String trimText = (sectionIdx != -1) ? text.substring(sectionIdx).trim() : text.trim();
            Matcher titleMatcher = titlePattern.matcher(trimText);
            if (titleMatcher.matches()) {
                title = titleMatcher.group(2).replaceAll("-\\\\n\\s*", "").replaceAll("\\\\n?\\s*", " ");
            }
            else {
                logger.warn("Section title pattern mismatch for document id {}", docInfo.getDocumentId());
                title = trimText;
            }
        }
        return StringUtils.abbreviate(title, 140);
    }

    protected static String capitalizeTitle(String title) {
        if (title != null && !title.isEmpty()) {
            String capStr = WordUtils.capitalizeFully(title);
            return capStr.substring(0, 1) + Stream.of(capStr.substring(1).split(" "))
                    .map(s -> (s.matches("(Of|Or|The|For|A|And|An)")) ? s.toLowerCase() : s)
                    .collect(Collectors.joining(" "));
        }
        return title;
    }


}