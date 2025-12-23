package com.deva.vitrina.model;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
public class Deal {

    @Id
    @UuidGenerator
    private UUID uuid;

    private String dealName;

    @ManyToOne
    private Customer createdBy;

    @OneToOne
    private Chat chat;

    public Deal() {
    }
}
