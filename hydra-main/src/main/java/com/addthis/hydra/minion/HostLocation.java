package com.addthis.hydra.minion;

import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds physical location info of a minion. The info can be useful to decide where the replicas should be put, e.g.
 * replicas should be spread across different datacenters as much as possible, to minimize the risk of data loss
 */

public class HostLocation {
    private String dataCenter;
    private String rack;
    private String physicalHost; // a physical host can have many VMs

    @JsonCreator
    HostLocation(@JsonProperty("dataCenter") String dataCenter,
                 @JsonProperty("rack") String rack,
                 @JsonProperty("physicalHost") String physicalHost) {
        this.dataCenter = dataCenter;
        this.rack = rack;
        this.physicalHost = physicalHost;
    }

    public static HostLocation forHost(String hostname) {
        return new HostLocation("Unknown", "Unknown", hostname);
    }

    public String getDataCenter() {
        return dataCenter;
    }

    public void setDataCenter(String dataCenter) {
        this.dataCenter = dataCenter;
    }

    public String getRack() {
        return rack;
    }

    public void setRack(String rack) {
        this.rack = rack;
    }

    public String getPhysicalHost() {
        return physicalHost;
    }

    public void setPhysicalHost(String physicalHost) {
        this.physicalHost = physicalHost;
    }

    @Override
    public int hashCode() {
        return this.toString().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        HostLocation location = (HostLocation) obj;
        return location.getDataCenter().equals(this.dataCenter) &&
               location.getRack().equals(this.rack) &&
               location.getPhysicalHost().equals(this.physicalHost);
    }

    public String toString() {
        return "dataCenter=" + dataCenter + ", rack=" + rack + ", physicalHost=" + physicalHost;
    }

    /**
     * Enforce an order when comparing HostLocations
     * @param o
     * @return
     */
    public int compare(HostLocation o) {
        if (!this.getDataCenter().equals(o.getDataCenter())) {
            // Different dataCenter
            return -3;
        } else {
            if (!this.getRack().equals(o.getRack())) {
                // Same dataCenter, different rack
                return -2;
            } else if (this.getPhysicalHost().equals(o.getPhysicalHost())) {
                // dataCenter, rack and physicalHost are the same
                return 0;
            } else {
                // Same dataCenter, same rack, different physicalHost
                return -1;
            }
        }
    }
}