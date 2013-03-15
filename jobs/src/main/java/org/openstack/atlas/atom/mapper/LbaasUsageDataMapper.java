package org.openstack.atlas.atom.mapper;

import com.rackspace.docs.core.event.DC;
import com.rackspace.docs.core.event.EventType;
import com.rackspace.docs.usage.lbaas.ResourceTypes;
import com.rackspace.docs.usage.lbaas.SslModeEnum;
import com.rackspace.docs.usage.lbaas.StatusEnum;
import com.rackspace.docs.usage.lbaas.VipTypeEnum;
import org.openstack.atlas.atom.config.AtomHopperConfigurationKeys;
import org.openstack.atlas.atom.pojo.EntryPojo;
import org.openstack.atlas.atom.pojo.LBaaSUsagePojo;
import org.openstack.atlas.atom.pojo.UsageV1Pojo;
import org.openstack.atlas.atom.util.AHUSLUtil;
import org.openstack.atlas.atom.util.UUIDUtil;
import org.openstack.atlas.cfg.Configuration;
import org.openstack.atlas.service.domain.entities.Usage;
import org.openstack.atlas.service.domain.events.entities.SslMode;
import org.openstack.atlas.service.domain.usage.BitTag;
import org.openstack.atlas.service.domain.usage.BitTags;
import org.w3._2005.atom.Title;
import org.w3._2005.atom.Type;
import org.w3._2005.atom.UsageCategory;
import org.w3._2005.atom.UsageContent;

import javax.ws.rs.core.MediaType;
import javax.xml.datatype.DatatypeConfigurationException;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.UUID;

/**
 * Used for mapping values from usage records to generate usage objects...
 */
public class LbaasUsageDataMapper {
    private static String region = "GLOBAL";
    private static final String label = "loadBalancerUsage";
    private static final String lbaasTitle = "cloudLoadBalancers";
    private static String SERVICE_CODE = "CloudLoadBalancers";
    private static final String version = "1"; //SCHEMA VERSIONS found in META-INF/xml and META-INF/xsd

    public static EntryPojo buildUsageEntry(Usage usageRecord, Configuration configuration, String configRegion) throws NoSuchAlgorithmException, DatatypeConfigurationException {
        EntryPojo entry = buildEntry();

        UsageContent usageContent = new UsageContent();
        usageContent.setEvent(LbaasUsageDataMapper.generateUsageEntry(configuration, configRegion, usageRecord));
        entry.setContent(usageContent);
        entry.getContent().setType(MediaType.APPLICATION_XML);

        entry.getCategory().add(buildUsageCategory());
        return entry;
    }

    private static UsageV1Pojo generateUsageEntry(Configuration configuration, String configRegion, Usage usageRecord) throws DatatypeConfigurationException, NoSuchAlgorithmException {
        configRegion = configuration.getString(AtomHopperConfigurationKeys.ahusl_region);
        if (configRegion != null) {
            region = configRegion;
        }

        UsageV1Pojo usageV1 = new UsageV1Pojo();
        usageV1.setVersion(version);
        usageV1.setRegion(AHUSLUtil.mapRegion(region));
        usageV1.setTenantId(usageRecord.getAccountId().toString());
        usageV1.setResourceId(usageRecord.getLoadbalancer().getId().toString());
        usageV1.setResourceName(usageRecord.getLoadbalancer().getName());
        usageV1.setDataCenter(DC.fromValue(configuration.getString(AtomHopperConfigurationKeys.ahusl_data_center)));

        EventType usageRecordEventType = AHUSLUtil.mapEventType(usageRecord);
        if (usageRecordEventType != null && (usageRecordEventType.equals(EventType.DELETE))) {
            usageV1.setType(usageRecordEventType);
            usageV1.setEventTime(AHUSLUtil.processCalendar(usageRecord.getStartTime()));
        } else {
            usageV1.setType(EventType.USAGE);
            usageV1.setStartTime(AHUSLUtil.processCalendar(usageRecord.getStartTime()));
            usageV1.setEndTime(AHUSLUtil.processCalendar(usageRecord.getEndTime()));
        }

        //Generate UUID
        UUID uuid = UUIDUtil.genUUIDMD5Hash(genUUIDString(usageRecord));
        usageV1.setId(uuid.toString());

        if (usageRecord.getUuid() != null) {
            //This is an updated usage record, need the reference id from previous record
            usageV1.setReferenceId(usageRecord.getUuid());
        } else {
            //They dont want this null...............
            usageV1.setReferenceId(uuid.toString());
        }


        usageV1.getAny().add(buildLbaasUsageRecord(usageRecord));
        return usageV1;
    }

    private static String genUUIDString(Usage usageRecord) {
        return SERVICE_CODE + "_" + usageRecord.getId() + "_" + usageRecord.getLoadbalancer().getId() + "_" + region + "_" + Calendar.getInstance();
    }

    private static EntryPojo buildEntry() {
        EntryPojo entry = new EntryPojo();
        Title title = new Title();
        title.setType(Type.TEXT);
        title.setValue(lbaasTitle);
        entry.setTitle(title);
        return entry;
    }

    private static UsageCategory buildUsageCategory() {
        UsageCategory usageCategory = new UsageCategory();
        usageCategory.setLabel(label);
        usageCategory.setTerm("plain");
        return usageCategory;
    }

    private static LBaaSUsagePojo buildLbaasUsageRecord(Usage usageRecord) throws DatatypeConfigurationException {
        //LBAAS specific values
        LBaaSUsagePojo lu = new LBaaSUsagePojo();
        lu.setAvgConcurrentConnections(usageRecord.getAverageConcurrentConnections());
        lu.setAvgConcurrentConnectionsSsl(usageRecord.getAverageConcurrentConnectionsSsl());
        lu.setBandWidthOutSsl(usageRecord.getOutgoingTransferSsl());
        lu.setBandWidthInSsl(usageRecord.getIncomingTransferSsl());
        lu.setBandWidthOut(usageRecord.getOutgoingTransfer());
        lu.setBandWidthIn(usageRecord.getIncomingTransfer());
        lu.setResourceType(ResourceTypes.LOADBALANCER);
        lu.setNumPolls(usageRecord.getNumberOfPolls());
        lu.setNumVips(usageRecord.getNumVips());
        lu.setServiceCode(SERVICE_CODE);
        lu.setVersion(version);

        StatusEnum status = (AHUSLUtil.mapEventType(usageRecord) != null && AHUSLUtil.mapEventType(usageRecord).equals(EventType.SUSPEND)) ? StatusEnum.SUSPENDED : StatusEnum.ACTIVE;
        lu.setStatus(status);

        BitTags bitTags = new BitTags(usageRecord.getTags());
        if (bitTags.isTagOn(BitTag.SERVICENET_LB)) {
            lu.setVipType(VipTypeEnum.SERVICENET);
        } else {
            lu.setVipType(VipTypeEnum.PUBLIC);
        }
        lu.setSslMode(SslModeEnum.fromValue(SslMode.getMode(bitTags).name()));

        return lu;
    }
}
