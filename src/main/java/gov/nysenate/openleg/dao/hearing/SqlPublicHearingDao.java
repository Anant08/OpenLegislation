package gov.nysenate.openleg.dao.hearing;

import com.google.common.collect.MapDifference;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.OrderBy;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.base.SqlBaseDao;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.Member;
import gov.nysenate.openleg.model.entity.MemberNotFoundEx;
import gov.nysenate.openleg.model.hearing.PublicHearing;
import gov.nysenate.openleg.model.hearing.PublicHearingCommittee;
import gov.nysenate.openleg.model.hearing.PublicHearingFile;
import gov.nysenate.openleg.model.hearing.PublicHearingId;
import gov.nysenate.openleg.service.entity.member.MemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static gov.nysenate.openleg.dao.hearing.SqlPublicHearingQuery.*;
import static gov.nysenate.openleg.util.CollectionUtils.difference;
import static gov.nysenate.openleg.util.DateUtils.toDate;
import static gov.nysenate.openleg.util.DateUtils.toTime;

@Repository
public class SqlPublicHearingDao extends SqlBaseDao implements PublicHearingDao
{
    private static final Logger logger = LoggerFactory.getLogger(SqlPublicHearingDao.class);

    @Autowired
    private MemberService memberService;

    /** {@inheritDoc} */
    @Override
    public List<PublicHearingId> getPublicHearingIds(SortOrder order, LimitOffset limOff) {
        OrderBy orderBy = new OrderBy("filename", order);
        List<PublicHearingId> ids = jdbcNamed.query(
                SELECT_PUBLIC_HEARING_IDS.getSql(schema(), orderBy, limOff), publicHearingIdRowMapper);
        return ids;
    }

    /** {@inheritDoc} */
    @Override
    public PublicHearing getPublicHearing(PublicHearingId publicHearingId) {
        MapSqlParameterSource params = getPublicHearingIdParams(publicHearingId);
        PublicHearing publicHearing = jdbcNamed.queryForObject(
                SELECT_PUBLIC_HEARING_BY_ID.getSql(schema()), params, publicHearingRowMapper);
        publicHearing.setAttendance(getPublicHearingAttendance(publicHearingId));
        publicHearing.setCommittees(getPublicHearingCommittees(publicHearingId));
        return publicHearing;
    }

    /** {@inheritDoc} */
    @Override
    public void updatePublicHearing(PublicHearing publicHearing, PublicHearingFile publicHearingFile) {
        MapSqlParameterSource params = getPublicHearingParams(publicHearing, publicHearingFile);
        if (jdbcNamed.update(UPDATE_PUBLIC_HEARING.getSql(schema()), params) == 0) {
            jdbcNamed.update(INSERT_PUBLIC_HEARING.getSql(schema()), params);
        }
        updatePublicHearingAttendance(publicHearing);
        updatePublicHearingCommittees(publicHearing);
    }

    private void updatePublicHearingAttendance(PublicHearing publicHearing) {
        List<Member> existingAttendance = getPublicHearingAttendance(publicHearing.getId());

        if (existingAttendance != null && publicHearing.getAttendance() != null) {
            if (!existingAttendance.equals(publicHearing.getAttendance())) {
                MapDifference<Member, Integer> diff = difference(existingAttendance, publicHearing.getAttendance(), 1);

                diff.entriesOnlyOnLeft().forEach((member, ordinal) -> {
                    jdbcNamed.update(DELETE_PUBLIC_HEARING_ATTENDANCE.getSql(schema()),
                            getAttendanceParams(publicHearing.getId(), member));
                });
                diff.entriesOnlyOnRight().forEach((member, ordinal) -> {
                    jdbcNamed.update(INSERT_PUBLIC_HEARING_ATTENDANCE.getSql(schema()),
                            getAttendanceParams(publicHearing.getId(), member));
                });
            }
        }
    }

    /**
     * Get the list of members present at this PublicHearing.
     * @param publicHearingId
     * @return
     */
    private List<Member> getPublicHearingAttendance(PublicHearingId publicHearingId) {
        MapSqlParameterSource params = getPublicHearingIdParams(publicHearingId);
        List<Member> members = new ArrayList<>();
        List<Member> sessionMemberIds = jdbcNamed.query(SELECT_PUBLIC_HEARING_ATTENDANCE.getSql(schema()), params, attendanceMemberIdRowMapper);
        for (Member member : sessionMemberIds) {
            try {
                members.add(memberService.getMemberBySessionId(member.getSessionMemberId()));
            } catch (MemberNotFoundEx ex) {
                logger.error("Error getting Public Hearing Member.", ex);
            }
        }

        return members;
    }

