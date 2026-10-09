package com.cras.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "allocations")
public class Allocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "request_id")
    private EmergencyRequest request;

    @ManyToOne(optional = false)
    @JoinColumn(name = "resource_id")
    private Resource resource;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AllocationStatus status = AllocationStatus.ACTIVE;

    @Column(nullable = false)
    private LocalDateTime allocatedAt = LocalDateTime.now();

    public Allocation() {}

    public Allocation(EmergencyRequest request, Resource resource, int quantity) {
        this.request = request;
        this.resource = resource;
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public EmergencyRequest getRequest() { return request; }
    public Resource getResource() { return resource; }
    public int getQuantity() { return quantity; }
    public AllocationStatus getStatus() { return status; }
    public LocalDateTime getAllocatedAt() { return allocatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setRequest(EmergencyRequest request) { this.request = request; }
    public void setResource(Resource resource) { this.resource = resource; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setStatus(AllocationStatus status) { this.status = status; }
    public void setAllocatedAt(LocalDateTime allocatedAt) { this.allocatedAt = allocatedAt; }
}
