package com.cras.entity;

import jakarta.persistence.*;

/** One cell of the Banker's Max / Allocation matrices: a camp's claim on one resource type. */
@Entity
@Table(name = "camp_claims",
        uniqueConstraints = @UniqueConstraint(name = "uq_camp_resource", columnNames = {"camp_id", "resource_id"}))
public class CampClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "camp_id")
    private ReliefCamp camp;

    @ManyToOne(optional = false)
    @JoinColumn(name = "resource_id")
    private Resource resource;

    @Column(nullable = false)
    private int maxNeed;

    @Column(nullable = false)
    private int allocated;

    public CampClaim() {}
    public CampClaim(ReliefCamp camp, Resource resource, int maxNeed) {
        this.camp = camp;
        this.resource = resource;
        this.maxNeed = maxNeed;
    }

    public Long getId() { return id; }
    public ReliefCamp getCamp() { return camp; }
    public Resource getResource() { return resource; }
    public int getMaxNeed() { return maxNeed; }
    public int getAllocated() { return allocated; }
    public void setAllocated(int allocated) { this.allocated = allocated; }
    public void setMaxNeed(int maxNeed) { this.maxNeed = maxNeed; }
}
