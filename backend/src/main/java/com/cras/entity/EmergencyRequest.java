package com.cras.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "emergency_requests")
public class EmergencyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "resource_id")
    private Resource resource;

    @ManyToOne
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(nullable = false)
    private String requesterEmail;

    @Column(nullable = false)
    private int quantity;

    /** 1–10 */
    @Column(nullable = false)
    private int severity;

    @Column(nullable = false)
    private int affectedPeople;

    /** User-entered scarcity 1–10 (Phase II). Phase III prefers live stock ratio. */
    @Column(nullable = false)
    private int scarcity = 5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(nullable = false)
    private int priorityScore = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public EmergencyRequest() {}

    public Long getId() { return id; }
    public Resource getResource() { return resource; }
    public Location getLocation() { return location; }
    public String getRequesterEmail() { return requesterEmail; }
    public int getQuantity() { return quantity; }
    public int getSeverity() { return severity; }
    public int getAffectedPeople() { return affectedPeople; }
    public int getScarcity() { return scarcity; }
    public RequestStatus getStatus() { return status; }
    public int getPriorityScore() { return priorityScore; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setResource(Resource resource) { this.resource = resource; }
    public void setLocation(Location location) { this.location = location; }
    public void setRequesterEmail(String requesterEmail) { this.requesterEmail = requesterEmail; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setSeverity(int severity) { this.severity = severity; }
    public void setAffectedPeople(int affectedPeople) { this.affectedPeople = affectedPeople; }
    public void setScarcity(int scarcity) { this.scarcity = scarcity; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
