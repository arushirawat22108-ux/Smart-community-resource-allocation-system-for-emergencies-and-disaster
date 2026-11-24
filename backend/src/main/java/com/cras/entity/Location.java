package com.cras.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "locations")
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** 1 (safe) … 10 (critical disaster zone) */
    @Column(nullable = false)
    private int riskLevel = 5;

    private Double latitude;
    private Double longitude;

    public Location() {}

    public Location(String name, int riskLevel) {
        this.name = name;
        this.riskLevel = riskLevel;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getRiskLevel() { return riskLevel; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }

    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setRiskLevel(int riskLevel) { this.riskLevel = riskLevel; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
}
