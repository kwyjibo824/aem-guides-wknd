package com.adobe.aem.guides.wknd.core.workflow.tp;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.ParticipantStepChooser;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TP案件 コンテンツ公開ワークフロー
 * カスタム開発#3: 起票者による本番公開確認ステップ
 *
 * Dynamic Participant Step として登録され、ワークフロー実行時に
 * initiator（起票者）のユーザーIDを返すことで、起票者本人の Inbox に
 * 承認タスクを送る。
 *
 * Workflow Model 側では Participant Step を配置し、
 * Participant Chooser の `chooser.name` プロパティ値として
 * "TP Initiator Chooser" を指定する。
 */
@Component(service = ParticipantStepChooser.class, property = {
        ParticipantStepChooser.SERVICE_PROPERTY_LABEL + "=TP Initiator Chooser"
})
public class InitiatorParticipantChooser implements ParticipantStepChooser {

    private static final Logger log = LoggerFactory.getLogger(InitiatorParticipantChooser.class);

    @Override
    public String getParticipant(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {
        String initiator = workItem.getWorkflow().getInitiator();
        if (initiator == null || initiator.trim().isEmpty()) {
            log.warn("TP Approval: initiator not found on workflow '{}'. Falling back to 'admin'.",
                    workItem.getWorkflow().getId());
            return "admin";
        }
        log.info("TP Approval: assigning approval task to initiator='{}'", initiator);
        return initiator;
    }
}
