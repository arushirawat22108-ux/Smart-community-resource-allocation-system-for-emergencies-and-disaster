package com.cras.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "emergency_requests",
       indexes = {
           @Index(name = "idx_request_queue", columnList = "resource_id,status,priority_score"),
           @Index(name = "idx_request_created", columnList = "created_at")
       })
public class EmergencyRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User requester;

    @ManyToOne(optional = false)
    @JoinColumn(name = "resource_id")
    private Resource resource;

    @ManyToOne
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int severity;

    @Column(nullable = false)
    private int affectedPeople;

    @Column(nullable = false)
    private int scarcity;

    @Column(nullable = false)
    private int priorityScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public EmergencyRequest() {}

    public Long getId() { return id; }
    public User getRequester() { return requester; }
    public Resource getResource() { return resource; }
    public Location getLocation() { return location; }
    public int getQuantity() { return quantity; }
    public int getSeverity() { return severity; }
    public int getAffectedPeople() { return affectedPeople; }
    public int getScarcity() { return scarcity; }
    public int getPriorityScore() { return priorityScore; }
    public RequestStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setRequester(User requester) { this.requester = requester; }
    public void setResource(Resource resource) { this.resource = resource; }
    public void setLocation(Location location) { this.location = location; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setSeverity(int severity) { this.severity = severity; }
    public void setAffectedPeople(int affectedPeople) { this.affectedPeople = affectedPeople; }
    public void setScarcity(int scarcity) { this.scarcity = scarcity; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
