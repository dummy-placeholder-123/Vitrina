package com.deva.vitrina.model;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

@Entity
public class Message {

    @Id
    private String ulid = UlidCreator.getUlid().toString();

    private Instant createdAt = Instant.now();

    @ManyToOne
    private Customer createdBy;

    @ManyToOne
    private Chat chat;

    public Message() {
    }
}
