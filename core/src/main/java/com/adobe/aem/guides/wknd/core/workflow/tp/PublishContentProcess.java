package com.adobe.aem.guides.wknd.core.workflow.tp;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.replication.AgentIdFilter;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import java.util.Collections;

/**
 * TP案件 コンテンツ公開ワークフロー
 * カスタム開発#6: Preview / 本番公開ステップ
 *
 * Replication API を使って payload を指定 agentId に対して replicate する。
 * Preview 公開と本番公開で同一クラスを利用し、PROCESS_ARGS の agentId で切替える。
 *
 * 設定例（ワークフローモデルの Process Step の Arguments）:
 *   agentId=preview   → Preview公開
 *   agentId=publish   → 本番公開
 */
@Component(service = WorkflowProcess.class, property = {
        "process.label=TP Publish Content (Replication)"
})
public class PublishContentProcess implements WorkflowProcess {

    private static final Logger log = LoggerFactory.getLogger(PublishContentProcess.class);

    private static final String ARG_AGENT_ID = "agentId";
    private static final String METADATA_AGENT_ID = "agentId";
    private static final String SERVICE_USER_SUBSERVICE = "tp-workflow";

    @Reference
    private Replicator replicator;

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {

        String agentId = resolveAgentId(workItem, args);
        if (agentId == null || agentId.isEmpty()) {
            throw new WorkflowException(
                    "TP Publish: agentId is not specified in PROCESS_ARGS nor workflow metadata.");
        }

        WorkflowData data = workItem.getWorkflowData();
        if (!"JCR_PATH".equals(data.getPayloadType())) {
            log.warn("TP Publish: Unsupported payload type '{}', skipping.", data.getPayloadType());
            return;
        }
        String payloadPath = data.getPayload().toString();

        ReplicationActionType actionType = resolveActionType(workItem, args);

        try (ResourceResolver resolver = openResolver()) {
            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                throw new WorkflowException("TP Publish: Failed to adapt to JCR Session.");
            }

            ReplicationOptions options = new ReplicationOptions();
            options.setFilter(new AgentIdFilter(agentId));
            options.setSuppressVersions(true);
            options.setSuppressStatusUpdate(false);

            log.info("TP Publish: replicate path='{}' agentId='{}' action='{}'",
                    payloadPath, agentId, actionType.getName());

            replicator.replicate(session, actionType, payloadPath, options);

            workItem.getWorkflow().getMetaDataMap()
                    .put("lastReplicatedAgentId", agentId);

        } catch (LoginException e) {
            throw new WorkflowException("TP Publish: Service user login failed.", e);
        } catch (ReplicationException e) {
            throw new WorkflowException(
                    "TP Publish: Replication failed for path=" + payloadPath + " agentId=" + agentId, e);
        }
    }

    private String resolveAgentId(WorkItem workItem, MetaDataMap args) {
        String fromArgs = args.get(ARG_AGENT_ID, String.class);
        if (fromArgs != null && !fromArgs.trim().isEmpty()) {
            return fromArgs.trim();
        }
        String fromProcessArgs = args.get("PROCESS_ARGS", String.class);
        if (fromProcessArgs != null && fromProcessArgs.contains("agentId=")) {
            for (String token : fromProcessArgs.split(",")) {
                String[] kv = token.trim().split("=", 2);
                if (kv.length == 2 && ARG_AGENT_ID.equals(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return workItem.getWorkflow().getMetaDataMap().get(METADATA_AGENT_ID, String.class);
    }

    private ReplicationActionType resolveActionType(WorkItem workItem, MetaDataMap args) {
        String action = args.get("action", String.class);
        if (action == null) {
            action = workItem.getWorkflow().getMetaDataMap().get("replicationAction", String.class);
        }
        if (action == null) {
            return ReplicationActionType.ACTIVATE;
        }
        ReplicationActionType type = ReplicationActionType.fromName(action.trim().toUpperCase());
        return type != null ? type : ReplicationActionType.ACTIVATE;
    }

    private ResourceResolver openResolver() throws LoginException {
        return resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SERVICE_USER_SUBSERVICE));
    }
}
