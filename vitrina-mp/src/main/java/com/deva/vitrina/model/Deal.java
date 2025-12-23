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

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer createdBy;

    @OneToOne(fetch = FetchType.LAZY)
    private Chat chat;

    public Deal() {
    }
}
