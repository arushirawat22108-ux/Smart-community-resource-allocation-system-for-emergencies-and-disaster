package com.cras.entity;

import jakarta.persistence.*;

/** A relief camp: the "process" in the Banker's algorithm. */
@Entity
@Table(name = "relief_camps")
public class ReliefCamp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    public ReliefCamp() {}
    public ReliefCamp(String name) { this.name = name; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
