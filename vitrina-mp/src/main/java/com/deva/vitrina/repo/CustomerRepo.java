package com.deva.vitrina.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.deva.vitrina.model.Customer;

@Repository
public interface CustomerRepo extends JpaRepository<Customer, UUID> {
    @EntityGraph(attributePaths = "tenant")
    java.util.List<Customer> findAll();

    @EntityGraph(attributePaths = "tenant")
    java.util.Optional<Customer> findById(UUID uuid);
}
