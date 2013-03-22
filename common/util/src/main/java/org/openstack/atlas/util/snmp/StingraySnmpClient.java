package org.openstack.atlas.util.snmp;

import java.io.IOException;
import java.util.ArrayList;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.openstack.atlas.util.common.VerboseLogger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.util.snmp.exceptions.StingraySnmpRetryExceededException;
import org.openstack.atlas.util.snmp.exceptions.StingraySnmpSetupException;
import org.openstack.atlas.util.snmp.exceptions.StingraySnmpGeneralException;
import org.openstack.atlas.util.staticutils.StaticDateTimeUtils;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;

public class StingraySnmpClient {

    private static final Pattern dotSplitter = Pattern.compile("\\.");
    private static final VerboseLogger vlog = new VerboseLogger(StingraySnmpClient.class);
    private static final Log LOG = LogFactory.getLog(StingraySnmpClient.class);
    private String address;
    private String port;
    private String community = "public"; // Sounds like a good default
    private long reportUdpCountEveryNMilliSeconds = 1000;
    private int maxRetrys = 13;
    private static final Random rnd = new Random();
    private static int requestId = 0;

    public static Random getRnd() {
        return rnd;
    }

    public StingraySnmpClient() {
    }

    public StingraySnmpClient(String address, String port) {
        this.address = address;
        this.port = port;
    }

    public StingraySnmpClient(String address, String port, String community) {
        this.address = address;
        this.port = port;
        this.community = community;

    }

    @Override
    public String toString() {
        return "StringraySnmpClient{address=" + address
                + ", port=" + port
                + ", community=" + community
                + ", maxRetries=" + maxRetrys
                + ", curRequestId=" + getRequestId()
                + "}";
    }

    public synchronized static int incRequestId() {
        requestId = (requestId + 1) % Integer.MAX_VALUE;
        return requestId;
    }

    public Map<String, RawSnmpUsage> getSnmpUsage() throws StingraySnmpSetupException, StingraySnmpRetryExceededException, StingraySnmpGeneralException {
        vlog.printf("in call to getSnmpUsage()");
        Map<String, RawSnmpUsage> rawSnmpMap = new HashMap<String, RawSnmpUsage>();
        List<VariableBinding> bindings;

        // Fetch Current Connections
        bindings = getWalkOidBindingList(OIDConstants.VS_CURRENT_CONNECTIONS);
        for (VariableBinding vb : bindings) {
            String vsName = getVirtualServerName(vb.getOid().toString());
            if (!rawSnmpMap.containsKey(vsName)) {
                RawSnmpUsage entry = new RawSnmpUsage();
                entry.setVsName(vsName);
                rawSnmpMap.put(vsName, entry);
            }
            rawSnmpMap.get(vsName).setConcurrentConnections(vb.getVariable().toLong());
        }

        // Fetch Total Connections
        bindings = getWalkOidBindingList(OIDConstants.VS_TOTAL_CONNECTIONS);
        for (VariableBinding vb : bindings) {
            String vsName = getVirtualServerName(vb.getOid().toString());
            if (!rawSnmpMap.containsKey(vsName)) {
                RawSnmpUsage entry = new RawSnmpUsage();
                entry.setVsName(vsName);
                rawSnmpMap.put(vsName, entry);
            }
            rawSnmpMap.get(vsName).setTotalConnections(vb.getVariable().toLong());
        }

        // Fetch BytesIn hi bytes
        bindings = getWalkOidBindingList(OIDConstants.VS_BYTES_IN_HI);
        for (VariableBinding vb : bindings) {
            String vsName = getVirtualServerName(vb.getOid().toString());
            if (!rawSnmpMap.containsKey(vsName)) {
                RawSnmpUsage entry = new RawSnmpUsage();
                entry.setVsName(vsName);
                rawSnmpMap.put(vsName, entry);
            }
            rawSnmpMap.get(vsName).setBytesInHi(vb.getVariable().toLong());
        }

        // Fetch Bytes In Lo
        bindings = getWalkOidBindingList(OIDConstants.VS_BYTES_IN_LO);
        for (VariableBinding vb : bindings) {
            String vsName = getVirtualServerName(vb.getOid().toString());
            if (!rawSnmpMap.containsKey(vsName)) {
                RawSnmpUsage entry = new RawSnmpUsage();
                entry.setVsName(vsName);
                rawSnmpMap.put(vsName, entry);
            }
            rawSnmpMap.get(vsName).setBytesInLo(vb.getVariable().toLong());
        }

        // Fetch Bytes out Hi
        bindings = getWalkOidBindingList(OIDConstants.VS_BYTES_OUT_HI);
        for (VariableBinding vb : bindings) {
            String vsName = getVirtualServerName(vb.getOid().toString());
            if (!rawSnmpMap.containsKey(vsName)) {
                RawSnmpUsage entry = new RawSnmpUsage();
                entry.setVsName(vsName);
                rawSnmpMap.put(vsName, entry);
            }
            rawSnmpMap.get(vsName).setBytesOutHi(vb.getVariable().toLong());
        }

        // Fetch Bytes Out Lo
        bindings = getWalkOidBindingList(OIDConstants.VS_BYTES_OUT_LO);
        for (VariableBinding vb : bindings) {
            String vsName = getVirtualServerName(vb.getOid().toString());
            if (!rawSnmpMap.containsKey(vsName)) {
                RawSnmpUsage entry = new RawSnmpUsage();
                entry.setVsName(vsName);
                rawSnmpMap.put(vsName, entry);
            }
            rawSnmpMap.get(vsName).setBytesOutLo(vb.getVariable().toLong());
        }
        return rawSnmpMap;
    }

