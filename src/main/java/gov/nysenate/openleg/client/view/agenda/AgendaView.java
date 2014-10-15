package gov.nysenate.openleg.client.view.agenda;

import gov.nysenate.openleg.client.view.base.ListView;
import gov.nysenate.openleg.client.view.base.ViewObject;
import gov.nysenate.openleg.model.agenda.Agenda;
import gov.nysenate.openleg.model.entity.CommitteeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AgendaView extends AgendaSummaryView implements ViewObject
{
    private ListView<AgendaCommView> committeeAgendas;

    public AgendaView(Agenda agenda) {
        super(agenda);
        if (agenda != null) {
            this.committeeAgendas = ListView.of(agenda.getAgendaInfoAddenda().values().stream()
                .flatMap(ia -> ia.getCommitteeInfoMap().keySet().stream())
                .distinct()
                .map(cid -> new AgendaCommView(cid, agenda))
                .collect(Collectors.toList()));
        }
    }

    public ListView<AgendaCommView> getCommitteeAgendas() {
        return committeeAgendas;
    }

    @Override
    public String getViewType() {
        return "agenda";
    }
}
