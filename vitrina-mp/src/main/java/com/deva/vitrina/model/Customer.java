package com.deva.vitrina.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.lang.NonNull;

import java.util.UUID;

@Entity
public class Customer {

    @Id
    @UuidGenerator
    private UUID uuid;

    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private String address;
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    private Tenant tenant;

    public Customer() {
    }

    public UUID getUuid() {
        return uuid;
    }

    public @NonNull Customer setUuid(UUID uuid) {
        this.uuid = uuid;
        return this;
    }

    public String getFirstName() {
        return firstName;
    }

    public @NonNull Customer setFirstName(String firstName) {
        this.firstName = firstName;
        return this;
    }

    public String getLastName() {
        return lastName;
    }

    public @NonNull Customer setLastName(String lastName) {
        this.lastName = lastName;
        return this;
    }

    public String getEmail() {
        return email;
    }

    public @NonNull Customer setEmail(String email) {
        this.email = email;
        return this;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public @NonNull Customer setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        return this;
    }

    public String getAddress() {
        return address;
    }

    public @NonNull Customer setAddress(String address) {
        this.address = address;
        return this;
    }

    public String getName() {
        return name;
    }

    public @NonNull Customer setName(String name) {
        this.name = name;
        return this;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public @NonNull Customer setTenant(Tenant tenant) {
        this.tenant = tenant;
        return this;
    }
}