    public List<VariableBinding> getWalkOidBindingList(String oid) throws StingraySnmpSetupException, StingraySnmpRetryExceededException, StingraySnmpGeneralException {
        int retryCount = maxRetrys;
        int udpsSent = 0;
        long delay = 1; // Start with a back off of 1 Milliseconds
        vlog.printf("int call getWalkIudBindingList(%s)", oid);
        List<VariableBinding> bindingsList = new ArrayList<VariableBinding>();
        OID targetOID = new OID(oid);
        PDU requestPDU = new PDU();
        requestPDU.add(new VariableBinding(targetOID));
        requestPDU.setType(PDU.GETNEXT);

        CommunityTarget target = new CommunityTarget();
        target.setCommunity(new OctetString(community));
        target.setAddress(new UdpAddress(address + "/" + port));
        target.setVersion(SnmpConstants.version1);
        try {
            TransportMapping transport;
            try {
                transport = new DefaultUdpTransportMapping();
            } catch (IOException ex) {
                throw new StingraySnmpSetupException("Error setting up DefaultUdpTransportMapping for snmp client", ex);
            }
            Snmp snmp = new Snmp(transport);
            try {
                transport.listen();
            } catch (IOException ex) {
                String msg = "Unable to listen to address " + transport.getListenAddress().toString();
                throw new StingraySnmpSetupException(msg, ex);
            }

            boolean finished = false;
            long startMillis = System.currentTimeMillis();
            while (!finished) {
                long endMillis = System.currentTimeMillis();
                if (endMillis - startMillis > reportUdpCountEveryNMilliSeconds) {
                    vlog.printf("Sent %d udp packets ", udpsSent);
                    startMillis = endMillis;
                }

                VariableBinding vb = null;
                ResponseEvent event;
                try {
                    event = snmp.send(requestPDU, target);
                    udpsSent++;
                } catch (IOException ex) {
                    throw new StingraySnmpGeneralException("Error sending snmp request zxtm agent", ex);
                }
                PDU responsePDU = event.getResponse();
                if (responsePDU != null) {
                    vb = responsePDU.get(0);
                }

                if (responsePDU == null) {
                    if (retryCount <= 0) {
                        throw new StingraySnmpRetryExceededException("Exceeded maxRetries in snmp request to Zxtm agent after " + udpsSent + "udp packets sent");
                    }
                    retryCount--;
                    String msg = String.format("timeout waiting for UDP packet from snmp: waiting %d millis to try again. %d retries left: send %d udps so far", delay, retryCount, udpsSent);
                    vlog.printf("%s", msg);
                    Thread.sleep(delay);
                    delay *= 2; // Use a stable Exponential backoff.
                } else if (responsePDU.getErrorStatus() != 0) {
                    finished = true;
                } else if (vb.getOid() == null) {
                    finished = true;
                } else if (Null.isExceptionSyntax(vb.getVariable().getSyntax())) {
                    finished = true;
                } else if (vb.getOid().size() < targetOID.size()) {
                    finished = true;
                } else if (targetOID.leftMostCompare(targetOID.size(),
                        vb.getOid()) != 0) {
                    finished = true;
                } else if (vb.getOid().compareTo(targetOID) <= 0) {
                    finished = true;
                } else {
                    bindingsList.add(vb);
                    String vbString = vb.toString();
                    requestPDU.setRequestID(new Integer32(incRequestId()));
                    requestPDU.set(0, vb);
                }
            }
            try {
                snmp.close();
            } catch (IOException ex) {
                throw new StingraySnmpGeneralException("Could not close low lever snmp client", ex);
            }
        } catch (Exception ex) {
            // This is something unexpected
            throw new StingraySnmpGeneralException("Unhandled exception", ex);
        }

        return bindingsList;
    }

    public static String getVirtualServerName(String oid) {
        StringBuilder sb = new StringBuilder();
        String[] nums = dotSplitter.split(oid);
        for (int i = 14; i < nums.length; i++) {
            sb.append((char) Integer.parseInt(nums[i]));
        }
        return sb.toString();
    }

    public String getAddress() {
        return address;
    }

    public String getPort() {
        return port;
    }

    public String getCommunity() {
        return community;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public void setCommunity(String community) {
        this.community = community;
    }

    public static void nop() {
    }

    public static int getRequestId() {
        return requestId;
    }

    public int getMaxRetrys() {
        return maxRetrys;
    }

    public void setMaxRetrys(int maxRetrys) {
        this.maxRetrys = maxRetrys;
    }

    public synchronized static void setRequestId(int aRequestId) {
        requestId = aRequestId;
    }

    public long getReportUdpCountEveryNMilliSeconds() {
        return reportUdpCountEveryNMilliSeconds;
    }

    public void setReportUdpCountEveryNMilliSeconds(long reportUdpCountEveryNMilliSeconds) {
        this.reportUdpCountEveryNMilliSeconds = reportUdpCountEveryNMilliSeconds;
    }
}
