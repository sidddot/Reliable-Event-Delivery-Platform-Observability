package com.eventplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String role = "api";
    private String apiKey = "dev-api-key-32chars-secret-token-key";
    private WorkerProperties worker = new WorkerProperties();
    private DeliveryProperties delivery = new DeliveryProperties();

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public WorkerProperties getWorker() {
        return worker;
    }

    public void setWorker(WorkerProperties worker) {
        this.worker = worker;
    }

    public DeliveryProperties getDelivery() {
        return delivery;
    }

    public void setDelivery(DeliveryProperties delivery) {
        this.delivery = delivery;
    }

    public static class WorkerProperties {
        private String id = "worker-1";
        private int batchSize = 10;
        private long pollIntervalMs = 500;
        private int leaseSeconds = 30;
        private int concurrency = 8;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }
    }

    public static class DeliveryProperties {
        private int baseBackoffSeconds = 10;
        private int maxBackoffSeconds = 3600;
        private int maxAttempts = 8;
        private int httpTimeoutMs = 5000;

        public int getBaseBackoffSeconds() {
            return baseBackoffSeconds;
        }

        public void setBaseBackoffSeconds(int baseBackoffSeconds) {
            this.baseBackoffSeconds = baseBackoffSeconds;
        }

        public int getMaxBackoffSeconds() {
            return maxBackoffSeconds;
        }

        public void setMaxBackoffSeconds(int maxBackoffSeconds) {
            this.maxBackoffSeconds = maxBackoffSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getHttpTimeoutMs() {
            return httpTimeoutMs;
        }

        public void setHttpTimeoutMs(int httpTimeoutMs) {
            this.httpTimeoutMs = httpTimeoutMs;
        }
    }
}
