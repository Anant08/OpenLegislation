package gov.nysenate.openleg.dao.spotcheck.elastic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.eventbus.Subscribe;
import gov.nysenate.openleg.dao.base.ElasticBaseDao;
import gov.nysenate.openleg.dao.base.SearchIndex;
import gov.nysenate.openleg.dao.spotcheck.SpotCheckReportDao;
import gov.nysenate.openleg.model.search.ClearIndexEvent;
import gov.nysenate.openleg.model.search.RebuildIndexEvent;
import gov.nysenate.openleg.model.spotcheck.*;
import gov.nysenate.openleg.service.base.search.IndexedSearchService;
import gov.nysenate.openleg.util.OutputUtils;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryAction;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.MultiSearchRequestBuilder;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.*;

/**
 * Created by PKS on 7/25/16.
 */
public abstract class AbstractSpotCheckReportElasticDao<ContentKey>
        extends ElasticBaseDao
        implements SpotCheckReportDao<ContentKey>, IndexedSearchService<SpotCheckReport<ContentKey>>{

    private static final Logger logger = LoggerFactory.getLogger(AbstractSpotCheckReportElasticDao.class);

    /** Subclasses should initialize with proper index name. */
    protected final String spotcheckIndex;

    protected static final String reportType = "reports";

    protected static final String observationType = "observations";

    /** Subclasses should call this constructor in their default constructor.
     *
     * @param refType
     */
    public AbstractSpotCheckReportElasticDao(SpotCheckRefType refType){
        spotcheckIndex = refType.getRefName();
    }

    /** --- Abstract Methods --- */

    /**
     * Subclasses should implement this conversion from a Map containing certain key/val pairs to
     * an instance of ContentKey. This is needed since the keys are stored as an hstore in the
     * database.
     *
     * @param keyMap Map<String, String>
     * @return ContentKey
     */
    public abstract ContentKey getKeyFromMap(Map<String, String> keyMap);

    /**
     * Subclasses should implement a conversion from an instance of ContentKey to a Map of
     * key/val pairs that fully represent that ContentKey.
     *
     * @param key ContentKey
     * @return Map<String, String>
     */
    public abstract Map<String, String> getMapFromKey(ContentKey key);

    /** --- Implemented Methods --- */

    /** {@inheritDoc} */
    @Override
    public SpotCheckReport<ContentKey> getReport(SpotCheckReportId Id) throws DataAccessException {
        SearchResponse reportSearchResponse = searchClient.prepareSearch()
                    .setIndices(spotcheckIndex)
                    .setTypes(reportType)
                    .setQuery(matchQuery("reportId.reportDateTime", Id.getReportDateTime()))
                    .execute()
                    .actionGet();
        if(reportSearchResponse.getHits().getTotalHits() > 0) {
            try {
                SpotCheckReport<ContentKey> report = getSpotcheckReportFrom(reportSearchResponse.getHits().getAt(0).getSource());
                String reportId = reportSearchResponse.getHits().getAt(0).getId();
                Map<ContentKey, SpotCheckObservation<ContentKey>> observationMap = getObservationsForReport(reportId);
                report.setObservations(observationMap);
                return report;

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        throw new SpotCheckReportNotFoundEx(Id);
    }

    /** {@inheritDoc} */
    @Override
    public void saveReport(SpotCheckReport<ContentKey> report) {
        if (report == null) {
            throw new IllegalArgumentException("Supplied report cannot be null.");
        }
        if (report.getObservations() == null) {
            logger.warn("The observations have not been set on this report.");
            return;
        }

        ElasticReport<ContentKey> elasticReport = new ElasticReport<>(report);

        String elasticReportJson = OutputUtils.toJson(elasticReport);

        BulkRequestBuilder bulkRequest = searchClient.prepareBulk();

        bulkRequest.add(
                searchClient.prepareIndex()
                        .setIndex(spotcheckIndex)
                        .setType(reportType)
                        .setId(String.valueOf(report.hashCode()))
                        .setSource(elasticReportJson)
        );

        List<ElasticObservation<ContentKey>> elasticObservations = setMismatchStatuses(
                toElasticObservations(String.valueOf(report.hashCode()),
                        report.getObservations()
                                .values().stream()
                                .collect(Collectors.toList())));

        elasticObservations.forEach(elasticObservation ->
            bulkRequest.add(
                    searchClient.prepareIndex()
                            .setIndex(spotcheckIndex)
                            .setType(observationType)
                            .setId(elasticObservation.getMismatchId().toString())
                            .setSource(OutputUtils.toJson(elasticObservation))
            )
        );

        bulkRequest.execute().actionGet();
    }

    @Override
    public List<SpotCheckReportSummary> getReportSummaries(SpotCheckRefType refType, LocalDateTime start,
                                                           LocalDateTime end, gov.nysenate.openleg.dao.base.SortOrder dateOrder) {
        SearchResponse reportSearchResponse = searchClient.prepareSearch()
                .setIndices(spotcheckIndex)
                .setTypes(reportType)
                .setSize(100)
                .setScroll(new TimeValue(60000))
                .setQuery(rangeQuery("reportId.reportDateTime").from(start).to(end))
                .addSort("reportId.reportDateTime", SortOrder.valueOf(dateOrder.toString()))
                .execute()
                .actionGet();
        List<SpotCheckReportSummary> spotCheckReportSummaries = new ArrayList<>();
        while(true){
            reportSearchResponse.getHits().forEach(report -> {
                String reportId = report.getId();
                SpotCheckReport<ContentKey> spotcheckReport = getSpotcheckReportFrom(report.getSource());
                Map<ContentKey, SpotCheckObservation<ContentKey>> observationMap =
                        getObservationsForReport(reportId);
                spotcheckReport.setObservations(observationMap);
                SpotCheckReportSummary summary = spotcheckReport.getSummary();
                summary.addCountsFromObservations(observationMap.values());
                spotCheckReportSummaries.add(spotcheckReport.getSummary());
            });

            reportSearchResponse = searchClient.prepareSearchScroll(reportSearchResponse.getScrollId())
                    .setScroll(new TimeValue(60000))
                    .execute()
                    .actionGet();
            if(reportSearchResponse.getHits().getHits().length == 0){
                break;
            }
        }
        return spotCheckReportSummaries;
    }

    @Override
    public SpotCheckOpenMismatches<ContentKey> getOpenMismatches(OpenMismatchQuery query) {
        MultiSearchRequestBuilder multiSearch = searchClient.prepareMultiSearch();
        BoolQueryBuilder queryFilters = QueryBuilders.boolQuery();
        query.getMismatchTypes().forEach(spotCheckMismatchType -> {
            queryFilters.should(QueryBuilders.matchQuery("mismatchType", spotCheckMismatchType));
        });
        query.getRefTypes().forEach(spotCheckRefType -> {
            multiSearch.add(
                    searchClient.prepareSearch()
                            .setIndices(spotCheckRefType.getRefName())
                            .setTypes(observationType)
                            .setQuery(rangeQuery("observedDateTime").from(query.getObservedAfter()))
                            .setPostFilter(queryFilters)
                            .addSort(query.getOrderBy().getColName(), SortOrder.valueOf(query.getOrder().toString()))
                            .setScroll(new TimeValue(60000))
                            .setSize(query.getLimitOffset().getLimit())
            );
        });
        MultiSearchResponse multiSearchResponse = multiSearch.execute().actionGet();
        Map<ContentKey, SpotCheckObservation<ContentKey>> observations = new HashMap<>();
        List<Integer> countList = new ArrayList<>();
        multiSearchResponse.forEach(r -> {
            if(!r.isFailure()){
                SearchResponse response =  r.getResponse();
                Integer offset = query.getLimitOffset().getOffsetStart();
                Integer scroll = 1;
                while (true){
                    if(offset.equals(scroll)){
                        response.getHits().forEach(observation -> {
                            Map<String, Object> observationObjectMap = observation.getSource();
                            TypeReference<ElasticObservation<ContentKey>> elasticObservationTypeReference = new TypeReference<ElasticObservation<ContentKey>>(){};
                            ElasticObservation<ContentKey> elasticObservation = OutputUtils.getJsonMapper().convertValue(observationObjectMap, elasticObservationTypeReference);
                            SpotCheckObservation<ContentKey> spotcheckObservation = elasticObservation
                                    .toSpotCheckObservation(getKeyFromMap(elasticObservation.getObservationKey()), observation.getId());
                            countList.add(spotcheckObservation.getMismatches().values().size());
                            observations.put(spotcheckObservation.getKey(),spotcheckObservation);
                        });
                    }
                    scroll += 10;
                    response = searchClient.prepareSearchScroll(response.getScrollId())
                            .setScroll(new TimeValue(60000))
                            .execute()
                            .actionGet();
                    if(response.getHits().getHits().length == 0 || observations.size() == query.getLimitOffset().getLimit()){
                        break;
                    }
                }

            }
        });
        Integer totalMismatches = countList.stream().mapToInt(Integer::intValue).sum();
        return new SpotCheckOpenMismatches<>(query.getRefTypes(),observations,totalMismatches);
    }

    @Override
    public OpenMismatchSummary getOpenMismatchSummary(SpotCheckRefType refType, LocalDateTime observedAfter) {
        SearchResponse searchObservationsResponse = searchClient.prepareSearch()
                .setIndices(spotcheckIndex)
                .setTypes(observationType)
                .setQuery(QueryBuilders.boolQuery()
                                .must(matchQuery("referenceId.referenceType",refType))
                                .must(rangeQuery("observedDateTime").from(observedAfter))
                )
                .setScroll(new TimeValue(60000))
                .setSize(100)
                .execute().actionGet();
        Map<SpotCheckRefType, Collection<SpotCheckObservation<ContentKey>>>observationsMap = new HashMap<>();
        while (true){
            searchObservationsResponse.getHits().forEach(observation -> {
                SpotCheckObservation<ContentKey> spotcheckObservation = getSpotcheckObservationFrom(observation);
                Collection<SpotCheckObservation<ContentKey>> observations =
                        observationsMap.get(spotcheckObservation.getReferenceId().getReferenceType());
                if(observations == null)
                    observations = new ArrayList<SpotCheckObservation<ContentKey>>();
                observations.add(spotcheckObservation);
                observationsMap.put(spotcheckObservation.getReferenceId().getReferenceType(),observations);
            });
            searchObservationsResponse = searchClient.prepareSearchScroll(searchObservationsResponse.getScrollId())
                    .setScroll(new TimeValue(60000))
                    .execute()
                    .actionGet();
            if(searchObservationsResponse.getHits().getHits().length == 0){
                break;
            }
        }
        OpenMismatchSummary openMismatchSummary = new OpenMismatchSummary(refType,observedAfter);
        if(observationsMap.get(refType) != null)
            openMismatchSummary.getRefTypeSummary(refType).addCountsFromObservations(observationsMap.get(refType));
        return openMismatchSummary;
    }

    @Override
    public void deleteReport(SpotCheckReportId reportId) {
        SearchResponse reportSearchResponse = searchClient.prepareSearch()
                .setIndices(spotcheckIndex)
                .setTypes(reportType)
                .setQuery(matchQuery("reportId.reportDateTime", reportId.getReportDateTime()))
                .execute()
                .actionGet();
        if(reportSearchResponse.getHits().getTotalHits() > 0){
            String id = reportSearchResponse.getHits().getAt(0).getId();
            searchClient.prepareDelete()
                    .setIndex(spotcheckIndex)
                    .setType(reportType)
                    .setId(id)
                    .execute()
                    .actionGet();
            DeleteByQueryRequestBuilder builder = new DeleteByQueryRequestBuilder(searchClient, DeleteByQueryAction.INSTANCE);
            builder.setIndices(spotcheckIndex)
                    .setTypes(observationType)
                    .setQuery(matchQuery("spotcheckReportId",id));
        }
    }

    @Override
    public void setMismatchIgnoreStatus(int mismatchId, SpotCheckMismatchIgnore ignoreStatus) {
        try {
            UpdateRequest updateRequest = new UpdateRequest(spotcheckIndex, observationType, String.valueOf(mismatchId))
                    .doc(jsonBuilder()
                            .startObject()
                            .field("mismatchIgnore", ignoreStatus)
                            .endObject());
            searchClient.update(updateRequest).actionGet();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addIssueId(int mismatchId, String issueId) {
        GetResponse response = searchClient.prepareGet(spotcheckIndex,observationType, String.valueOf(mismatchId))
                .execute().actionGet();
        List<String> issueIds = (List<String>) response.getSource().get("issueIds");
        issueIds.add(issueId);
        try {
            UpdateRequest updateRequest = new UpdateRequest(spotcheckIndex, observationType, String.valueOf(mismatchId))
                    .doc(jsonBuilder()
                            .startObject()
                            .array("issueIds",issueIds.toArray())
                            .endObject());
            searchClient.update(updateRequest).actionGet();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void deleteIssueId(int mismatchId, String issueId) {
        GetResponse response = searchClient.prepareGet(spotcheckIndex,observationType, String.valueOf(mismatchId))
                .execute().actionGet();
        List<String> issueIds = (List<String>) response.getSource().get("issueIds");
        issueIds.remove(issueId);
        try {
            UpdateRequest updateRequest = new UpdateRequest(spotcheckIndex, observationType, String.valueOf(mismatchId))
                    .doc(jsonBuilder()
                            .startObject()
                            .array("issueIds",issueIds.toArray())
                            .endObject());
            searchClient.update(updateRequest).actionGet();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** --- Internal Methods --- */

    /**
     * Looks at past observations for each checked content key and sets mismatch statuses in the current report
     * according to their presence in previous reports
     */
    private List<ElasticObservation<ContentKey>> setMismatchStatuses(List<ElasticObservation<ContentKey>> elasticObservations) {
        List<ElasticObservation<ContentKey>> observations = new ArrayList<>();
        elasticObservations.forEach(elasticObservation -> {
            Map<String, String> observationKeyMap = elasticObservation.getObservationKey();
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
            observationKeyMap.forEach((k, v) ->{
                if (!v.isEmpty())  {
                MatchQueryBuilder matchQuery = matchQuery("observationKey."+ k, v);
                boolQuery.must(matchQuery);}
            });
            BoolQueryBuilder filterQuery = QueryBuilders.boolQuery();
            filterQuery.must(matchQuery("mismatchType", elasticObservation.getMismatchType()));
            if(!elasticObservation.getObservedData().isEmpty())
                filterQuery.must(matchQuery("observedData", elasticObservation.getObservedData()));
            if(!elasticObservation.getReferenceData().isEmpty())
                filterQuery.must(matchQuery("referenceData", elasticObservation.getReferenceData()));
            boolQuery.filter(filterQuery);
            SearchResponse searchResponse = searchClient.prepareSearch()
                    .setIndices(spotcheckIndex).setTypes(observationType)
                    .setQuery(boolQuery)
                    .execute().actionGet();
            if(searchResponse.getHits().totalHits() > 0){
                elasticObservation.setMismatchStatus(SpotCheckMismatchStatus.EXISTING);
            }
            observations.add(elasticObservation);
        });
        return observations;
    }

    private List<ElasticObservation<ContentKey>> toElasticObservations(String reportId,
                                                                        List<SpotCheckObservation<ContentKey>> observations){
        List<ElasticObservation<ContentKey>> elasticObservations = new ArrayList<>();
        observations.forEach(observation -> {
            observation.getMismatches().values().forEach(spotCheckMismatch -> {
                elasticObservations.add(new ElasticObservation<ContentKey>(reportId, getMapFromKey(observation.getKey()),
                        observation, spotCheckMismatch));
            });
        });
        return elasticObservations;
    }

    private Map<ContentKey, SpotCheckObservation<ContentKey>> getObservationsForReport(String reportId){
        SearchResponse observationSearchResponse = searchClient.prepareSearch()
                .setIndices(spotcheckIndex)
                .setTypes(observationType)
                .setQuery(matchQuery("spotcheckReportId", reportId))
                .setScroll(new TimeValue(60000))
                .setSize(100)
                .execute()
                .actionGet();
        Map<ContentKey, SpotCheckObservation<ContentKey>> observationMap = new HashMap<>();
        while(true){

            observationSearchResponse.getHits().forEach(observation -> {
                SpotCheckObservation<ContentKey> spotcheckObservation = getSpotcheckObservationFrom(observation);
                if(observationMap.containsKey(spotcheckObservation.getKey())){
                    SpotCheckObservation spotCheckObservation = observationMap.get(spotcheckObservation.getKey());
                    spotcheckObservation.getMismatches().putAll(spotCheckObservation.getMismatches());
                }
                observationMap.put(spotcheckObservation.getKey(),spotcheckObservation);
            });

            observationSearchResponse = searchClient.prepareSearchScroll(observationSearchResponse.getScrollId())
                    .setScroll(new TimeValue(60000))
                    .execute()
                    .actionGet();
            if(observationSearchResponse.getHits().getHits().length == 0){
                break;
            }
        }
        return observationMap;
    }

    private SpotCheckObservation<ContentKey> getSpotcheckObservationFrom(SearchHit observation){
        ElasticObservation<ContentKey> elasticObservation = getElasticObservationFrom(observation);
        return elasticObservation
                .toSpotCheckObservation(getKeyFromMap(elasticObservation.getObservationKey()), observation.getId());
    }

    private ElasticObservation<ContentKey> getElasticObservationFrom(SearchHit observation){
        Map<String, Object> observationObjectMap = observation.getSource();
        TypeReference<ElasticObservation<ContentKey>> elasticObservationTypeReference = new TypeReference<ElasticObservation<ContentKey>>(){};
        return OutputUtils.getJsonMapper()
                .convertValue(observationObjectMap, elasticObservationTypeReference);
    }

    private SpotCheckReport<ContentKey> getSpotcheckReportFrom(Map<String, Object> objectMap){
        TypeReference<ElasticReport<ContentKey>> elasticReportTypeReference =
                new TypeReference<ElasticReport<ContentKey>>(){};

        ElasticReport<ContentKey> elasticReport = OutputUtils.getJsonMapper()
                .convertValue(objectMap,elasticReportTypeReference);

        return elasticReport.toSpotCheckReport();
    }


    /** {@inheritDoc} */
    @Override
    protected List<String> getIndices() {
        return Collections.singletonList(spotcheckIndex);
    }

    /** {@inheritDoc} */
    @Override
    public void updateIndex(SpotCheckReport<ContentKey> content) {}

    /** {@inheritDoc} */
    @Override
    public void updateIndex(Collection<SpotCheckReport<ContentKey>> content) {}

    /** {@inheritDoc} */
    @Override
    public void clearIndex() {
        purgeIndices();
    }

    /** {@inheritDoc} */
    @Override
    public void rebuildIndex() {
        clearIndex();
    }

    /** {@inheritDoc} */
    @Subscribe
    @Override
    public void handleRebuildEvent(RebuildIndexEvent event) {
        if (event.affects(SearchIndex.SPOTCHECK)) {
            rebuildIndex();
        }
    }

    /** {@inheritDoc} */
    @Override
    @Subscribe
    public void handleClearEvent(ClearIndexEvent event) {
        if (event.affects(SearchIndex.SPOTCHECK)) {
            clearIndex();
        }
    }

}