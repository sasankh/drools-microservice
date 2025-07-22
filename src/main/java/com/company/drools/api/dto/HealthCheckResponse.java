package com.company.drools.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

public class HealthCheckResponse {
    private String status;
    private Instant timestamp;
    private Map<String, ComponentHealth> components;
    
    public HealthCheckResponse() {
        this.timestamp = Instant.now();
    }
    
    public HealthCheckResponse(String status, Map<String, ComponentHealth> components) {
        this();
        this.status = status;
        this.components = components;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
    
    public Map<String, ComponentHealth> getComponents() {
        return components;
    }
    
    public void setComponents(Map<String, ComponentHealth> components) {
        this.components = components;
    }
    
    public static class ComponentHealth {
        private String status;
        private Map<String, Object> details;
        
        public ComponentHealth() {}
        
        public ComponentHealth(String status, Map<String, Object> details) {
            this.status = status;
            this.details = details;
        }
        
        public String getStatus() {
            return status;
        }
        
        public void setStatus(String status) {
            this.status = status;
        }
        
        public Map<String, Object> getDetails() {
            return details;
        }
        
        public void setDetails(Map<String, Object> details) {
            this.details = details;
        }
    }
}