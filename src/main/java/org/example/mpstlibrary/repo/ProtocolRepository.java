package org.example.mpstlibrary.repo;

import org.example.mpstlibrary.data.Protocol;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProtocolRepository extends CrudRepository<Protocol, String> {
}
