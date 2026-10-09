package com.cras.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "resources")
public class Resource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String unit;

    @Column(nullable = false)
    private int availableQuantity;

    public Resource() {}

    public Resource(String name, String unit, int availableQuantity) {
        this.name = name;
        this.unit = unit;
        this.availableQuantity = availableQuantity;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getUnit() { return unit; }
    public int getAvailableQuantity() { return availableQuantity; }

    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setUnit(String unit) { this.unit = unit; }
    public void setAvailableQuantity(int availableQuantity) { this.availableQuantity = availableQuantity; }
}
