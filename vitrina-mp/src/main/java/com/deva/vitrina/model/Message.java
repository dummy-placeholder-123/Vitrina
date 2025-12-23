package com.deva.vitrina.model;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

@Entity
public class Message {

    @Id
    private String ulid = UlidCreator.getUlid().toString();

    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    private Chat chat;

    public Message() {
    }
}
