package me.scinorandex;

import java.util.List;

public class Config {
    private String upstream;
    private int port;
    private List<DnsRecord> records;

    // Getters and Setters
    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public List<DnsRecord> getRecords() {
        return records;
    }

    public void setRecords(List<DnsRecord> records) {
        this.records = records;
    }
}
