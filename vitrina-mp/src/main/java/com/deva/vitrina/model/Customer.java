package com.deva.vitrina.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
public class Customer {

    @Id
    @UuidGenerator
    private UUID uuid;

    private String name;

    @ManyToOne
    private Tenant tenant;

    public Customer() {
    }

}
