package com.deva.vitrina.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.deva.vitrina.exception.IdempotencyKeyConflictException;
import com.deva.vitrina.model.Customer;
import com.deva.vitrina.model.IdempotencyKey;
import com.deva.vitrina.repo.CustomerRepo;
import com.deva.vitrina.repo.IdempotencyKeyRepo;

// Service layer keeps business rules separate from HTTP and persistence concerns.
@Service
@Transactional(readOnly = true)
public class CustomerCrudService {

    // Constructor injection makes dependencies explicit and easy to test.
    private final CustomerRepo customerRepo;
    private final IdempotencyKeyRepo idempotencyKeyRepo;

    public CustomerCrudService(CustomerRepo customerRepo, IdempotencyKeyRepo idempotencyKeyRepo) {
        this.customerRepo = customerRepo;
        this.idempotencyKeyRepo = idempotencyKeyRepo;
    }

    // Read-only list operation keeps controller logic thin and reusable.
    public List<Customer> listAll() {
        return customerRepo.findAll();
    }

    // Fetch-by-id centralizes the not-found check for consistent 404 behavior.
    public Customer getById(UUID id) {
        return customerRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Customer not found"));
    }

    // Write transaction is required to persist the new entity.
    @Transactional
    public CustomerCreateResult create(Customer input, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required for POST /customers");
        }

        String requestHash = hashCustomer(input);
        IdempotencyKey existingKey = idempotencyKeyRepo.findById(idempotencyKey).orElse(null);
        if (existingKey != null) {
            if (!requestHash.equals(existingKey.getRequestHash())) {
                throw new IdempotencyKeyConflictException("Idempotency-Key reuse with different payload");
            }
            if (existingKey.getResourceId() == null) {
                throw new IdempotencyKeyConflictException("Request with this Idempotency-Key is in progress");
            }
            Customer existing = customerRepo.findById(existingKey.getResourceId())
                    .orElseThrow(() -> new EntityNotFoundException("Customer not found"));
            return new CustomerCreateResult(existing, false);
        }

        IdempotencyKey newKey = new IdempotencyKey();
        newKey.setIdempotencyKey(idempotencyKey);
        newKey.setRequestHash(requestHash);
        newKey.setResourceType("customer");

        try {
            idempotencyKeyRepo.save(newKey);
        } catch (DataIntegrityViolationException ex) {
            // Special case: two requests raced to insert the same Idempotency-Key.
            // The unique constraint rejects the loser; we must re-read and return
            // the winning result (or a conflict if the payload differs).
            IdempotencyKey racedKey = idempotencyKeyRepo.findById(idempotencyKey).orElse(null);
            if (racedKey == null) {
                throw ex;
            }
            if (!requestHash.equals(racedKey.getRequestHash())) {
                throw new IdempotencyKeyConflictException("Idempotency-Key reuse with different payload");
            }
            if (racedKey.getResourceId() == null) {
                throw new IdempotencyKeyConflictException("Request with this Idempotency-Key is in progress");
            }
            Customer existing = customerRepo.findById(racedKey.getResourceId())
                    .orElseThrow(() -> new EntityNotFoundException("Customer not found"));
            return new CustomerCreateResult(existing, false);
        }

        Customer customer = new Customer()
                .setFirstName(input.getFirstName())
                .setLastName(input.getLastName())
                .setEmail(input.getEmail())
                .setPhoneNumber(input.getPhoneNumber())
                .setAddress(input.getAddress())
                .setName(input.getName())
                .setTenant(input.getTenant());
        Customer created = customerRepo.save(customer);
        newKey.setResourceId(created.getUuid());
        idempotencyKeyRepo.save(newKey);
        return new CustomerCreateResult(created, true);
    }

    // Updates mutate a managed entity so JPA can track and flush changes.
    @Transactional
    public Customer update(UUID id, Customer input) {
        Customer existing = getById(id);
        existing.setFirstName(input.getFirstName())
                .setLastName(input.getLastName())
                .setEmail(input.getEmail())
                .setPhoneNumber(input.getPhoneNumber())
                .setAddress(input.getAddress())
                .setName(input.getName())
                .setTenant(input.getTenant());
        return customerRepo.save(existing);
    }

    // Delete is a no-op if the record doesn't exist to keep the API idempotent.
    @Transactional
    public void delete(@NonNull UUID id) {
        customerRepo.deleteById(id);
    }

    // Compact result type for create to preserve 201 vs 200 semantics.
    public static final class CustomerCreateResult {
        private final Customer customer;
        private final boolean created;

        public CustomerCreateResult(Customer customer, boolean created) {
            this.customer = customer;
            this.created = created;
        }

        public Customer getCustomer() {
            return customer;
        }

        public boolean isCreated() {
            return created;
        }
    }

    private static String hashCustomer(Customer input) {
        String payload = String.join("|",
                safe(input.getFirstName()),
                safe(input.getLastName()),
                safe(input.getEmail()),
                safe(input.getPhoneNumber()),
                safe(input.getAddress()),
                safe(input.getName()),
                input.getTenant() == null || input.getTenant().getUuid() == null
                        ? ""
                        : input.getTenant().getUuid().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
