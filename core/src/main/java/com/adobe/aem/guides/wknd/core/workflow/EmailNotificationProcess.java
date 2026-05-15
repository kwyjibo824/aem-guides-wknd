package com.adobe.aem.guides.wknd.core.workflow;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.mailer.MessageGateway;
import com.day.cq.mailer.MessageGatewayService;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = WorkflowProcess.class, property = {
    "process.label=Notify by Email (Custom)"
})
public class EmailNotificationProcess implements WorkflowProcess {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationProcess.class);
    private static final String METADATA_KEY = "notificationEmails";

    @Reference
    private MessageGatewayService messageGatewayService;

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {

        MetaDataMap metadata = workItem.getWorkflow().getMetaDataMap();
        String emailsRaw = metadata.get(METADATA_KEY, String.class);

        if (emailsRaw == null || emailsRaw.trim().isEmpty()) {
            log.debug("notificationEmails not set, skipping email notification.");
            return;
        }

        String payload = workItem.getWorkflowData().getPayload().toString();
        MessageGateway<Email> gateway = messageGatewayService.getGateway(Email.class);

        if (gateway == null) {
            log.warn("MessageGateway unavailable, cannot send notification emails.");
            return;
        }

        for (String raw : emailsRaw.split(",")) {
            String address = raw.trim();
            if (address.isEmpty()) {
                continue;
            }
            try {
                Email message = new SimpleEmail();
                message.addTo(address);
                message.setSubject("AEM公開通知: " + payload);
                message.setMsg("以下のコンテンツが公開されました。\n\n" + payload);
                gateway.send(message);
                log.info("Notification sent to: {}", address);
            } catch (Exception e) {
                log.error("Failed to send notification to: {}", address, e);
            }
        }
    }
}
