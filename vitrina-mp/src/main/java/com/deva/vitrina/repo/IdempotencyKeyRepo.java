package com.deva.vitrina.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.deva.vitrina.model.IdempotencyKey;

@Repository
public interface IdempotencyKeyRepo extends JpaRepository<IdempotencyKey, String> {}