    /**
     * Updates the backing store with this PublicHearings PublicHearingCommittee information, or inserts
     * it if it doesn't exist.
     * @param publicHearing
     */
    private void updatePublicHearingCommittees(PublicHearing publicHearing) {
        List<PublicHearingCommittee> existingCommittees = getPublicHearingCommittees(publicHearing.getId());

        if (existingCommittees != null && publicHearing.getCommittees() != null) {
            if (!existingCommittees.equals(publicHearing.getCommittees())) {
                MapDifference<PublicHearingCommittee, Integer> diff = difference(existingCommittees, publicHearing.getCommittees(), 1);

                diff.entriesOnlyOnLeft().forEach((member, ordinal) -> {
                    jdbcNamed.update(DELETE_PUBLIC_HEARING_COMMITTEE.getSql(schema()),
                            getCommitteeParams(publicHearing.getId(), member));
                });
                diff.entriesOnlyOnRight().forEach((member, ordinal) -> {
                    jdbcNamed.update(INSERT_PUBLIC_HEARING_COMMITTEES.getSql(schema()),
                            getCommitteeParams(publicHearing.getId(), member));
                });
            }
        }
    }

    /**
     * Get a list of {@link PublicHearingCommittee} belonging to a PublicHearing.
     * @param publicHearingId
     * @return
     */
    private List<PublicHearingCommittee> getPublicHearingCommittees(PublicHearingId publicHearingId) {
        MapSqlParameterSource params = getPublicHearingIdParams(publicHearingId);
        return jdbcNamed.query(SELECT_PUBLIC_HEARING_COMMITTEES.getSql(schema()), params, committeeRowMapper);
    }

    /** --- Param Source Methods --- */

    private MapSqlParameterSource getPublicHearingParams(PublicHearing publicHearing, PublicHearingFile publicHearingFile) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("filename", publicHearingFile.getFileName());
        params.addValue("date", toDate(publicHearing.getDate()));
        params.addValue("title", publicHearing.getTitle());
        params.addValue("address", publicHearing.getAddress());
        params.addValue("text", publicHearing.getText());
        params.addValue("startTime", toTime(publicHearing.getStartTime()));
        params.addValue("endTime", toTime(publicHearing.getEndTime()));
        addModPubDateParams(publicHearing.getModifiedDateTime(), publicHearing.getPublishedDateTime(), params);
        return params;
    }

    private MapSqlParameterSource getPublicHearingIdParams(PublicHearingId publicHearingId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("filename", publicHearingId.getFileName());
        return params;
    }

//    private MapSqlParameterSource getPublicHearingIdYearParams(int year) {
//        MapSqlParameterSource params = new MapSqlParameterSource();
//        params.addValue("year", year);
//        return params;
//    }

    private MapSqlParameterSource getAttendanceParams(PublicHearingId id, Member member) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("filename", id.getFileName());
        params.addValue("sessionMemberId", member.getSessionMemberId());
        return params;
    }

    private MapSqlParameterSource getCommitteeParams(PublicHearingId id, PublicHearingCommittee committee) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("filename", id.getFileName());
        params.addValue("committeeName", committee.getName());
        params.addValue("committeeChamber", committee.getChamber().name().toLowerCase());
        return params;
    }

    /** --- Row Mapper Instances --- */

    static RowMapper<PublicHearing> publicHearingRowMapper = (rs, rowNum) -> {
        PublicHearingId id = new PublicHearingId(rs.getString("filename"));
        PublicHearing publicHearing = new PublicHearing(id, getLocalDateFromRs(rs, "date"), rs.getString("text"));
        publicHearing.setTitle(rs.getString("title"));
        publicHearing.setAddress(rs.getString("address"));
        publicHearing.setStartTime(getLocalTimeFromRs(rs, "start_time"));
        publicHearing.setEndTime(getLocalTimeFromRs(rs, "end_time"));
        publicHearing.setModifiedDateTime(getLocalDateTimeFromRs(rs, "modified_date_time"));
        publicHearing.setPublishedDateTime(getLocalDateTimeFromRs(rs, "published_date_time"));
        return publicHearing;
    };

    static RowMapper<PublicHearingId> publicHearingIdRowMapper = (rs, rowNum) ->
            new PublicHearingId(rs.getString("filename"));

    static RowMapper<Member> attendanceMemberIdRowMapper = (rs, rowNum) -> {
        Member member = new Member();
        member.setSessionMemberId(rs.getInt("session_member_id"));
        return member;
    };

    static RowMapper<PublicHearingCommittee> committeeRowMapper = (rs, rowNum) -> {
        PublicHearingCommittee committee = new PublicHearingCommittee();
        committee.setName(rs.getString("committee_name"));
        committee.setChamber(Chamber.valueOf(rs.getString("committee_chamber").toUpperCase()));
        return committee;
    };
}