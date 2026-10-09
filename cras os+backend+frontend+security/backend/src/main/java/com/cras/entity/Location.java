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

    private double latitude;
    private double longitude;
    private int riskLevel;

    public Location() {}

    public Location(String name, double latitude, double longitude, int riskLevel) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.riskLevel = riskLevel;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getRiskLevel() { return riskLevel; }

    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public void setRiskLevel(int riskLevel) { this.riskLevel = riskLevel; }
}
