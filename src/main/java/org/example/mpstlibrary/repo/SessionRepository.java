package org.example.mpstlibrary.repo;
import org.example.mpstlibrary.data.Session;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionRepository extends CrudRepository<Session, String> {
}