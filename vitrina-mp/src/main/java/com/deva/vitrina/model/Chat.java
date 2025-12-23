package com.deva.vitrina.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;

@Entity
public class Chat {
    @Id
    @UuidGenerator
    private UUID uuid;

    private String chatName;

    public Chat() {
    }
}
